#!/usr/bin/env python3
"""Run the pre-registered ETH/SOL research cycle and lock one holdout evaluation."""
from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import math
from pathlib import Path

try:
    from tools.scalp_research import (
        DEFAULT_SPLITS, Geometry, RuleSpec, _json_safe, _summary, acquire_corpus,
        align_corpus, build_features, deflated_sharpe_probability, generate_rules,
        manifest_document, performance_json, simulate,
    )
except ImportError:  # Direct execution from tools/.
    from scalp_research import (
        DEFAULT_SPLITS, Geometry, RuleSpec, _json_safe, _summary, acquire_corpus,
        align_corpus, build_features, deflated_sharpe_probability, generate_rules,
        manifest_document, performance_json, simulate,
    )


GEOMETRIES = (Geometry(1.25, 1.0), Geometry(1.75, 1.25))
PROTOCOL_ID = "NMC_CAUSAL_SCALP_RESEARCH_V1_20260808"
PROTOCOL_SCHEMA = "NMC_CAUSAL_SCALP_RESEARCH_SCHEMA_V1"
MAX_CONFIGURATIONS = 128


def compact(performance):
    value = performance_json(performance)
    value.pop("results", None)
    return value


def development_gate(train, validation) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    train_resolved = train.tp + train.sl
    validation_resolved = validation.tp + validation.sl
    if train_resolved < 100:
        reasons.append("TRAIN_TRADES_LT_100")
    if validation_resolved < 20:
        reasons.append("VALIDATION_TRADES_LT_20")
    if train.expectancy_r is None or train.expectancy_r < 0.03:
        reasons.append("TRAIN_EXPECTANCY_LT_003R")
    if validation.expectancy_r is None or validation.expectancy_r <= 0:
        reasons.append("VALIDATION_EXPECTANCY_NOT_POSITIVE")
    if train.profit_factor_r is None or train.profit_factor_r < 1.10:
        reasons.append("TRAIN_PF_LT_110")
    if validation.profit_factor_r is None or validation.profit_factor_r < 1.05:
        reasons.append("VALIDATION_PF_LT_105")
    if train.monthly_positive_ratio is None or train.monthly_positive_ratio < 0.55:
        reasons.append("TRAIN_MONTH_STABILITY_LT_55PCT")
    if validation.monthly_positive_ratio is None or validation.monthly_positive_ratio < 0.50:
        reasons.append("VALIDATION_MONTH_STABILITY_LT_50PCT")
    if train.opportunities_per_hour is None or train.opportunities_per_hour < 0.03:
        reasons.append("TRAIN_FREQUENCY_LT_003_PER_HOUR")
    if validation.opportunities_per_hour is None or validation.opportunities_per_hour < 0.03:
        reasons.append("VALIDATION_FREQUENCY_LT_003_PER_HOUR")
    if train.max_drawdown_r > 12:
        reasons.append("TRAIN_DRAWDOWN_GT_12R")
    if validation.max_drawdown_r > 6:
        reasons.append("VALIDATION_DRAWDOWN_GT_6R")
    return not reasons, reasons


def holdout_gate(nominal, cost_stress, delay_stress, dsr) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    if nominal.tp + nominal.sl < 120:
        reasons.append("HOLDOUT_TRADES_LT_120")
    if nominal.expectancy_r is None or nominal.expectancy_r < 0.12:
        reasons.append("HOLDOUT_EXPECTANCY_LT_012R")
    if nominal.profit_factor_r is None or nominal.profit_factor_r < 1.30:
        reasons.append("HOLDOUT_PF_LT_130")
    if nominal.monthly_positive_ratio is None or nominal.monthly_positive_ratio < 0.60:
        reasons.append("HOLDOUT_MONTH_STABILITY_LT_60PCT")
    if nominal.opportunities_per_hour is None or nominal.opportunities_per_hour < 0.08:
        reasons.append("HOLDOUT_FREQUENCY_LT_008_PER_HOUR")
    if nominal.max_drawdown_r > 8:
        reasons.append("HOLDOUT_DRAWDOWN_GT_8R")
    for label, performance in (("COST_STRESS", cost_stress), ("DELAY_STRESS", delay_stress)):
        if performance.expectancy_r is None or performance.expectancy_r <= 0:
            reasons.append(f"{label}_EXPECTANCY_NOT_POSITIVE")
        if performance.profit_factor_r is None or performance.profit_factor_r < 1.10:
            reasons.append(f"{label}_PF_LT_110")
    if dsr is None or dsr < 0.95:
        reasons.append("DEFLATED_SHARPE_PROBABILITY_LT_95PCT")
    return not reasons, reasons


