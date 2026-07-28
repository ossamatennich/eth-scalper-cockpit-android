import math
import importlib.util
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


if __name__ == "__main__":
    unittest.main()
