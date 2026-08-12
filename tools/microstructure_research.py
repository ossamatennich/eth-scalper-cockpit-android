#!/usr/bin/env python3
"""Offline causal feature builder for NMC capture V1/V2/V3/V4.

Features are descriptive research inputs only. They are not live signals and make no claim of
predictive value. State is cleared at every explicit GAP/DROP_SUMMARY and no future record is read.
"""
from __future__ import annotations

import argparse
import collections
import json
import math
from pathlib import Path
from typing import Any, Deque, Dict, Iterable, List, Optional, Tuple

SCHEMA_V1 = "NMC_CAUSAL_MARKET_CAPTURE_V1"
SCHEMA_V2 = "NMC_CAUSAL_MARKET_CAPTURE_V2"
SCHEMA_V3 = "NMC_CAUSAL_MARKET_CAPTURE_V3"
SCHEMA_V4 = "NMC_CAUSAL_MARKET_CAPTURE_V4"
SCHEMA = SCHEMA_V4
SUPPORTED_CAPTURE_FORMATS = {(SCHEMA_V1, 1), (SCHEMA_V2, 2), (SCHEMA_V3, 3),
                             (SCHEMA_V4, 4)}
SYMBOLS = ("ETHUSDT", "SOLUSDT", "BTCUSDT")
WINDOWS_MS = (1_000, 5_000, 15_000, 60_000)


def _finite(value: Any) -> Optional[float]:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def _safe_ratio(numerator: float, denominator: float) -> Optional[float]:
    if not math.isfinite(numerator) or not math.isfinite(denominator) or denominator == 0:
        return None
    value = numerator / denominator
    return value if math.isfinite(value) else None


def depth_features(record: Dict[str, Any], previous: Optional[Dict[str, float]] = None) -> Dict[str, Any]:
    bids, asks = record.get("bids"), record.get("asks")
    if not isinstance(bids, list) or not isinstance(asks, list) or len(bids) < 20 or len(asks) < 20:
        raise ValueError("depth20 requires 20 bid and ask levels")

    def side(rows: List[Any]) -> List[Tuple[float, float]]:
        output = []
        for row in rows[:20]:
            if not isinstance(row, list) or len(row) < 2:
                raise ValueError("malformed depth level")
            price, quantity = _finite(row[0]), _finite(row[1])
            if price is None or quantity is None or price <= 0 or quantity < 0:
                raise ValueError("invalid depth level")
            output.append((price, quantity))
        return output

    bid_levels, ask_levels = side(bids), side(asks)
    best_bid, bid_quantity = bid_levels[0]
    best_ask, ask_quantity = ask_levels[0]
    if best_ask < best_bid:
        raise ValueError("crossed depth")
    mid = (best_bid + best_ask) / 2.0
    result: Dict[str, Any] = {
        "mid": mid,
        "spreadBps": _safe_ratio((best_ask - best_bid) * 10_000.0, mid),
        "microprice": _safe_ratio(best_ask * bid_quantity + best_bid * ask_quantity,
                                    bid_quantity + ask_quantity),
    }
    for levels in (5, 10, 20):
        bid_notional = sum(price * quantity for price, quantity in bid_levels[:levels])
        ask_notional = sum(price * quantity for price, quantity in ask_levels[:levels])
        result[f"bidNotionalTop{levels}"] = bid_notional
        result[f"askNotionalTop{levels}"] = ask_notional
        result[f"imbalanceTop{levels}"] = _safe_ratio(bid_notional - ask_notional,
                                                       bid_notional + ask_notional)
    previous = previous or {}
    result["bidDepthChangeTop20"] = result["bidNotionalTop20"] - previous.get(
        "bidNotionalTop20", result["bidNotionalTop20"])
    result["askDepthChangeTop20"] = result["askNotionalTop20"] - previous.get(
        "askNotionalTop20", result["askNotionalTop20"])
    result["nearTouchBidReplenishment"] = max(0.0, result["bidNotionalTop5"] - previous.get(
        "bidNotionalTop5", result["bidNotionalTop5"]))
    result["nearTouchAskReplenishment"] = max(0.0, result["askNotionalTop5"] - previous.get(
        "askNotionalTop5", result["askNotionalTop5"]))
    result["nearTouchBidDepletion"] = max(0.0, previous.get("bidNotionalTop5",
        result["bidNotionalTop5"]) - result["bidNotionalTop5"])
    result["nearTouchAskDepletion"] = max(0.0, previous.get("askNotionalTop5",
        result["askNotionalTop5"]) - result["askNotionalTop5"])
    return result


