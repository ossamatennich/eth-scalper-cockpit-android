#!/usr/bin/env python3
"""Credential-free real-network smoke checks for the V4 transport endpoints."""
from __future__ import annotations

import argparse
import asyncio
import json
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ASSETS = "AAVE ADA AIXBT ALGO APT ARB ASTER ATOM AVAX BCH BNB BTC CRV DOGE DOT ETC ETH FARTCOIN FIL GRASS HBAR HYPE INJ JTO JUP KAITO LDO LINK LTC MOODENG NEAR ONDO OP PENGU PNUT POL POPCAT PUMP RENDER S SOL STX SUI TAO TIA TRUMP TRX UNI VIRTUAL WIF WLD XPL XRP".split()
REST = "https://fapi.binance.com"


def get_json(path: str):
    request = urllib.request.Request(REST + path, headers={"User-Agent": "NMC-V4-6.1-smoke"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


async def websocket_smoke(url: str) -> dict:
    import websockets

    wanted = {"BTCUSDT", "ETHUSDT", "SOLUSDT", "AAVEUSDT", "BNBUSDT", "XRPUSDT"}
    observed: set[str] = set()
    async with websockets.connect(url, open_timeout=20, close_timeout=5, ping_interval=20) as socket:
        deadline = asyncio.get_running_loop().time() + 35
        while asyncio.get_running_loop().time() < deadline and not wanted.issubset(observed):
            raw = await asyncio.wait_for(socket.recv(), timeout=10)
            payload = json.loads(raw)
            data = payload.get("data", {})
            symbol = data.get("s")
            if symbol in wanted and float(data.get("b", 0)) > 0 and float(data.get("a", 0)) >= float(data.get("b", 0)):
                observed.add(symbol)
    return {"status": "PASS" if wanted.issubset(observed) else "FAIL", "observed": sorted(observed), "required": sorted(wanted)}


async def run(output: Path) -> int:
    daily = get_json("/fapi/v1/klines?symbol=BTCUSDT&interval=1d&limit=2")
    exchange = get_json("/fapi/v1/exchangeInfo")
    minute = get_json("/fapi/v1/klines?symbol=BTCUSDT&interval=1m&limit=10")
    metadata = {}
    for symbol in exchange["symbols"]:
        if symbol["symbol"] not in {"BTCUSDT", "AAVEUSDT"}:
            continue
        filters = {item["filterType"]: item for item in symbol["filters"]}
        metadata[symbol["symbol"]] = {
            "tickSize": filters["PRICE_FILTER"]["tickSize"],
            "stepSize": filters["LOT_SIZE"]["stepSize"],
            "minQty": filters["LOT_SIZE"]["minQty"],
            "minNotional": filters.get("MIN_NOTIONAL", {}).get("notional"),
        }
    streams = "/".join(f"{asset.lower()}usdt@bookTicker" for asset in ASSETS)
    android_url = "wss://fstream.binance.com/public/stream?streams=" + streams
    websocket = await websocket_smoke(android_url)
    chronological = all(int(minute[i][0]) + 60_000 == int(minute[i + 1][0]) for i in range(len(minute) - 1))
    report = {
        "schema": "NMC_V4_BINANCE_NETWORK_SMOKE_V1",
        "observedAtUtc": datetime.now(timezone.utc).isoformat(),
        "credentialsUsed": False,
        "dailyRest": {"endpoint": REST + "/fapi/v1/klines", "symbol": "BTCUSDT", "status": "PASS", "rows": len(daily)},
        "exchangeInfo": {"endpoint": REST + "/fapi/v1/exchangeInfo", "status": "PASS" if len(metadata) == 2 else "FAIL", "metadata": metadata},
        "bookTickerWebSocket": {"endpoint": android_url, **websocket},
        "minuteReconstruction": {"endpoint": REST + "/fapi/v1/klines", "symbol": "BTCUSDT", "interval": "1m", "rows": len(minute), "chronological": chronological, "status": "PASS" if chronological else "FAIL"},
    }
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if all(section["status"] == "PASS" for section in (report["dailyRest"], report["exchangeInfo"], report["bookTickerWebSocket"], report["minuteReconstruction"])) else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("NMC_STABLE_6_1_BINANCE_SMOKE_REPORT.json"))
    args = parser.parse_args()
    raise SystemExit(asyncio.run(run(args.output)))
