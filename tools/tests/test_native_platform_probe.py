"""Native process completion alone cannot qualify a packaged target."""

from types import SimpleNamespace
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools import native_platform_probe as platform
from tools.native_platform_probe import retain_captures, retain_native_report, successful_boot


class OptionalAdmissionTests(unittest.TestCase):
    def test_isolated_scene_refuses_unknown_or_mixed_scope_before_resolution(self):
        for scene, options in [("unknown", {"client": True}), ("operations", {}),
                               ("operations", {"client": True, "workspace_client": True}),
                               ("operations", {"client": True, "recipe_viewer": "jei"}),
                               ("captions", {}), ("captions", {"client": True, "workspace_client": True}),
                               ("references", {}), ("references", {"client": True, "workspace_client": True}),
                               ("entries", {}), ("entries", {"client": True, "workspace_client": True}),
                               ("batches", {}), ("batches", {"client": True, "workspace_client": True}),
                               ("components", {}), ("components", {"client": True, "workspace_client": True})]:
            with self.subTest(scene=scene, options=options), patch.object(platform, "select_library") as resolve:
                with self.assertRaisesRegex(ValueError, "separate admitted isolated"):
                    platform.run("neoforge-1_21_1", None, 1, isolated_scene=scene, **options)
                resolve.assert_not_called()

    def test_native_recipe_context_requires_separate_declared_target(self):
        for target, options in [("fabric-26_3", {"client": True, "native_recipe_client": True, "recipe_viewer": "emi"}),
                                ("neoforge-1_21_1", {"native_recipe_client": True}),
                                ("neoforge-1_21_1", {"client": True, "native_recipe_client": True, "world_client": True})]:
            with self.subTest(target=target, options=options), patch.object(platform, "select_library") as resolve:
                with self.assertRaises(ValueError):
                    platform.run(target, None, 1, **options)
                resolve.assert_not_called()

    def test_recipe_links_require_separate_client_before_resolving_inputs(self):
        for options in [{}, {"client": True, "world_client": True},
                        {"client": True, "authoring_client": True}]:
            with self.subTest(options=options), patch.object(platform, "select_library") as resolve:
                with self.assertRaisesRegex(ValueError, "Recipe links require their own"):
                    platform.run("neoforge-1_21_1", None, 1, recipe_links_client=True, **options)
                resolve.assert_not_called()

    def test_viewer_cannot_be_added_to_unrelated_client_scenario(self):
        with patch.object(platform, "select_library") as resolve:
            with self.assertRaisesRegex(ValueError, "requires its own declared recipe client target"):
                platform.run("neoforge-1_21_1", None, 1, client=True, workspace_client=True, recipe_viewer="jei")
            resolve.assert_not_called()

    def test_dual_viewers_require_declared_native_container_target(self):
        for target, options in [("fabric-26_3", {"client": True, "native_recipe_client": True}),
                                ("neoforge-1_21_1", {"client": True, "recipe_links_client": True}),
                                ("neoforge-1_21_1", {})]:
            with self.subTest(target=target, options=options), patch.object(platform, "select_library") as resolve:
                with self.assertRaises(ValueError):
                    platform.run(target, None, 1, recipe_viewer="both", **options)
                resolve.assert_not_called()

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
    def test_complete_declared_workspace_roles_survive_retention(self):
        names = sorted(platform.WORKSPACE_CAPTURES)
        self.assertIn("platform-operation-close-pending.png", names)
        self.assertIn("platform-operation-close-terminal.png", names)
        with tempfile.TemporaryDirectory() as directory:
            fixture, reports = Path(directory) / "fixture", Path(directory) / "reports"
            fixture.mkdir()
            reports.mkdir()
            for name in names:
                (fixture / name).write_bytes(name.encode())
            kept = retain_captures(fixture, reports, names, platform.WORKSPACE_CAPTURES, False)
            self.assertEqual(["first-" + name for name in names], [item["file"] for item in kept])
            for item, name in zip(kept, names):
                self.assertEqual((fixture / name).read_bytes(), (reports / item["file"]).read_bytes())
            with self.assertRaises(ValueError):
                retain_captures(fixture, reports, [names[0], names[0]],
                                platform.WORKSPACE_CAPTURES, True)

    def test_authoring_retains_its_embedded_workflow_graphics_only(self):
        names = ["platform-form-graphic-texture.png", "platform-form-graphic-layers.png",
                 "platform-form-graphic-orbit.png"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture, reports = root / "fixture", root / "reports"
            fixture.mkdir()
            reports.mkdir()
            for name in names:
                (fixture / name).write_bytes(name.encode())
            kept = retain_captures(fixture, reports, names, platform.AUTHORING_CAPTURES, False)
            self.assertEqual(["first-" + name for name in names], [item["file"] for item in kept])
            with self.assertRaisesRegex(ValueError, "Unrecognized native capture"):
                retain_captures(fixture, reports, ["platform-form-graphic-foreign.png"],
                                platform.AUTHORING_CAPTURES, False)

    def test_workspace_sprite_capture_roles_are_bounded_and_retained(self):
        import tools.native_platform_probe as platform
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture, reports = root / "fixture", root / "reports"
            fixture.mkdir()
            reports.mkdir()
            names = ["platform-loaded-sprite-gui.png", "platform-loaded-sprite-block.png", "platform-loaded-sprite-before-prepare.png", "platform-loaded-sprite-before-draw.png"]
            for name in names:
                (fixture / name).write_bytes(b"fixture native pixels")
            for reopen in [False, True]:
                kept = retain_captures(fixture, reports, names, platform.WORKSPACE_CAPTURES, reopen)
                self.assertEqual(len(kept), 4)
                self.assertTrue(all((reports / entry["file"]).is_file() for entry in kept))
            with self.assertRaisesRegex(ValueError, "Unrecognized native capture"):
                retain_captures(fixture, reports, ["platform-loaded-sprite-foreign.png"], platform.WORKSPACE_CAPTURES, False)

    def test_workspace_item_capture_roles_are_bounded_and_retained(self):
        import tools.native_platform_probe as platform
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture, reports = root / "fixture", root / "reports"
            fixture.mkdir()
            reports.mkdir()
            names = ["platform-loaded-item-stone.png", "platform-loaded-item-stone-orbit.png",
                     "platform-loaded-item-1.png", "platform-loaded-item-2.png",
                     "platform-loaded-item-small.png", "platform-loaded-item-returned.png"]
            for name in names:
                (fixture / name).write_bytes(b"actual item capture stand-in")
            for reopen in [False, True]:
                kept = retain_captures(fixture, reports, names, platform.WORKSPACE_CAPTURES, reopen)
                self.assertEqual(len(kept), len(names))
                for source, saved in zip(names, kept):
                    self.assertEqual((fixture / source).read_bytes(), (reports / saved["file"]).read_bytes())
            with self.assertRaisesRegex(ValueError, "Unrecognized native capture"):
                retain_captures(fixture, reports, ["platform-loaded-item-foreign.png"],
                                platform.WORKSPACE_CAPTURES, False)

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

    def test_actual_report_survives_later_capture_refusal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture, reports = root / "fixture", root / "reports"
            fixture.mkdir()
            reports.mkdir()
            raw = fixture / "platform-report.json"
            raw.write_bytes(b'{"status":"PASS","workspace":{"captures":["unknown.png"]}}')
            saved = retain_native_report(raw, reports, False)
            with self.assertRaisesRegex(ValueError, "Unrecognized native capture"):
                retain_captures(fixture, reports, ["unknown.png"], {"expected.png"}, False)
            self.assertEqual(raw.read_bytes(), (reports / saved["file"]).read_bytes())
            self.assertEqual(hashlib.sha256(raw.read_bytes()).hexdigest(), saved["sha256"])

    def test_raw_retention_preserves_malformed_payload_without_qualifying_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "raw.json"
            raw.write_bytes(b'{malformed')
            saved = retain_native_report(raw, root, True)
            self.assertEqual("reopen-native.json", saved["file"])
            self.assertEqual(raw.read_bytes(), (root / saved["file"]).read_bytes())
            self.assertIsNone(retain_native_report(root / "absent.json", root, False))

    def test_scene_failure_payload_survives_capture_admission_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "platform-scene-failure.json"
            content = b'{"status":"FAIL","failure":"exact native failure","captures":["foreign.png"]}'
            raw.write_bytes(content)
            saved = retain_native_report(raw, root, False, "scene-failure")
            self.assertEqual("first-scene-failure.json", saved["file"])
            with self.assertRaisesRegex(ValueError, "Unrecognized native capture"):
                retain_captures(root, root, ["foreign.png"], set(), False)
            self.assertEqual(content, (root / saved["file"]).read_bytes())
            self.assertEqual(hashlib.sha256(content).hexdigest(), saved["sha256"])
            with self.assertRaisesRegex(ValueError, "Unrecognized native report role"):
                retain_native_report(raw, root, False, "../outside")

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


class RuntimeArtifactRetentionTests(unittest.TestCase):
    def fixture(self, root):
        source = root / "actual.jar"
        source.write_bytes(b"measured-runtime-archive")
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        return source, {"runtimeArtifacts": {"reform": {
            "path": str(source), "bytes": source.stat().st_size, "sha256": digest}}}

    def test_actual_archive_is_copied_once_and_rehashed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, native = self.fixture(root)
            first = platform.retain_runtime_artifacts(native, root / "store")
            copy = Path(first["reform"]["path"])
            self.assertEqual(copy.read_bytes(), source.read_bytes())
            before = copy.stat().st_mtime_ns
            self.assertEqual(first, platform.retain_runtime_artifacts(native, root / "store"))
            self.assertEqual(copy.stat().st_mtime_ns, before)

    def test_changed_archive_cannot_pass_its_earlier_observation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, native = self.fixture(root)
            source.write_bytes(b"different-runtime-input!")
            with self.assertRaisesRegex(ValueError, "differs from its native observation"):
                platform.retain_runtime_artifacts(native, root / "store")

    def test_corrupt_existing_copy_is_not_overwritten_or_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, native = self.fixture(root)
            first = platform.retain_runtime_artifacts(native, root / "store")
            copy = Path(first["reform"]["path"])
            copy.write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "Retained runtime archive differs"):
                platform.retain_runtime_artifacts(native, root / "store")
            self.assertEqual(copy.read_bytes(), b"corrupt")

    def test_symlink_is_not_used_as_actual_runtime_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, native = self.fixture(root)
            alias = root / "alias.jar"
            alias.symlink_to(source)
            native["runtimeArtifacts"]["reform"]["path"] = str(alias)
            with self.assertRaisesRegex(ValueError, "differs from its native observation"):
                platform.retain_runtime_artifacts(native, root / "store")


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
        self.native = {"status": "PASS", "shutdownClosed": True, "librarySha256": "a" * 64,
                       "java": "25.0.4+101", "loadedLibrary": "file:/actual/library.jar",
                       "minecraft": "26.3", "loader": "0.20.1"}
        self.native["runtimeArtifacts"] = {"reform": {
            "path": "/actual/library.jar", "bytes": 100, "sha256": "a" * 64,
            "witness": "design/inherent/reform/minecraft/configuration/ServerSettings.class",
            "witnessSha256": "b" * 64, "resource": "jar:file:/actual/library.jar!/witness.class",
            "resourceMatchesArchive": True}}
        self.library = SimpleNamespace(sha256="a" * 64)
        self.target = {"java": 25, "minecraftVersion": "26.3"}

    def accepted(self):
        return successful_boot(self.process, self.native, self.library, self.target, "0.20.1")

    def test_complete_native_outcome(self):
        self.assertTrue(self.accepted())

    def test_echoed_original_without_runtime_archive_is_not_success(self):
        self.native.pop("runtimeArtifacts")
        self.assertFalse(self.accepted())

    def test_unremapped_runtime_archive_must_match_original(self):
        self.native["runtimeArtifacts"]["reform"]["sha256"] = "c" * 64
        self.assertFalse(self.accepted())

    def test_remapped_runtime_records_its_distinct_actual_hash(self):
        self.target["remapMinecraft"] = True
        self.native["runtimeArtifacts"]["reform"]["sha256"] = "c" * 64
        self.assertTrue(self.accepted())

    def test_runtime_archive_requires_matching_class_resource(self):
        self.native["runtimeArtifacts"]["reform"]["resourceMatchesArchive"] = False
        self.assertFalse(self.accepted())

    def test_installed_viewer_cannot_omit_runtime_archive(self):
        self.native["recipeViewers"] = {"emi": "1.1.24"}
        self.assertFalse(self.accepted())

    def test_unremapped_viewer_archive_must_match_selected_original(self):
        self.native["recipeViewers"] = {"emi": "1.1.24"}
        fact = dict(self.native["runtimeArtifacts"]["reform"])
        fact.update(path="/actual/emi.jar", witness="dev/emi/emi/api/EmiApi.class", sha256="c" * 64)
        self.native["runtimeArtifacts"]["emi"] = fact
        original = {"provider": "emi", "sha256": "d" * 64}
        self.assertFalse(platform.runtime_artifacts_match(self.native, self.library, self.target, original))
        original["sha256"] = "c" * 64
        self.assertTrue(platform.runtime_artifacts_match(self.native, self.library, self.target, original))

    def test_both_runtime_viewers_require_both_selected_identities(self):
        originals = []
        for provider, witness, digest in [("jei", "mezz/jei/api/IModPlugin.class", "c" * 64),
                                           ("emi", "dev/emi/emi/api/EmiApi.class", "d" * 64)]:
            fact = dict(self.native["runtimeArtifacts"]["reform"])
            fact.update(path="/actual/" + provider + ".jar", witness=witness, sha256=digest)
            self.native["runtimeArtifacts"][provider] = fact
            originals.append({"provider": provider, "sha256": digest})
        self.native["recipeViewers"] = {"jei": "19", "emi": "1"}
        inputs = {"provider": "both", "inputs": originals}
        self.assertTrue(platform.runtime_artifacts_match(self.native, self.library, self.target, inputs))
        originals[1]["sha256"] = "e" * 64
        self.assertFalse(platform.runtime_artifacts_match(self.native, self.library, self.target, inputs))
        self.target["remapMinecraft"] = True
        self.assertTrue(platform.runtime_artifacts_match(self.native, self.library, self.target, inputs))
        inputs["inputs"] = originals[:1]
        self.assertFalse(platform.runtime_artifacts_match(self.native, self.library, self.target, inputs))

    def test_runtime_archive_rejects_invalid_or_unbounded_facts(self):
        fact = self.native["runtimeArtifacts"]["reform"]
        for key, value in [("path", "relative.jar"), ("bytes", 0),
                           ("bytes", 64 * 1024 * 1024 + 1), ("bytes", True),
                           ("sha256", "not-a-hash"), ("witnessSha256", ""),
                           ("witness", "wrong.class"), ("resource", "")]:
            with self.subTest(key=key, value=value):
                original = fact[key]
                fact[key] = value
                self.assertFalse(self.accepted())
                fact[key] = original

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
        for name in ["world", "recipe", "authoring", "initializationFailure", "nativeCommands", "nativeSearch", "connector", "clientCommands", "workspace", "workflow", "operations", "customControls", "choiceCaptions"]:
            with self.subTest(scenario=name):
                def accepted():
                    return successful_boot(self.process, self.native, self.library,
                                           self.target, "0.20.1", required_scenarios=(name,))
                self.assertFalse(accepted())
                for child in [None, "PASS", ["PASS"], {}, {"status": "FAIL"}]:
                    self.native[name] = child
                    self.assertFalse(accepted())
                self.native[name] = {"status": "PASS"}
                if name == "workspace":
                    self.assertFalse(accepted())
                    self.native["nativeItemShutdown"] = NativeItemShutdownTests.complete()
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

    def test_component_status_without_exact_mount_cleanup_and_source_facts_is_refused(self):
        def accepted():
            return successful_boot(self.process, self.native, self.library, self.target,
                                   "0.20.1", required_scenarios=("semanticComponents",))
        complete = {"status": "PASS", "passed": True, "sourceUnchanged": True,
                    "retiredUiDataZero": True, "keyedRowsVerified": True, "boundFieldsVerified": True, "boundFieldOperations": ["first", "second"], "textInputVerified": True, "textInputOperations": ["raw", "repair"], "keyedRowFactories": 4, "keyedRowCloses": 4, "frameworkFactories": 1, "frameworkCloses": 1,
                    "externalFactories": 1, "externalCloses": 1, "externalDraws": 8}
        self.native["semanticComponents"] = dict(complete)
        self.assertTrue(accepted())
        for field in complete:
            for wrong in [None, "true", False, {}, []]:
                with self.subTest(field=field, wrong=wrong):
                    self.native["semanticComponents"][field] = wrong
                    self.assertFalse(accepted())
            self.native["semanticComponents"][field] = complete[field]

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


