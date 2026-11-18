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


def successful_run(result, mode, digest):
    native = result.get("native", {})
    return (result["exitCode"] == 0 and result["termination"] is None
            and native.get("status") == "PASS" and native.get("nativeShutdownClosed") is True
            and native.get("mode") == mode and native.get("librarySha256") == digest
            and (not mode.startswith("structured") or native.get("structured", {}).get("status") == "PASS")
            and (not mode.startswith("references") or native.get("references", {}).get("status") == "PASS")
            and (not mode.startswith("numeric") or native.get("numeric", {}).get("status") == "PASS")
            and (not mode.startswith("dependencies") or native.get("dependencies", {}).get("status") == "PASS")
            and (mode != "recipe" or native.get("recipe", {}).get("managedShutdownClosed") is True))


def run(java_home, timeout, lifecycle_only=False, recipe_only=False, structured_only=False, references_only=False, numeric_only=False, dependencies_only=False):
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
    if dependencies_only:
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
                 "dependencies", "dependencies-reopen")
    try:
        for mode in modes:
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
                break
        for source in sorted((fixture / "screenshots").glob("form-*.png")):
            if source.is_file() and source.stat().st_size <= 8 * 1024 * 1024:
                data = source.read_bytes()
                destination = report_dir / source.name
                destination.write_bytes(data)
                report["screenshots"][source.name] = hashlib.sha256(data).hexdigest()
        if (len(report["runs"]) == len(modes)
                and all(successful_run(result, mode, digest)
                        for result, mode in zip(report["runs"], modes))):
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
    args = parser.parse_args()
    if not 1 <= args.timeout <= 600:
        parser.error("Expected a bounded 1..600 second timeout")
    raise SystemExit(run(args.java_home, args.timeout, args.lifecycle_only, args.recipe_only, args.structured_only, args.references_only, args.numeric_only, args.dependencies_only))
