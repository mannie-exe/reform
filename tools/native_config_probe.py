#!/usr/bin/env python3
"""Bounded POSIX-only native source fixture. No GUI, installed instances or personal worlds."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
MARKER = ".reform-native-fixture.json"


def create_fixture(root, token):
    fixture = root / "neoforge/runs/native-config"
    fixture.mkdir(parents=True, exist_ok=False)
    (fixture / MARKER).write_text(json.dumps({"token": token, "purpose": "native-config-probe"}))
    (fixture / "eula.txt").write_text("eula=true\n")
    properties = {
        "server-ip": "127.0.0.1", "server-port": "0", "online-mode": "false",
        "enforce-secure-profile": "false", "level-name": "world", "level-seed": "42",
        "level-type": "minecraft:flat", "generator-settings": json.dumps({
            "biome": "minecraft:plains", "layers": [
                {"block": "minecraft:bedrock", "height": 1},
                {"block": "minecraft:dirt", "height": 2},
                {"block": "minecraft:grass_block", "height": 1}], "structure_overrides": []}, separators=(",", ":")),
        "view-distance": "2", "simulation-distance": "2", "spawn-protection": "0",
        "max-players": "1", "max-tick-time": "120000", "enable-query": "false",
        "enable-rcon": "false", "allow-nether": "false", "enable-status": "false",
    }
    (fixture / "server.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()))
    return fixture


def cleanup_fixture(root, token):
    fixture = root / "neoforge/runs/native-config"
    if fixture.is_symlink() or not fixture.is_dir():
        raise ValueError("Expected the task-owned native fixture directory")
    expected = {"token": token, "purpose": "native-config-probe"}
    if json.loads((fixture / MARKER).read_text()) != expected:
        raise ValueError("Fixture ownership changed; refusing cleanup")
    config = fixture / "config"
    if config.is_dir() and not config.is_symlink():
        # The deliberate native pre-write failure can leave this owned directory read-only on kill.
        config.chmod(config.stat().st_mode | stat.S_IWUSR | stat.S_IXUSR)
    shutil.rmtree(fixture)


def execute(command, root, log, deadline):
    start = time.monotonic()
    termination = None
    with log.open("wb") as output:
        process = subprocess.Popen(command, cwd=root, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        while process.poll() is None:
            if time.monotonic() - start > deadline:
                termination = "deadline"
            elif log.stat().st_size > 8 * 1024 * 1024:
                termination = "log limit"
            if termination:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                break
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass
    result = {"command": command, "exitCode": process.returncode,
              "seconds": round(time.monotonic() - start, 3), "termination": termination}
    if process.returncode != 0 or termination:
        with log.open("rb") as stream:
            stream.seek(max(0, log.stat().st_size - 4096))
            result["logTail"] = stream.read(4096).decode("utf-8", errors="replace")
    return result


def run(root, java_home, timeout):
    fixture = root / "neoforge/runs/native-config"
    if fixture.exists() or fixture.is_symlink():
        raise FileExistsError("Native fixture already exists; inspect it before running this tool")
    report_dir = root / "build/reports"
    report_dir.mkdir(parents=True, exist_ok=True)
    report = {"launchMode": "ModDev source containers, not packaged-product loading",
              "scope": "native FML/NeoForge callback contract only", "status": "FAIL", "runs": []}
    inputs = ["neoforge/build.gradle", "tools/native_config_probe.py",
              "neoforge/src/nativeProbe/java/example/reform/NativeConfigProbe.java",
              "neoforge/src/nativeProbe/resources/META-INF/neoforge.mods.toml"]
    report["sourceDigests"] = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in inputs}
    report["gitHead"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    command = [str(root / "gradlew"), "--no-daemon"]
    if java_home:
        command.append("-Dorg.gradle.java.home=" + str(java_home))
    token = str(uuid.uuid4())
    created = False
    logs = []
    try:
        log = root / "build/native-config-build.log"
        logs.append(log)
        built = execute(command + ["check", "build", ":neoforge:nativeProbeJar"], root, log, timeout)
        report["build"] = built
        if built["exitCode"] != 0 or built["termination"]:
            raise RuntimeError("Native fixture build failed")
        created = True
        create_fixture(root, token)
        for mode in ("first", "reopen"):
            (fixture / "native-probe-mode.json").write_text(json.dumps({"mode": mode}) + "\n")
            log = root / f"build/native-config-{mode}.log"
            logs.append(log)
            result = execute(command + ["-PnativeProbe=true", ":neoforge:runNativeProbeServer"], root, log, timeout)
            report["runs"].append(result)
            evidence = fixture / f"native-config-{mode}.json"
            if evidence.is_file() and evidence.stat().st_size <= 65536:
                result["native"] = json.loads(evidence.read_text())
            if (result["exitCode"] != 0 or result["termination"]
                    or result.get("native", {}).get("status") != "PASS"
                    or result.get("native", {}).get("mode") != mode):
                raise RuntimeError("Native control failed: " + mode)
        artifacts = sorted((root / "build/distributions").iterdir())
        report["packagedArtifactDigests"] = {
            path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in artifacts if path.is_file()}
        report["status"] = "PASS"
    except Exception as failure:
        report["failure"] = str(failure)
    finally:
        if created:
            try:
                cleanup_fixture(root, token)
                report["fixtureRemoved"] = not fixture.exists()
            except Exception as failure:
                report["status"] = "FAIL"
                report["cleanupFailure"] = str(failure)
        for log in logs:
            try:
                log.unlink(missing_ok=True)
            except OSError as failure:
                report["status"] = "FAIL"
                report.setdefault("logCleanupFailures", []).append(str(failure))
        path = report_dir / "native-config-contract.json"
        path.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"status": report["status"], "report": str(path),
                          "fixtureRemoved": report.get("fixtureRemoved", False)}))
    return 0 if report["status"] == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--timeout", type=int, default=150, help="Seconds per build/boot (1..300)")
    args = parser.parse_args()
    if os.name != "posix" or not 1 <= args.timeout <= 300:
        parser.error("This permission-control fixture requires POSIX and a 1..300 second deadline")
    return run(ROOT, args.java_home, args.timeout)


if __name__ == "__main__":
    raise SystemExit(main())
