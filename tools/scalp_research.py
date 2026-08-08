#!/usr/bin/env python3
"""Causal, reproducible ETH/SOL scalp research laboratory.

This module deliberately lives outside the Android runtime.  It uses only completed
Binance USD-M Futures one-minute bars, enters on the following bar, applies the same
profile costs and quantity bounds as the app, and resolves ambiguous bars against the
strategy (SL first).  Its purpose is to reject fragile ideas before any Java policy is
changed; it is not a profitability promise.
"""
from __future__ import annotations

import dataclasses
import datetime as dt
import hashlib
import itertools
import json
import math
import statistics
import zipfile
from collections import defaultdict
from pathlib import Path
from statistics import NormalDist
from typing import Iterable, Iterator, Mapping, Sequence

import numpy as np
import pandas as pd

try:
    from tools.validate_sol_profile import archive, months
except ImportError:  # Direct execution from tools/.
    from validate_sol_profile import archive, months


SYMBOLS = ("ETHUSDT", "SOLUSDT", "BTCUSDT")
TRADED_SYMBOLS = ("ETHUSDT", "SOLUSDT")
TICKS = {"ETHUSDT": 0.01, "SOLUSDT": 0.01}
MAX_QUANTITY = {"ETHUSDT": 7, "SOLUSDT": 120}
MIN_QUANTITY = {"ETHUSDT": 1, "SOLUSDT": 1}
RISK_BUDGET = 14.55
PROFILE_COST = {"ETHUSDT": 1.43, "SOLUSDT": 0.06}
SOL_REFERENCE_PRICE = 75.80
DEFAULT_SPLITS = {
    "train": ("2025-01-01", "2025-12-31 23:59:59"),
    "validation": ("2026-01-01", "2026-03-31 23:59:59"),
    "holdout": ("2026-04-01", "2026-07-31 23:59:59"),
}


@dataclasses.dataclass(frozen=True)
class RuleSpec:
    symbol: str
    family: str
    side: int
    params: tuple[tuple[str, float], ...]

    @property
    def side_name(self) -> str:
        return "LONG" if self.side > 0 else "SHORT"

    @property
    def identifier(self) -> str:
        encoded = "|".join(f"{k}={v:.8g}" for k, v in self.params)
        raw = f"{self.symbol}|{self.family}|{self.side_name}|{encoded}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]

    def value(self, key: str) -> float:
        return dict(self.params)[key]


@dataclasses.dataclass(frozen=True)
class Geometry:
    target_a: float
    stop_a: float

    @property
    def identifier(self) -> str:
        return f"TP{self.target_a:g}_SL{self.stop_a:g}"


@dataclasses.dataclass(frozen=True)
class Trade:
    symbol: str
    side: str
    qualified_at: int
    opened_at: int
    terminal_at: int | None
    status: str
    entry: float
    tp: float
    sl: float
    quantity: int
    cost_per_unit: float
    result_r: float
    net_usdt: float
    duration_minutes: int | None


@dataclasses.dataclass(frozen=True)
class Performance:
    trades: int
    tp: int
    sl: int
    unresolved: int
    net_r: float
    net_usdt: float
    positive_r: float
    negative_r_abs: float
    profit_factor_r: float | None
    expectancy_r: float | None
    win_rate: float | None
    max_drawdown_r: float
    maximum_consecutive_losses: int
    fresh_hours: float
    opportunities_per_hour: float | None
    monthly_positive_ratio: float | None
    results: tuple[float, ...]
    trades_detail: tuple[Trade, ...]


LINEAR_FEATURES = (
    "m1", "m3", "m8", "m15", "eff15", "eff60", "range_pos20", "z20",
    "ema_gap", "log_volume_ratio", "btc_ret3", "btc_ret8", "other_ret3", "a_pct",
    "m1_squared", "m3_squared", "z20_squared", "m3_eff15", "z20_centered_range",
    "btc3_m3", "other3_m3", "abs_m1", "abs_m3", "abs_z20",
)


