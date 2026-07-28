import math
import importlib.util
import hashlib
import json
from pathlib import Path
import unittest

_spec = importlib.util.spec_from_file_location(
    "validate_sol_profile", Path(__file__).with_name("validate_sol_profile.py"))
validator = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(validator)


class SolProfileValidatorTest(unittest.TestCase):
    def test_quantity_one_is_valid(self):
        self.assertEqual(1, validator.validate_quantity(1, 10.0, 4.0))

    def test_quantity_one_hundred_twenty_is_valid(self):
        self.assertEqual(120, validator.validate_quantity(120, 14.55, 0.12))

    def test_quantity_zero_is_rejected(self):
        with self.assertRaises(RuntimeError):
            validator.validate_quantity(0, 10.0, 0.10)

    def test_quantity_one_hundred_twenty_one_is_rejected(self):
        with self.assertRaises(RuntimeError):
            validator.validate_quantity(121, 14.55, 0.10)

    def test_real_raw_quantity_above_cap_is_rejected_without_clamp(self):
        raw = math.floor(14.55 / 0.07)
        self.assertGreater(raw, 120)
        with self.assertRaises(RuntimeError):
            validator.validate_quantity(raw, 14.55, 0.07)

    def test_budget_excess_is_rejected(self):
        with self.assertRaises(RuntimeError):
            validator.validate_quantity(120, 10.0, 0.09)

    def test_stop_minimum_above_maximum_is_rejected(self):
        with self.assertRaises(RuntimeError):
            validator.validate_distance_bounds(0.11, 0.10, 0.12, 0.23)

    def test_target_floor_above_cap_is_rejected(self):
        with self.assertRaises(RuntimeError):
            validator.validate_distance_bounds(0.03, 0.10, 0.24, 0.23)


class SolProfileManifestIntegrityTest(unittest.TestCase):
    def test_versioned_manifest_matches_canonical_sha_and_report(self):
        root = Path(__file__).resolve().parents[1]
        manifest_path = root / "SOL_PROFILE_V1_CORPUS_MANIFEST.json"
        report_path = root / "SOL_PROFILE_V1_RESEARCH_REPORT.md"
        document = json.loads(manifest_path.read_text(encoding="utf-8"))
        expected = document.pop("manifestSha256")
        canonical = json.dumps(
            document, sort_keys=True, separators=(",", ":")).encode()
        self.assertEqual(expected, hashlib.sha256(canonical).hexdigest())
        self.assertEqual(
            {"ETHUSDT": 1088640, "SOLUSDT": 1088640, "BTCUSDT": 1088640},
            document["counts"])
        self.assertEqual(
            {"ETHUSDT": 0, "SOLUSDT": 0, "BTCUSDT": 0}, document["gaps"])
        self.assertEqual(
            {"ETHUSDT": 0, "SOLUSDT": 0, "BTCUSDT": 0}, document["duplicates"])
        self.assertEqual(15496, document["quantityRejectionsAboveSafetyCap"])
        report = report_path.read_text(encoding="utf-8")
        self.assertIn(expected, report)
        self.assertIn("1,088,640 bougies", report)
        self.assertIn("15,496 calculs de quantité rejetés", report)


if __name__ == "__main__":
    unittest.main()