def select_locked_candidates(trials: list[dict]) -> list[dict]:
    """Choose at most one configuration per symbol without reading holdout data."""
    locked: list[dict] = []
    for symbol in ("ETHUSDT", "SOLUSDT"):
        eligible = [trial for trial in trials if trial["symbol"] == symbol and trial["developmentAccepted"]]
        eligible.sort(key=lambda item: (
            min(item["train"]["expectancy_r"], item["validation"]["expectancy_r"]),
            min(item["train"]["profit_factor_r"], item["validation"]["profit_factor_r"]),
            item["validation"]["trades"],
            item["configurationId"],
        ), reverse=True)
        if eligible:
            locked.append(eligible[0])
    return locked


def should_open_holdout(locked: list[dict]) -> bool:
    return len(locked) == 2 and {item.get("symbol") for item in locked} == {
        "ETHUSDT", "SOLUSDT"
    }


def combined(performance_items):
    trades = []
    fresh_hours = 0.0
    for performance in performance_items:
        trades.extend(performance.trades_detail)
        fresh_hours = max(fresh_hours, performance.fresh_hours)
    trades.sort(key=lambda item: (item.opened_at, item.symbol, item.side))
    return _summary(trades, fresh_hours)


def markdown_report(document: dict) -> str:
    lines = [
        "# NMC causal scalp research — cycle 2026-08-08",
        "",
        "## Méthode",
        "",
        "Recherche indépendante du moteur Android sur bougies Binance Futures USD-M 1m closes. "
        "Toute qualification utilise uniquement le passé et entre sur la bougie suivante. "
        "Les barres ambiguës sont comptées au SL, les coûts du profil sont inclus, et les "
        "128 configurations préenregistrées restent toutes dans le registre, gagnantes ou non.",
        "",
        "Ce replay 1m sert à la découverte et au rejet. Il ne remplace pas une validation exacte "
        "du bid/ask, de la latence et du slippage sur une collecte bookTicker forward. Aucun résultat "
        "ci-dessous ne garantit un bénéfice futur.",
        "",
        f"- Protocole : `{document['protocolId']}`",
        f"- Corpus SHA-256 : `{document['corpus']['corpusSha256']}`",
        f"- Configurations examinées : {document['configurationCount']}",
        f"- Finalistes verrouillés avant holdout : {len(document['lockedCandidates'])}",
        "",
        "## Décision",
        "",
        f"**{document['decision']}**",
        "",
    ]
    for candidate in document["holdout"]:
        nominal = candidate["nominal"]
        lines.extend([
            f"### {candidate['symbol']} — {candidate['family']} {candidate['side']}", "",
            f"- Configuration : `{candidate['configurationId']}`",
            f"- Résolus : {nominal['tp'] + nominal['sl']} ({nominal['tp']} TP / {nominal['sl']} SL)",
            f"- Win rate : {nominal['win_rate']}",
            f"- Net R : {nominal['net_r']}",
            f"- Expectancy : {nominal['expectancy_r']} R/trade",
            f"- Profit factor R : {nominal['profit_factor_r']}",
            f"- Drawdown : {nominal['max_drawdown_r']} R",
            f"- Fréquence : {nominal['opportunities_per_hour']} / h fraîche",
            f"- DSR ajusté {MAX_CONFIGURATIONS} essais : {candidate['deflatedSharpeProbability']}",
            f"- Gate holdout : {'PASS' if candidate['holdoutAccepted'] else 'FAIL'} — {', '.join(candidate['holdoutReasons']) or 'aucun échec'}",
            "",
        ])
    lines.extend([
        "## Interprétation", "",
        "Une configuration n’est intégrable que si ETH **et** SOL passent séparément le holdout, "
        "les stress de coût et de délai, et le contrôle de sélection. Dans tous les autres cas, "
        "le moteur public reste inchangé et le cycle est déclaré négatif plutôt que recalibré sur le holdout.",
    ])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", default=".research-data/binance-futures-1m")
    parser.add_argument("--output", default=".research-output/causal-scalp-cycle-20260808.json")
    parser.add_argument("--report", default=".research-output/causal-scalp-cycle-20260808.md")
    args = parser.parse_args()
    start = dt.date(2025, 1, 1)
    development_end = dt.date(2026, 3, 31)
    # Physical holdout isolation: April--July archives are not downloaded, parsed or
    # transformed until both symbols independently produce a locked finalist.
    corpus, archives = acquire_corpus(Path(args.cache), start, development_end)
    aligned = align_corpus(corpus)
    features = {symbol: build_features(aligned, symbol) for symbol in ("ETHUSDT", "SOLUSDT")}
    rules = list(generate_rules())
    configurations = [(rule, geometry) for rule in rules for geometry in GEOMETRIES]
    if len(configurations) != MAX_CONFIGURATIONS:
        raise RuntimeError(f"Pre-registered budget changed: {len(configurations)} != {MAX_CONFIGURATIONS}")
    trials: list[dict] = []
    for index, (rule, geometry) in enumerate(configurations, start=1):
        frame = features[rule.symbol]
        train = simulate(frame, rule, geometry, DEFAULT_SPLITS["train"])
        validation = simulate(frame, rule, geometry, DEFAULT_SPLITS["validation"])
        accepted, reasons = development_gate(train, validation)
        trials.append({
            "trialNumber": index,
            "configurationId": f"{rule.identifier}-{geometry.identifier}",
            "symbol": rule.symbol, "family": rule.family, "side": rule.side_name,
            "params": dict(rule.params), "targetA": geometry.target_a, "stopA": geometry.stop_a,
            "train": compact(train), "validation": compact(validation),
            "developmentAccepted": accepted, "developmentReasons": reasons,
        })
    locked = select_locked_candidates(trials)
    holdout: list[dict] = []
    holdout_performances = []
    holdout_opened = should_open_holdout(locked)
    if holdout_opened:
        holdout_end = dt.date(2026, 7, 31)
        corpus, archives = acquire_corpus(Path(args.cache), start, holdout_end)
        aligned = align_corpus(corpus)
        features = {
            symbol: build_features(aligned, symbol) for symbol in ("ETHUSDT", "SOLUSDT")
        }
    for selected in locked if holdout_opened else []:
        rule = next(item for item in rules if item.identifier == selected["configurationId"].split("-")[0])
        geometry = next(item for item in GEOMETRIES if selected["configurationId"].endswith(item.identifier))
        frame = features[rule.symbol]
        nominal = simulate(frame, rule, geometry, DEFAULT_SPLITS["holdout"])
        cost_stress = simulate(frame, rule, geometry, DEFAULT_SPLITS["holdout"], cost_multiplier=1.5)
        delay_stress = simulate(frame, rule, geometry, DEFAULT_SPLITS["holdout"], entry_delay_bars=2)
        dsr = deflated_sharpe_probability(nominal.results, MAX_CONFIGURATIONS)
        accepted, reasons = holdout_gate(nominal, cost_stress, delay_stress, dsr)
        holdout_performances.append(nominal)
        holdout.append({**{key: selected[key] for key in ("configurationId", "symbol", "family", "side", "params", "targetA", "stopA")},
                        "nominal": compact(nominal), "costStress150Pct": compact(cost_stress),
                        "delayStressOneExtraBar": compact(delay_stress),
                        "deflatedSharpeProbability": dsr,
                        "holdoutAccepted": accepted, "holdoutReasons": reasons})
    all_accepted = len(holdout) == 2 and all(item["holdoutAccepted"] for item in holdout)
    decision = "CANDIDATE_ACCEPTED_FOR_FORWARD_SHADOW" if all_accepted else "NO_ROBUST_TWO_SYMBOL_CANDIDATE"
    document = {
        "protocolId": PROTOCOL_ID, "protocolSchema": PROTOCOL_SCHEMA,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "futureGuarantee": False, "automaticPromotionAllowed": False,
        "splits": DEFAULT_SPLITS, "configurationBudget": MAX_CONFIGURATIONS,
        "configurationCount": len(configurations),
        "corpus": manifest_document(
            archives, corpus, start,
            dt.date(2026, 7, 31) if holdout_opened else development_end,
        ),
        "holdoutPhysicallyOpened": holdout_opened,
        "trialRegistry": trials, "lockedCandidates": locked, "holdout": holdout,
        "combinedHoldout": compact(combined(holdout_performances)) if holdout_performances else None,
        "decision": decision,
    }
    document = _json_safe(document)
    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, indent=2, ensure_ascii=False), encoding="utf-8")
    report.write_text(markdown_report(document), encoding="utf-8")
    print(json.dumps({
        "protocolId": PROTOCOL_ID, "corpusSha256": document["corpus"]["corpusSha256"],
        "configurations": len(configurations), "developmentAccepted": sum(t["developmentAccepted"] for t in trials),
        "locked": [item["configurationId"] for item in locked], "decision": decision,
        "holdout": holdout, "output": str(output), "report": str(report),
    }, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