@dataclasses.dataclass(frozen=True)
class LinearScoreModel:
    symbol: str
    horizon_minutes: int
    alpha: float
    quantile: float
    feature_names: tuple[str, ...]
    means: tuple[float, ...]
    scales: tuple[float, ...]
    coefficients: tuple[float, ...]
    threshold: float

    def scores(self, frame: pd.DataFrame) -> np.ndarray:
        matrix = linear_feature_matrix(frame)
        means = np.asarray(self.means)
        scales = np.asarray(self.scales)
        coefficients = np.asarray(self.coefficients)
        return ((matrix - means) / scales) @ coefficients

    def signal_mask(self, frame: pd.DataFrame) -> np.ndarray:
        matrix = linear_feature_matrix(frame)
        valid = np.isfinite(matrix).all(axis=1)
        scores = self.scores(frame)
        return valid & np.isfinite(scores) & (scores >= self.threshold)


def _timestamp_ms(value: pd.Series) -> pd.Series:
    numeric = pd.to_numeric(value, errors="coerce").dropna().astype("int64")
    # Public archives can contain microsecond timestamps. Normalize without using
    # machine time or any future row.
    while len(numeric) and int(numeric.max()) > 10_000_000_000_000:
        numeric = numeric // 1000
    return numeric


def read_kline_archive(path: Path, symbol: str) -> pd.DataFrame:
    """Read and validate one official Binance one-minute archive."""
    names = [
        "open_time", "open", "high", "low", "close", "volume", "close_time",
        "quote_volume", "trades", "taker_base", "taker_quote", "ignore",
    ]
    with zipfile.ZipFile(path) as zf:
        csv_names = [name for name in zf.namelist() if name.lower().endswith(".csv")]
        if len(csv_names) != 1:
            raise RuntimeError(f"Unexpected archive members in {path}: {csv_names}")
        with zf.open(csv_names[0]) as source:
            raw = pd.read_csv(source, header=None, names=names, usecols=range(12), low_memory=False)
    raw["open_time"] = pd.to_numeric(raw["open_time"], errors="coerce")
    raw = raw.dropna(subset=["open_time"])
    for column in ("open", "high", "low", "close", "volume"):
        raw[column] = pd.to_numeric(raw[column], errors="coerce")
    raw = raw.dropna(subset=["open", "high", "low", "close", "volume"])
    raw["open_time"] = _timestamp_ms(raw["open_time"])
    raw = raw.drop_duplicates("open_time", keep="last").sort_values("open_time")
    valid = (
        np.isfinite(raw[["open", "high", "low", "close", "volume"]]).all(axis=1)
        & (raw[["open", "high", "low", "close"]] > 0).all(axis=1)
        & (raw["volume"] >= 0)
        & (raw["high"] >= raw[["open", "close"]].max(axis=1))
        & (raw["low"] <= raw[["open", "close"]].min(axis=1))
    )
    if not bool(valid.all()):
        raise RuntimeError(f"Invalid OHLCV rows in {path.name}")
    frame = raw[["open_time", "open", "high", "low", "close", "volume"]].copy()
    frame["symbol"] = symbol
    return frame


def acquire_corpus(cache: Path, start: dt.date, end: dt.date) -> tuple[dict[str, pd.DataFrame], list[dict]]:
    """Download checksum-verified monthly archives and return one frame per symbol."""
    if start.day != 1 or end.day < 28:
        raise ValueError("Research corpus must use complete calendar months")
    period_names = list(months(start, dt.date(end.year, end.month, 1)))
    corpus: dict[str, list[pd.DataFrame]] = {symbol: [] for symbol in SYMBOLS}
    manifest: list[dict] = []
    for period in period_names:
        for symbol in SYMBOLS:
            path, item = archive(cache, symbol, period, False)
            manifest.append({**item, "symbol": symbol, "period": period})
            corpus[symbol].append(read_kline_archive(path, symbol))
    joined: dict[str, pd.DataFrame] = {}
    for symbol, parts in corpus.items():
        frame = pd.concat(parts, ignore_index=True).drop_duplicates("open_time", keep="last")
        frame = frame.sort_values("open_time").reset_index(drop=True)
        minute_delta = frame["open_time"].diff().dropna()
        # Gaps are retained as hard causality boundaries in feature construction; they
        # are reported rather than silently forward-filled.
        frame.attrs["gaps"] = int((minute_delta != 60_000).sum())
        joined[symbol] = frame
    return joined, manifest


