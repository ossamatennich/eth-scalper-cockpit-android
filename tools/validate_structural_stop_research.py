#!/usr/bin/env python3
"""Deterministic, causal v2.34.3 stop-grid validation over the packaged 14 sessions."""
from __future__ import annotations

import csv
import hashlib
import io
import json
import math
import statistics
import sys
import zipfile
from pathlib import Path

PACKAGE_NAME = "NMC_CODEX_SINGLE_PACKAGE_v2.34.2.0_20260728.zip"
PACKAGE_SHA256 = "a355f99251795256014de9f2046853169603fbe22caff79595098e84fdffac30"
WINDOWS = (5, 8, 15)
BUFFERS = (0.15, 0.20, 0.25, 0.35)
SELECTED = (5, 0.15)


def package_path() -> Path | None:
    direct = Path(r"C:\Users\Tenni\Downloads") / PACKAGE_NAME
    if direct.is_file():
        return direct
    for path in Path(r"C:\Users\Tenni").rglob(PACKAGE_NAME):
        if path.is_file():
            return path
    return None


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fixture_from_package(package: zipfile.ZipFile) -> list[dict[str, str]]:
    name = next(n for n in package.namelist() if n.endswith("03_EXTRAITS_CRITIQUES/final_trades_16.csv"))
    return list(csv.DictReader(io.StringIO(package.read(name).decode("utf-8-sig"))))


def relay_from_package(package: zipfile.ZipFile) -> zipfile.ZipFile:
    name = next(n for n in package.namelist() if n.endswith("02_SOURCES/ETH_SCALPER_FULL_RELAY_2026-07-26.zip"))
    return zipfile.ZipFile(io.BytesIO(package.read(name)))


def minute_bars(diag_bytes: bytes, confirmation_at: int, window: int) -> list[dict[str, float]]:
    with zipfile.ZipFile(io.BytesIO(diag_bytes)) as diag:
        entry = "persistent_market_frames.jsonl"
        minutes: dict[int, dict[str, float]] = {}
        with diag.open(entry) as raw:
            for encoded in raw:
                frame = json.loads(encoded)
                at = int(frame.get("at", 0))
                price = float(frame.get("eth", float("nan")))
                if at > confirmation_at or not math.isfinite(price) or price <= 0:
                    continue
                minute = at // 60_000
                if (minute + 1) * 60_000 > confirmation_at:
                    continue
                bar = minutes.get(minute)
                if bar is None:
                    minutes[minute] = {"minute": minute, "open": price, "high": price,
                                       "low": price, "close": price, "last_at": at}
                else:
                    bar["high"] = max(bar["high"], price)
                    bar["low"] = min(bar["low"], price)
                    if at >= bar["last_at"]:
                        bar["close"] = price
                        bar["last_at"] = at
        values = sorted(minutes.values(), key=lambda b: b["minute"])
        cutoff = confirmation_at // 60_000 - window
        return [bar for bar in values if bar["minute"] >= cutoff]


def anchor(bars: list[dict[str, float]], side: str, entry: float, a: float) -> float | None:
    for index in range(len(bars) - 2, 0, -1):
        before, pivot, after = bars[index - 1:index + 2]
        if after["minute"] - pivot["minute"] > 1 or pivot["minute"] - before["minute"] > 1:
            continue
        if side == "LONG":
            local = pivot["low"] < entry and pivot["low"] <= before["low"] and pivot["low"] <= after["low"]
            confirmed = min(before["close"], after["close"]) >= pivot["low"] + 0.10 * a
            coherent = entry - pivot["low"] <= 1.50 * a + 1e-12
            if local and confirmed and coherent:
                return pivot["low"]
        else:
            local = pivot["high"] > entry and pivot["high"] >= before["high"] and pivot["high"] >= after["high"]
            confirmed = max(before["close"], after["close"]) <= pivot["high"] - 0.10 * a
            coherent = pivot["high"] - entry <= 1.50 * a + 1e-12
            if local and confirmed and coherent:
                return pivot["high"]
    return None