class MicrostructureResearchBuilder:
    def __init__(self) -> None:
        self.flows: Dict[str, Deque[Tuple[int, float, int]]] = {
            symbol: collections.deque() for symbol in SYMBOLS
        }
        self.depth: Dict[str, Dict[str, float]] = {}
        self.top: Dict[str, Tuple[int, float]] = {}
        self.last_flow_features: Dict[str, Tuple[int, Dict[str, Any]]] = {}
        self.last_at = -1
        self.last_sequence = 0
        self.session = ""
        self.previous_safe_at: Optional[int] = None
        self.usable_ms = 0
        self.counts: Dict[str, int] = collections.Counter()
        self.gaps = 0
        self.rejected = 0
        self.coverage = collections.Counter()
        self.capture_schemas = set()
        self.liquidation_records: List[Dict[str, Any]] = []
        self.depth_diff_records: List[Dict[str, Any]] = []
        self.depth_bootstrap_records: List[Dict[str, Any]] = []
        self.depth_reconstruction: Dict[str, Dict[str, Any]] = {
            symbol: {"anchor": None, "last": None, "valid": False, "breaks": 0,
                     "intervalStart": None, "intervals": []} for symbol in SYMBOLS
        }

    def consume(self, record: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        identity = (record.get("schema"), record.get("formatVersion")) if isinstance(record, dict) else None
        if identity not in SUPPORTED_CAPTURE_FORMATS:
            raise ValueError("unsupported capture schema/version")
        self.capture_schemas.add(f"{identity[0]}:{identity[1]}")
        kind = str(record.get("kind", ""))
        at = record.get("receivedAt")
        sequence = record.get("sequence")
        if not isinstance(at, int) or at < 0 or not isinstance(sequence, int) or sequence <= 0:
            raise ValueError("invalid causal clock/sequence")
        if kind == "SESSION":
            self._reset_causal_state()
            self.session = str(record.get("sessionId", ""))
            self.last_at, self.last_sequence, self.previous_safe_at = at, sequence, at
            self.counts[kind] += 1
            return None
        if not self.session or record.get("sessionId") != self.session:
            raise ValueError("record outside active session")
        if at < self.last_at or sequence <= self.last_sequence:
            raise ValueError("non-monotonic causal input")
        if self.previous_safe_at is not None:
            delta = at - self.previous_safe_at
            if 0 <= delta <= 5_000:
                self.usable_ms += delta
        self.previous_safe_at = at
        self.last_at, self.last_sequence = at, sequence
        self.counts[kind] += 1
        if kind in ("GAP", "DROP_SUMMARY"):
            self.gaps += 1
            for state in self.depth_reconstruction.values():
                self._close_depth_interval(state, at)
                state.update(anchor=None, last=None, valid=False)
            self._reset_causal_state(keep_session=True)
            self.previous_safe_at = None
            return None
        symbol = str(record.get("symbol", ""))
        if symbol not in SYMBOLS:
            return None
        if kind == "TOP_OF_BOOK_SAMPLE":
            bid, ask = _finite(record.get("bid")), _finite(record.get("ask"))
            if bid is None or ask is None or bid <= 0 or ask < bid:
                self.rejected += 1
                return None
            self.top[symbol] = (at, (bid + ask) / 2.0)
            return None
        if kind == "DEPTH20_SAMPLE":
            try:
                self.depth[symbol] = depth_features(record, self.depth.get(symbol))
            except ValueError:
                self.rejected += 1
            return None
        if kind == "LIQUIDATION_SNAPSHOT":
            required_numbers = ("originalQuantity", "price", "averagePrice",
                                "lastFilledQuantity", "accumulatedFilledQuantity")
            values = {key: _finite(record.get(key)) for key in required_numbers}
            if (any(value is None or value < 0 for value in values.values())
                    or not str(record.get("orderSide", "")).strip()):
                self.rejected += 1
                return None
            self.liquidation_records.append({
                "schema": "NMC_LIQUIDATION_RESEARCH_RECORD_V1",
                "receivedAt": at,
                "symbol": symbol,
                "exchangeEventAt": record.get("exchangeEventAt"),
                "tradeAt": record.get("tradeAt"),
                "orderSide": record.get("orderSide"),
                "orderType": record.get("orderType"),
                "timeInForce": record.get("timeInForce"),
                "orderStatus": record.get("orderStatus"),
                **values,
            })
            return None
        if kind == "DEPTH_BOOTSTRAP":
            update_id = record.get("lastUpdateId")
            bids, asks = record.get("bids"), record.get("asks")
            if not isinstance(update_id, int) or update_id < 0 or not self._valid_raw_levels(bids, False) \
                    or not self._valid_raw_levels(asks, False):
                self.rejected += 1
                return None
            state = self.depth_reconstruction[symbol]
            self._close_depth_interval(state, at)
            state.update(anchor=update_id, last=None, valid=False, intervalStart=None)
            self.depth_bootstrap_records.append(dict(record))
            return None
        if kind == "DEPTH_DIFF":
            first, final, previous = (record.get("firstUpdateId"), record.get("finalUpdateId"),
                                      record.get("previousFinalUpdateId"))
            if (not isinstance(first, int) or not isinstance(final, int) or first < 0 or final < first
                    or previous is not None and (not isinstance(previous, int) or previous < 0)
                    or not self._valid_raw_levels(record.get("bids"), True)
                    or not self._valid_raw_levels(record.get("asks"), True)):
                self.rejected += 1
                return None
            self.depth_diff_records.append(dict(record))
            state = self.depth_reconstruction[symbol]
            if state["anchor"] is None:
                return None
            if not state["valid"]:
                if final < state["anchor"]:
                    return None
                if first <= state["anchor"] <= final:
                    state.update(valid=True, last=final, intervalStart=at)
                else:
                    state["breaks"] += 1
                    state.update(anchor=None, last=None, valid=False)
                return None
            if final <= state["last"]:
                return None
            continuous = previous == state["last"] if previous is not None else \
                first <= state["last"] + 1 <= final
            if continuous:
                state["last"] = final
            else:
                state["breaks"] += 1
                self._close_depth_interval(state, at)
                state.update(anchor=None, last=None, valid=False)
            return None
        if kind != "FLOW_100MS":
            return None
        buyer = _finite(record.get("buyerNotional"))
        seller = _finite(record.get("sellerNotional"))
        trade_count = record.get("aggregateCount")
        if buyer is None or seller is None or buyer < 0 or seller < 0 or not isinstance(trade_count, int):
            self.rejected += 1
            return None
        signed = buyer - seller
        queue = self.flows[symbol]
        queue.append((at, signed, trade_count))
        while queue and at - queue[0][0] > max(WINDOWS_MS):
            queue.popleft()
        result: Dict[str, Any] = {
            "schema": "NMC_MICROSTRUCTURE_FEATURES_V1",
            "observedAt": at,
            "symbol": symbol,
            "buyerAggressiveNotional": buyer,
            "sellerAggressiveNotional": seller,
            "signedAggressiveNotional": signed,
            "buySellRatio": _safe_ratio(buyer, seller),
            "tradeCount": trade_count,
        }
        for window in WINDOWS_MS:
            values = [item for item in queue if at - item[0] <= window]
            result[f"cvd{window // 1000}s"] = sum(item[1] for item in values)
            result[f"tradeCount{window // 1000}s"] = sum(item[2] for item in values)
        current_1s = result["tradeCount1s"]
        prior = self.last_flow_features.get(symbol)
        result["aggressionAcceleration"] = current_1s - (prior[1].get("tradeCount1s", current_1s)
                                                            if prior else current_1s)
        depth = self.depth.get(symbol)
        if depth:
            result.update(depth)
            self.coverage["depth"] += 1
        top = self.top.get(symbol)
        mid = top[1] if top and top[0] <= at else depth.get("mid") if depth else None
        previous_mid = prior[1].get("causalMid") if prior else None
        result["causalMid"] = mid
        price_delta = mid - previous_mid if mid is not None and previous_mid is not None else None
        total_flow = buyer + seller
        result["absorptionProxy"] = _safe_ratio(abs(signed), abs(price_delta) + 1e-12) \
            if price_delta is not None else None
        result["exhaustionProxy"] = _safe_ratio(abs(price_delta), total_flow + 1e-12) \
            if price_delta is not None else None
        if depth:
            result["sweepDepletionProxy"] = (depth["nearTouchAskDepletion"] if signed > 0
                                               else depth["nearTouchBidDepletion"])
        else:
            result["sweepDepletionProxy"] = None
        result["priceFlowDivergence"] = (price_delta * signed < 0) if price_delta is not None else None
        context: Dict[str, Any] = {}
        for other in SYMBOLS:
            known = self.last_flow_features.get(other)
            if known and known[0] <= at:
                context[other] = {"ageMs": at - known[0], "signedAggressiveNotional":
                    known[1]["signedAggressiveNotional"], "cvd5s": known[1]["cvd5s"]}
        result["crossAssetContext"] = context
        if len(context) >= 2:
            self.coverage["crossAsset"] += 1
        if mid is not None:
            self.coverage["price"] += 1
        self.last_flow_features[symbol] = (at, result)
        self.coverage["features"] += 1
        return result

    def summary(self) -> Dict[str, Any]:
        features = self.coverage["features"]
        return {
            "schema": "NMC_MICROSTRUCTURE_RESEARCH_SUMMARY_V1",
            "captureSchema": SCHEMA,
            "captureSchemasObserved": sorted(self.capture_schemas),
            "recordCounts": dict(self.counts),
            "explicitGaps": self.gaps,
            "rejectedRecords": self.rejected,
            "usableMs": self.usable_ms,
            "usableHours": self.usable_ms / 3_600_000.0,
            "featureRows": features,
            "liquidationSnapshots": len(self.liquidation_records),
            "liquidationCountsBySymbol": dict(collections.Counter(
                row["symbol"] for row in self.liquidation_records)),
            "depthDiffCountBySymbol": dict(collections.Counter(
                row["symbol"] for row in self.depth_diff_records)),
            "depthBootstrapCountBySymbol": dict(collections.Counter(
                row["symbol"] for row in self.depth_bootstrap_records)),
            "incrementalDepthContinuity": {symbol: {
                "continuityBreaks": state["breaks"], "reconstructible": state["valid"],
                "validIntervals": list(state["intervals"])
            } for symbol, state in self.depth_reconstruction.items()},
            "depthCoverage": _safe_ratio(self.coverage["depth"], features),
            "priceCoverage": _safe_ratio(self.coverage["price"], features),
            "crossAssetCoverage": _safe_ratio(self.coverage["crossAsset"], features),
        }

    def _reset_causal_state(self, keep_session: bool = False) -> None:
        for queue in self.flows.values():
            queue.clear()
        self.depth.clear()
        self.top.clear()
        self.last_flow_features.clear()
        if not keep_session:
            self.session = ""

    @staticmethod
    def _valid_raw_levels(rows: Any, allow_empty: bool) -> bool:
        if not isinstance(rows, list) or (not rows and not allow_empty):
            return False
        for row in rows:
            if not isinstance(row, list) or len(row) < 2:
                return False
            price, quantity = _finite(row[0]), _finite(row[1])
            if price is None or quantity is None or price < 0 or quantity < 0:
                return False
        return True

    @staticmethod
    def _close_depth_interval(state: Dict[str, Any], at: int) -> None:
        start = state.get("intervalStart")
        if state.get("valid") and isinstance(start, int) and at >= start:
            state["intervals"].append({"from": start, "to": at})
        state["intervalStart"] = None


def chronological_incremental_depth(records: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Deterministic causal view; receive time wins and sequence breaks ties."""
    values = [dict(record) for record in records if record.get("kind") in
              ("DEPTH_DIFF", "DEPTH_BOOTSTRAP")]
    return sorted(values, key=lambda record: (record.get("receivedAt", -1),
                                               record.get("sequence", -1)))


def build(records: Iterable[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    builder = MicrostructureResearchBuilder()
    features = []
    for record in records:
        value = builder.consume(record)
        if value is not None:
            features.append(value)
    return features, builder.summary()


def read_jsonl(path: Path) -> Iterable[Dict[str, Any]]:
    with path.open("r", encoding="utf-8") as source:
        for number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"malformed JSON at line {number}") from error
            yield value


def main() -> int:
    parser = argparse.ArgumentParser(description="Build causal microstructure features from NMC V1/V2/V3 JSONL")
    parser.add_argument("input", type=Path)
    parser.add_argument("--features", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    features, summary = build(read_jsonl(args.input))
    if args.features:
        with args.features.open("w", encoding="utf-8") as output:
            for row in features:
                output.write(json.dumps(row, allow_nan=False, separators=(",", ":")) + "\n")
    text = json.dumps(summary, allow_nan=False, indent=2, sort_keys=True)
    if args.summary:
        args.summary.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