def align_corpus(corpus: Mapping[str, pd.DataFrame]) -> pd.DataFrame:
    aligned: pd.DataFrame | None = None
    for symbol in SYMBOLS:
        source = corpus[symbol].copy()
        prefix = symbol[:3].lower()
        source = source.rename(columns={c: f"{prefix}_{c}" for c in ("open", "high", "low", "close", "volume")})
        source = source.drop(columns=["symbol"])
        aligned = source if aligned is None else aligned.merge(source, on="open_time", how="inner", validate="one_to_one")
    assert aligned is not None
    aligned = aligned.sort_values("open_time").reset_index(drop=True)
    # A gap never becomes an artificial one-minute return.
    aligned["contiguous"] = aligned["open_time"].diff().fillna(60_000).eq(60_000)
    return aligned


def _grouped_rolling(series: pd.Series, segments: pd.Series, window: int,
                     operation: str) -> pd.Series:
    grouped = series.groupby(segments, sort=False)
    if operation == "mean":
        return grouped.transform(lambda value: value.rolling(window, min_periods=window).mean())
    if operation == "std":
        return grouped.transform(
            lambda value: value.rolling(window, min_periods=window).std(ddof=0)
        )
    if operation == "max":
        return grouped.transform(lambda value: value.rolling(window, min_periods=window).max())
    if operation == "min":
        return grouped.transform(lambda value: value.rolling(window, min_periods=window).min())
    raise ValueError(operation)


def _efficiency(close: pd.Series, segments: pd.Series, window: int) -> pd.Series:
    delta = close.groupby(segments, sort=False).diff().abs()
    distance = _grouped_rolling(delta, segments, window, "mean") * window
    anchor = close.groupby(segments, sort=False).shift(window)
    return (close - anchor) / distance.replace(0, np.nan)


def build_features(aligned: pd.DataFrame, symbol: str) -> pd.DataFrame:
    """Build causal features from completed bars; no negative shift is used."""
    if symbol not in TRADED_SYMBOLS:
        raise ValueError(symbol)
    p = symbol[:3].lower()
    out = pd.DataFrame({"open_time": aligned["open_time"]})
    for field in ("open", "high", "low", "close", "volume"):
        out[field] = aligned[f"{p}_{field}"].astype(float)
    contiguous = aligned["contiguous"].astype(bool)
    # Every discontinuity starts a new causal segment. Rolling features, shifts and
    # exponential averages below never bridge a missing market minute.
    segments = (~contiguous).cumsum().astype("int64")
    out["segment_id"] = segments
    close = out["close"]
    ranges = out["high"] - out["low"]
    out["a"] = _grouped_rolling(ranges, segments, 20, "mean")
    out["volume_ratio"] = out["volume"] / _grouped_rolling(
        out["volume"], segments, 20, "mean"
    ).replace(0, np.nan)
    for window in (1, 3, 8, 15, 60):
        anchor = close.groupby(segments, sort=False).shift(window)
        out[f"m{window}"] = (close - anchor) / out["a"]
    out["eff15"] = _efficiency(close, segments, 15)
    out["eff60"] = _efficiency(close, segments, 60)
    rolling_high = _grouped_rolling(out["high"], segments, 20, "max")
    rolling_low = _grouped_rolling(out["low"], segments, 20, "min")
    out["range_pos20"] = (close - rolling_low) / (rolling_high - rolling_low).replace(0, np.nan)
    mean20 = _grouped_rolling(close, segments, 20, "mean")
    std20 = _grouped_rolling(close, segments, 20, "std")
    out["z20"] = (close - mean20) / std20.replace(0, np.nan)
    ema20 = close.groupby(segments, sort=False).transform(
        lambda value: value.ewm(span=20, adjust=False).mean()
    )
    ema60 = close.groupby(segments, sort=False).transform(
        lambda value: value.ewm(span=60, adjust=False).mean()
    )
    out["ema_gap"] = (ema20 - ema60) / out["a"]
    out["previous_m1"] = out["m1"].groupby(segments, sort=False).shift(1)
    btc = aligned["btc_close"].astype(float)
    other = aligned["sol_close" if symbol == "ETHUSDT" else "eth_close"].astype(float)
    out["btc_ret3"] = btc.groupby(segments, sort=False).pct_change(3, fill_method=None)
    out["btc_ret8"] = btc.groupby(segments, sort=False).pct_change(8, fill_method=None)
    out["other_ret3"] = other.groupby(segments, sort=False).pct_change(3, fill_method=None)
    out["month"] = pd.to_datetime(out["open_time"], unit="ms", utc=True).dt.strftime("%Y-%m")
    out.attrs["symbol"] = symbol
    return out


