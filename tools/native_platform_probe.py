#!/usr/bin/env python3
"""Run one checked target's independent packaged consumer on disposable dedicated servers."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import uuid

if __package__:
    from .native_config_probe import execute
    from .native_form_probe import create_fixture, cleanup_fixture, console_evidence, crash_evidence, configure_dedicated_fixture
    from .native_library import select_library
    from .targets import load_targets
else:
    from native_config_probe import execute
    from native_form_probe import create_fixture, cleanup_fixture, console_evidence, crash_evidence, configure_dedicated_fixture
    from native_library import select_library
    from targets import load_targets

ROOT = Path(__file__).resolve().parents[1]


WORKSPACE_CAPTURES = {
    'platform-field-order.png',
    'platform-form-root-actions-narrow.png',
    'platform-form-root-actions-wide.png',
    'platform-form-root-batch-changes.png',
    'platform-form-root-batch-narrow.png',
    'platform-form-root-batch-wide.png',
    'platform-form-root-close-narrow.png',
    'platform-form-root-close-wide.png',
    'platform-form-root-conflict.png',
    'platform-form-root-import-narrow.png',
    'platform-form-root-import-wide.png',
    'platform-form-root-inspection-narrow.png',
    'platform-form-root-inspection-wide.png',
    'platform-form-root-review-choices-narrow.png',
    'platform-form-root-review-choices-wide.png',
    'platform-form-workspace-actions-lifetime.png',
    'platform-form-workspace-chooser-adjacent.png',
    'platform-form-workspace-chooser-focused.png',
    'platform-form-workspace-chooser-return.png',
    'platform-form-workspace-defaults.png',
    'platform-form-workspace-information-inline.png',
    'platform-form-workspace-information-wide.png',
    'platform-form-workspace-long-navigation.png',
    'platform-form-workspace-narrow.png',
    'platform-form-workspace-reference-invalid.png',
    'platform-form-workspace-reference-manual-id.png',
    'platform-form-workspace-reference-titles.png',
    'platform-form-workspace-wide.png',
    'platform-loaded-sprite-block.png',
    'platform-loaded-sprite-before-prepare.png',
    'platform-loaded-sprite-before-draw.png',
    'platform-loaded-sprite-gui.png',
    'platform-loaded-texture-small.png',
    'platform-loaded-texture.png',
    'platform-operation-small.png',
    'platform-operation-wide.png',
    'platform-shell-canvas-failure.png',
    'platform-shell-document.png',
    'platform-shell-home.png',
    'platform-shell-maximum-hierarchy.png',
    'platform-task-catalogue.png',
    'platform-task-preview-before-prepare.png',
    'platform-task-preview-pre-draw.png',
    'platform-task-preview-small.png',
    'platform-task-preview.png',
    'platform-theme-input-alpha-0.png',
    'platform-theme-input-alpha-128.png',
    'platform-theme-input-alpha-255.png',
    'platform-theme-input-alpha-3.png',
    'platform-theme-input-native-alpha-3.png',
}


AUTHORING_CAPTURES = {
    'platform-form-catalog-localized.png',
    'platform-form-catalog-narrow.png',
    'platform-form-catalog-wide.png',
    'platform-form-collection-choices.png',
    'platform-form-collection-unbound.png',
    'platform-form-collection-previews.png',
    'platform-form-collection-preview-empty.png',
    'platform-form-collection-preview-one.png',
    'platform-form-conflict-invalid.png',
    'platform-form-conflict-narrow.png',
    'platform-form-conflict-review.png',
    'platform-form-dependencies-final.png',
    'platform-form-dependencies-inactive.png',
    'platform-form-graphic-texture.png',
    'platform-form-graphic-layers.png',
    'platform-form-graphic-orbit.png',
    'platform-form-import-repairable.png',
    'platform-form-import-review.png',
    'platform-form-numeric-initial.png',
    'platform-form-numeric-invalid.png',
    'platform-form-numeric-narrow.png',
    'platform-form-numeric-range.png',
    'platform-form-numeric-open-captions.png',
    'platform-form-numeric-caption-review.png',
    'platform-form-reference-block.png',
    'platform-form-reference-missing.png',
    'platform-form-review-diagnostics.png',
    'platform-form-review-export.png',
    'platform-form-review-narrow.png',
    'platform-form-structured-invalid.png',
    'platform-form-structured-ready.png',
    'platform-form-workflow-narrow.png',
    'platform-form-workflow-wide.png',
}


def retain_native_report(source, reports, reopen, role="native"):
    """Preserve the actual payload before capture admission or later interpretation can fail."""
    if not source.exists():
        return None
    if source.is_symlink() or not source.is_file() or source.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Native report is not a bounded regular file")
    if role not in {"native", "scene-failure"}:
        raise ValueError("Unrecognized native report role")
    saved = reports / (("reopen-" if reopen else "first-") + role + ".json")
    shutil.copy2(source, saved)
    return {"file": saved.name, "sha256": hashlib.sha256(saved.read_bytes()).hexdigest()}


def retain_captures(fixture, reports, names, allowed, reopen):
    if not isinstance(names, list) or len(names) > 64:
        raise ValueError("Native capture list exceeds its bound")
    captures = []
    for name in names:
        if not isinstance(name, str) or name not in allowed:
            raise ValueError("Unrecognized native capture")
        source = fixture / name
        if source.is_symlink() or not source.is_file() or source.stat().st_size > 8 * 1024 * 1024:
            raise ValueError("Native capture is absent or exceeds its bound")
        saved = reports / (("reopen-" if reopen else "first-") + name)
        shutil.copy2(source, saved)
        captures.append({"file": saved.name, "sha256": hashlib.sha256(saved.read_bytes()).hexdigest()})
    return captures


def complete_held_stops(world):
    stops = world.get("heldStops")
    return (isinstance(stops, list) and len(stops) == 2
            and all(isinstance(stop, dict) and stop.get("status") == "PASS"
                    and stop.get("phase") == phase and stop.get("writes") == writes
                    and stop.get("savedDistance") == distance
                    and stop.get("closedBeforeRelease") is True
                    and stop.get("queuedRepliesOnly") is True
                    and stop.get("outstandingCalls") == 0
                    and stop.get("editorReportValid") is False
                    and stop.get("receiptPresent") is bool(writes)
                    and (not writes or (stop.get("persistence") == "SAVED"
                                        and stop.get("application") == "UNAVAILABLE"
                                        and stop.get("privateObservationPresent") is False))
                    for stop, (phase, writes, distance) in
                    zip(stops, [("PREPARE", 0, 7), ("RETURN", 1, 9)])))


def successful_boot(result, native, library, target, loader_version, client=False, required_scenarios=()):
    return (result["exitCode"] == 0 and result["termination"] is None
            and native.get("status") == "PASS" and native.get("shutdownClosed") is True
            and all(isinstance(native.get(name), dict) and native[name].get("status") == "PASS"
                    for name in required_scenarios)
            and ("world" not in required_scenarios or complete_held_stops(native["world"]))
            and ("recipe" not in required_scenarios
                 or (isinstance(native["recipe"].get("advanced"), dict)
                     and native["recipe"]["advanced"].get("status") == "PASS"
                     and native["recipe"]["advanced"].get("shutdownClosed") is True))
            and ("connector" not in required_scenarios
                 or (native["connector"].get("connectorVersion") == target.get("connectorVersion")
                     and isinstance(target.get("connectorVersion"), str)
                     and native["connector"].get("fabricApiVersion") == target.get("forgifiedFabricApiVersion")
                     and isinstance(target.get("forgifiedFabricApiVersion"), str)))
            and ("clientCommands" not in required_scenarios
                 or (isinstance(native["clientCommands"].get("variants"), dict)
                     and native["clientCommands"]["variants"].get("status") == "PASS"
                     and isinstance(native["clientCommands"].get("held"), dict)
                     and native["clientCommands"]["held"].get("status") == "PASS"
                     and native["clientCommands"]["held"].get("contextClosed") is True
                     and native["clientCommands"].get("shutdownOwnerClosed") is True))
            and ("workflow" not in required_scenarios
                 or all(native["workflow"].get(key) is True
                        for key in ("workflowClosed", "independentClosed", "shutdownClosed")))
            and ("chooser" not in required_scenarios
                 or native["chooser"].get("nativeFileDialogExercised") is True)
            and native.get("librarySha256") == library.sha256
            and native.get("java", "").split(".", 1)[0] == str(target["java"])
            and native.get("minecraft") == target["minecraftVersion"]
            and native.get("loader") == loader_version
            and ".jar" in native.get("loadedLibrary", "")
            and (not client or (native.get("shutdownEditorReady") is True
                                and native.get("shutdownEditorClosed") is True
                                and type(native.get("visibleLabelPixels")) is int
                                and native["visibleLabelPixels"] >= 20
                                and isinstance(native.get("imageSha256"), str)
                                and len(native["imageSha256"]) == 64)))


def run(project, java_home, timeout, version=None, tested_loader=False, client=False, synced_override=False, world_client=False, mod_menu=False, initialization_failure=False, recipe_client=False, authoring_client=False, chooser_client=False, connector=False, command_client=False, workspace_client=False, workflow_client=False):
    target = next((t for t in load_targets(ROOT) if t["project"] == project), None)
    if target is None:
        raise ValueError("Select an admitted native target")
    if connector and (client or synced_override or "connectorVersion" not in target):
        raise ValueError("Connector requires a declared dedicated NeoForge target")
    if workflow_client and (not client or any((world_client, recipe_client, authoring_client, chooser_client, command_client, workspace_client))):
        raise ValueError("Workflow requires a separate native client scenario")
    if workspace_client and (not client or any((world_client, recipe_client, authoring_client, chooser_client, command_client))):
        raise ValueError("Workspace requires a separate native client scenario")
    if command_client and (not client or any((world_client, recipe_client, authoring_client, chooser_client))):
        raise ValueError("Client commands require a separate native client scenario")
    if tested_loader and "testedLoaderVersion" not in target:
        raise ValueError("This target has no additional loader pin")
    if synced_override and (client or project != "neoforge-26_3"):
        raise ValueError("The native SYNCED world-override fixture requires the NeoForge 26.3 dedicated target")
    if world_client and not client:
        raise ValueError("The integrated world scenario requires the native client")
    if initialization_failure and not client:
        raise ValueError("The controlled initialization failure requires the native client")
    if recipe_client and (not client or world_client):
        raise ValueError("Unsaved creation requires a native client separate from the joined-world scenario")
    if authoring_client and (not client or world_client or recipe_client):
        raise ValueError("Authoring requires a separate native client scenario")
    if chooser_client and (not client or world_client or recipe_client or authoring_client):
        raise ValueError("The OS chooser requires a separate native client and external desktop driver")
    if mod_menu and (not client or target["loader"] != "fabric"):
        raise ValueError("Mod Menu qualification requires a Fabric native client")
    library = select_library(ROOT, project, version)
    example = ROOT / "examples/platform"
    fixture = example / "runs" / (project + ("-client" if client else ""))
    if fixture.exists() or fixture.is_symlink():
        raise FileExistsError("Platform fixture exists; inspect it before creating another")
    token = str(uuid.uuid4())
    create_fixture(fixture, token, client=client)
    role = "tested" if tested_loader else "minimum"
    loader_version = target["testedLoaderVersion" if tested_loader else "loaderVersion"]
    if client:
        mode = "client-workflow" if workflow_client else "client-workspace" if workspace_client else "client-commands" if command_client else "client-chooser" if chooser_client else "client-authoring" if authoring_client else "client-recipe" if recipe_client else "client-world" if world_client else "client"
        if mod_menu:
            mode += "-modmenu"
        if initialization_failure:
            mode += "-init-failure"
    else:
        mode = "dedicated-connector" if connector else "dedicated-synced-override" if synced_override else "dedicated"
    reports = ROOT / "build/reports/native-platform" / project / role / mode
    reports.mkdir(parents=True, exist_ok=True)
    required_scenarios = tuple(name for name, enabled in
                               [("world", world_client), ("recipe", recipe_client), ("authoring", authoring_client),
                                ("initializationFailure", initialization_failure), ("chooser", chooser_client),
                                ("nativeCommands", not client), ("nativeSearch", not client),
                                ("clientCommands", command_client), ("workspace", workspace_client), ("workflow", workflow_client)] if enabled)
    if connector:
        required_scenarios += ("connector",)
    if synced_override:
        native = "platform-native-server.toml"
        global_file = fixture / "config" / native
        world_file = fixture / "world/syncedconfig" / native
        global_file.parent.mkdir(parents=True, exist_ok=True)
        world_file.parent.mkdir(parents=True, exist_ok=True)
        global_file.write_text('level = 21\nlabel = "installation"\n')
        world_file.write_text('#Native server source comment\nlevel = 4\nlabel = "native"\n')
    if client:
        configure_dedicated_fixture(fixture)
    command = [str(ROOT / "gradlew"), "--no-daemon"]
    if java_home:
        command.append("-Dorg.gradle.java.home=" + str(java_home))
    command.extend(["-Ptarget=" + project, "-PreformVersion=" + library.version,
                    "-PreformSha256=" + library.sha256, "-PtestedLoader=" + str(tested_loader).lower(),
                    "-PwithModMenu=" + str(mod_menu).lower(),
                    "-PwithConnector=" + str(connector).lower(),
                    "-PclientMode=" + str(client).lower(), "runClient" if client else "runServer"])
    report = {"status": "FAIL", "target": target, "version": library.version, "sha256": library.sha256,
              "launch": "Independent addon source container; verified Maven library; no library source roots",
              "loaderRole": role, "loaderVersion": loader_version, "mode": "client" if client else "dedicated", "syncedWorldOverride": synced_override, "worldClient": world_client, "recipeClient": recipe_client, "authoringClient": authoring_client, "chooserClient": chooser_client, "commandClient": command_client, "workspaceClient": workspace_client, "workflowClient": workflow_client, "modMenu": mod_menu, "connector": connector, "initializationFailure": initialization_failure, "boots": [],
              "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sorted(example.rglob("*")) if p.is_file()
                          and not any(part in {"build", "runs", ".gradle"} for part in p.relative_to(example).parts)}}
    try:
        for reopen in (False, True):
            output = fixture / "platform-report.json"
            output.unlink(missing_ok=True)
            if authoring_client and not reopen:
                source = fixture / "config/reform_platform_example/numeric.json"
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("{invalid numeric fixture")
            if recipe_client:
                source = fixture / "config/reform_platform_example/future_invalid.json"
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("{invalid future defaults")
            (fixture / "platform-input.json").write_text(json.dumps({"reopen": reopen, "librarySha256": library.sha256, "syncedWorldOverride": synced_override, "worldClient": world_client, "recipeClient": recipe_client, "authoringClient": authoring_client, "chooserClient": chooser_client, "commandClient": command_client, "workspaceClient": workspace_client, "workflowClient": workflow_client, "modMenu": mod_menu, "connector": connector, "initializationFailure": initialization_failure}))
            log = reports / ("reopen.log" if reopen else "first.log")
            result = execute(command, example, log, timeout,
                             chooser_report=output if chooser_client else None)
            raw_report = retain_native_report(output, reports, reopen)
            if raw_report is not None:
                report.setdefault("rawNativeReports", []).append(raw_report)
            native = json.loads(output.read_text()) if output.is_file() else {}
            failed_scene = fixture / "platform-scene-failure.json"
            if failed_scene.is_file() and not failed_scene.is_symlink() and failed_scene.stat().st_size <= 131072:
                report.setdefault("rawSceneFailures", []).append(
                    retain_native_report(failed_scene, reports, reopen, "scene-failure"))
                native["sceneFailure"] = json.loads(failed_scene.read_text())
            image = fixture / "platform-form.png"
            if client and image.is_file() and not image.is_symlink():
                copied = reports / ("reopen.png" if reopen else "first.png")
                shutil.copy2(image, copied)
                native["imageSha256"] = hashlib.sha256(copied.read_bytes()).hexdigest()
            if authoring_client and isinstance(native.get("authoring"), dict):
                native["authoring"]["captures"] = retain_captures(
                    fixture, reports, native["authoring"].get("captures", []), AUTHORING_CAPTURES, reopen)
            if workspace_client and isinstance(native.get("workspace"), dict):
                native["workspace"]["captures"] = retain_captures(
                    fixture, reports, native["workspace"].get("captures", []),
                    WORKSPACE_CAPTURES, reopen)
            if workspace_client and isinstance(native.get("sceneFailure"), dict):
                native["sceneFailure"]["captures"] = retain_captures(
                    fixture, reports, native["sceneFailure"].get("captures", []),
                    WORKSPACE_CAPTURES, reopen)
            if workflow_client and isinstance(native.get("workflow"), dict):
                native["workflow"]["captures"] = retain_captures(
                    fixture, reports, native["workflow"].get("captures", []),
                    {"platform-form-workflow-wide.png", "platform-form-workflow-narrow.png",
                     "platform-form-graphic-texture.png", "platform-form-graphic-layers.png", "platform-form-graphic-orbit.png"}, reopen)
            if workflow_client and isinstance(native.get("sceneFailure"), dict):
                native["sceneFailure"]["captures"] = retain_captures(
                    fixture, reports, native["sceneFailure"].get("captures", []),
                    {"platform-form-workflow-wide.png", "platform-form-workflow-narrow.png",
                     "platform-form-graphic-texture.png", "platform-form-graphic-layers.png", "platform-form-graphic-orbit.png"}, reopen)
            if recipe_client and isinstance(native.get("recipe"), dict):
                workflow = native["recipe"].get("advanced")
                if isinstance(workflow, dict):
                    workflow["captures"] = retain_captures(
                        fixture, reports, workflow.get("captures", []),
                        {"platform-form-recipe-preview.png", "platform-form-recipe.png",
                         "platform-form-defaults-source.png", "platform-form-defaults-import.png"}, reopen)
            log_text = log.read_text(errors="replace")
            original_cause_present = not initialization_failure or "IllegalStateException: reform-native-init-fault" in log_text
            expected_refusal_not_logged = not initialization_failure or "reform-native-expected-refusal" not in log_text
            report["boots"].append({"run": result, "native": native, "expectedOriginalCausePresent": original_cause_present,
                                    "expectedRefusalNotLogged": expected_refusal_not_logged,
                                    "consoleEvidence": console_evidence(log), "crashReports": crash_evidence(fixture)})
            if not original_cause_present or not expected_refusal_not_logged or not successful_boot(result, native, library, target, loader_version, client, required_scenarios):
                break
        else:
            report["status"] = "PASS"
    finally:
        cleanup_fixture(fixture, token)
        report["fixtureRemoved"] = not fixture.exists()
        destination = reports / "result.json"
        destination.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "target": project, "report": str(destination),
                      "fixtureRemoved": report["fixtureRemoved"]}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=[t["project"] for t in load_targets(ROOT)])
    parser.add_argument("--java-home", type=Path, help="Build JDK; game uses its declared target toolchain")
    parser.add_argument("--timeout", type=int, default=240, help="Each bounded build/server boot, 1..600 seconds")
    parser.add_argument("--library-version", help="Frozen staged version; otherwise use the built POM")
    parser.add_argument("--tested-loader", action="store_true", help="Use the matrix's additional qualified-input pin")
    parser.add_argument("--client", action="store_true", help="Native GUI checks; requires current coordinated desktop access")
    parser.add_argument("--synced-world-override", action="store_true", help="NeoForge 26.3 dedicated: retain distinct installation and actual world SYNCED source")
    parser.add_argument("--world-client", action="store_true", help="With --client: actual integrated world, typed chat, native links and repeated sessions")
    parser.add_argument("--mod-menu", action="store_true", help="Fabric client: qualify the real optional Mod Menu entry")
    parser.add_argument("--connector", action="store_true", help="Declared NeoForge dedicated target: Connector/FFAPI coexistence")
    parser.add_argument("--init-failure", action="store_true", help="Native client: controlled constructor failure must retain original cause without defaults")
    parser.add_argument("--recipe-client", action="store_true", help="With --client: actual unsaved creation, selected-view edits and native RegistryOps")
    parser.add_argument("--commands-client", action="store_true", help="With --client: actual typed client commands, suggestions, imports and conflicts")
    parser.add_argument("--authoring-client", action="store_true", help="With --client: native editors, source conflicts, catalog reload and save/reopen")
    parser.add_argument("--workspace-client", action="store_true", help="With --client: mixed owners, persistent navigation, aliases and retained auxiliary lifetimes")
    parser.add_argument("--workflow-client", action="store_true", help="With --client: retained multi-owner saves, revision conflicts and close/discard decisions")
    parser.add_argument("--chooser-client", action="store_true", help="With --client: actual OS file dialogs; requires a separate opt-in desktop driver")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 600:
        parser.error("Expected a bounded 1..600 second timeout")
    raise SystemExit(run(args.target, args.java_home, args.timeout, args.library_version, args.tested_loader, args.client, args.synced_world_override, args.world_client, args.mod_menu, args.init_failure, args.recipe_client, args.authoring_client, args.chooser_client, args.connector, args.commands_client, args.workspace_client, args.workflow_client))
