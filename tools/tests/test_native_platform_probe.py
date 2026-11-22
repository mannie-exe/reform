"""Native process completion alone cannot qualify a packaged target."""

from types import SimpleNamespace
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools import native_platform_probe as platform
from tools.native_platform_probe import retain_captures, successful_boot


class OptionalAdmissionTests(unittest.TestCase):
    def test_workspace_requires_its_own_client_scenario(self):
        for options in [{}, {"client": True, "world_client": True},
                        {"client": True, "recipe_client": True},
                        {"client": True, "authoring_client": True},
                        {"client": True, "chooser_client": True},
                        {"client": True, "command_client": True}]:
            with self.subTest(options=options), patch.object(platform, "select_library") as resolve:
                with self.assertRaisesRegex(ValueError, "separate native client scenario"):
                    platform.run("fabric-26_3", None, 1, workspace_client=True, **options)
                resolve.assert_not_called()

    def test_workflow_requires_its_own_client_scenario(self):
        for options in [{}, {"client": True, "world_client": True},
                        {"client": True, "recipe_client": True},
                        {"client": True, "authoring_client": True},
                        {"client": True, "chooser_client": True},
                        {"client": True, "command_client": True},
                        {"client": True, "workspace_client": True}]:
            with self.subTest(options=options), patch.object(platform, "select_library") as resolve:
                with self.assertRaisesRegex(ValueError, "separate native client scenario"):
                    platform.run("fabric-26_3", None, 1, workflow_client=True, **options)
                resolve.assert_not_called()

    def test_connector_refuses_undeclared_targets_or_client_before_resolution(self):
        for target, client in [("fabric-1_21_1", False), ("fabric-26_3", False),
                               ("neoforge-26_3", False), ("neoforge-1_21_1", True)]:
            with self.subTest(target=target, client=client), patch.object(platform, "select_library") as resolve:
                with self.assertRaisesRegex(ValueError, "declared dedicated NeoForge target"):
                    platform.run(target, None, 1, client=client, connector=True)
                resolve.assert_not_called()

    def test_client_commands_require_their_own_native_client_scenario(self):
        for options in [{}, {"client": True, "world_client": True},
                        {"client": True, "recipe_client": True},
                        {"client": True, "authoring_client": True},
                        {"client": True, "chooser_client": True}]:
            with self.subTest(options=options), patch.object(platform, "select_library") as resolve:
                with self.assertRaisesRegex(ValueError, "separate native client scenario"):
                    platform.run("fabric-26_3", None, 1, command_client=True, **options)
                resolve.assert_not_called()


class CaptureRetentionTests(unittest.TestCase):
    def test_checked_capture_is_retained_and_hashed_for_each_boot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture, reports = root / "fixture", root / "reports"
            fixture.mkdir()
            reports.mkdir()
            name, content = "preview.png", b"native screenshot"
            (fixture / name).write_bytes(content)
            for reopen, prefix in [(False, "first-"), (True, "reopen-")]:
                kept = retain_captures(fixture, reports, [name], {name}, reopen)
                self.assertEqual([{"file": prefix + name,
                                   "sha256": hashlib.sha256(content).hexdigest()}], kept)
                self.assertEqual(content, (reports / (prefix + name)).read_bytes())

    def test_foreign_or_unbounded_capture_list_is_refused(self):
        for names in [None, "preview.png", ["preview.png"] * 65,
                      ["../foreign.png"], ["unrequested.png"], [None]]:
            with self.subTest(names=names), self.assertRaises(ValueError):
                retain_captures(Path("absent"), Path("absent"), names, {"preview.png"}, False)

    def test_missing_symlink_and_oversized_capture_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture, reports = root / "fixture", root / "reports"
            fixture.mkdir()
            reports.mkdir()
            (root / "foreign.png").write_bytes(b"foreign")
            (fixture / "symlink.png").symlink_to(root / "foreign.png")
            with (fixture / "large.png").open("wb") as output:
                output.truncate(8 * 1024 * 1024 + 1)
            for name in ["missing.png", "symlink.png", "large.png"]:
                with self.subTest(name=name), self.assertRaises(ValueError):
                    retain_captures(fixture, reports, [name], {name}, False)
            self.assertEqual([], list(reports.iterdir()))