def linear_feature_matrix(frame: pd.DataFrame) -> np.ndarray:
    m1 = frame["m1"].to_numpy(float)
    m3 = frame["m3"].to_numpy(float)
    z20 = frame["z20"].to_numpy(float)
    eff15 = frame["eff15"].to_numpy(float)
    range_pos = frame["range_pos20"].to_numpy(float)
    btc3 = frame["btc_ret3"].to_numpy(float)
    other3 = frame["other_ret3"].to_numpy(float)
    base = np.column_stack([
        m1, m3, frame["m8"].to_numpy(float), frame["m15"].to_numpy(float),
        eff15, frame["eff60"].to_numpy(float), range_pos, z20,
        frame["ema_gap"].to_numpy(float),
        np.log1p(np.maximum(0.0, frame["volume_ratio"].to_numpy(float))),
        btc3, frame["btc_ret8"].to_numpy(float), other3,
        frame["a"].to_numpy(float) / frame["close"].to_numpy(float),
    ])
    nonlinear = np.column_stack([
        m1 * m1, m3 * m3, z20 * z20, m3 * eff15,
        z20 * (range_pos - 0.5), btc3 * m3, other3 * m3,
        np.abs(m1), np.abs(m3), np.abs(z20),
    ])
    return np.column_stack([base, nonlinear])


def fit_linear_score_model(frame: pd.DataFrame, train_period: tuple[str, str],
                           horizon_minutes: int = 30, alpha: float = 10.0,
                           quantile: float = 0.9975) -> LinearScoreModel:
    if horizon_minutes < 1 or not (0.5 < quantile < 1.0) or alpha < 0:
        raise ValueError("linear score configuration")
    matrix = linear_feature_matrix(frame)
    target = (frame["close"].shift(-horizon_minutes) / frame["open"].shift(-1) - 1).to_numpy(float)
    selected = purged_period_mask(frame, train_period, horizon_minutes)
    selected &= np.isfinite(matrix).all(axis=1) & np.isfinite(target)
    if int(selected.sum()) < 1_000:
        raise RuntimeError("Insufficient causal training samples")
    sample = matrix[selected]
    means = sample.mean(axis=0)
    scales = sample.std(axis=0)
    scales[scales < 1e-12] = 1.0
    standardized = (sample - means) / scales
    coefficients = np.linalg.solve(
        standardized.T @ standardized + alpha * np.eye(standardized.shape[1]),
        standardized.T @ target[selected],
    )
    training_scores = standardized @ coefficients
    threshold = float(np.quantile(training_scores, quantile))
    return LinearScoreModel(
        symbol=frame.attrs.get("symbol", ""), horizon_minutes=horizon_minutes,
        alpha=alpha, quantile=quantile, feature_names=LINEAR_FEATURES,
        means=tuple(float(value) for value in means),
        scales=tuple(float(value) for value in scales),
        coefficients=tuple(float(value) for value in coefficients), threshold=threshold,
    )


def period_mask(frame: pd.DataFrame, period: tuple[str, str]) -> np.ndarray:
    start = int(pd.Timestamp(period[0], tz="UTC").timestamp() * 1000)
    end = int(pd.Timestamp(period[1], tz="UTC").timestamp() * 1000)
    values = frame["open_time"].to_numpy(dtype=np.int64)
    return (values >= start) & (values <= end)


