"""The GUI runner may remove only the disposable tree it created, even after a failed boot."""

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
CONFIG_SPEC = importlib.util.spec_from_file_location("native_config_probe", TOOLS / "native_config_probe.py")
CONFIG = importlib.util.module_from_spec(CONFIG_SPEC)
CONFIG_SPEC.loader.exec_module(CONFIG)
SPEC = importlib.util.spec_from_file_location("reform_form_probe", TOOLS / "native_form_probe.py")
PROBE = importlib.util.module_from_spec(SPEC)
with patch.dict(sys.modules, {"native_config_probe": CONFIG}):
    SPEC.loader.exec_module(PROBE)


class NativeFormFixtureGuards(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="reform-form-tool-test-")
        self.addCleanup(temporary.cleanup)
        self.fixture = Path(temporary.name) / "runs/client"

    def test_existing_client_is_never_adopted(self):
        self.fixture.mkdir(parents=True)
        existing = self.fixture / "options.txt"
        existing.write_text("retained")
        with self.assertRaises(FileExistsError):
            PROBE.create_fixture(self.fixture, "own-token")
        self.assertEqual(existing.read_text(), "retained")
        self.assertFalse((self.fixture / PROBE.MARKER).exists())

    def test_cleanup_requires_the_original_ownership_token(self):
        PROBE.create_fixture(self.fixture, "own-token")
        with self.assertRaisesRegex(ValueError, "ownership"):
            PROBE.cleanup_fixture(self.fixture, "foreign-token")
        self.assertTrue((self.fixture / "options.txt").is_file())
        PROBE.cleanup_fixture(self.fixture, "own-token")
        self.assertFalse(self.fixture.exists())

    def test_failure_keeps_bounded_crash_causes_before_owned_cleanup(self):
        PROBE.create_fixture(self.fixture, "own-token")
        crash = self.fixture / "crash-reports"
        crash.mkdir()
        (crash / "crash-normal.txt").write_text("Actual root exception\n")
        (crash / "crash-oversize.txt").write_bytes(b"x" * 131073)
        external = self.fixture.parent / "private.txt"
        external.write_text("outside fixture")
        (crash / "crash-link.txt").symlink_to(external)
        reports = PROBE.crash_evidence(self.fixture)
        self.assertEqual([r["name"] for r in reports], ["crash-normal.txt", "crash-oversize.txt"])
        self.assertEqual(reports[0]["text"], "Actual root exception\n")
        self.assertTrue(reports[0]["complete"])
        self.assertEqual(len(reports[1]["text"]), 131072)
        self.assertFalse(reports[1]["complete"])
        PROBE.cleanup_fixture(self.fixture, "own-token")
        self.assertFalse(self.fixture.exists())
        self.assertEqual(external.read_text(), "outside fixture")

    def test_command_import_success_requires_actual_controls_and_context_cleanup(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "command-imports",
            "librarySha256": "exact-jar"}}
        self.assertFalse(PROBE.successful_run(result, "command-imports", "exact-jar"))
        result["native"]["commandImports"] = {"status": "PASS", "contextClosed": False}
        self.assertFalse(PROBE.successful_run(result, "command-imports", "exact-jar"))
        result["native"]["commandImports"]["contextClosed"] = True
        self.assertTrue(PROBE.successful_run(result, "command-imports", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "command-imports-reopen", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "command-imports", "foreign-jar"))
