from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[1]


def _frame(periods=12):
    index = pd.date_range("2022-01-01", periods=periods, freq="1h", tz="UTC")
    return pd.DataFrame(
        {
            "open": [100.0] * periods,
            "high": [101.0] * periods,
            "low": [99.0] * periods,
            "close": [100.0] * periods,
            "atr": [10.0] * periods,
            "market_regime": ["BULL"] * periods,
            "vol_regime": ["MID"] * periods,
            "h4_trend_score": [0.5] * periods,
        },
        index=index,
    )


def test_round24_protocol_hash_and_budget(modules):
    protocol_path = ROOT / "protocol/round24/ROUND24_PROTOCOL_LOCKED_2026-07-18T19-06-02Z.json"
    expected = protocol_path.with_suffix(".sha256").read_text().split()[0]
    assert hashlib.sha256(protocol_path.read_bytes()).hexdigest() == expected
    protocol = json.loads(protocol_path.read_text())
    assert protocol["locked_before_evaluation"] is True
    assert protocol["candidate_budget"]["maximum_total_variants"] == 108
    assert protocol["data"]["round22_holdout"].startswith("PERMANENT_DIAGNOSTIC_ONLY")
    assert protocol["android_integration_allowed"] is False


def test_max_hold_means_n_completed_bars(modules):
    e = modules["round23_engine"]
    frame = _frame()
    spec = e.Spec(
        "MECHANICS_TEST",
        "LONG",
        atr_stop_mult=1.0,
        atr_stop_cap=3.0,
        reward_risk=2.0,
        max_hold_bars=3,
        breakeven_r=99.0,
        trail_start_r=99.0,
        cooldown_bars=0,
    )
    event = [{"signal_i": 0, "complete_i": 0, "stop": 90.0}]
    with patch.object(e, "setup_events", return_value=event):
        trades, _ = e.run_backtest(frame, spec, start=frame.index[0], end=frame.index[-1] + pd.Timedelta(hours=1), fee=0, slip=0)
    row = trades.iloc[0]
    assert row.exit_reason == "MAX_HOLD"
    assert row.entry_time == frame.index[1]
    assert row.exit_time == frame.index[4]
    assert row.completed_bars_before_exit == 3
    assert row.bars_held == 3
    assert bool(row.exit_at_bar_open)


def test_time_decay_means_n_completed_bars(modules):
    e, p2 = modules["round23_engine"], modules["round23_phase2"]
    frame = _frame()
    spec = p2.P2Spec(
        "DIRECTIONAL_RANGE_EXPANSION",
        "LONG",
        atr_stop_mult=1.0,
        atr_stop_cap=3.0,
        reward_risk=2.0,
        time_decay_bars=3,
        minimum_favourable_r=0.5,
        max_hold_bars=8,
        cooldown_bars=0,
    )
    event = [{"signal_i": 0, "complete_i": 0, "stop": 90.0}]
    with patch.object(p2, "setup_events", return_value=event):
        trades, _ = p2.run_backtest(frame, spec, start=frame.index[0], end=frame.index[-1] + pd.Timedelta(hours=1), fee=0, slip=0)
    row = trades.iloc[0]
    assert row.exit_reason == "TIME_DECAY"
    assert row.exit_time == frame.index[4]
    assert row.completed_bars_before_exit == 3
    assert bool(row.exit_at_bar_open)


def test_portfolio_collision_and_caps(modules):
    p6 = modules["round23_phase6"]
    start = pd.Timestamp("2024-01-01T00:00:00Z")
    rows = [
        {"entry_time": start, "exit_time": start + pd.Timedelta(hours=5), "module": "VCE_SHORT", "side": "SHORT", "r_net": -1.0},
        {"entry_time": start, "exit_time": start + pd.Timedelta(hours=4), "module": "DRE_SHORT", "side": "SHORT", "r_net": 1.0},
        {"entry_time": start, "exit_time": start + pd.Timedelta(hours=3), "module": "VCE_LONG", "side": "LONG", "r_net": -1.0},
        {"entry_time": start + pd.Timedelta(hours=1), "exit_time": start + pd.Timedelta(hours=2), "module": "MOMENTUM_LONG", "side": "LONG", "r_net": 1.0},
    ]
    accepted = p6.apply_portfolio_rules(pd.DataFrame(rows))
    assert set(accepted.module) == {"DRE_SHORT", "VCE_LONG"}
    assert accepted.portfolio_nominal_risk_after.max() <= p6.PORTFOLIO_RULES["max_total_risk"]
    assert accepted.portfolio_nominal_leverage_after.max() <= p6.PORTFOLIO_RULES["max_total_leverage"]
    for _, group in accepted.groupby("side"):
        ordered = group.sort_values("entry_time")
        for previous, current in zip(ordered.itertuples(), ordered.iloc[1:].itertuples()):
            assert previous.exit_time <= current.entry_time