class NativeItemShutdownTests(unittest.TestCase):
    @staticmethod
    def complete():
        return {"status": "PASS", "epilogueConfirmed": True, "workflowOpenAtStop": True,
                "outstandingUsesAtStop": 1, "nativeBytesAtStop": 1024, "outputBytesAtStop": 64,
                "rendererReturn": {"releaseCompleted": True, "released": True,
                                   "workflowClosed": True, "sourceUnchanged": True,
                                   "outstandingUses": 0, "nativeBytes": 0,
                                   "nativeResources": 0, "outputBytes": 0}}

    def test_pre_stop_success_cannot_qualify_shutdown(self):
        complete = self.complete()
        self.assertTrue(platform.complete_native_item_shutdown(complete))
        for key in complete:
            value = self.complete()
            del value[key]
            self.assertFalse(platform.complete_native_item_shutdown(value), key)
        for key in ("workflowOpenAtStop", "epilogueConfirmed"):
            value = self.complete()
            value[key] = False
            self.assertFalse(platform.complete_native_item_shutdown(value), key)
        value = self.complete()
        value["status"] = "RUNNING"
        self.assertFalse(platform.complete_native_item_shutdown(value))

    def test_renderer_return_must_prove_actual_release(self):
        for key in self.complete()["rendererReturn"]:
            for wrong in (None, "0", 1 if key in ("outstandingUses", "nativeBytes", "nativeResources", "outputBytes") else False):
                value = self.complete()
                value["rendererReturn"][key] = wrong
                self.assertFalse(platform.complete_native_item_shutdown(value), (key, wrong))
        for key in ("outstandingUsesAtStop", "nativeBytesAtStop", "outputBytesAtStop"):
            for wrong in (0, None, True):
                value = self.complete()
                value[key] = wrong
                self.assertFalse(platform.complete_native_item_shutdown(value), (key, wrong))
