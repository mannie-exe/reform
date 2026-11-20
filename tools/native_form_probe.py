#!/usr/bin/env python3
"""Run the independent public-API addon in a task-owned, disposable Minecraft client."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import uuid

from native_config_probe import execute

ROOT = Path(__file__).resolve().parents[1]
MARKER = ".reform-form-fixture.json"


def create_fixture(fixture, token, client=True):
    fixture.mkdir(parents=True, exist_ok=False)
    (fixture / MARKER).write_text(json.dumps({"token": token, "purpose": "independent-native-form"}))
    if client:
        (fixture / "options.txt").write_text("onboardAccessibility:false\nguiScale:2\nfullscreen:false\nmaxFps:60\n")


def cleanup_fixture(fixture, token):
    expected = {"token": token, "purpose": "independent-native-form"}
    if fixture.is_symlink() or not fixture.is_dir():
        raise ValueError("Expected the owned native form fixture directory")
    if json.loads((fixture / MARKER).read_text()) != expected:
        raise ValueError("Fixture ownership changed; data retained")
    shutil.rmtree(fixture)


def crash_evidence(fixture):
    """Keep the cause before disposing a failed owned game, without following external links."""
    directory = fixture / "crash-reports"
    if directory.is_symlink() or not directory.is_dir():
        return []
    reports = []
    for source in sorted(directory.glob("crash-*.txt"))[:4]:
        if source.is_symlink() or not source.is_file():
            continue
        with source.open("rb") as stream:
            data = stream.read(131073)
        complete = len(data) <= 131072
        data = data[:131072]
        reports.append({"name": source.name, "complete": complete,
                        "sha256": hashlib.sha256(data).hexdigest(),
                        "text": data.decode("utf-8", errors="replace")})
    return reports


def console_evidence(log):
    """Native assertion failures can exit normally; retain their bounded bootstrap diagnostics."""
    if log.is_symlink() or not log.is_file():
        return None
    size = log.stat().st_size
    with log.open("rb") as stream:
        stream.seek(max(0, size - 32768))
        data = stream.read(32768)
    return {"complete": size <= 32768, "sha256": hashlib.sha256(data).hexdigest(),
            "text": data.decode("utf-8", errors="replace")}


def successful_run(result, mode, digest):
    native = result.get("native", {})
    return (result["exitCode"] == 0 and result["termination"] is None
            and native.get("status") == "PASS" and native.get("nativeShutdownClosed") is True
            and native.get("mode") == mode and native.get("librarySha256") == digest
            and (not mode.startswith("structured") or native.get("structured", {}).get("status") == "PASS")
            and (not mode.startswith("references") or native.get("references", {}).get("status") == "PASS")
            and (not mode.startswith("numeric") or native.get("numeric", {}).get("status") == "PASS")
            and (not mode.startswith("dependencies") or native.get("dependencies", {}).get("status") == "PASS")
            and (not mode.startswith("collections") or native.get("collections", {}).get("status") == "PASS")
            and (not mode.startswith("imports") or native.get("imports", {}).get("status") == "PASS")
            and (not mode.startswith("command-imports") or (native.get("commandImports", {}).get("status") == "PASS"
                                                         and native.get("commandImports", {}).get("contextClosed") is True))
            and (mode != "chooser" or (native.get("chooser", {}).get("status") == "PASS"
                                       and native.get("chooser", {}).get("nativeFileDialogExercised") is True))
            and (not mode.startswith("conflicts") or native.get("conflicts", {}).get("status") == "PASS")
            and (not mode.startswith("command-conflicts") or (native.get("commandConflicts", {}).get("status") == "PASS"
                                                            and native.get("commandConflicts", {}).get("contextClosed") is True))
            and (not mode.startswith("keys-owner") or (native.get("keyOwner", {}).get("status") == "PASS"
                                                      and native.get("keyOwner", {}).get("contextClosed") is True))
            and (not mode.startswith("keys-controls") or (native.get("keyControls", {}).get("status") == "PASS"
                                                         and native.get("keyControls", {}).get("contextClosed") is True))
            and (not mode.startswith("keys-factory") or (native.get("keyFactory", {}).get("status") == "PASS"
                                                        and native.get("keyFactory", {}).get("factoryClosed") is True
                                                        and native.get("keyFactory", {}).get("unavailableClosed") is True))
            and (not mode.startswith("catalog") or (native.get("catalog", {}).get("status") == "PASS"
                                                   and native.get("catalog", {}).get("editorClosed") is True
                                                   and native.get("catalog", {}).get("factoryClosed") is True))
            and (not mode.startswith("review") or (native.get("review", {}).get("status") == "PASS"
                                                  and native.get("review", {}).get("editorClosed") is True
                                                  and native.get("review", {}).get("factoryClosed") is True))
            and (not mode.startswith("close") or (native.get("close", {}).get("status") == "PASS"
                                                 and native.get("close", {}).get("contextClosed") is True
                                                 and native.get("close", {}).get("workerTerminated") is True))
            and (not mode.startswith("outcomes") or (native.get("outcome", {}).get("status") == "PASS"
                                                    and native.get("outcome", {}).get("contextClosed") is True
                                                    and native.get("outcome", {}).get("workerTerminated") is True))
            and (not mode.startswith("files") or (native.get("files", {}).get("status") == "PASS"
                                                and native.get("files", {}).get("factoriesClosed") is True))
            and (not mode.startswith("startup") or (native.get("startup", {}).get("status") == "PASS"
                                                  and native.get("startup", {}).get("factoriesClosed") is True
                                                  and native.get("startup", {}).get("heldConstructionClosed") is True))
            and (not mode.startswith("registered") or (native.get("nativeRegistered", {}).get("status") == "PASS"
                                                     and native.get("nativeRegistered", {}).get("clientClosed") is True
                                                     and native.get("nativeRegistered", {}).get("defaultsClosed") is True))
            and (not mode.startswith("workflow") or (native.get("workflow", {}).get("status") == "PASS"
                and native.get("workflow", {}).get("workflowClosed") is True
                and native.get("workflow", {}).get("independentClosed") is True
                and native.get("workflow", {}).get("shutdownClosed") is True))
            and (not mode.startswith("batch") or (native.get("batch", {}).get("status") == "PASS"
                and native.get("batch", {}).get("workflowClosed") is True))
            and (not mode.startswith("defaults") or (native.get("defaults", {}).get("status") == "PASS"
                and all(native.get("defaults", {}).get(key) is True for key in ("editorClosed", "factoryClosed", "eagerClosed"))))
            and (not mode.startswith("integrated") or (native.get("integrated", {}).get("status") == "PASS"
                and native.get("integrated", {}).get("contextsClosed") is True))
            and (mode != "recipe" or (native.get("recipe", {}).get("managedShutdownClosed") is True
                                      and native.get("recipe", {}).get("preview", {}).get("status") == "PASS")))


def successful_report(report, modes, digest):
    captures = {f"form-{family}-{size}{frame}.png"
                for family, sizes in {"catalog": ("wide", "narrow", "localized"),
                                      "review": ("diagnostics", "narrow"),
                                      "close": ("wide", "narrow")}.items() if family in modes
                for size in sizes for frame in ("", "-entry")}
    if "outcomes" in modes:
        captures.update({f"form-{kind}{frame}.png" for kind in ("submit-wide", "outcome-wide", "outcome-narrow")
                         for frame in ("", "-entry")})
    if "files" in modes:
        captures.update({"form-files-properties.png", "form-files-properties-entry.png"})
    if "startup" in modes:
        captures.update({"form-startup-review.png", "form-startup-review-entry.png"})
    if "workflow" in modes:
        captures.update({f"form-workflow-{size}{frame}.png" for size in ("wide", "narrow") for frame in ("", "-entry")})
    if "batch" in modes:
        captures.update({f"form-batch-{name}{frame}.png" for name in ("invalid", "wide", "narrow", "result") for frame in ("", "-entry")})
    if "defaults" in modes:
        captures.update({f"form-defaults-{name}{frame}.png" for name in ("source", "import") for frame in ("", "-entry")})
    if "integrated" in modes:
        captures.update(f"form-integrated-{name}{frame}.png"
                        for name in ("file", "toml") for frame in ("", "-entry"))
    if "registered" in modes:
        captures.update({f"form-native-{name}{frame}.png" for name in ("review", "defaults") for frame in ("", "-entry")})
    if "recipe" in modes:
        captures.add("form-recipe-preview.png")
    if "review" in modes:
        captures.add("form-review-export.png")
    return (len(report["runs"]) == len(modes)
            and all(successful_run(result, mode, digest)
                    for result, mode in zip(report["runs"], modes))
            and captures.issubset(report["screenshots"]))


def run(java_home, timeout, lifecycle_only=False, recipe_only=False, structured_only=False, references_only=False, numeric_only=False, dependencies_only=False, collections_only=False, imports_only=False, command_imports_only=False, chooser_only=False, conflicts_only=False, command_conflicts_only=False, keys_owner_only=False, keys_controls_only=False, keys_factory_only=False, catalog_only=False, review_only=False, catalog_review_only=False, close_only=False, submission_only=False, files_only=False, startup_only=False, registered_only=False, workflow_only=False, batch_only=False, defaults_only=False, integrated_only=False):
    example = ROOT / "examples/native"
    fixture = example / "runs/client"
    if fixture.exists() or fixture.is_symlink():
        raise FileExistsError("Client fixture exists; inspect it before starting a new run")
    version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text().splitlines()
                   if line.startswith("mod_version="))
    jar = ROOT / f"neoforge/build/libs/reform-{version}-mc1.21.1-neoforge.jar"
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    command = [str(ROOT / "gradlew"), "--no-daemon"]
    if java_home:
        command.append("-Dorg.gradle.java.home=" + str(java_home))
    command.extend([f"-PreformVersion={version}", f"-PreformSha256={digest}", "-PnativeProbe=true"])
    report_dir = ROOT / "build/reports/native-form"
    report_dir.mkdir(parents=True, exist_ok=True)
    token = str(uuid.uuid4())
    create_fixture(fixture, token)
    report = {"status": "FAIL", "librarySha256": digest, "runs": [],
              "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "launch": "ModDev native example/probe source containers; dependency is a packaged Maven JAR",
              "inputs": {}, "screenshots": {}}
    for source in sorted([*(p for p in example.glob("src/**/*") if p.is_file()),
                          example / "build.gradle", example / "settings.gradle",
                          Path(__file__).resolve(), ROOT / "tools/native_config_probe.py"]):
        report["inputs"][str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
    log = ROOT / "build/native-form-client.log"
    if integrated_only:
        modes = ("integrated", "integrated-reopen")
    elif defaults_only:
        modes = ("defaults", "defaults-reopen", "defaults-invalid")
    elif batch_only:
        modes = ("batch", "batch-reopen")
    elif workflow_only:
        modes = ("workflow", "workflow-reopen")
    elif registered_only:
        modes = ("registered", "registered-reopen")
    elif startup_only:
        modes = ("startup", "startup-reopen")
    elif files_only:
        modes = ("files", "files-reopen")
    elif submission_only:
        modes = ("outcomes", "outcomes-reopen", "review", "review-reopen", "recipe", "close", "close-reopen")
    elif close_only:
        modes = ("first", "reopen", "malformed", "close", "close-reopen", "recipe")
    elif catalog_review_only:
        modes = ("catalog", "catalog-reopen", "review", "review-reopen")
    elif review_only:
        modes = ("review", "review-reopen")
    elif catalog_only:
        modes = ("catalog", "catalog-reopen")
    elif keys_factory_only:
        modes = ("keys-factory", "keys-factory-reopen")
    elif keys_controls_only:
        modes = ("keys-controls", "keys-controls-reopen")
    elif keys_owner_only:
        modes = ("keys-owner", "keys-owner-reopen")
    elif command_conflicts_only:
        modes = ("command-conflicts", "command-conflicts-reopen")
    elif conflicts_only:
        modes = ("conflicts", "conflicts-reopen")
    elif chooser_only:
        modes = ("chooser",)
    elif command_imports_only:
        modes = ("command-imports", "command-imports-reopen")
    elif imports_only:
        modes = ("imports", "imports-reopen")
    elif collections_only:
        modes = ("collections", "collections-reopen")
    elif dependencies_only:
        modes = ("dependencies", "dependencies-reopen")
    elif numeric_only:
        modes = ("numeric", "numeric-reopen")
    elif references_only:
        modes = ("references", "references-reopen")
    elif structured_only:
        modes = ("structured", "structured-reopen")
    elif recipe_only:
        modes = ("recipe",)
    elif lifecycle_only:
        modes = ("reentrant",)
    else:
        modes = ("first", "reopen", "malformed", "recipe", "reentrant", "structured",
                 "structured-reopen", "references", "references-reopen", "numeric", "numeric-reopen",
                 "dependencies", "dependencies-reopen", "collections", "collections-reopen",
                 "imports", "imports-reopen", "command-imports", "command-imports-reopen", "conflicts", "conflicts-reopen",
                 "command-conflicts", "command-conflicts-reopen", "keys-owner", "keys-owner-reopen",
                 "keys-controls", "keys-controls-reopen", "keys-factory", "keys-factory-reopen", "catalog", "catalog-reopen", "review", "review-reopen", "close", "close-reopen", "outcomes", "outcomes-reopen", "files", "files-reopen", "startup", "startup-reopen", "registered", "registered-reopen", "workflow", "workflow-reopen", "batch", "batch-reopen", "defaults", "defaults-reopen", "defaults-invalid", "integrated", "integrated-reopen")
    try:
        for mode in modes:
            if mode == "integrated":
                sources = fixture / "config"
                sources.mkdir(parents=True, exist_ok=True)
                (sources / "reform-server-native.toml").write_text(
                    "# Native server policy; no replacement JSON serializer.\nlevel = 4\n")
            if mode in ("workflow", "batch"):
                sources = fixture / "config"
                sources.mkdir(parents=True, exist_ok=True)
                (sources / "legacy registered-client.toml").write_text('live = 4\nworld = 1\ngame = 1\n')
                (sources / "legacy-defaults.toml").write_text('level = 3\n')
            if mode == "defaults-invalid":
                invalid = fixture / "config/reform_native_example/future_defaults.json"
                invalid.parent.mkdir(parents=True, exist_ok=True)
                invalid.write_text("{invalid future defaults")
            if mode == "registered":
                sources = fixture / "config"
                sources.mkdir(parents=True, exist_ok=True)
                (sources / "legacy registered-client.toml").write_text('live = 9\nworld = 1\ngame = 1\n')
                (sources / "legacy-defaults.toml").write_text('level = 7\n')
            if mode == "startup":
                sources = fixture / "config/startup-tools"
                sources.mkdir(parents=True, exist_ok=True)
                (sources / "known.properties").write_text("level=2\n")
                (sources / "broken.properties").write_text("level=3\n")
                (sources / "unknown.properties").write_text("level=7\n")
            if mode == "files":
                sources = fixture / "config/legacy-tools"
                sources.mkdir(parents=True, exist_ok=True)
                (sources / "choices.properties").write_bytes(b"# preserved author comment\r\nsize=6\r\ntitle=before\r\n")
                (sources / "bare.json").write_text('{"count":6}\n')
                (sources / "counter.txt").write_text("count=6\n")
                (sources / "broken.properties").write_text("enabled=TRUE\nsize=4\ntitle=before\n")
            if mode == "numeric":
                source = fixture / "config/reform_native_example/numeric.json"
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("{invalid numeric fixture")
            if mode == "malformed":
                saved = fixture / "config/reform_native_example/preferences.json"
                (fixture / "native-form-valid.json").write_bytes(saved.read_bytes())
                saved.write_text("{invalid retained input")
            if mode == "reentrant":
                source = fixture / "config/reform_native_example_probe/lifecycle.json"
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("{invalid retained input")
            evidence = fixture / "native-form-report.json"
            evidence.unlink(missing_ok=True)
            (fixture / "native-form-mode.txt").write_text(mode + "\n")
            result = execute(command + ["probeJar", "runClient"], example, log, timeout)
            if evidence.is_file() and evidence.stat().st_size <= 131072:
                result["native"] = json.loads(evidence.read_text())
            report["runs"].append(result)
            if not successful_run(result, mode, digest):
                result["crashReports"] = crash_evidence(fixture)
                result["consoleEvidence"] = console_evidence(log)
                break
        for source in sorted((fixture / "screenshots").glob("form-*.png")):
            if source.is_file() and source.stat().st_size <= 8 * 1024 * 1024:
                data = source.read_bytes()
                destination = report_dir / source.name
                destination.write_bytes(data)
                report["screenshots"][source.name] = hashlib.sha256(data).hexdigest()
        if successful_report(report, modes, digest):
            report["status"] = "PASS"
    except Exception as failure:
        report["failure"] = str(failure)
    finally:
        try:
            cleanup_fixture(fixture, token)
            report["fixtureRemoved"] = not fixture.exists()
        except Exception as failure:
            report["status"] = "FAIL"
            report["cleanupFailure"] = str(failure)
        log.unlink(missing_ok=True)
        output = report_dir / "native-form.json"
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"status": report["status"], "report": str(output),
                          "nativeFailures": [result.get("native", {}).get("failure") or result.get("native", {}).get("shutdownFailure") for result in report["runs"]],
                          "fixtureRemoved": report.get("fixtureRemoved", False)}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--timeout", type=int, default=180, help="Client/build timeout in seconds (1..600)")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--recipe-only", action="store_true", help="Run only native unsaved creation recipe controls")
    modes.add_argument("--lifecycle-only", action="store_true", help="Run only the held reentrant retry/shutdown control")
    modes.add_argument("--structured-only", action="store_true", help="Run recursive editing and saved reopen controls")
    modes.add_argument("--references-only", action="store_true", help="Run native reference picking and saved reopen controls")
    modes.add_argument("--numeric-only", action="store_true", help="Run native mapped units, steps and saved reopen controls")
    modes.add_argument("--dependencies-only", action="store_true", help="Run native local availability, retained drafts and saved reopen controls")
    modes.add_argument("--collections-only", action="store_true", help="Run native scalar collection selection and saved reopen controls")
    modes.add_argument("--imports-only", action="store_true", help="Run native admitted profile import and saved reopen controls")
    modes.add_argument("--command-imports-only", action="store_true", help="Run registered-source Brigadier import and saved reopen controls")
    modes.add_argument("--chooser-only", action="store_true", help="Opt-in native OS dialog controls; requires an external desktop driver")
    modes.add_argument("--conflicts-only", action="store_true", help="Run native manual conflict choices, validation and saved reopen")
    modes.add_argument("--command-conflicts-only", action="store_true", help="Run native Brigadier manual conflict choices and saved reopen")
    modes.add_argument("--keys-owner-only", action="store_true", help="Run actual native Options key owner and saved reopen")
    modes.add_argument("--keys-controls-only", action="store_true", help="Run native key capture/reset/conflict controls and saved reopen")
    modes.add_argument("--keys-factory-only", action="store_true", help="Run ordinary native key factory, failed-view ownership and GUI/command/reopen controls")
    modes.add_argument("--catalog-only", action="store_true", help="Run native profile catalog, retained drafts, focus/reflow and saved reopen")
    modes.add_argument("--review-only", action="store_true", help="Run native source/value inspection, related diagnostics and unchanged-source reopen")
    modes.add_argument("--catalog-review-only", action="store_true", help="Run catalog save/reopen followed by inspection/reopen in one owned fixture")
    modes.add_argument("--close-only", action="store_true", help="Run ordinary form save/discard/reopen, saved/unapplied/late-edit controls and native recipe close")
    modes.add_argument("--submission-only", action="store_true", help="Run identified submission, actual outcome/recovery/drain and inspection/recipe/close regressions")
    modes.add_argument("--files-only", action="store_true", help="Run explicit properties/bare/custom files, malformed recovery and unchanged native reopen")
    modes.add_argument("--startup-only", action="store_true", help="Run actual selected/saved startup choices, unknown active input and held construction shutdown")
    modes.add_argument("--registered-only", action="store_true", help="Run actual CLIENT/COMMON native form/save/command/reload and fresh-JVM checks")
    modes.add_argument("--workflow-only", action="store_true", help="Run retained JSON/native/Controls composition, separate saves, conflicts, lifetime and fresh-JVM checks")
    modes.add_argument("--batch-only", action="store_true", help="Run complete-vector cross-document preflight, ordered native saves and fresh-JVM checks")
    modes.add_argument("--defaults-only", action="store_true", help="Run optional future defaults, explicit native recipe import/admission, reopen and malformed-source repair")
    modes.add_argument("--integrated-only", action="store_true", help="Run integrated world policy authority, native GUI saves, disconnect and fresh-JVM reopen")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 600:
        parser.error("Expected a bounded 1..600 second timeout")
    raise SystemExit(run(args.java_home, args.timeout, args.lifecycle_only, args.recipe_only, args.structured_only, args.references_only, args.numeric_only, args.dependencies_only, args.collections_only, args.imports_only, args.command_imports_only, args.chooser_only, args.conflicts_only, args.command_conflicts_only, args.keys_owner_only, args.keys_controls_only, args.keys_factory_only, args.catalog_only, args.review_only, args.catalog_review_only, args.close_only, args.submission_only, args.files_only, args.startup_only, args.registered_only, args.workflow_only, args.batch_only, args.defaults_only, args.integrated_only))