def evaluate(trades: list[dict[str, str]], relay: zipfile.ZipFile) -> dict:
    diagnostic_entries = {Path(n).name: n for n in relay.namelist() if "RAW_DIAGNOSTICS_14_SESSIONS" in n and n.endswith(".zip")}
    cached: dict[str, bytes] = {}
    grid = {(w, b): [] for w in WINDOWS for b in BUFFERS}
    rows = []
    for trade in trades:
        session = trade["session_id"]
        diag_name = next(name for name in diagnostic_entries if session in name)
        if diag_name not in cached:
            cached[diag_name] = relay.read(diagnostic_entries[diag_name])
        confirmation = int(trade["confirmation_at"])
        entry = float(trade["entry"]); a = max(0.35, float(trade["A"])); adverse = max(0.0, float(trade["E"]))
        side = trade["side"]; room = max(0.0, float(trade["R"])); old_stop = float(trade["stop_distance"])
        base = max(0.55, a, adverse + 0.20 * a)
        target = min(5.50, max(2.80, 2.70 * a + 0.20 * room))
        all_bars = minute_bars(cached[diag_name], confirmation, max(WINDOWS))
        for config in grid:
            window, multiplier = config
            cutoff = confirmation // 60_000 - window
            bars = [bar for bar in all_bars if bar["minute"] >= cutoff]
            pivot = anchor(bars, side, entry, a)
            distance = 0.0 if pivot is None else (entry - pivot if side == "LONG" else pivot - entry)
            structural = 0.0 if pivot is None else distance + multiplier * a
            required = max(base, structural)
            rounded = math.ceil((required - 1e-12) / 0.01) * 0.01
            rr = target / rounded
            quantity_cap = int(trade["quality_cap"])
            risk_per_unit = rounded + 2.35
            quantity = min(math.floor(10.00 / risk_per_unit), quantity_cap, 7)
            record = {"session": session, "sleeve": trade["sleeve"], "side": side,
                      "confirmationAt": confirmation, "oldStop": old_stop, "baseStop": base,
                      "anchor": pivot, "requiredStop": required, "roundedStop": rounded,
                      "target": target, "grossRR": rr, "quantity": quantity,
                      "structureDominates": structural > base + 1e-12,
                      "valid": rr >= 1.40 and quantity >= 1}
            grid[config].append(record)
            if config == SELECTED:
                rows.append(record)
    summaries = {}
    for config, records in grid.items():
        increases = [r["roundedStop"] - r["oldStop"] for r in records]
        summaries[f"{config[0]}m/{config[1]:.2f}A"] = {
            "validPlans": sum(r["valid"] for r in records),
            "anchors": sum(r["anchor"] is not None for r in records),
            "structureDominates": sum(r["structureDominates"] for r in records),
            "meanIncrease": statistics.mean(increases),
            "maximumIncrease": max(increases),
        }
    return {"grid": summaries, "selected": f"{SELECTED[0]}m/{SELECTED[1]:.2f}A",
            "selectedPlans": rows}


def main() -> int:
    package_file = package_path()
    if package_file is None:
        raise RuntimeError(f"{PACKAGE_NAME} unavailable")
    actual_sha = sha256(package_file)
    if actual_sha != PACKAGE_SHA256:
        raise RuntimeError(f"package SHA mismatch: {actual_sha}")
    with zipfile.ZipFile(package_file) as package:
        trades = fixture_from_package(package)
        with relay_from_package(package) as relay:
            result = evaluate(trades, relay)
    if len(trades) != 16 or sum(t["sleeve"] == "P01" for t in trades) != 7 \
            or sum(t["sleeve"] == "P02" for t in trades) != 9:
        raise RuntimeError("canonical selection parity failed")
    if any(t["outcome"] != "TP" for t in trades):
        raise RuntimeError("canonical terminal parity failed")
    if any(float(t["age_s"]) < 15.0 for t in trades):
        raise RuntimeError("public timing parity failed")
    selected = result["selectedPlans"]
    result.update({"package": str(package_file), "packageSha256": actual_sha,
                   "sessions": len({t["session_id"] for t in trades}), "plans": 16,
                   "p01": 7, "p02": 9, "tp": 16, "sl": 0,
                   "noPublicBefore15Seconds": True, "lookAheadFramesUsed": 0})
    if len(selected) != 16 or any(not p["valid"] for p in selected):
        print(json.dumps(result, ensure_ascii=False, indent=2))
        raise RuntimeError("selected structural configuration rejected a canonical plan")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"STRUCTURAL_STOP_VALIDATION_FAILED: {exc}", file=sys.stderr)
        raise