def purged_period_mask(frame: pd.DataFrame, period: tuple[str, str],
                       forward_bars: int) -> np.ndarray:
    """Select only source rows whose complete label stays in one split and segment."""
    if forward_bars < 0:
        raise ValueError("forward_bars")
    base = period_mask(frame, period)
    if forward_bars == 0:
        return base
    times = frame["open_time"].to_numpy(np.int64)
    segments = frame["segment_id"].to_numpy(np.int64)
    future_times = np.roll(times, -forward_bars)
    future_segments = np.roll(segments, -forward_bars)
    end = int(pd.Timestamp(period[1], tz="UTC").timestamp() * 1000)
    valid = np.arange(len(frame)) + forward_bars < len(frame)
    valid &= future_times <= end
    valid &= future_segments == segments
    return base & valid


def rule_mask(frame: pd.DataFrame, spec: RuleSpec) -> np.ndarray:
    side = float(spec.side)
    finite = np.isfinite(frame[["a", "m1", "m3", "m15", "eff15", "eff60", "range_pos20", "z20", "ema_gap", "btc_ret3"]]).all(axis=1).to_numpy()
    m1 = side * frame["m1"].to_numpy()
    m3 = side * frame["m3"].to_numpy()
    m15 = side * frame["m15"].to_numpy()
    eff15 = side * frame["eff15"].to_numpy()
    eff60 = side * frame["eff60"].to_numpy()
    btc3 = side * frame["btc_ret3"].to_numpy()
    other3 = side * frame["other_ret3"].to_numpy()
    rp = frame["range_pos20"].to_numpy()
    z = frame["z20"].to_numpy()
    ema = side * frame["ema_gap"].to_numpy()
    prev_m1 = side * frame["previous_m1"].to_numpy()
    p = dict(spec.params)
    if spec.family == "TREND_CONTINUATION":
        positional = rp <= p["range_cap"] if side > 0 else rp >= 1.0 - p["range_cap"]
        mask = (m3 >= p["m3_min"]) & (m15 >= p["m15_min"]) & (eff15 >= p["eff_min"])
        mask &= frame["volume_ratio"].to_numpy() >= p["volume_min"]
        mask &= btc3 >= p["btc_min"]
        mask &= positional
    elif spec.family == "PULLBACK_RESUMPTION":
        mask = (ema >= p["ema_min"]) & (m15 >= p["m15_min"]) & (eff60 >= p["eff_min"])
        mask &= prev_m1 <= -p["pullback_min"]
        mask &= m1 >= p["resume_min"]
        mask &= btc3 >= p["btc_min"]
    elif spec.family == "RANGE_REVERSION":
        extreme = -side * z
        positional = rp <= p["range_edge"] if side > 0 else rp >= 1.0 - p["range_edge"]
        mask = (extreme >= p["z_min"]) & positional & (m1 >= p["resume_min"])
        mask &= eff60 >= -p["max_adverse_eff"]
        mask &= btc3 >= p["btc_floor"]
    elif spec.family == "BTC_LEAD_LAG":
        mask = (btc3 >= p["btc_min"]) & (m3 <= p["lag_max"]) & (m3 >= p["lag_min"])
        mask &= m1 >= p["start_min"]
        mask &= other3 >= p["other_min"]
    else:
        raise ValueError(spec.family)
    return finite & mask


