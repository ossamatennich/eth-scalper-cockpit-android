import math
import tempfile
import unittest
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

from tools.scalp_research import (
    Geometry, RuleSpec, build_features, ceil_tick, deflated_sharpe_probability,
    fit_linear_score_model, floor_tick, generate_rules, linear_feature_matrix,
    profile_cost, purged_period_mask, read_kline_archive, rule_mask, simulate,
)
from tools.run_scalp_research import should_open_holdout


def aligned_frame(minutes=180, start=1_735_689_600_000):
    rows = []
    for index in range(minutes):
        at = start + index * 60_000
        eth = 1900.0 + index * 0.20
        sol = 75.0 + index * 0.01
        btc = 90_000.0 + index * 2.0
        rows.append({
            "open_time": at,
            "eth_open": eth, "eth_high": eth + 0.40, "eth_low": eth - 0.40,
            "eth_close": eth + 0.10, "eth_volume": 100 + index,
            "sol_open": sol, "sol_high": sol + 0.03, "sol_low": sol - 0.03,
            "sol_close": sol + 0.005, "sol_volume": 1000 + index,
            "btc_open": btc, "btc_high": btc + 5, "btc_low": btc - 5,
            "btc_close": btc + 1, "btc_volume": 500 + index,
            "contiguous": True,
        })
    return pd.DataFrame(rows)


