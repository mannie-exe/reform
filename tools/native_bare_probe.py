#!/usr/bin/env python3
"""Qualify one packaged library without a consumer on a disposable dedicated server."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

if __package__:
    from .native_config_probe import execute, process_table
    from .native_form_probe import create_fixture, cleanup_fixture
    from .native_library import select_library
    from .targets import load_targets
else:
    from native_config_probe import execute, process_table
    from native_form_probe import create_fixture, cleanup_fixture
    from native_library import select_library
    from targets import load_targets

ROOT = Path(__file__).resolve().parents[1]


def verify_fixture_network(fixture):
    values = dict(line.split("=", 1) for line in (fixture / "server.properties").read_text().splitlines()
                  if line and not line.startswith("#") and "=" in line)
    if any(values.get(key) != value for key, value in {
            "server-ip": "127.0.0.1", "server-port": "0", "enable-rcon": "false", "enable-query": "false"}.items()):
        raise ValueError("Native fixture requires loopback, an ephemeral port and disabled auxiliary listeners")


def native_jvm(root, fixture):
    rows = process_table()
    owned = {root}
    while True:
        found = {pid for pid, info in rows.items() if info[0] in owned}
        if found <= owned:
            break
        owned |= found
    matches = []
    for pid in owned:
        if pid not in rows or rows[pid][3] != "java":
            continue
        lines = subprocess.check_output(["lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"],
                                        text=True, timeout=5).splitlines()
        if "n" + str(fixture.resolve()) in lines:
            matches.append(pid)
    if len(matches) != 1:
        raise RuntimeError("Expected exactly one owned native JVM at the fixture directory")
    return matches[0]


def probe_ready(parent, log, fixture, target, java_home, observation):
    text = log.read_text(errors="replace")
    if ")! For help, type \"help\"" not in text:
        return None
    if "starting minecraft server version " + target["minecraftVersion"] not in text.lower():
        raise RuntimeError("Native server reported a different game version")
    if "reform_platform_example" in text:
        raise RuntimeError("Bare launch discovered the consumer mod")
    pid = native_jvm(parent, fixture)
    sockets = subprocess.check_output(["lsof", "-a", "-p", str(pid), "-iTCP", "-sTCP:LISTEN", "-nP", "-Fn"],
                                      text=True, timeout=5).splitlines()
    listening = [line[1:] for line in sockets if line.startswith("n")]
    observation["listening"] = listening
    if len(listening) != 1 or not listening[0].startswith("127.0.0.1:"):
        raise RuntimeError("Native fixture did not bind exactly its loopback listener")
    tool = str(java_home / "bin/jcmd")
    entry = ("design.inherent.reform.neoforge.Reform" if target["loader"] == "neoforge"
             else "design.inherent.reform.fabric.ReformFabric")
    loaded = subprocess.check_output([tool, str(pid), "VM.class_hierarchy", entry],
                                     text=True, timeout=10)
    observation.update(nativePid=pid, expectedEntryPoint=entry, classHierarchy=loaded)
    if entry not in loaded and entry.replace(".", "/") not in loaded:
        raise RuntimeError("The actual native JVM has not loaded the library entry point")
    version = subprocess.check_output([tool, str(pid), "VM.version"], text=True, timeout=10)
    observation["runtimeVersion"] = version
    if "JDK " + str(target["java"]) + "." not in version:
        raise RuntimeError("Native JVM differs from the target runtime")
    dump = subprocess.check_output([tool, str(pid), "Thread.print"], text=True, timeout=10)
    workers = [line for line in dump.splitlines() if line.startswith('"reform-')]
    if workers:
        raise RuntimeError("Library-only startup created re:form workers")
    return {"status": "PASS", "nativePid": pid, "entryPoint": entry,
            "classHierarchy": loaded, "runtimeVersion": version,
            "reformWorkerThreads": workers, "listening": listening}


def successful_boot(result, settings):
    return (result["exitCode"] == 0 and result["termination"] is None
            and isinstance(result.get("nativeConsole"), dict)
            and result["nativeConsole"].get("status") == "PASS" and not settings)


def run(project, java_home, timeout, version=None):
    target = next(t for t in load_targets(ROOT) if t["project"] == project)
    library = select_library(ROOT, project, version)
    example = ROOT / "examples/platform"
    fixture = example / "runs" / (project + "-bare")
    if fixture.exists() or fixture.is_symlink():
        raise FileExistsError("Bare fixture exists; inspect it before another launch")
    report_dir = ROOT / "build/reports/native-bare" / project
    report_dir.mkdir(parents=True, exist_ok=True)
    token = str(uuid.uuid4())
    create_fixture(fixture, token, client=False)
    command = ["/usr/bin/env", "JAVA_HOME=" + str(java_home), str(ROOT / "gradlew"),
               "--no-daemon", "-Ptarget=" + project, "-PbareLibrary=true",
               "-PreformVersion=" + library.version, "-PreformSha256=" + library.sha256, "runServer"]
    report = {"status": "FAIL", "target": target, "version": library.version,
              "librarySha256": library.sha256, "scope": "Dedicated library-only native bootstrap; no consumer, GUI or optional Connector qualification",
              "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in [Path(__file__), ROOT / "tools/native_config_probe.py",
                                    ROOT / "tools/native_form_probe.py", example / "build.gradle"]},
              "boots": []}
    try:
        for reopen in (False, True):
            verify_fixture_network(fixture)
            log = report_dir / ("reopen.log" if reopen else "first.log")
            observation = {}
            def ready(parent, output):
                try:
                    return probe_ready(parent, output, fixture, target, java_home, observation)
                except Exception as failed:
                    observation["failure"] = str(failed)[:4096]
                    raise
            result = execute(command, example, log, timeout,
                             ready_console=ready)
            settings = [str(p.relative_to(fixture)) for p in (fixture / "config").rglob("*")
                        if "reform" in p.name.lower()]
            report["boots"].append({"reopen": reopen, "run": result, "librarySettingsPaths": settings,
                                    "observation": observation})
            if not successful_boot(result, settings):
                break
        else:
            report["status"] = "PASS"
    finally:
        cleanup_fixture(fixture, token)
        report["fixtureRemoved"] = not fixture.exists()
        saved = report_dir / "result.json"
        saved.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "target": project, "report": str(saved),
                      "fixtureRemoved": report["fixtureRemoved"]}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=[t["project"] for t in load_targets(ROOT)])
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--library-version")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 600:
        parser.error("Select a bounded 1..600 second timeout")
    raise SystemExit(run(args.target, args.java_home, args.timeout, args.library_version))
