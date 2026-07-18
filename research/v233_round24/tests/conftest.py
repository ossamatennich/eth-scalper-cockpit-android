from __future__ import annotations

import importlib
import os
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "corrected_engine"
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))


@pytest.fixture(scope="session")
def modules():
    return {
        name: importlib.import_module(name)
        for name in (
            "round23_engine",
            "round23_phase2",
            "round23_phase3",
            "round23_phase4",
            "round23_phase5",
            "round23_phase6",
            "round23_phase7",
        )
    }


@pytest.fixture(scope="session")
def corpus_path():
    value = os.environ.get("ROUND24_CORPUS")
    if not value:
        pytest.skip("ROUND24_CORPUS is required for corpus integration tests")
    path = Path(value)
    if not path.exists():
        raise RuntimeError(f"missing corpus: {path}")
    return path


@pytest.fixture(scope="session")
def raw(modules, corpus_path):
    return modules["round23_engine"].load_ohlcv(corpus_path)


@pytest.fixture(scope="session")
def features(modules, raw):
    return modules["round23_engine"].prepare_features(raw)