class PlatformOutcomeTests(unittest.TestCase):
    @staticmethod
    def held_stops():
        return [{"status": "PASS", "phase": phase, "writes": writes,
                 "savedDistance": distance, "closedBeforeRelease": True,
                 "queuedRepliesOnly": True, "outstandingCalls": 0,
                 "editorReportValid": False, "receiptPresent": bool(writes),
                 **({"persistence": "SAVED", "application": "UNAVAILABLE",
                     "privateObservationPresent": False} if writes else {})}
                for phase, writes, distance in [("PREPARE", 0, 7), ("RETURN", 1, 9)]]

    def setUp(self):
        self.process = {"exitCode": 0, "termination": None}
        self.native = {"status": "PASS", "shutdownClosed": True, "librarySha256": "actual",
                       "java": "25.0.4+101", "loadedLibrary": "file:/actual/library.jar",
                       "minecraft": "26.3", "loader": "0.20.1"}
        self.library = SimpleNamespace(sha256="actual")
        self.target = {"java": 25, "minecraftVersion": "26.3"}

    def accepted(self):
        return successful_boot(self.process, self.native, self.library, self.target, "0.20.1")

    def test_complete_native_outcome(self):
        self.assertTrue(self.accepted())

    def test_normal_exit_without_native_report_is_not_success(self):
        self.native = {}
        self.assertFalse(self.accepted())

    def test_failed_or_unclosed_native_scope_is_not_success(self):
        for field, value in [("status", "FAIL"), ("shutdownClosed", False)]:
            with self.subTest(field=field):
                original = self.native[field]
                self.native[field] = value
                self.assertFalse(self.accepted())
                self.native[field] = original

    def test_wrong_runtime_or_product_is_not_success(self):
        for field, value in [("java", "21.0.8"), ("librarySha256", "other"),
                             ("minecraft", "1.21.1"), ("loader", "other"),
                             ("loadedLibrary", "file:/source/classes/")]:
            with self.subTest(field=field):
                original = self.native[field]
                self.native[field] = value
                self.assertFalse(self.accepted())
                self.native[field] = original

    def test_terminated_or_failed_process_is_not_success(self):
        for field, value in [("exitCode", 1), ("termination", "deadline")]:
            with self.subTest(field=field):
                original = self.process[field]
                self.process[field] = value
                self.assertFalse(self.accepted())
                self.process[field] = original

    def test_client_requires_visible_value_pixels_and_retained_image(self):
        def accepted():
            return successful_boot(self.process, self.native, self.library, self.target, "0.20.1", True)
        self.assertFalse(accepted())
        self.native.update(visibleLabelPixels=444, imageSha256="a" * 64,
                           shutdownEditorReady=True, shutdownEditorClosed=True)
        self.assertTrue(accepted())
        self.native["visibleLabelPixels"] = 0
        self.assertFalse(accepted())

    def test_client_runtime_closure_does_not_prove_editor_closure(self):
        self.native.update(visibleLabelPixels=444, imageSha256="a" * 64,
                           shutdownEditorReady=True, shutdownEditorClosed=True)
        for field in ["shutdownEditorReady", "shutdownEditorClosed"]:
            with self.subTest(field=field):
                del self.native[field]
                self.assertFalse(successful_boot(self.process, self.native, self.library,
                                                 self.target, "0.20.1", True))
                self.native[field] = False
                self.assertFalse(successful_boot(self.process, self.native, self.library,
                                                 self.target, "0.20.1", True))
                self.native[field] = True

    def test_workflow_success_requires_every_native_lifetime_closed(self):
        fields = ("workflowClosed", "independentClosed", "shutdownClosed")
        self.native["workflow"] = {"status": "PASS", **dict.fromkeys(fields, True)}
        def accepted():
            return successful_boot(self.process, self.native, self.library, self.target,
                                   "0.20.1", required_scenarios=("workflow",))
        self.assertTrue(accepted())
        for field in fields:
            for value in [None, False, 1, "true"]:
                with self.subTest(field=field, value=value):
                    self.native["workflow"][field] = value
                    self.assertFalse(accepted())
            self.native["workflow"][field] = True

    def test_requested_native_scenario_needs_its_own_success(self):
        for name in ["world", "recipe", "authoring", "initializationFailure", "nativeCommands", "nativeSearch", "connector", "clientCommands", "workspace", "workflow"]:
            with self.subTest(scenario=name):
                def accepted():
                    return successful_boot(self.process, self.native, self.library,
                                           self.target, "0.20.1", required_scenarios=(name,))
                self.assertFalse(accepted())
                for child in [None, "PASS", {}, {"status": "FAIL"}]:
                    self.native[name] = child
                    self.assertFalse(accepted())
                self.native[name] = {"status": "PASS"}
                if name == "world":
                    self.assertFalse(accepted())
                    self.native[name]["heldStops"] = self.held_stops()
                if name == "workflow":
                    self.assertFalse(accepted())
                    self.native[name].update(workflowClosed=True, independentClosed=True, shutdownClosed=True)
                if name == "recipe":
                    self.assertFalse(accepted())
                    self.native[name]["advanced"] = {"status": "PASS"}
                    self.assertFalse(accepted())
                    self.native[name]["advanced"]["shutdownClosed"] = True
                if name == "connector":
                    self.assertFalse(accepted())
                    self.target.update(connectorVersion="connector-pin", forgifiedFabricApiVersion="api-pin")
                    self.native[name].update(connectorVersion="connector-pin", fabricApiVersion="api-pin")
                    self.assertTrue(accepted())
                    self.native[name]["connectorVersion"] = "unselected-pin"
                    self.assertFalse(accepted())
                    self.native[name]["connectorVersion"] = "connector-pin"
                if name == "clientCommands":
                    self.assertFalse(accepted())
                    self.native[name]["variants"] = {"status": "PASS"}
                    self.assertFalse(accepted())
                    self.native[name]["shutdownOwnerClosed"] = True
                    self.assertFalse(accepted())
                    self.native[name]["held"] = {"status": "PASS"}
                    self.assertFalse(accepted())
                    self.native[name]["held"]["contextClosed"] = True
                self.assertTrue(accepted())
                del self.native[name]

    def test_held_stop_requires_truthful_storage_and_closed_delivery(self):
        for broken in [None, [], self.held_stops()[:1], list(reversed(self.held_stops()))]:
            with self.subTest(stops=broken):
                self.assertFalse(platform.complete_held_stops({"heldStops": broken}))
        for index, field, value in [(0, "writes", 1), (0, "receiptPresent", True),
                                     (1, "writes", 0), (1, "savedDistance", 7),
                                     (1, "persistence", "UNKNOWN"),
                                     (1, "application", "APPLIED"),
                                     (1, "privateObservationPresent", True)]:
            with self.subTest(index=index, field=field, value=value):
                stops = self.held_stops()
                stops[index][field] = value
                self.assertFalse(platform.complete_held_stops({"heldStops": stops}))
        self.assertTrue(platform.complete_held_stops({"heldStops": self.held_stops()}))

    def test_chooser_needs_actual_native_dialog_outcome(self):
        self.native["chooser"] = {"status": "PASS"}
        def accepted():
            return successful_boot(self.process, self.native, self.library,
                                   self.target, "0.20.1", required_scenarios=("chooser",))
        self.assertFalse(accepted())
        self.native["chooser"]["nativeFileDialogExercised"] = False
        self.assertFalse(accepted())
        self.native["chooser"]["nativeFileDialogExercised"] = True
        self.assertTrue(accepted())
