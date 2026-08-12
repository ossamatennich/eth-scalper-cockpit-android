import json
import math
import unittest

from tools.microstructure_research import MicrostructureResearchBuilder, build, depth_features


def session(at=0, sequence=1):
    return {"schema": "NMC_CAUSAL_MARKET_CAPTURE_V2", "formatVersion": 2,
            "kind": "SESSION", "sessionId": "s", "sequence": sequence, "receivedAt": at}


def flow(at, sequence, symbol="ETHUSDT", buy=100.0, sell=50.0):
    return {"schema": "NMC_CAUSAL_MARKET_CAPTURE_V2", "formatVersion": 2,
            "kind": "FLOW_100MS", "sessionId": "s", "sequence": sequence,
            "receivedAt": at, "symbol": symbol, "buyerNotional": buy,
            "sellerNotional": sell, "aggregateCount": 2}


def depth(at, sequence, symbol="ETHUSDT", bid_qty=2.0, ask_qty=1.0):
    bids = [[100 - i * .01, bid_qty] for i in range(20)]
    asks = [[101 + i * .01, ask_qty] for i in range(20)]
    return {"schema": "NMC_CAUSAL_MARKET_CAPTURE_V2", "formatVersion": 2,
            "kind": "DEPTH20_SAMPLE", "sessionId": "s", "sequence": sequence,
            "receivedAt": at, "symbol": symbol, "bids": bids, "asks": asks}


class MicrostructureResearchTest(unittest.TestCase):
    def test_depth_top_5_10_20_imbalance_and_microprice(self):
        value = depth_features(depth(1, 2))
        self.assertGreater(value["imbalanceTop5"], 0)
        self.assertGreater(value["bidNotionalTop20"], value["bidNotionalTop5"])
        self.assertAlmostEqual((101 * 2 + 100 * 1) / 3, value["microprice"])

    def test_cvd_windows_and_safe_zero_ratio(self):
        features, summary = build([session(), flow(100, 2, buy=10, sell=0),
                                   flow(1_000, 3, buy=5, sell=2)])
        self.assertIsNone(features[0]["buySellRatio"])
        self.assertEqual(13, features[1]["cvd1s"])
        self.assertEqual(2, summary["featureRows"])

    def test_explicit_gap_breaks_rolling_state(self):
        gap = {"schema": "NMC_CAUSAL_MARKET_CAPTURE_V2", "formatVersion": 2,
               "kind": "GAP", "sessionId": "s", "sequence": 3, "receivedAt": 200}
        features, summary = build([session(), flow(100, 2), gap, flow(300, 4, buy=1, sell=0)])
        self.assertEqual(1, features[-1]["cvd60s"])
        self.assertEqual(1, summary["explicitGaps"])

    def test_cross_asset_context_never_reads_future(self):
        records = [session(), flow(100, 2, "ETHUSDT"), flow(200, 3, "SOLUSDT"),
                   flow(300, 4, "BTCUSDT"), flow(400, 5, "ETHUSDT")]
        features, _ = build(records)
        self.assertNotIn("SOLUSDT", features[0]["crossAssetContext"])
        self.assertIn("SOLUSDT", features[-1]["crossAssetContext"])
        self.assertIn("BTCUSDT", features[-1]["crossAssetContext"])

    def test_non_monotonic_and_unsupported_input_rejected(self):
        builder = MicrostructureResearchBuilder()
        builder.consume(session(100))
        with self.assertRaises(ValueError):
            builder.consume(flow(99, 2))
        bad = session()
        bad["formatVersion"] = 1
        with self.assertRaises(ValueError):
            MicrostructureResearchBuilder().consume(bad)

    def test_summary_and_features_are_strict_json_finite(self):
        features, summary = build([session(), depth(10, 2), flow(100, 3)])
        serialized = json.dumps({"features": features, "summary": summary}, allow_nan=False)
        self.assertNotIn("NaN", serialized)
        self.assertNotIn("Infinity", serialized)
        self.assertGreaterEqual(summary["usableMs"], 0)

    def test_v3_liquidation_is_loaded_and_absence_is_optional(self):
        v3_session = session()
        v3_session.update(schema="NMC_CAUSAL_MARKET_CAPTURE_V3", formatVersion=3)
        liquidation = {"schema": "NMC_CAUSAL_MARKET_CAPTURE_V3", "formatVersion": 3,
                       "kind": "LIQUIDATION_SNAPSHOT", "sessionId": "s", "sequence": 2,
                       "receivedAt": 100, "symbol": "ETHUSDT", "exchangeEventAt": 99,
                       "tradeAt": 98, "orderSide": "SELL", "orderType": "LIMIT",
                       "timeInForce": "IOC", "orderStatus": "FILLED", "originalQuantity": 2.0,
                       "price": 1900.0, "averagePrice": 1899.5, "lastFilledQuantity": 2.0,
                       "accumulatedFilledQuantity": 2.0}
        builder = MicrostructureResearchBuilder()
        self.assertIsNone(builder.consume(v3_session))
        self.assertIsNone(builder.consume(liquidation))
        self.assertEqual(1, builder.summary()["liquidationSnapshots"])
        self.assertEqual("SELL", builder.liquidation_records[0]["orderSide"])
        _, empty_summary = build([v3_session])
        self.assertEqual(0, empty_summary["liquidationSnapshots"])

    def test_v1_v2_v3_are_accepted_but_unknown_pair_is_rejected(self):
        for schema_name, version in (("NMC_CAUSAL_MARKET_CAPTURE_V1", 1),
                                     ("NMC_CAUSAL_MARKET_CAPTURE_V2", 2),
                                     ("NMC_CAUSAL_MARKET_CAPTURE_V3", 3)):
            value = session()
            value.update(schema=schema_name, formatVersion=version)
            MicrostructureResearchBuilder().consume(value)
        bad = session()
        bad.update(schema="NMC_CAUSAL_MARKET_CAPTURE_V3", formatVersion=2)
        with self.assertRaises(ValueError):
            MicrostructureResearchBuilder().consume(bad)


if __name__ == "__main__":
    unittest.main()
