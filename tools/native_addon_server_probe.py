#!/usr/bin/env python3
"""Check the independent addon on a bounded loopback-only native dedicated server."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

from native_config_probe import execute
from native_form_probe import create_fixture, cleanup_fixture

ROOT = Path(__file__).resolve().parents[1]


def run(java_home, timeout, registered=False):
    example = ROOT / "examples/native"
    fixture = example / "runs/server"
    if fixture.exists() or fixture.is_symlink():
        raise FileExistsError("Server fixture exists; inspect it before creating a new one")
    version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text().splitlines()
                   if line.startswith("mod_version="))
    jar = ROOT / f"neoforge/build/libs/reform-{version}-mc1.21.1-neoforge.jar"
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    command = [str(ROOT / "gradlew"), "--no-daemon"]
    if java_home:
        command.append("-Dorg.gradle.java.home=" + str(java_home))
    command.extend([f"-PreformVersion={version}", f"-PreformSha256={digest}", "-PnativeProbe=true", "probeJar", "runServer"])
    token = str(uuid.uuid4())
    create_fixture(fixture, token, client=False)
    (fixture / "eula.txt").write_text("eula=true\n")
    terrain = {"biome": "minecraft:plains", "layers": [{"block": "minecraft:bedrock", "height": 1}], "structure_overrides": []}
    properties = {"server-ip": "127.0.0.1", "server-port": "0", "online-mode": "false",
                  "enforce-secure-profile": "false", "level-seed": "42", "level-type": "minecraft:flat",
                  "generator-settings": json.dumps(terrain, separators=(",", ":")),
                  "view-distance": "2", "simulation-distance": "2", "max-players": "1",
                  "enable-status": "false", "enable-rcon": "false", "enable-query": "false", "allow-nether": "false"}
    (fixture / "server.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()))
    report = {"status": "FAIL", "librarySha256": digest,
              "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "launch": "ModDev source-container addon/probe; exact packaged Maven library", "inputs": {}}
    for source in [Path(__file__).resolve(), ROOT / "tools/native_form_probe.py", ROOT / "tools/native_config_probe.py",
                   example / "build.gradle", example / "src/probe/java/example/reformprobe/NativeAddonServerProbe.java",
                   example / "src/probe/java/example/reformprobe/RegisteredOwnerProbe.java",
                   example / "src/probe/java/example/reformprobe/NativeReferenceChecks.java",
                   example / "src/probe/java/example/reformprobe/NativeDraftChecks.java"]:
        report["inputs"][str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
    log = ROOT / "build/native-addon-server.log"
    try:
        boots = []
        for mode in (["first", "reopen"] if registered else ["ordinary"]):
            if registered:
                (fixture / "registered-probe-mode.json").write_text(json.dumps({"mode": mode}))
            observation = fixture / "native-addon-server.json"
            observation.unlink(missing_ok=True)
            result = execute(command, example, log, timeout)
            native = (json.loads(observation.read_text())
                      if observation.is_file() and observation.stat().st_size <= 65536 else {})
            boots.append({"mode": mode, "run": result, "native": native})
            if not (result["exitCode"] == 0 and result["termination"] is None
                    and native.get("status") == "PASS" and native.get("librarySha256") == digest):
                break
        report["boots"] = boots
        expected_boots = 2 if registered else 1
        if len(boots) == expected_boots and all(boot["run"]["exitCode"] == 0
                and boot["run"]["termination"] is None and boot["native"].get("status") == "PASS"
                and boot["native"].get("librarySha256") == digest for boot in boots):
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
        output = ROOT / "build/reports" / ("native-registered-owner.json" if registered else "native-addon-server.json")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"status": report["status"], "report": str(output), "fixtureRemoved": report.get("fixtureRemoved", False)}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--timeout", type=int, default=150)
    parser.add_argument("--registered", action="store_true", help="Exercise the native registered owner/editor across two boots")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 300:
        parser.error("Expected a bounded 1..300 second deadline")
    raise SystemExit(run(args.java_home, args.timeout, args.registered))
