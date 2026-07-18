from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
from unittest.mock import patch

import pandas as pd


ROOT = Path(__file__).resolve().parents[1]


def test_legacy_engine_is_byte_pinned_non_regression():
    expected = {
        "__init__.py": "0fe8a5f636b6cbb18953b76a6639946930faf956efbcb16b6d9276b976298bed",
        "round23_engine.py": "f747042e72807fca101b6292ffbebb338538fde345ee26f075bdf63a67a40002",
        "round23_phase2.py": "f3fe955a43470ddacc7c52155e4050373716717b94071a198d8eaff381404a54",
        "round23_phase3.py": "85490ddb8fa96fc6796ea4d71022ad8da87d104187a29fdc07f40790b8702df0",
        "round23_phase4.py": "71ed9891fad0021669de46980e5c3df4a2f120e7de73fe3158fdabc471d3294a",
        "round23_phase5.py": "f63c0f05f40f6990fe5e617e1beec47cf881a85b3af659475c5a97702a2b0624",
        "round23_phase6.py": "74aaf3c535862a1baf2d6f1b71ab1fd1cf2cdaa5a93d4d227782a4975cbfac6e",
        "round23_phase7.py": "f67e681a13151dcb578e3cfef7231ab4d7fcf50f618e07a539bb6437296e428f",
    }
    actual = {
        path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted((ROOT / "legacy_engine").glob("*.py"))
    }
    assert actual == expected


def test_packaged_round23_reference_contract_non_regression():
    reference = json.loads((ROOT / "reference/ROUND23_PACKAGED_BASELINE.json").read_text())
    assert reference["candidate_count"] == 43
    assert len(reference["candidates"]) == 43
    assert reference["corpus_sha256"] == "358ed25f46e123deefeda86be464a34e912c53551ff7abc8285132a79fcacbc2"
    assert reference["tolerance"] == 1e-12
    assert all("trade_structure_hash" in candidate for candidate in reference["candidates"])


def test_determinism_hash_covers_trade_values_and_structure_hash_does_not():
    spec = importlib.util.spec_from_file_location("reproduce_round23_contract", ROOT / "src/reproduce_round23.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    start = pd.Timestamp("2024-01-01T00:00:00Z")
    original = pd.DataFrame(
        {
            "architecture": ["TEST"],
            "side": ["LONG"],
            "signal_time": [start],
            "entry_time": [start + pd.Timedelta(hours=1)],
            "exit_time": [start + pd.Timedelta(hours=2)],
            "exit_reason": ["TARGET"],
            "entry": [100.0],
            "exit": [101.0],
            "r_net": [1.0],
        }
    )
    changed = original.copy()
    changed.loc[0, "r_net"] = 0.9
    assert module.canonical_trade_structure_hash(original) == module.canonical_trade_structure_hash(changed)
    assert module.canonical_trade_hash(original) != module.canonical_trade_hash(changed)


def test_long_short_and_combined_are_scoped_separately(modules):
    engine = modules["round23_engine"]
    start = pd.Timestamp("2024-01-01T00:00:00Z")
    trades = pd.DataFrame(
        {
            "entry_time": [start, start + pd.Timedelta(hours=1), start + pd.Timedelta(hours=2)],
            "exit_time": [start + pd.Timedelta(hours=1), start + pd.Timedelta(hours=2), start + pd.Timedelta(hours=3)],
            "side": ["LONG", "SHORT", "SHORT"],
            "r_net": [1.0, -1.0, 0.5],
            "market_regime": ["BULL", "BEAR", "BEAR"],
            "vol_regime": ["MID", "MID", "HIGH"],
        }
    )

    def count_scope(central, *_args, **_kwargs):
        return {"passes_all": False, "scoped_trade_count": len(central)}

    bootstrap = {"p_mean_positive": 0.0, "q10_mean_r": -1.0}
    mc = {"q95_max_drawdown_abs": 1.0}
    stresses = {"BASE": trades}
    with (
        patch.object(engine, "bootstrap_month", return_value=bootstrap),
        patch.object(engine, "monte_carlo", return_value=mc),
        patch.object(engine, "gate_report", side_effect=count_scope),
    ):
        long = engine.scoped_gate_report(trades, trades, trades, trades, stresses, [], {}, "LONG")
        short = engine.scoped_gate_report(trades, trades, trades, trades, stresses, [], {}, "SHORT")
        combined = engine.scoped_gate_report(trades, trades, trades, trades, stresses, [], {}, None)

    assert long["scoped_trade_count"] == 1
    assert short["scoped_trade_count"] == 2
    assert combined["scoped_trade_count"] == 3


def test_corrected_engine_real_corpus_integration(modules, features):
    engine = modules["round23_engine"]
    spec = next(
        item
        for item in engine.central_specs()
        if item.architecture == "MOMENTUM_ACCELERATION" and item.side == "LONG"
    )
    trades, metrics = engine.run_backtest(features, spec)
    assert metrics["trades"] == len(trades) > 0
    assert {"entry_time", "exit_time", "exit_reason", "completed_bars_before_exit", "r_net"} <= set(trades)
    assert (pd.to_datetime(trades.exit_time, utc=True) > pd.to_datetime(trades.entry_time, utc=True)).all()
