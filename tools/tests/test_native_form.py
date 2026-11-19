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
    def test_batch_requires_native_completion_shutdown_and_complete_captures(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "batch",
            "librarySha256": "exact-jar", "batch": {"status": "PASS"}}}
        self.assertFalse(PROBE.successful_run(result, "batch", "exact-jar"))
        result["native"]["batch"]["workflowClosed"] = True
        self.assertTrue(PROBE.successful_run(result, "batch", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "batch", "another-jar"))
        report = {"runs": [result], "screenshots": {}}
        self.assertFalse(PROBE.successful_report(report, ["batch"], "exact-jar"))
        report["screenshots"] = {f"form-batch-{name}{frame}.png": "digest"
                                 for name in ("invalid", "wide", "narrow", "result")
                                 for frame in ("", "-entry")}
        self.assertTrue(PROBE.successful_report(report, ["batch"], "exact-jar"))
        result["native"]["batch"]["status"] = "RUNNING"
        self.assertFalse(PROBE.successful_report(report, ["batch"], "exact-jar"))

    def test_composed_workflow_requires_all_lifetimes_and_both_viewports(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "workflow",
            "librarySha256": "exact-jar", "workflow": {"status": "PASS"}}}
        for name in ("workflowClosed", "independentClosed", "shutdownClosed"):
            self.assertFalse(PROBE.successful_run(result, "workflow", "exact-jar"))
            result["native"]["workflow"][name] = True
        self.assertTrue(PROBE.successful_run(result, "workflow", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "workflow-reopen", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "workflow", "another-jar"))
        report = {"runs": [result], "screenshots": {}}
        self.assertFalse(PROBE.successful_report(report, ["workflow"], "exact-jar"))
        report["screenshots"] = {f"form-workflow-{size}{frame}.png": "digest"
                                 for size in ("wide", "narrow") for frame in ("", "-entry")}
        self.assertTrue(PROBE.successful_report(report, ["workflow"], "exact-jar"))

    def test_console_cause_is_bounded_and_never_follows_an_external_link(self):
        log = self.fixture.parent / "runtime.log"
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_bytes(b"x" * 40000 + b"actual native refusal")
        evidence = PROBE.console_evidence(log)
        self.assertFalse(evidence["complete"])
        self.assertEqual(len(evidence["text"].encode()), 32768)
        self.assertTrue(evidence["text"].endswith("actual native refusal"))
        link = log.with_name("external.log")
        link.symlink_to(log)
        self.assertIsNone(PROBE.console_evidence(link))
        self.assertIsNone(PROBE.console_evidence(log.with_name("missing.log")))

    def test_registered_native_pass_requires_both_native_lifetimes_and_captures(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "registered",
            "librarySha256": "exact-jar", "nativeRegistered": {"status": "PASS", "clientClosed": True}}}
        self.assertFalse(PROBE.successful_run(result, "registered", "exact-jar"))
        result["native"]["nativeRegistered"]["defaultsClosed"] = True
        self.assertTrue(PROBE.successful_run(result, "registered", "exact-jar"))
        report = {"runs": [result], "screenshots": {}}
        self.assertFalse(PROBE.successful_report(report, ["registered"], "exact-jar"))
        report["screenshots"] = {f"form-native-{kind}{frame}.png": "digest"
                                 for kind in ("review", "defaults") for frame in ("", "-entry")}
        self.assertTrue(PROBE.successful_report(report, ["registered"], "exact-jar"))

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

    def test_chooser_success_requires_actual_native_ui_receipt(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "chooser",
            "librarySha256": "exact-jar", "chooser": {"status": "PASS"}}}
        self.assertFalse(PROBE.successful_run(result, "chooser", "exact-jar"))
        result["native"]["chooser"]["nativeFileDialogExercised"] = True
        self.assertTrue(PROBE.successful_run(result, "chooser", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "chooser", "different-jar"))

    def test_conflict_success_requires_actual_choices_and_matching_run(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "conflicts",
            "librarySha256": "exact-jar"}}
        self.assertFalse(PROBE.successful_run(result, "conflicts", "exact-jar"))
        result["native"]["conflicts"] = {"status": "PASS"}
        self.assertTrue(PROBE.successful_run(result, "conflicts", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "conflicts-reopen", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "conflicts", "different-jar"))
        result["native"]["nativeShutdownClosed"] = False
        self.assertFalse(PROBE.successful_run(result, "conflicts", "exact-jar"))

    def test_native_owned_workflows_require_a_closed_context(self):
        for mode, field in [("command-conflicts", "commandConflicts"), ("keys-owner", "keyOwner"), ("keys-controls", "keyControls")]:
            with self.subTest(mode=mode):
                result = {"exitCode": 0, "termination": None, "native": {
                    "status": "PASS", "nativeShutdownClosed": True, "mode": mode,
                    "librarySha256": "exact-jar", field: {"status": "PASS", "contextClosed": False}}}
                self.assertFalse(PROBE.successful_run(result, mode, "exact-jar"))
                result["native"][field]["contextClosed"] = True
                self.assertTrue(PROBE.successful_run(result, mode, "exact-jar"))
                self.assertFalse(PROBE.successful_run(result, mode + "-reopen", "exact-jar"))
                self.assertFalse(PROBE.successful_run(result, mode, "different-jar"))

    def test_native_key_factory_requires_both_lifecycle_shutdowns(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "keys-factory",
            "librarySha256": "exact-jar", "keyFactory": {"status": "PASS",
                "factoryClosed": False, "unavailableClosed": False}}}
        self.assertFalse(PROBE.successful_run(result, "keys-factory", "exact-jar"))
        result["native"]["keyFactory"]["factoryClosed"] = True
        self.assertFalse(PROBE.successful_run(result, "keys-factory", "exact-jar"))
        result["native"]["keyFactory"]["unavailableClosed"] = True
        self.assertTrue(PROBE.successful_run(result, "keys-factory", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "keys-factory-reopen", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "keys-factory", "different-jar"))

    def test_close_requires_actual_context_end_and_worker_drain(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "close",
            "librarySha256": "exact-jar", "close": {"status": "PASS"}}}
        self.assertFalse(PROBE.successful_run(result, "close", "exact-jar"))
        result["native"]["close"]["contextClosed"] = True
        self.assertFalse(PROBE.successful_run(result, "close", "exact-jar"))
        result["native"]["close"]["workerTerminated"] = True
        self.assertTrue(PROBE.successful_run(result, "close", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "close-reopen", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "close", "different-jar"))

    def test_submission_requires_actual_outcomes_drain_and_complete_frames(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "outcomes",
            "librarySha256": "exact-jar", "outcome": {"status": "PASS", "contextClosed": True}}}
        self.assertFalse(PROBE.successful_run(result, "outcomes", "exact-jar"))
        result["native"]["outcome"]["workerTerminated"] = True
        self.assertTrue(PROBE.successful_run(result, "outcomes", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "outcomes-reopen", "exact-jar"))
        report = {"runs": [result], "screenshots": {
            f"form-{kind}{frame}.png": "a" * 64
            for kind in ("submit-wide", "outcome-wide", "outcome-narrow")
            for frame in ("", "-entry")}}
        self.assertTrue(PROBE.successful_report(report, ("outcomes",), "exact-jar"))
        del report["screenshots"]["form-outcome-narrow.png"]
        self.assertFalse(PROBE.successful_report(report, ("outcomes",), "exact-jar"))

    def test_files_require_actual_factory_shutdown_and_completed_frames(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "files",
            "librarySha256": "exact-jar", "files": {"status": "PASS", "factoriesClosed": False}}}
        self.assertFalse(PROBE.successful_run(result, "files", "exact-jar"))
        result["native"]["files"]["factoriesClosed"] = True
        self.assertTrue(PROBE.successful_run(result, "files", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "files-reopen", "exact-jar"))
        report = {"runs": [result], "screenshots": {
            "form-files-properties.png": "a" * 64, "form-files-properties-entry.png": "a" * 64}}
        self.assertTrue(PROBE.successful_report(report, ("files",), "exact-jar"))
        del report["screenshots"]["form-files-properties.png"]
        self.assertFalse(PROBE.successful_report(report, ("files",), "exact-jar"))

    def test_startup_requires_closed_factories_and_pending_construction_outcome(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "startup",
            "librarySha256": "exact-jar", "startup": {
                "status": "PASS", "factoriesClosed": True, "heldConstructionClosed": False}}}
        self.assertFalse(PROBE.successful_run(result, "startup", "exact-jar"))
        result["native"]["startup"]["heldConstructionClosed"] = True
        self.assertTrue(PROBE.successful_run(result, "startup", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "startup-reopen", "exact-jar"))
        report = {"runs": [result], "screenshots": {
            "form-startup-review.png": "a" * 64, "form-startup-review-entry.png": "a" * 64}}
        self.assertTrue(PROBE.successful_report(report, ("startup",), "exact-jar"))
        del report["screenshots"]["form-startup-review.png"]
        self.assertFalse(PROBE.successful_report(report, ("startup",), "exact-jar"))

    def test_catalog_success_requires_editor_and_owner_shutdown(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "catalog",
            "librarySha256": "exact-jar", "catalog": {"status": "PASS",
                "editorClosed": False, "factoryClosed": False}}}
        self.assertFalse(PROBE.successful_run(result, "catalog", "exact-jar"))
        result["native"]["catalog"]["editorClosed"] = True
        self.assertFalse(PROBE.successful_run(result, "catalog", "exact-jar"))
        result["native"]["catalog"]["factoryClosed"] = True
        self.assertTrue(PROBE.successful_run(result, "catalog", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "catalog-reopen", "exact-jar"))
        self.assertFalse(PROBE.successful_run(result, "catalog", "different-jar"))

    def test_catalog_report_requires_captured_completed_frames(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "catalog",
            "librarySha256": "exact-jar", "catalog": {"status": "PASS",
                "editorClosed": True, "factoryClosed": True}}}
        report = {"runs": [result], "screenshots": {}}
        self.assertTrue(PROBE.successful_run(result, "catalog", "exact-jar"))
        self.assertFalse(PROBE.successful_report(report, ("catalog",), "exact-jar"))
        report["screenshots"] = {name: "a" * 64 for name in (
            "form-catalog-wide.png", "form-catalog-wide-entry.png",
            "form-catalog-narrow.png", "form-catalog-narrow-entry.png",
            "form-catalog-localized.png", "form-catalog-localized-entry.png")}
        self.assertTrue(PROBE.successful_report(report, ("catalog",), "exact-jar"))
        del report["screenshots"]["form-catalog-narrow.png"]
        self.assertFalse(PROBE.successful_report(report, ("catalog",), "exact-jar"))

    def test_review_requires_actual_editor_shutdown_and_completed_frame_captures(self):
        result = {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "review",
            "librarySha256": "exact-jar", "review": {"status": "PASS",
                "editorClosed": True, "factoryClosed": False}}}
        self.assertFalse(PROBE.successful_run(result, "review", "exact-jar"))
        result["native"]["review"]["factoryClosed"] = True
        self.assertTrue(PROBE.successful_run(result, "review", "exact-jar"))
        report = {"runs": [result], "screenshots": {}}
        self.assertFalse(PROBE.successful_report(report, ("review",), "exact-jar"))
        report["screenshots"] = {name: "a" * 64 for name in (
            "form-review-diagnostics.png", "form-review-diagnostics-entry.png",
            "form-review-narrow.png", "form-review-narrow-entry.png")}
        self.assertTrue(PROBE.successful_report(report, ("review",), "exact-jar"))
        self.assertFalse(PROBE.successful_report(report, ("review",), "different-jar"))
        report["runs"].insert(0, {"exitCode": 0, "termination": None, "native": {
            "status": "PASS", "nativeShutdownClosed": True, "mode": "catalog",
            "librarySha256": "exact-jar", "catalog": {"status": "PASS",
                "editorClosed": True, "factoryClosed": True}}})
        self.assertFalse(PROBE.successful_report(report, ("catalog", "review"), "exact-jar"))
        report["screenshots"].update({name: "a" * 64 for name in (
            "form-catalog-wide.png", "form-catalog-wide-entry.png",
            "form-catalog-narrow.png", "form-catalog-narrow-entry.png",
            "form-catalog-localized.png", "form-catalog-localized-entry.png")})
        self.assertTrue(PROBE.successful_report(report, ("catalog", "review"), "exact-jar"))
        del report["screenshots"]["form-review-narrow.png"]
        self.assertFalse(PROBE.successful_report(report, ("catalog", "review"), "exact-jar"))