def generate_rules() -> Iterator[RuleSpec]:
    """Yield exactly 64 pre-registered rules (128 with the two fixed geometries).

    Keeping the search budget small is intentional: every inspected configuration is
    a statistical trial, including losing ones.
    """
    templates = {
        "TREND_CONTINUATION": (
            (0.25, 0.75, 0.25, 0.75, -0.0003, 0.95),
            (0.55, 0.75, 0.35, 1.00, 0.0, 0.95),
            (0.55, 1.50, 0.45, 0.75, 0.0, 0.85),
            (0.85, 1.50, 0.45, 1.10, 0.0003, 0.85),
        ),
        "PULLBACK_RESUMPTION": (
            (0.50, 1.00, 0.10, 0.20, -0.10, -0.0003),
            (0.50, 1.75, 0.25, 0.20, 0.00, 0.0),
            (1.00, 1.00, 0.25, 0.40, -0.10, 0.0),
            (1.00, 1.75, 0.25, 0.40, 0.00, 0.0),
        ),
        "RANGE_REVERSION": (
            (1.25, 0.12, -0.10, 0.60, -0.0010),
            (1.75, 0.12, -0.10, 0.35, -0.0002),
            (1.75, 0.05, 0.05, 0.60, -0.0010),
            (2.25, 0.05, 0.05, 0.35, -0.0002),
        ),
        "BTC_LEAD_LAG": (
            (0.0005, 0.60, -0.40, -0.05, -0.0002),
            (0.0005, 0.25, 0.00, 0.10, 0.0002),
            (0.0010, 0.60, -0.40, 0.10, -0.0002),
            (0.0010, 0.25, 0.00, -0.05, 0.0002),
        ),
    }
    keys_by_family = {
        "TREND_CONTINUATION": ("m3_min", "m15_min", "eff_min", "volume_min", "btc_min", "range_cap"),
        "PULLBACK_RESUMPTION": ("ema_min", "m15_min", "eff_min", "pullback_min", "resume_min", "btc_min"),
        "RANGE_REVERSION": ("z_min", "range_edge", "resume_min", "max_adverse_eff", "btc_floor"),
        "BTC_LEAD_LAG": ("btc_min", "lag_max", "lag_min", "start_min", "other_min"),
    }
    for symbol, side in itertools.product(TRADED_SYMBOLS, (1, -1)):
        for family, family_templates in templates.items():
            keys = keys_by_family[family]
            for values in family_templates:
                yield RuleSpec(symbol, family, side, tuple(zip(keys, values)))


def profile_cost(symbol: str, entry: float, multiplier: float = 1.0) -> float:
    raw = PROFILE_COST[symbol]
    if symbol == "SOLUSDT":
        raw *= entry / SOL_REFERENCE_PRICE
    return ceil_tick(raw * multiplier, TICKS[symbol])


def ceil_tick(value: float, tick: float) -> float:
    return math.ceil((value - 1e-12) / tick) * tick


def floor_tick(value: float, tick: float) -> float:
    return math.floor((value + 1e-12) / tick) * tick


def _summary(trades: Sequence[Trade], fresh_hours: float) -> Performance:
    resolved = [trade for trade in trades if trade.status in ("TP", "SL")]
    results = [trade.result_r for trade in resolved]
    tp = sum(trade.status == "TP" for trade in resolved)
    sl = sum(trade.status == "SL" for trade in resolved)
    unresolved = sum(trade.status == "UNRESOLVED" for trade in trades)
    positive = sum(value for value in results if value > 0)
    negative = sum(-value for value in results if value < 0)
    equity = peak = drawdown = 0.0
    consecutive = maximum_consecutive = 0
    month_results: dict[str, float] = defaultdict(float)
    for trade in resolved:
        equity += trade.result_r
        peak = max(peak, equity)
        drawdown = max(drawdown, peak - equity)
        if trade.result_r < 0:
            consecutive += 1
            maximum_consecutive = max(maximum_consecutive, consecutive)
        else:
            consecutive = 0
        month = dt.datetime.fromtimestamp(trade.opened_at / 1000, tz=dt.timezone.utc).strftime("%Y-%m")
        month_results[month] += trade.result_r
    monthly_ratio = None if not month_results else sum(v > 0 for v in month_results.values()) / len(month_results)
    count = len(resolved)
    return Performance(
        trades=len(trades), tp=tp, sl=sl, unresolved=unresolved,
        net_r=sum(results), net_usdt=sum(trade.net_usdt for trade in resolved),
        positive_r=positive, negative_r_abs=negative,
        profit_factor_r=None if negative == 0 else positive / negative,
        expectancy_r=None if count == 0 else sum(results) / count,
        win_rate=None if count == 0 else tp / count,
        max_drawdown_r=drawdown, maximum_consecutive_losses=maximum_consecutive,
        fresh_hours=fresh_hours,
        opportunities_per_hour=None if fresh_hours <= 0 else len(trades) / fresh_hours,
        monthly_positive_ratio=monthly_ratio, results=tuple(results), trades_detail=tuple(trades),
    )