class ScalpResearchTest(unittest.TestCase):
    def test_holdout_requires_locked_candidates_for_both_assets(self):
        eth = {"symbol": "ETHUSDT"}
        sol = {"symbol": "SOLUSDT"}
        self.assertFalse(should_open_holdout([]))
        self.assertFalse(should_open_holdout([eth]))
        self.assertFalse(should_open_holdout([eth, eth]))
        self.assertTrue(should_open_holdout([sol, eth]))

    def test_pre_registered_budget_is_exactly_sixty_four_rules(self):
        rules = list(generate_rules())
        self.assertEqual(64, len(rules))
        self.assertEqual(64, len({rule.identifier for rule in rules}))

    def test_features_are_causal_when_future_changes(self):
        source = aligned_frame()
        before = build_features(source, "ETHUSDT")
        changed = source.copy()
        changed.loc[150:, ["eth_open", "eth_high", "eth_low", "eth_close"]] *= 4
        after = build_features(changed, "ETHUSDT")
        pd.testing.assert_series_equal(before.loc[:149, "m15"], after.loc[:149, "m15"])
        pd.testing.assert_series_equal(before.loc[:149, "eff60"], after.loc[:149, "eff60"])

    def test_gap_invalidates_every_window_until_it_is_rebuilt(self):
        source = aligned_frame()
        source.loc[100, "contiguous"] = False
        features = build_features(source, "SOLUSDT")
        for column in ("m1", "m3", "m8", "m15", "m60", "eff15", "eff60",
                       "a", "range_pos20", "btc_ret8", "other_ret3"):
            self.assertTrue(math.isnan(features.loc[100, column]), column)
        self.assertTrue(math.isnan(features.loc[110, "m15"]))
        self.assertTrue(math.isnan(features.loc[130, "m60"]))
        self.assertTrue(math.isfinite(features.loc[161, "m60"]))

    def test_purged_split_keeps_the_complete_label_inside_the_split(self):
        frame = build_features(aligned_frame(), "ETHUSDT")
        start_at = int(frame.open_time.iloc[20])
        end_at = int(frame.open_time.iloc[120])
        period = (
            pd.Timestamp(start_at, unit="ms", tz="UTC").isoformat(),
            pd.Timestamp(end_at, unit="ms", tz="UTC").isoformat(),
        )
        mask = purged_period_mask(frame, period, 30)
        selected = np.flatnonzero(mask)
        self.assertEqual(90, int(selected[-1]))
        self.assertLessEqual(int(frame.open_time.iloc[selected[-1] + 30]), end_at)

    def test_purged_split_rejects_a_label_crossing_a_feed_gap(self):
        frame = build_features(aligned_frame(), "ETHUSDT")
        frame.loc[100:, "segment_id"] = 2
        period = (
            pd.Timestamp(int(frame.open_time.iloc[0]), unit="ms", tz="UTC").isoformat(),
            pd.Timestamp(int(frame.open_time.iloc[-1]), unit="ms", tz="UTC").isoformat(),
        )
        mask = purged_period_mask(frame, period, 30)
        self.assertFalse(bool(mask[80]))
        self.assertTrue(bool(mask[110]))

    def test_tick_rounding_and_scaled_cost(self):
        self.assertEqual(1900.01, ceil_tick(1900.001, 0.01))
        self.assertEqual(1900.00, floor_tick(1900.009, 0.01))
        self.assertEqual(1.43, profile_cost("ETHUSDT", 2500.0))
        self.assertEqual(0.06, profile_cost("SOLUSDT", 75.80))
        self.assertEqual(0.12, profile_cost("SOLUSDT", 151.60))

    def test_archive_reader_rejects_future_independent_invalid_ohlc(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "sample.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("sample.csv", "1735689600000,10,9,8,10,1,0,0,0,0,0,0\n")
            with self.assertRaises(RuntimeError):
                read_kline_archive(archive, "ETHUSDT")

    def test_rule_identifier_is_deterministic(self):
        first = list(generate_rules())[0]
        same = RuleSpec(first.symbol, first.family, first.side, first.params)
        self.assertEqual(first.identifier, same.identifier)

    def test_rule_mask_does_not_use_future_close(self):
        source = aligned_frame()
        frame = build_features(source, "ETHUSDT")
        rule = list(generate_rules())[0]
        before = rule_mask(frame, rule)
        source.loc[170:, "eth_close"] *= 3
        after = rule_mask(build_features(source, "ETHUSDT"), rule)
        np.testing.assert_array_equal(before[:169], after[:169])

    def test_simulator_enters_on_following_bar_and_uses_sl_priority(self):
        source = aligned_frame(220)
        source["eth_high"] = source["eth_close"] + 2.0
        source["eth_low"] = source["eth_close"] - 2.0
        frame = build_features(source, "ETHUSDT")
        # Force a known mask by using a permissive lead/lag rule and deterministic features.
        rule = RuleSpec("ETHUSDT", "BTC_LEAD_LAG", 1, (
            ("btc_min", -1.0), ("lag_max", 100.0), ("lag_min", -100.0),
            ("start_min", -100.0), ("other_min", -1.0),
        ))
        qualified = int(np.flatnonzero(rule_mask(frame, rule))[0])
        entry_index = qualified + 1
        a = float(frame.loc[qualified, "a"])
        entry = ceil_tick(float(frame.loc[entry_index, "open"]), 0.01)
        tp = floor_tick(entry + 1.25 * a, 0.01)
        sl = floor_tick(entry - 1.0 * a, 0.01)
        frame.loc[entry_index, "high"] = tp + 1
        frame.loc[entry_index, "low"] = sl - 1
        start = pd.to_datetime(int(frame.loc[qualified, "open_time"]), unit="ms", utc=True).strftime("%Y-%m-%d %H:%M:%S")
        end = pd.to_datetime(int(frame.iloc[-1]["open_time"]), unit="ms", utc=True).strftime("%Y-%m-%d %H:%M:%S")
        result = simulate(frame, rule, Geometry(1.25, 1.0), (start, end))
        self.assertGreaterEqual(result.trades, 1)
        first = result.trades_detail[0]
        self.assertEqual(int(frame.loc[entry_index, "open_time"]), first.opened_at)
        self.assertEqual("SL", first.status)
        self.assertEqual(-1.0, first.result_r)

    def test_deflated_sharpe_penalizes_multiple_trials(self):
        results = [0.8] * 40 + [-1.0] * 10
        one = deflated_sharpe_probability(results, 1)
        many = deflated_sharpe_probability(results, 128)
        self.assertIsNotNone(one)
        self.assertIsNotNone(many)
        self.assertGreater(one, many)

    def test_non_finite_values_do_not_qualify(self):
        frame = build_features(aligned_frame(), "SOLUSDT")
        rule = list(generate_rules())[-1]
        frame.loc[:, "a"] = np.nan
        self.assertFalse(rule_mask(frame, rule).any())

    def test_linear_model_is_deterministic_and_causal(self):
        frame = build_features(aligned_frame(1600), "ETHUSDT")
        start = pd.to_datetime(int(frame.iloc[0]["open_time"]), unit="ms", utc=True).strftime("%Y-%m-%d %H:%M:%S")
        end = pd.to_datetime(int(frame.iloc[1400]["open_time"]), unit="ms", utc=True).strftime("%Y-%m-%d %H:%M:%S")
        first = fit_linear_score_model(frame, (start, end), horizon_minutes=30)
        second = fit_linear_score_model(frame, (start, end), horizon_minutes=30)
        self.assertEqual(first, second)
        self.assertEqual(24, len(first.feature_names))
        self.assertEqual((len(frame), 24), linear_feature_matrix(frame).shape)
        self.assertFalse(first.signal_mask(frame)[:60].any())


if __name__ == "__main__":
    unittest.main()
