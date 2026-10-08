"""Archive checks catch silently incomplete capture after coordinator extraction."""
from pathlib import Path
import json
import tempfile
import unittest
import zipfile

from validate_archives import validate_archive


class ArchiveValidationTest(unittest.TestCase):
    def setUp(self):
        huge = str((1 << 120) + 1)
        self.entries = {
            "export-status.json": {"complete": True},
            "request.json": {"engine": "CGSE", "target": "k0", "amount": huge},
            "result.json": {"status": "FEASIBLE", "fallback": False, "coordinator": {
                "directEmission": True, "fallbackAttempted": False, "fallbackMode": False}},
            "network.json": {"pattern_count": 0, "grid_size": 0, "capture_errors": [], "capture_ns": "123",
                             "cached_inventory": {"k0": huge}, "advertised_inventory": {"k0": huge},
                             "extractable_inventory": {"k0": huge}, "producer_order": {}},
            "resources.jsonl": {"id": "k0", "snbt": "{}"},
            "patterns.jsonl": "",
            "nodes.jsonl": "",
            "cgse-input.json": {"complete": True, "available": {}, "stock": {"k0": huge},
                                "force_craft": False, "recipes": [], "producer_order": {}},
            "cgse-plan.json": {"result": "FEASIBLE", "target": "k0", "amount": huge,
                               "initial": {"k0": huge}, "missing": {}, "seeds": {}, "recipes": [],
                               "program_root": 0, "program": [{"id": 0, "kind": "sequence", "children": []}]},
        }

    def validate(self):
        with tempfile.TemporaryDirectory(prefix="cgse-aedump-test-") as temporary:
            path = Path(temporary) / "capture.zip"
            with zipfile.ZipFile(path, "w") as archive:
                for name, data in self.entries.items():
                    archive.writestr(name, data if isinstance(data, str) else json.dumps(data))
            return validate_archive(path)

    def test_exact_amounts_and_direct_emission(self):
        report = self.validate()
        self.assertTrue(report["complete"])
        self.assertEqual(1, report["stock_above_2pow53"])
        self.assertEqual(str((1 << 120) + 1), report["amount"])

    def test_missing_delegate_is_not_complete_input(self):
        self.entries["cgse-input.json"]["complete"] = False
        with self.assertRaisesRegex(AssertionError, "delegate was not captured"):
            self.validate()

    def test_missing_moved_flags(self):
        self.entries["result.json"]["coordinator"] = {}
        with self.assertRaisesRegex(AssertionError, "delegated coordinator flag"):
            self.validate()

    def test_emission_force_craft_mismatch(self):
        self.entries["cgse-input.json"]["force_craft"] = True
        with self.assertRaisesRegex(AssertionError, "force-craft"):
            self.validate()

    def test_fallback_auto_export_state(self):
        self.entries["result.json"]["coordinator"]["fallbackAttempted"] = True
        with self.assertRaisesRegex(AssertionError, "Fallback attempt omitted"):
            self.validate()
        self.entries["result.json"]["fallback"] = True
        self.assertTrue(self.validate()["complete"])

    def test_actual_input_must_use_integer_strings(self):
        self.entries["cgse-input.json"]["available"] = {"k0": 9007199254740993}
        with self.assertRaisesRegex(AssertionError, "Lossy quantity"):
            self.validate()

    def test_incomplete_archive_stays_declared(self):
        self.entries = {"export-status.json": {"complete": False, "failed_entry": "network.json"}}
        report = self.validate()
        self.assertFalse(report["complete"])
        self.assertEqual("network.json", report["failed_entry"])


if __name__ == "__main__":
    unittest.main()