def simulate(frame: pd.DataFrame, spec: RuleSpec, geometry: Geometry,
             period: tuple[str, str], cost_multiplier: float = 1.0,
             entry_delay_bars: int = 1, episode_gap_minutes: int = 3) -> Performance:
    """Conservative one-position replay for a single symbol and route."""
    return simulate_signal_mask(frame, spec.symbol, spec.side, rule_mask(frame, spec),
                                geometry, period, cost_multiplier, entry_delay_bars,
                                episode_gap_minutes)


def simulate_signal_mask(frame: pd.DataFrame, symbol: str, side: int,
                         signal_mask: np.ndarray, geometry: Geometry,
                         period: tuple[str, str], cost_multiplier: float = 1.0,
                         entry_delay_bars: int = 1, episode_gap_minutes: int = 3) -> Performance:
    """Replay a precomputed causal mask without allowing it to inspect the period's future."""
    if symbol not in TRADED_SYMBOLS or side not in (1, -1):
        raise ValueError("symbol/side")
    if len(signal_mask) != len(frame):
        raise ValueError("signal mask length")
    mask = np.asarray(signal_mask, dtype=bool) & period_mask(frame, period)
    indices = np.flatnonzero(mask)
    opens = frame["open"].to_numpy(float)
    highs = frame["high"].to_numpy(float)
    lows = frame["low"].to_numpy(float)
    a_values = frame["a"].to_numpy(float)
    times = frame["open_time"].to_numpy(np.int64)
    in_period = period_mask(frame, period)
    final_index = int(np.flatnonzero(in_period)[-1]) if bool(in_period.any()) else -1
    fresh_hours = float(in_period.sum()) / 60.0
    trades: list[Trade] = []
    available_at = -1
    last_episode_at = -10**18
    tick = TICKS[symbol]
    for qualified in indices:
        if qualified < available_at:
            continue
        if times[qualified] - last_episode_at <= episode_gap_minutes * 60_000:
            continue
        entry_index = qualified + max(1, entry_delay_bars)
        if entry_index > final_index:
            break
        entry_raw = opens[entry_index]
        a = a_values[qualified]
        if not (math.isfinite(entry_raw) and entry_raw > 0 and math.isfinite(a) and a > 0):
            continue
        if side > 0:
            entry = ceil_tick(entry_raw, tick)
            tp = floor_tick(entry + geometry.target_a * a, tick)
            sl = floor_tick(entry - geometry.stop_a * a, tick)
        else:
            entry = floor_tick(entry_raw, tick)
            tp = ceil_tick(entry - geometry.target_a * a, tick)
            sl = ceil_tick(entry + geometry.stop_a * a, tick)
        target_distance = abs(tp - entry)
        stop_distance = abs(entry - sl)
        cost = profile_cost(symbol, entry, cost_multiplier)
        net_reward = target_distance - cost
        net_risk = stop_distance + cost
        quantity = min(MAX_QUANTITY[symbol], int(math.floor((RISK_BUDGET + 1e-12) / net_risk)))
        if quantity < MIN_QUANTITY[symbol] or net_reward <= 0 or net_reward / net_risk < 0.40:
            continue
        last_episode_at = times[qualified]
        terminal_index: int | None = None
        status = "UNRESOLVED"
        for cursor in range(entry_index, final_index + 1):
            if side > 0:
                stop_touched = lows[cursor] <= sl
                target_touched = highs[cursor] >= tp
            else:
                stop_touched = highs[cursor] >= sl
                target_touched = lows[cursor] <= tp
            if stop_touched or target_touched:
                terminal_index = cursor
                status = "SL" if stop_touched else "TP"
                break
        if terminal_index is None:
            result_r = 0.0
            net_usdt = 0.0
            available_at = final_index + 1
            terminal_at = None
            duration = None
        elif status == "SL":
            result_r = -1.0
            net_usdt = -quantity * net_risk
            available_at = terminal_index + 1
            terminal_at = int(times[terminal_index])
            duration = int((times[terminal_index] - times[entry_index]) // 60_000)
        else:
            result_r = net_reward / net_risk
            net_usdt = quantity * net_reward
            available_at = terminal_index + 1
            terminal_at = int(times[terminal_index])
            duration = int((times[terminal_index] - times[entry_index]) // 60_000)
        trades.append(Trade(symbol, "LONG" if side > 0 else "SHORT", int(times[qualified]), int(times[entry_index]),
                            terminal_at, status, entry, tp, sl, quantity, cost, result_r, net_usdt, duration))
    return _summary(trades, fresh_hours)


def proxy_score(frame: pd.DataFrame, spec: RuleSpec, period: tuple[str, str]) -> tuple[float, int, float]:
    """Cheap development-only ranking before exact barrier simulation."""
    mask = rule_mask(frame, spec) & purged_period_mask(frame, period, 5)
    candidates = np.flatnonzero(mask)
    if len(candidates) == 0:
        return -math.inf, 0, 0.0
    # One observation per three-minute episode; the label uses the next bar entry and
    # the close five bars later. It is only a ranking proxy and never a reported PnL.
    keep: list[int] = []
    last = -10
    for index in candidates:
        if index - last > 3 and index + 5 < len(frame):
            keep.append(int(index))
            last = int(index)
    if not keep:
        return -math.inf, 0, 0.0
    idx = np.asarray(keep, dtype=int)
    entry = frame["open"].to_numpy(float)[idx + 1]
    future = frame["close"].to_numpy(float)[idx + 5]
    a = frame["a"].to_numpy(float)[idx]
    normalized = spec.side * (future - entry) / a
    finite = np.isfinite(normalized)
    normalized = normalized[finite]
    if len(normalized) == 0:
        return -math.inf, 0, 0.0
    months_seen = frame["month"].to_numpy()[idx][finite]
    monthly = [float(np.mean(normalized[months_seen == month])) for month in np.unique(months_seen)]
    consistency = sum(value > 0 for value in monthly) / len(monthly)
    score = float(np.mean(normalized)) + 0.20 * consistency + 0.02 * math.log1p(len(normalized))
    return score, int(len(normalized)), consistency


def deflated_sharpe_probability(results: Sequence[float], trials: int) -> float | None:
    """Selection-adjusted probability that the per-trade Sharpe exceeds noise."""
    values = np.asarray(results, dtype=float)
    values = values[np.isfinite(values)]
    if len(values) < 3 or float(values.std(ddof=1)) <= 0:
        return None
    sr = float(values.mean() / values.std(ddof=1))
    centered = values - values.mean()
    sigma = float(values.std(ddof=0))
    skew = float(np.mean((centered / sigma) ** 3))
    kurt = float(np.mean((centered / sigma) ** 4))
    n_trials = max(1, int(trials))
    normal = NormalDist()
    if n_trials == 1:
        expected_max = 0.0
    else:
        gamma = 0.5772156649015329
        expected_max = ((1 - gamma) * normal.inv_cdf(1 - 1 / n_trials)
                        + gamma * normal.inv_cdf(1 - 1 / (n_trials * math.e))) / math.sqrt(len(values))
    denominator = math.sqrt(max(1e-12, 1 - skew * sr + ((kurt - 1) / 4) * sr * sr))
    statistic = (sr - expected_max) * math.sqrt(len(values) - 1) / denominator
    return normal.cdf(statistic)


def performance_json(performance: Performance) -> dict:
    out = dataclasses.asdict(performance)
    out.pop("trades_detail", None)
    out["results"] = list(performance.results)
    return _json_safe(out)


def _json_safe(value):
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating, float)):
        return float(value) if math.isfinite(float(value)) else None
    return value


def manifest_document(manifest: Sequence[Mapping], frames: Mapping[str, pd.DataFrame],
                      start: dt.date, end: dt.date) -> dict:
    body = {
        "source": "https://data.binance.vision/data/futures/um",
        "start": start.isoformat(), "end": end.isoformat(),
        "symbols": {symbol: int(len(frame)) for symbol, frame in frames.items()},
        "archives": list(manifest),
    }
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")
    body["corpusSha256"] = hashlib.sha256(canonical).hexdigest()
    return body
