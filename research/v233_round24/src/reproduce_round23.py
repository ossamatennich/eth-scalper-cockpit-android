#!/usr/bin/env python3
"""Reproduce the 43 frozen Round 23 candidates with one selected engine."""
from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import math
import sys
from dataclasses import asdict
from pathlib import Path

import numpy as np
import pandas as pd


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


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(safe(value), indent=2, sort_keys=True) + "\n", encoding="utf-8")


def canonical_trade_hash(trades: pd.DataFrame) -> str:
    if not len(trades):
        return hashlib.sha256(b"EMPTY\n").hexdigest()
    columns = [
        c
        for c in (
            "module",
            "architecture",
            "side",
            "signal_time",
            "entry_time",
            "exit_time",
            "exit_reason",
            "entry",
            "exit",
            "r_net",
        )
        if c in trades
    ]
    frame = trades[columns].copy()
    for column in ("signal_time", "entry_time", "exit_time"):
        if column in frame:
            frame[column] = pd.to_datetime(frame[column], utc=True).dt.strftime("%Y-%m-%dT%H:%M:%S%z")
    for column in frame.select_dtypes(include=[np.number]).columns:
        frame[column] = frame[column].map(lambda x: "" if pd.isna(x) else f"{float(x):.12g}")
    payload = frame.fillna("").to_csv(index=False, lineterminator="\n").encode()
    return hashlib.sha256(payload).hexdigest()


def metric_delta(before: dict, after: dict) -> dict:
    result = {}
    for key in ("trades", "profit_factor", "expectancy_r", "return_r", "max_drawdown_r"):
        a, b = before.get(key), after.get(key)
        result[key] = None if a is None or b is None else float(b) - float(a)
    return result


def load_engine(engine_dir: Path):
    sys.path.insert(0, str(engine_dir.resolve()))
    names = [
        "round23_engine",
        "round23_phase2",
        "round23_phase3",
        "round23_phase4",
        "round23_phase5",
        "round23_phase6",
        "round23_phase7",
    ]
    return [importlib.import_module(name) for name in names]


def candidates(e, p2, p3, p4, p5, p6, p7):
    for spec in e.central_specs():
        yield "phase1", f"{spec.architecture}__{spec.side}", spec, lambda f, s=spec: e.run_backtest(f, s)[0]
    for spec in p2.central_specs():
        yield "phase2", f"{spec.architecture}__{spec.side}", spec, lambda f, s=spec: p2.run_backtest(f, s)[0]
    for spec in p3.central_specs():
        yield "phase3", f"{spec.architecture}__{spec.side}", spec, lambda f, s=spec: p3.run_backtest(f, s)[0]
    for spec in p4.central_specs():
        yield "phase4", f"{spec.architecture}__{spec.side}", spec, lambda f, s=spec: p4.run_backtest(f, s)[0]
    for spec in p5.specs():
        yield "phase5", f"{spec.architecture}__{spec.side}", spec, lambda f, s=spec: p3.run_backtest(f, s)[0]
    for spec in p6.specs():
        yield "phase6", spec.architecture, spec, lambda f, s=spec: p6.module_trades(f, s)
    for spec in p7.central_specs():
        yield "phase7", spec.architecture, spec, lambda f, s=spec: p7.governed(f, s)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine-dir", type=Path, required=True)
    parser.add_argument("--engine-label", choices=("legacy", "corrected"), required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--reference-root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    trades_dir = args.output / "trades"
    trades_dir.mkdir(exist_ok=True)

    e, p2, p3, p4, p5, p6, p7 = load_engine(args.engine_dir)
    raw = e.load_ohlcv(args.corpus)
    features = e.prepare_features(raw)
    rows = []
    for phase, name, spec, runner in candidates(e, p2, p3, p4, p5, p6, p7):
        trades = runner(features)
        if len(trades):
            for column in ("signal_time", "entry_time", "exit_time"):
                trades[column] = pd.to_datetime(trades[column], utc=True)
        metrics = e.metrics(trades)
        directions = {
            side: e.metrics(trades[trades.side == side]) if len(trades) else e.metrics(trades)
            for side in ("LONG", "SHORT")
        }
        candidate_id = f"{phase}/{name}"
        file_name = candidate_id.replace("/", "__") + ".csv"
        trades.to_csv(trades_dir / file_name, index=False)
        row = {
            "candidate": candidate_id,
            "phase": phase,
            "name": name,
            "spec": asdict(spec),
            "metrics": metrics,
            "direction_metrics": {**directions, "COMBINED": metrics},
            "trade_hash": canonical_trade_hash(trades),
            "trade_file": f"trades/{file_name}",
        }
        if args.engine_label == "corrected":
            row["temporal_null_p_value"] = e.temporal_null_pvalue(trades)
            row["temporal_bootstrap"] = e.bootstrap_month(trades, n=4000)
            row["temporal_cluster_monte_carlo"] = e.monte_carlo(trades, n=4000)
        if args.reference_root:
            reference_path = args.reference_root / "results" / phase / name / "central_trades.csv"
            if not reference_path.exists():
                raise RuntimeError(f"missing packaged reference: {reference_path}")
            reference = pd.read_csv(reference_path)
            for column in ("signal_time", "entry_time", "exit_time"):
                if column in reference:
                    reference[column] = pd.to_datetime(reference[column], utc=True)
            reference_metrics = e.metrics(reference)
            row["packaged_reference"] = {
                "path": str(reference_path),
                "metrics": reference_metrics,
                "metric_delta": metric_delta(reference_metrics, metrics),
                "trade_hash": canonical_trade_hash(reference),
                "trade_signature_match": canonical_trade_hash(reference) == canonical_trade_hash(trades),
            }
        rows.append(row)

    if len(rows) != 43:
        raise RuntimeError(f"expected 43 candidates, got {len(rows)}")
    if args.engine_label == "corrected":
        corrected = e.holm_bonferroni(
            [{"candidate": row["candidate"], "p_value": row["temporal_null_p_value"]} for row in rows]
        )
        adjustment = {row["candidate"]: row for row in corrected}
        for row in rows:
            row["selection_correction"] = adjustment[row["candidate"]]

    metadata = {
        "engine_label": args.engine_label,
        "candidate_count": len(rows),
        "corpus_sha256": sha256(args.corpus),
        "engine_files_sha256": {
            path.name: sha256(path) for path in sorted(args.engine_dir.glob("*.py"))
        },
        "holdout_round22_loaded": False,
        "android_integration_allowed": False,
        "scalp_engine_touched": False,
    }
    write_json(args.output / "REPRODUCTION_SUMMARY.json", {"metadata": metadata, "candidates": rows})
    flat = []
    for row in rows:
        flat.append(
            {
                "candidate": row["candidate"],
                **{f"metric_{k}": v for k, v in row["metrics"].items()},
                "trade_hash": row["trade_hash"],
                "temporal_null_p_value": row.get("temporal_null_p_value"),
                "holm_adjusted_p": row.get("selection_correction", {}).get("holm_adjusted_p"),
                "passes_holm_fwer": row.get("selection_correction", {}).get("passes_holm_fwer"),
            }
        )
    pd.DataFrame(flat).to_csv(args.output / "REPRODUCTION_SUMMARY.csv", index=False)
    print(json.dumps(metadata, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
