#!/usr/bin/env python3
"""Execute the preregistered Round 24 search with the corrected engine."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Callable

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class Variant:
    candidate_id: str
    architecture: str
    side: str
    variant_id: str
    spec: object
    runner_name: str


def safe(value):
    if isinstance(value, dict):
        return {str(k): safe(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [safe(v) for v in value]
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        return None if not np.isfinite(value) else float(value)
    if isinstance(value, pd.Timestamp):
        return value.isoformat()
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(safe(value), indent=2, sort_keys=True) + "\n", encoding="utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_protocol(path: Path) -> tuple[dict, str]:
    got = sha256(path)
    checksum = path.with_suffix(".sha256")
    expected = checksum.read_text(encoding="utf-8").split()[0]
    if got != expected:
        raise RuntimeError(f"protocol hash mismatch: expected {expected}, got {got}")
    protocol = json.loads(path.read_text(encoding="utf-8"))
    if not protocol["locked_before_evaluation"] or protocol["candidate_budget"]["adaptive_additions_allowed"]:
        raise RuntimeError("protocol is not locked")
    return protocol, got


def load_modules(engine_dir: Path):
    sys.path.insert(0, str(engine_dir.resolve()))
    import round23_engine as e
    import round23_phase2 as p2
    import round23_phase4 as p4
    import round23_phase6 as p6

    return e, p2, p4, p6


def resolve_central(definition: dict, side: str, e, p2, p4):
    pools = {
        "round23_engine": e.central_specs,
        "round23_phase2": p2.central_specs,
        "round23_phase4": p4.central_specs,
    }
    pool = pools[definition["source_module"]]()
    matches = [
        spec
        for spec in pool
        if spec.architecture == definition["source_architecture"] and spec.side == side
    ]
    if len(matches) != 1:
        raise RuntimeError(f"cannot resolve central spec: {definition['id']} {side}")
    return matches[0]


def build_universe(protocol: dict, e, p2, p4) -> list[Variant]:
    universe = []
    for definition in protocol["architectures"]:
        for side in ("LONG", "SHORT"):
            central = resolve_central(definition, side, e, p2, p4)
            runner_name = definition["source_module"]
            base_id = f"{definition['id']}__{side}"
            universe.append(Variant(f"{base_id}__CENTRAL", definition["id"], side, "CENTRAL", central, runner_name))
            for field, values in definition["neighbours"].items():
                if len(values) != 2:
                    raise RuntimeError(f"{definition['id']} {field} must have exactly two neighbours")
                for value in values:
                    spec = replace(central, **{field: value})
                    variant_id = f"{field}={value}"
                    universe.append(Variant(f"{base_id}__{variant_id}", definition["id"], side, variant_id, spec, runner_name))
    expected = protocol["candidate_budget"]["maximum_total_variants"]
    canonical = [json.dumps(asdict(v.spec), sort_keys=True) for v in universe]
    if len(universe) != expected or len(set(canonical)) != expected:
        raise RuntimeError(f"variant budget/uniqueness failure: {len(universe)} / {len(set(canonical))}")
    return universe


def runner_for(variant: Variant, e, p2, p4) -> Callable:
    return {
        "round23_engine": e.run_backtest,
        "round23_phase2": p2.run_backtest,
        "round23_phase4": p4.run_backtest,
    }[variant.runner_name]


def run_variant(features: pd.DataFrame, variant: Variant, e, p2, p4, stress: dict | None = None) -> pd.DataFrame:
    kwargs = {}
    if stress:
        kwargs = {
            "fee": stress["fee"],
            "slip": stress["slippage"],
            "entry_delay_bars": stress["entry_delay_bars"],
            "extra_entry_penalty": stress["extra_entry_penalty"],
        }
    trades = runner_for(variant, e, p2, p4)(features, variant.spec, **kwargs)[0]
    if len(trades):
        trades = trades.copy()
        trades["candidate_id"] = variant.candidate_id
        trades["variant_id"] = variant.variant_id
        for column in ("signal_time", "entry_time", "exit_time"):
            trades[column] = pd.to_datetime(trades[column], utc=True)
    return trades


def slice_complete(trades: pd.DataFrame, start: pd.Timestamp, end: pd.Timestamp) -> pd.DataFrame:
    if not len(trades):
        return trades.copy()
    return trades[
        (trades.entry_time >= start)
        & (trades.entry_time < end)
        & (trades.exit_time < end)
    ].copy()


def selection_score(trades: pd.DataFrame, e) -> float:
    metrics = e.metrics(trades)
    if metrics["trades"] < 20:
        return -1e9 + metrics["trades"]
    z = trades.copy()
    z["month"] = z.entry_time.dt.tz_localize(None).dt.to_period("M").astype(str)
    monthly = pd.DataFrame(
        [{"month": month, **e.metrics(group)} for month, group in z.groupby("month", sort=True)]
    )
    active = monthly[monthly.trades > 0]
    if len(active) < 6:
        return -1e9 + metrics["trades"]
    return float(
        active.expectancy_r.median()
        + 0.15 * min(float(metrics["profit_factor"] or 0), 2.5)
        + 0.20 * (active.expectancy_r > 0).mean()
        + 0.10 * active.expectancy_r.quantile(0.10)
    )


def nested_monthly(variants: list[Variant], trades: dict[str, pd.DataFrame], protocol: dict, e):
    rules = protocol["validation"]
    start = pd.Timestamp(rules["outer_monthly_start"])
    end = pd.Timestamp(rules["outer_monthly_end_exclusive"])
    purge = pd.Timedelta(hours=rules["outer_purge_hours"])
    embargo = pd.Timedelta(hours=rules["outer_embargo_hours_each_edge"])
    inner_embargo = pd.Timedelta(hours=rules["inner_embargo_hours_each_edge"])
    max_months = rules["inner_validation_months_max"]
    parts, folds = [], []
    for outer_start in pd.date_range(start, end - pd.offsets.MonthBegin(1), freq="MS"):
        outer_end = min(outer_start + pd.offsets.MonthBegin(1), end)
        train_end = outer_start - purge
        inner_start = max(pd.Timestamp("2021-01-01T00:00:00Z"), outer_start - pd.DateOffset(months=max_months))
        inner_test_start = inner_start + inner_embargo
        inner_test_end = train_end - inner_embargo
        scored = []
        for variant in variants:
            inner = slice_complete(trades[variant.candidate_id], inner_test_start, inner_test_end)
            scored.append((selection_score(inner, e), variant.candidate_id, variant, e.metrics(inner)))
        scored.sort(key=lambda item: (-item[0], item[1]))
        score, _, chosen, inner_metrics = scored[0]
        test_start = outer_start + embargo
        test_end = outer_end - embargo
        outer = slice_complete(trades[chosen.candidate_id], test_start, test_end)
        if len(outer):
            outer = outer.copy()
            outer["outer_fold"] = outer_start.strftime("%Y-%m")
            outer["selected_candidate_id"] = chosen.candidate_id
            parts.append(outer)
        folds.append(
            {
                "fold": outer_start.strftime("%Y-%m"),
                "train_end_exclusive": train_end,
                "inner_test_start": inner_test_start,
                "inner_test_end_exclusive": inner_test_end,
                "test_start": test_start,
                "test_end_exclusive": test_end,
                "selected_candidate_id": chosen.candidate_id,
                "selection_score": score,
                "inner": inner_metrics,
                "outer": e.metrics(outer),
            }
        )
    return (pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()), folds


def expanding_annual(variants: list[Variant], trades: dict[str, pd.DataFrame], protocol: dict, e):
    rules = protocol["validation"]
    end = pd.Timestamp(rules["outer_monthly_end_exclusive"])
    purge = pd.Timedelta(hours=rules["outer_purge_hours"])
    embargo = pd.Timedelta(hours=rules["annual_boundary_embargo_hours_each_edge"])
    parts, folds = [], []
    for year in rules["annual_external_years"]:
        start = pd.Timestamp(f"{year}-01-01T00:00:00Z")
        year_end = min(pd.Timestamp(f"{year + 1}-01-01T00:00:00Z"), end)
        train_end = start - purge
        scored = []
        for variant in variants:
            train = trades[variant.candidate_id]
            train = train[(train.entry_time < train_end) & (train.exit_time < train_end)].copy()
            scored.append((selection_score(train, e), variant.candidate_id, variant, e.metrics(train)))
        scored.sort(key=lambda item: (-item[0], item[1]))
        score, _, chosen, train_metrics = scored[0]
        test_start, test_end = start + embargo, year_end - embargo
        outer = slice_complete(trades[chosen.candidate_id], test_start, test_end)
        if len(outer):
            outer = outer.copy()
            outer["outer_fold"] = f"Y{year}"
            outer["selected_candidate_id"] = chosen.candidate_id
            parts.append(outer)
        folds.append(
            {
                "fold": f"Y{year}",
                "train_end_exclusive": train_end,
                "test_start": test_start,
                "test_end_exclusive": test_end,
                "selected_candidate_id": chosen.candidate_id,
                "selection_score": score,
                "train": train_metrics,
                "outer": e.metrics(outer),
            }
        )
    return (pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()), folds


def fixed_selection_stream(folds: list[dict], trades: dict[str, pd.DataFrame]) -> pd.DataFrame:
    parts = []
    for fold in folds:
        chosen = fold["selected_candidate_id"]
        part = slice_complete(trades[chosen], pd.Timestamp(fold["test_start"]), pd.Timestamp(fold["test_end_exclusive"]))
        if len(part):
            part = part.copy()
            part["outer_fold"] = fold["fold"]
            part["selected_candidate_id"] = chosen
            parts.append(part)
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()


def frequency(trades: pd.DataFrame, start: pd.Timestamp, end: pd.Timestamp) -> dict:
    if len(trades):
        times = pd.to_datetime(trades.entry_time, utc=True).dt.tz_localize(None)
    else:
        times = pd.Series([], dtype="datetime64[ns]")
    months = pd.period_range(start.tz_localize(None).to_period("M"), (end - pd.Timedelta(seconds=1)).tz_localize(None).to_period("M"), freq="M")
    weeks = pd.period_range(start.tz_localize(None).to_period("W-SUN"), (end - pd.Timedelta(seconds=1)).tz_localize(None).to_period("W-SUN"), freq="W-SUN")
    monthly = times.dt.to_period("M").value_counts().reindex(months, fill_value=0).sort_index()
    weekly = times.dt.to_period("W-SUN").value_counts().reindex(weeks, fill_value=0).sort_index()
    return {
        "calendar_start": start,
        "calendar_end_exclusive": end,
        "signals": int(len(trades)),
        "mean_signals_per_week": float(weekly.mean()),
        "median_signals_per_week": float(weekly.median()),
        "mean_signals_per_month": float(monthly.mean()),
        "median_signals_per_month": float(monthly.median()),
        "weeks": int(len(weekly)),
        "months": int(len(monthly)),
        "zero_signal_weeks": int((weekly == 0).sum()),
        "zero_signal_months": int((monthly == 0).sum()),
    }


def fold_table(trades: pd.DataFrame, e) -> pd.DataFrame:
    if not len(trades):
        return pd.DataFrame(columns=["fold", "trades", "profit_factor", "expectancy_r"])
    return pd.DataFrame([{"fold": fold, **e.metrics(group)} for fold, group in trades.groupby("outer_fold", sort=True)])


def gate_direction(
    central: pd.DataFrame,
    nested: pd.DataFrame,
    annual: pd.DataFrame,
    stress_streams: dict[str, pd.DataFrame],
    neighbours: list[pd.DataFrame],
    full_variant_holm: float,
    outer_holm: float,
    protocol: dict,
    e,
) -> tuple[dict, dict]:
    required = protocol["required_all_per_direction"]
    cm, nm, am = e.metrics(central), e.metrics(nested), e.metrics(annual)
    months = e.month_table(nested)
    active_months = months[months.trades > 0]
    quarters = e.quarter_table(nested)
    active_quarters = quarters[quarters.trades > 0]
    annual_rows = fold_table(annual, e)
    stress_metrics = {key: e.metrics(value) for key, value in stress_streams.items()}
    temporal = e.bootstrap_month(nested, n=protocol["multiple_testing"]["bootstrap_samples"])
    monte = e.monte_carlo(nested, n=protocol["multiple_testing"]["bootstrap_samples"])
    freq = frequency(
        nested,
        pd.Timestamp(protocol["validation"]["outer_monthly_start"]),
        pd.Timestamp(protocol["validation"]["outer_monthly_end_exclusive"]),
    )
    neighbour_metrics = [e.metrics(frame) for frame in neighbours]
    neighbour_positive = sum(
        metric["expectancy_r"] > 0 and float(metric["profit_factor"] or 0) > 1.0
        for metric in neighbour_metrics
    ) / max(len(neighbour_metrics), 1)
    cost = [stress_metrics[key] for key in ("FEES_X1_5", "FEES_X2", "SLIPPAGE_X2")]
    checks = {
        "central_trades": cm["trades"] >= required["central_trades_min"],
        "central_pf": float(cm["profit_factor"] or 0) >= required["central_profit_factor_min"],
        "central_expectancy": cm["expectancy_r"] >= required["central_expectancy_r_min"],
        "nested_trades": nm["trades"] >= required["nested_outer_trades_min"],
        "nested_pf": float(nm["profit_factor"] or 0) >= required["nested_outer_profit_factor_min"],
        "nested_expectancy": nm["expectancy_r"] >= required["nested_outer_expectancy_r_min"],
        "active_months": len(active_months) >= required["active_outer_months_min"],
        "positive_month_ratio": (float((active_months.expectancy_r > 0).mean()) if len(active_months) else 0) >= required["positive_active_month_ratio_min"],
        "positive_quarter_ratio": (float((active_quarters.expectancy_r > 0).mean()) if len(active_quarters) else 0) >= required["positive_rolling_quarter_ratio_min"],
        "worst_quarter": (float(active_quarters.expectancy_r.min()) if len(active_quarters) else -math.inf) >= required["worst_rolling_quarter_expectancy_r_min"],
        "annual_active_folds": int((annual_rows.trades > 0).sum()) >= required["annual_active_folds_min"] if len(annual_rows) else False,
        "annual_positive_ratio": (float((annual_rows.expectancy_r > 0).mean()) if len(annual_rows) else 0) >= required["annual_positive_ratio_min"],
        "annual_pf": float(am["profit_factor"] or 0) >= required["annual_aggregate_profit_factor_min"],
        "annual_expectancy": am["expectancy_r"] >= required["annual_aggregate_expectancy_r_min"],
        "worst_annual": (float(annual_rows.expectancy_r.min()) if len(annual_rows) else -math.inf) >= required["worst_annual_expectancy_r_min"],
        "cost_pf": all(float(metric["profit_factor"] or 0) >= required["all_cost_stress_profit_factor_min"] for metric in cost),
        "cost_expectancy": all(metric["expectancy_r"] >= required["all_cost_stress_expectancy_r_min"] for metric in cost),
        "delay1_pf": float(stress_metrics["ENTRY_DELAY_1H"]["profit_factor"] or 0) >= required["delay_1h_profit_factor_min"],
        "delay1_expectancy": stress_metrics["ENTRY_DELAY_1H"]["expectancy_r"] >= required["delay_1h_expectancy_r_min"],
        "delay2_expectancy": stress_metrics["ENTRY_DELAY_2H"]["expectancy_r"] >= required["delay_2h_expectancy_r_min"],
        "gap_expectancy": stress_metrics["ADVERSE_GAP_10BP"]["expectancy_r"] >= required["adverse_gap_expectancy_r_min"],
        "neighbour_fraction": neighbour_positive >= required["parameter_neighbour_positive_fraction_min"],
        "bootstrap_probability": temporal["p_mean_positive"] >= required["temporal_bootstrap_probability_positive_min"],
        "bootstrap_q10": float(temporal["q10_mean_r"] or -math.inf) >= required["temporal_bootstrap_q10_expectancy_r_min"],
        "monte_carlo_drawdown": float(monte["q95_max_drawdown_abs"] or math.inf) <= required["temporal_cluster_monte_carlo_q95_drawdown_max"],
        "full_variant_holm": full_variant_holm <= required["holm_adjusted_p_max"],
        "outer_holm": outer_holm <= required["holm_adjusted_p_max"],
        "frequency_week_mean": freq["mean_signals_per_week"] >= required["average_signals_per_week_min"],
        "frequency_month_mean": freq["mean_signals_per_month"] >= required["average_signals_per_month_min"],
        "frequency_month_median": freq["median_signals_per_month"] >= required["median_signals_per_month_min"],
    }
    diagnostics = {
        "central": cm,
        "nested_outer": nm,
        "annual_outer": am,
        "annual_folds": annual_rows.to_dict("records"),
        "stress": stress_metrics,
        "temporal_bootstrap": temporal,
        "temporal_cluster_monte_carlo": monte,
        "signal_frequency": freq,
        "neighbour_positive_fraction": neighbour_positive,
        "full_variant_holm_adjusted_p": full_variant_holm,
        "nested_outer_holm_adjusted_p": outer_holm,
        "failed_checks": [key for key, passed in checks.items() if not passed],
    }
    return {"passes_all": all(checks.values()), "checks": checks, "diagnostics": diagnostics}, freq


def combine_portfolio(frames: list[tuple[str, pd.DataFrame]], p6) -> pd.DataFrame:
    parts = []
    for name, frame in frames:
        if len(frame):
            copy = frame.copy()
            copy["module"] = name
            parts.append(copy)
    if not parts:
        return pd.DataFrame()
    return p6.apply_portfolio_rules(pd.concat(parts, ignore_index=True))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine-dir", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    protocol, protocol_hash = verify_protocol(args.protocol)
    e, p2, p4, p6 = load_modules(args.engine_dir)
    raw = e.load_ohlcv(args.corpus)
    if sha256(args.corpus) != protocol["data"]["corpus_sha256"]:
        raise RuntimeError("corpus hash mismatch")
    features = e.prepare_features(raw)
    universe = build_universe(protocol, e, p2, p4)
    by_group: dict[tuple[str, str], list[Variant]] = {}
    for variant in universe:
        by_group.setdefault((variant.architecture, variant.side), []).append(variant)

    base_trades = {variant.candidate_id: run_variant(features, variant, e, p2, p4) for variant in universe}
    variant_rows = []
    for variant in universe:
        trades = base_trades[variant.candidate_id]
        variant_rows.append(
            {
                "candidate": variant.candidate_id,
                "architecture": variant.architecture,
                "side": variant.side,
                "variant_id": variant.variant_id,
                "spec": asdict(variant.spec),
                "metrics": e.metrics(trades),
                "p_value": e.temporal_null_pvalue(trades, n=protocol["multiple_testing"]["bootstrap_samples"]),
            }
        )
    adjusted_variants = e.holm_bonferroni(variant_rows, alpha=protocol["multiple_testing"]["alpha"])
    adjusted_by_id = {row["candidate"]: row for row in adjusted_variants}

    work = {}
    outer_p_values = []
    for (architecture, side), variants in sorted(by_group.items()):
        central = next(v for v in variants if v.variant_id == "CENTRAL")
        nested, nested_folds = nested_monthly(variants, base_trades, protocol, e)
        annual, annual_folds = expanding_annual(variants, base_trades, protocol, e)
        outer_p = e.temporal_null_pvalue(nested, n=protocol["multiple_testing"]["bootstrap_samples"])
        key = f"{architecture}__{side}"
        outer_p_values.append({"candidate": key, "p_value": outer_p})
        work[key] = {
            "architecture": architecture,
            "side": side,
            "variants": variants,
            "central": base_trades[central.candidate_id],
            "nested": nested,
            "nested_folds": nested_folds,
            "annual": annual,
            "annual_folds": annual_folds,
            "outer_p": outer_p,
        }
    adjusted_outer = {
        row["candidate"]: row
        for row in e.holm_bonferroni(outer_p_values, alpha=protocol["multiple_testing"]["alpha"])
    }

    stress_cache: dict[tuple[str, str], pd.DataFrame] = {}
    reports, frequency_rows = [], []
    for key, item in sorted(work.items()):
        variants = item["variants"]
        stress_streams = {}
        for stress in protocol["stress_matrix"]:
            if stress["id"] == "BASE":
                stress_variant_trades = base_trades
            else:
                stress_variant_trades = {}
                for variant in variants:
                    cache_key = (variant.candidate_id, stress["id"])
                    if cache_key not in stress_cache:
                        stress_cache[cache_key] = run_variant(features, variant, e, p2, p4, stress)
                    stress_variant_trades[variant.candidate_id] = stress_cache[cache_key]
            stress_streams[stress["id"]] = fixed_selection_stream(item["nested_folds"], stress_variant_trades)
        neighbour_frames = [base_trades[v.candidate_id] for v in variants if v.variant_id != "CENTRAL"]
        full_holm = min(adjusted_by_id[v.candidate_id]["holm_adjusted_p"] for v in variants)
        outer_holm = adjusted_outer[key]["holm_adjusted_p"]
        gate, freq = gate_direction(
            item["central"],
            item["nested"],
            item["annual"],
            stress_streams,
            neighbour_frames,
            full_holm,
            outer_holm,
            protocol,
            e,
        )
        report = {
            "candidate": key,
            "architecture": item["architecture"],
            "side": item["side"],
            "passes_all": gate["passes_all"],
            "gate": gate,
            "variant_count": len(variants),
            "best_full_variant_holm_adjusted_p": full_holm,
            "nested_outer_selection_correction": adjusted_outer[key],
            "round22_holdout_used": False,
            "android_integration_allowed": False,
        }
        reports.append(report)
        frequency_rows.append({"candidate": key, **freq})
        directory = args.output / "candidates" / key
        directory.mkdir(parents=True, exist_ok=True)
        item["central"].to_csv(directory / "central_trades.csv", index=False)
        item["nested"].to_csv(directory / "nested_monthly_outer_trades.csv", index=False)
        item["annual"].to_csv(directory / "annual_external_trades.csv", index=False)
        pd.DataFrame(item["nested_folds"]).to_json(directory / "nested_monthly_folds.json", orient="records", indent=2, date_format="iso")
        pd.DataFrame(item["annual_folds"]).to_json(directory / "annual_external_folds.json", orient="records", indent=2, date_format="iso")
        write_json(directory / "REPORT.json", report)

    eligible = [report for report in reports if report["passes_all"]]
    eligible_long = sorted((r for r in eligible if r["side"] == "LONG"), key=lambda r: (r["nested_outer_selection_correction"]["holm_adjusted_p"], -r["gate"]["diagnostics"]["nested_outer"]["expectancy_r"], r["candidate"]))
    eligible_short = sorted((r for r in eligible if r["side"] == "SHORT"), key=lambda r: (r["nested_outer_selection_correction"]["holm_adjusted_p"], -r["gate"]["diagnostics"]["nested_outer"]["expectancy_r"], r["candidate"]))
    combined_report = {"constructed": False, "passes_all": False, "reason": "LONG_AND_SHORT_MUST_PASS_SEPARATELY"}
    if eligible_long and eligible_short:
        selected = [eligible_long[0]["candidate"], eligible_short[0]["candidate"]]
        combined = combine_portfolio([(name, work[name]["nested"]) for name in selected], p6)
        combined_annual = combine_portfolio([(name, work[name]["annual"]) for name in selected], p6)
        combined_stress = {}
        for stress in protocol["stress_matrix"]:
            streams = []
            for name in selected:
                variants = work[name]["variants"]
                if stress["id"] == "BASE":
                    source = base_trades
                else:
                    source = {variant.candidate_id: stress_cache[(variant.candidate_id, stress["id"])] for variant in variants}
                streams.append((name, fixed_selection_stream(work[name]["nested_folds"], source)))
            combined_stress[stress["id"]] = combine_portfolio(streams, p6)
        required = protocol["required_all_combined"]
        nm, am = e.metrics(combined), e.metrics(combined_annual)
        freq = frequency(combined, pd.Timestamp(protocol["validation"]["outer_monthly_start"]), pd.Timestamp(protocol["validation"]["outer_monthly_end_exclusive"]))
        months = e.month_table(combined);active = months[months.trades > 0];annual_rows = fold_table(combined_annual, e)
        stress_metrics = {key: e.metrics(value) for key, value in combined_stress.items()}
        monte = e.monte_carlo(combined, n=protocol["multiple_testing"]["bootstrap_samples"])
        cost = [stress_metrics[key] for key in ("FEES_X1_5", "FEES_X2", "SLIPPAGE_X2")]
        checks = {
            "eligible_directions": len(selected) >= required["eligible_directions_min"],
            "nested_trades": nm["trades"] >= required["nested_outer_trades_min"],
            "nested_pf": float(nm["profit_factor"] or 0) >= required["nested_outer_profit_factor_min"],
            "nested_expectancy": nm["expectancy_r"] >= required["nested_outer_expectancy_r_min"],
            "positive_month_ratio": (float((active.expectancy_r > 0).mean()) if len(active) else 0) >= required["positive_active_month_ratio_min"],
            "annual_positive_ratio": (float((annual_rows.expectancy_r > 0).mean()) if len(annual_rows) else 0) >= required["annual_positive_ratio_min"],
            "cost_pf": all(float(metric["profit_factor"] or 0) >= required["all_cost_stress_profit_factor_min"] for metric in cost),
            "monte_carlo_drawdown": float(monte["q95_max_drawdown_abs"] or math.inf) <= required["temporal_cluster_monte_carlo_q95_drawdown_max"],
            "frequency_week_mean": freq["mean_signals_per_week"] >= required["average_signals_per_week_min"],
            "frequency_month_mean": freq["mean_signals_per_month"] >= required["average_signals_per_month_min"],
            "frequency_month_median": freq["median_signals_per_month"] >= required["median_signals_per_month_min"],
        }
        combined_report = {
            "constructed": True,
            "selected_directions": selected,
            "portfolio_rules": protocol["portfolio"],
            "passes_all": all(checks.values()),
            "checks": checks,
            "metrics": nm,
            "annual_metrics": am,
            "stress": stress_metrics,
            "signal_frequency": freq,
            "temporal_cluster_monte_carlo": monte,
            "failed_checks": [key for key, passed in checks.items() if not passed],
        }
        combined.to_csv(args.output / "ROUND24_COMBINED_OUTER_TRADES.csv", index=False)

    stable = bool(eligible)
    decision = {
        "round": 24,
        "stable": stable,
        "research_only": True,
        "decision": "ROUND24_STABLE_RESEARCH_ONLY" if stable else "ROUND24_REJECTED_NO_DIRECTION_PASSED_ALL_LOCKED_GATES",
        "eligible_direction_count": len(eligible),
        "eligible_directions": [report["candidate"] for report in eligible],
        "combined_portfolio": combined_report,
        "candidate_budget_used": len(universe),
        "candidate_budget_max": protocol["candidate_budget"]["maximum_total_variants"],
        "protocol_sha256": protocol_hash,
        "corpus_sha256": sha256(args.corpus),
        "round22_holdout_used_for_selection": False,
        "round22_holdout_used_for_validation": False,
        "round22_holdout_loaded": False,
        "scalp_engine_touched": False,
        "android_files_touched": False,
        "android_integration_allowed": False,
        "candidate_reports": reports,
    }
    write_json(args.output / "ROUND24_FINAL_DECISION.json", decision)
    write_json(args.output / "ROUND24_VARIANT_RESULTS.json", {"variants": adjusted_variants})
    pd.DataFrame(
        [
            {
                "candidate": row["candidate"],
                "architecture": row["architecture"],
                "side": row["side"],
                "variant_id": row["variant_id"],
                **{f"metric_{key}": value for key, value in row["metrics"].items()},
                "p_value": row["p_value"],
                "holm_adjusted_p": row["holm_adjusted_p"],
                "passes_holm_fwer": row["passes_holm_fwer"],
            }
            for row in adjusted_variants
        ]
    ).to_csv(args.output / "ROUND24_VARIANT_RESULTS.csv", index=False)
    write_json(args.output / "SIGNAL_FREQUENCY.json", {"rows": frequency_rows})
    pd.DataFrame(frequency_rows).to_csv(args.output / "SIGNAL_FREQUENCY.csv", index=False)
    lines = [
        "# Round 24 — décision stricte",
        "",
        f"- stable: `{str(stable).lower()}`",
        "- research_only: `true`",
        "- android_integration_allowed: `false`",
        f"- variantes exécutées: {len(universe)} / {protocol['candidate_budget']['maximum_total_variants']}",
        f"- directions admises: {len(eligible)} / 12",
        "- holdout Round 22: non chargé et non utilisé",
        "",
        "| Direction | Stable | Trades externes | PF externe | Espérance R | Signaux/semaine moyen | Signaux/mois médian | Échecs |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for report in reports:
        diag = report["gate"]["diagnostics"]
        freq = diag["signal_frequency"]
        lines.append(
            f"| {report['candidate']} | {report['passes_all']} | {diag['nested_outer']['trades']} | "
            f"{float(diag['nested_outer']['profit_factor'] or 0):.3f} | {diag['nested_outer']['expectancy_r']:.3f} | "
            f"{freq['mean_signals_per_week']:.2f} | {freq['median_signals_per_month']:.1f} | "
            f"{len(diag['failed_checks'])} |"
        )
    (args.output / "ROUND24_SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({key: decision[key] for key in ("stable", "decision", "eligible_direction_count", "candidate_budget_used", "android_integration_allowed")}, indent=2))


if __name__ == "__main__":
    main()
