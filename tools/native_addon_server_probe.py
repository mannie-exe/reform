#!/usr/bin/env python3
"""Check the independent addon on a bounded loopback-only native dedicated server."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

if __package__:
    from .native_config_probe import execute
    from .native_form_probe import create_fixture, cleanup_fixture, console_evidence, crash_evidence
    from .native_library import select_library
    from .targets import load_targets
else:
    from native_config_probe import execute
    from native_form_probe import create_fixture, cleanup_fixture, console_evidence, crash_evidence
    from native_library import select_library
    from targets import load_targets

ROOT = Path(__file__).resolve().parents[1]


def successful_boot(boot, digest, policies=False):
    native = boot.get("native", {})
    result = boot.get("run", {})
    commands = native.get("actualCommands")
    search = native.get("actualSearch")
    return (result.get("exitCode") == 0 and result.get("termination") is None
            and native.get("status") == "PASS" and native.get("librarySha256") == digest
            and (not policies or (native.get("serverShutdownClosed") is True
                                  and isinstance(commands, list) and len(commands) >= 12
                                  and isinstance(search, list) and len(search) >= 15)))


def run(java_home, timeout, registered=False, policies=False, library_version=None, target="neoforge-1_21_1"):
    if target not in {t["project"] for t in load_targets(ROOT) if t["loader"] == "neoforge"}:
        raise ValueError("Registered native owners require an admitted NeoForge target")
    if target != "neoforge-1_21_1" and not registered:
        raise ValueError("The additional target is qualified by the shared registered-owner scene only")
    platform = target != "neoforge-1_21_1"
    native = ROOT / "examples/native"
    example = ROOT / ("examples/platform" if platform else "examples/native")
    fixture = example / "runs" / (target if platform else "server")
    if fixture.exists() or fixture.is_symlink():
        raise FileExistsError("Server fixture exists; inspect it before creating a new one")
    library = select_library(ROOT, target, library_version)
    version, digest = library.version, library.sha256
    command = [str(ROOT / "gradlew"), "--no-daemon"]
    if java_home:
        command.append("-Dorg.gradle.java.home=" + str(java_home))
    command.extend([f"-PreformVersion={version}", f"-PreformArtifact={library.module}", f"-PreformSha256={digest}"])
    command.extend(["-Ptarget=" + target, "runServer"] if platform else ["-PnativeProbe=true", "probeJar", "runServer"])
    token = str(uuid.uuid4())
    create_fixture(fixture, token, client=False)
    if policies:
        sources = fixture / "world/serverconfig"
        sources.mkdir(parents=True)
        (sources / "reform-server-policy.json").write_text('{"schemaVersion":1,"values":{"level":9}}\n')
        (sources / "reform-server-native.toml").write_text('# Retained native server source\nlevel = 7\n')
        (sources / "reform-server-bad.json").write_text('malformed retained\n')
    report = {"status": "FAIL", "librarySha256": digest, "libraryVersion": version, "target": library.project,
              "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "launch": "ModDev source-container addon/probe; exact packaged Maven library", "inputs": {}}
    for source in [Path(__file__).resolve(), ROOT / "tools/native_form_probe.py", ROOT / "tools/native_config_probe.py",
                   example / "build.gradle", native / "src/probe/java/example/reformprobe/NativeAddonServerProbe.java",
                   native / "src/probe/java/example/reformprobe/RegisteredOwnerProbe.java",
                   native / "src/probe/java/example/reformprobe/NativeServerPolicyProbe.java",
                   native / "src/probe/java/example/reformprobe/NativeServerCommandControls.java",
                   native / "src/probe/java/example/reformprobe/NativeConsoleCapture.java",
                   native / "src/probe/java/example/reformprobe/NativeServerSearchControls.java",
                   native / "src/main/java/example/reform/ServerPolicyExample.java",
                   native / "src/main/java/example/reform/NativeExample.java",
                   native / "src/probe/java/example/reformprobe/NativeReferenceChecks.java",
                   native / "src/probe/java/example/reformprobe/NativeDraftChecks.java"]:
        report["inputs"][str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
    if platform:
        for relative in ["src/neoforge/java/example/reform/platform/NeoForgePlatform.java",
                         "src/neoforge/minecraft-26_3/java/example/reform/platform/NativeFixtureSide.java"]:
            source = example / relative
            report["inputs"][str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
    log = ROOT / "build/native-addon-server.log"
    try:
        boots = []
        for mode in (["first", "reopen"] if registered or policies else ["ordinary"]):
            if registered:
                (fixture / "registered-probe-mode.json").write_text(json.dumps({"mode": mode}))
            if policies:
                (fixture / "server-policy-probe-mode.json").write_text(json.dumps({"mode": mode}))
            observation = fixture / "native-addon-server.json"
            observation.unlink(missing_ok=True)
            result = execute(command, example, log, timeout)
            native = (json.loads(observation.read_text())
                      if observation.is_file() and observation.stat().st_size <= 65536 else {})
            boot = {"mode": mode, "run": result, "native": native,
                    "consoleEvidence": console_evidence(log), "crashReports": crash_evidence(fixture)}
            boots.append(boot)
            if not successful_boot(boot, digest, policies):
                break
        report["boots"] = boots
        expected_boots = 2 if registered or policies else 1
        if len(boots) == expected_boots and all(successful_boot(boot, digest, policies) for boot in boots):
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
        name = "native-registered-owner-" + target + ".json" if platform else "native-server-policy.json" if policies else "native-registered-owner.json" if registered else "native-addon-server.json"
        output = ROOT / "build/reports" / name
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"status": report["status"], "report": str(output), "fixtureRemoved": report.get("fixtureRemoved", False)}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="Gradle build JDK; the game uses its declared toolchain")
    parser.add_argument("--library-version", help="Frozen staged candidate; defaults to the selected built POM version")
    parser.add_argument("--target", default="neoforge-1_21_1", choices=[t["project"] for t in load_targets(ROOT) if t["loader"] == "neoforge"])
    parser.add_argument("--timeout", type=int, default=150)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--registered", action="store_true", help="Exercise the native registered owner/editor across two boots")
    modes.add_argument("--policies", action="store_true", help="Exercise actual server policy bindings, sources and shutdown across two boots")
    args = parser.parse_args()
    if args.target != "neoforge-1_21_1" and not args.registered:
        parser.error("The selected target currently requires --registered")
    if not 1 <= args.timeout <= 300:
        parser.error("Expected a bounded 1..300 second deadline")
    raise SystemExit(run(args.java_home, args.timeout, args.registered, args.policies, args.library_version, args.target))
