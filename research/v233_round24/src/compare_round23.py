#!/usr/bin/env python3
"""Build candidate-by-candidate pre/post and determinism evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import pandas as pd


METRICS = ("trades", "profit_factor", "expectancy_r", "return_r", "max_drawdown_r")
ENSEMBLE_PHASES = {"phase6", "phase7"}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def delta(before: dict, after: dict) -> dict:
    return {
        key: None if before.get(key) is None or after.get(key) is None else float(after[key]) - float(before[key])
        for key in METRICS
    }


def central_gate(metrics: dict) -> bool:
    return bool(
        metrics["trades"] >= 100
        and float(metrics.get("profit_factor") or 0.0) >= 1.25
        and metrics["expectancy_r"] >= 0.07
    )


def classify(before: dict, after: dict, adjusted_p: float, directional_pass: bool) -> str:
    if after["trades"] == 0:
        return "DISAPPEARED"
    if before["expectancy_r"] > 0 >= after["expectancy_r"]:
        return "EDGE_DISAPPEARED"
    if after["expectancy_r"] < before["expectancy_r"] - 0.01 or float(after.get("profit_factor") or 0) < float(before.get("profit_factor") or 0) - 0.05:
        return "DECLINED"
    if central_gate(after) and directional_pass and adjusted_p <= 0.05:
        return "SURVIVES_CORRECTION_NOT_VALIDATED"
    if abs(after["expectancy_r"] - before["expectancy_r"]) <= 0.01 and abs(float(after.get("profit_factor") or 0) - float(before.get("profit_factor") or 0)) <= 0.05:
        return "ESSENTIALLY_UNCHANGED_NOT_VALIDATED"
    return "IMPROVED_OR_MIXED_NOT_VALIDATED"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy-a", type=Path, required=True)
    parser.add_argument("--legacy-b", type=Path, required=True)
    parser.add_argument("--corrected-a", type=Path, required=True)
    parser.add_argument("--corrected-b", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    la, lb, ca, cb = (load(p / "REPRODUCTION_SUMMARY.json") for p in (args.legacy_a, args.legacy_b, args.corrected_a, args.corrected_b))
    maps = [{row["candidate"]: row for row in doc["candidates"]} for doc in (la, lb, ca, cb)]
    ids = sorted(maps[0])
    if len(ids) != 43 or any(sorted(m) != ids for m in maps):
        raise RuntimeError("the four reproductions do not contain the same 43 candidates")

    rows = []
    for candidate in ids:
        old_a, old_b, new_a, new_b = (m[candidate] for m in maps)
        old_deterministic = old_a["trade_hash"] == old_b["trade_hash"]
        new_deterministic = new_a["trade_hash"] == new_b["trade_hash"]
        selection = new_a["selection_correction"]
        direction_checks = {}
        for side in ("LONG", "SHORT"):
            metrics = new_a["direction_metrics"][side]
            if metrics["trades"] > 0:
                direction_checks[side] = {"central_gate_pass": central_gate(metrics), "metrics": metrics}
        directional_pass = all(x["central_gate_pass"] for x in direction_checks.values())
        status = classify(
            old_a["metrics"],
            new_a["metrics"],
            float(selection["holm_adjusted_p"]),
            directional_pass,
        )
        rows.append(
            {
                "candidate": candidate,
                "phase": old_a["phase"],
                "legacy": old_a["metrics"],
                "corrected": new_a["metrics"],
                "delta": delta(old_a["metrics"], new_a["metrics"]),
                "legacy_trade_hash": old_a["trade_hash"],
                "corrected_trade_hash": new_a["trade_hash"],
                "trade_sequence_changed": old_a["trade_hash"] != new_a["trade_hash"],
                "legacy_deterministic": old_deterministic,
                "corrected_deterministic": new_deterministic,
                "packaged_round23_reproduction": old_a.get("packaged_reference"),
                "selection_correction": selection,
                "directional_central_gates": direction_checks if old_a["phase"] in ENSEMBLE_PHASES else {},
                "classification": status,
                "validated": False,
            }
        )

    packaged_deltas = []
    for row in rows:
        reference = row.get("packaged_round23_reproduction") or {}
        for value in (reference.get("metric_delta") or {}).values():
            if value is not None:
                packaged_deltas.append(abs(float(value)))
    summary = {
        "candidate_count": len(rows),
        "legacy_deterministic": all(row["legacy_deterministic"] for row in rows),
        "corrected_deterministic": all(row["corrected_deterministic"] for row in rows),
        "legacy_packaged_reproduction_max_abs_metric_delta": max(packaged_deltas, default=0.0),
        "legacy_packaged_reproduction_pass_at_1e_12": max(packaged_deltas, default=0.0) <= 1e-12,
        "trade_sequences_changed": sum(row["trade_sequence_changed"] for row in rows),
        "holm_fwer_pass_count": sum(row["selection_correction"]["passes_holm_fwer"] for row in rows),
        "classifications": pd.Series([row["classification"] for row in rows]).value_counts().sort_index().to_dict(),
        "old_quasi_candidates_validated": False,
        "round22_holdout_used": False,
        "android_integration_allowed": False,
        "scalp_engine_touched": False,
    }
    payload = {"summary": summary, "candidates": rows}
    (args.output / "ROUND23_PRE_POST_COMPARISON.json").write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    flat = []
    for row in rows:
        flat.append(
            {
                "candidate": row["candidate"],
                "classification": row["classification"],
                "legacy_trades": row["legacy"]["trades"],
                "corrected_trades": row["corrected"]["trades"],
                "legacy_pf": row["legacy"]["profit_factor"],
                "corrected_pf": row["corrected"]["profit_factor"],
                "legacy_expectancy_r": row["legacy"]["expectancy_r"],
                "corrected_expectancy_r": row["corrected"]["expectancy_r"],
                "holm_adjusted_p": row["selection_correction"]["holm_adjusted_p"],
                "legacy_deterministic": row["legacy_deterministic"],
                "corrected_deterministic": row["corrected_deterministic"],
                "validated": False,
            }
        )
    pd.DataFrame(flat).to_csv(args.output / "ROUND23_PRE_POST_COMPARISON.csv", index=False)
    lines = [
        "# Round 23 — reproduction avant/après",
        "",
        "Les 43 paramètres centraux sont inchangés. Aucun ancien quasi-candidat n'est validé par cette reproduction.",
        "",
        f"- Reproduction legacy à 1e-12 : {summary['legacy_packaged_reproduction_pass_at_1e_12']}",
        f"- Déterminisme legacy/corrigé : {summary['legacy_deterministic']} / {summary['corrected_deterministic']}",
        f"- Séquences de trades modifiées : {summary['trade_sequences_changed']} / 43",
        f"- Survivants Holm FWER 5 % : {summary['holm_fwer_pass_count']} / 43",
        "",
        "| Candidat | Classification | Trades ancien → corrigé | PF ancien → corrigé | Espérance R ancienne → corrigée | p Holm |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for row in rows:
        old, new = row["legacy"], row["corrected"]
        lines.append(
            f"| {row['candidate']} | {row['classification']} | {old['trades']} → {new['trades']} | "
            f"{float(old.get('profit_factor') or 0):.3f} → {float(new.get('profit_factor') or 0):.3f} | "
            f"{old['expectancy_r']:.3f} → {new['expectancy_r']:.3f} | {row['selection_correction']['holm_adjusted_p']:.4f} |"
        )
    (args.output / "ROUND23_PRE_POST_COMPARISON.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