def test_temporal_bootstrap_and_cluster_mc_are_deterministic(modules):
    e = modules["round23_engine"]
    times = pd.to_datetime(
        ["2023-01-05", "2023-01-05", "2023-02-05", "2023-03-05", "2023-04-05", "2023-05-05"],
        utc=True,
    )
    trades = pd.DataFrame({"entry_time": times, "r_net": [-1.0, -1.0, 0.5, 0.6, -0.2, 0.8]})
    assert e.bootstrap_month(trades, n=200) == e.bootstrap_month(trades, n=200)
    assert e.monte_carlo(trades, n=200) == e.monte_carlo(trades, n=200)
    assert e.bootstrap_month(trades, n=200)["block_months"] == 3
    assert e.monte_carlo(trades, n=200)["cluster_key"] == "entry_time"


def test_holm_correction_controls_family_wise_error(modules):
    e = modules["round23_engine"]
    rows = [{"candidate": f"C{i}", "p_value": value} for i, value in enumerate((0.0001, 0.02, 0.04, 0.5))]
    adjusted = e.holm_bonferroni(rows)
    assert adjusted[0]["holm_adjusted_p"] == 0.0004
    assert adjusted[0]["passes_holm_fwer"]
    assert not adjusted[1]["passes_holm_fwer"]
    ordered = sorted(adjusted, key=lambda row: row["p_value"])
    assert [row["holm_adjusted_p"] for row in ordered] == sorted(row["holm_adjusted_p"] for row in ordered)


def test_fold_boundary_excludes_crossing_exit(modules):
    e = modules["round23_engine"]
    start = pd.Timestamp("2024-01-01T00:00:00Z")
    end = pd.Timestamp("2024-02-01T00:00:00Z")
    trades = pd.DataFrame(
        {
            "entry_time": [start + pd.Timedelta(days=2), start + pd.Timedelta(days=3)],
            "exit_time": [end - pd.Timedelta(hours=1), end],
            "r_net": [1.0, 1.0],
        }
    )
    sliced = e.slice_entries(trades, start, end, require_exit_before=end)
    assert len(sliced) == 1
    assert sliced.exit_time.max() < end


def test_zero_volume_ohlc_is_excluded_from_4h_resample(raw):
    source = raw[["open", "high", "low", "close", "volume"]].copy()
    zero = source.volume <= 0
    assert int(zero.sum()) == 1
    source.loc[zero, ["open", "high", "low", "close"]] = np.nan
    h4 = source.resample("4h", label="left", closed="left", origin="epoch").agg(
        open=("open", "first"),
        high=("high", "max"),
        low=("low", "min"),
        close=("close", "last"),
        volume=("volume", "sum"),
    )
    row = h4.loc[pd.Timestamp("2024-10-28T20:00:00Z")]
    assert round(float(row.open), 2) == 2518.92
    assert round(float(row.low), 2) == 2516.54


def test_bb_std_and_close_location_are_effective(modules, features):
    e, p4 = modules["round23_engine"], modules["round23_phase4"]
    range_spec = next(s for s in e.central_specs() if s.architecture == "RANGE_EXTREME_REVERSION" and s.side == "LONG")
    hashes = {
        hashlib.sha256(json.dumps(e.setup_events(features, replace(range_spec, bb_std=value)), sort_keys=True).encode()).hexdigest()
        for value in (2.0, 2.2, 2.4)
    }
    assert len(hashes) == 3
    follow = next(s for s in p4.central_specs() if s.architecture == "BREAKOUT_FOLLOW_THROUGH_CONFIRMATION" and s.side == "SHORT")
    event_counts = [len(p4.setup_events(features, replace(follow, close_location_min=value))) for value in (0.55, 0.75)]
    assert event_counts[0] != event_counts[1]


def test_governor_neighbours_have_unique_effective_parameters(modules):
    p7 = modules["round23_phase7"]
    for central in p7.central_specs():
        effective = []
        for spec in p7.neighbours(central):
            value = asdict(spec)
            value.pop("variant_id")
            effective.append(json.dumps(value, sort_keys=True))
        assert len(effective) == len(set(effective))


def test_feature_prefix_causality(modules, raw, features):
    e = modules["round23_engine"]
    cutoff = pd.Timestamp("2024-10-29T12:00:00Z")
    truncated = e.prepare_features(raw.loc[:cutoff])
    reference = features.loc[truncated.index]
    for column in truncated.columns:
        if pd.api.types.is_numeric_dtype(truncated[column]):
            assert np.allclose(
                pd.to_numeric(truncated[column], errors="coerce").to_numpy(float),
                pd.to_numeric(reference[column], errors="coerce").to_numpy(float),
                rtol=1e-12,
                atol=1e-12,
                equal_nan=True,
            ), column
        else:
            assert truncated[column].astype("string").fillna("<NA>").equals(reference[column].astype("string").fillna("<NA>")), column
