#!/usr/bin/env python3
"""Opt-in macOS driver for the marked native chooser fixture; never targets installed clients."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "examples/native/runs/client"


def script(source):
    result = subprocess.run(["osascript", "-e", source], text=True, capture_output=True, timeout=8)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def children(parent):
    rows = []
    for line in subprocess.check_output(["ps", "-axo", "pid=,ppid=,comm="], text=True).splitlines():
        parts = line.strip().split(None, 2)
        if len(parts) == 3:
            rows.append((int(parts[0]), int(parts[1]), Path(parts[2]).name))
    if not any(pid == parent and name == "java" for pid, _, name in rows):
        raise RuntimeError("The fixture JVM is not live")
    cwd = subprocess.check_output(["lsof", "-a", "-p", str(parent), "-d", "cwd", "-Fn"],
                                  text=True, timeout=5).splitlines()
    if "n" + str(FIXTURE.resolve()) not in cwd:
        raise RuntimeError("JVM working directory is not the owned fixture")
    owned = {parent}
    for _ in range(10):
        found = {pid for pid, ppid, _ in rows if ppid in owned}
        if found <= owned:
            break
        owned |= found
    return [pid for pid, _, name in rows if pid in owned and name == "osascript"]


def dialog(parent):
    for pid in children(parent):
        value = script(f'''tell application "System Events"
try
set targetProcess to first process whose unix id is {pid}
if name of window 1 of targetProcess is "Choose a File" then return "owned"
end try
return "absent"
end tell''')
        if value == "owned":
            return pid
    return None


def operate(pid, action, selected):
    prefix = f'''tell application "System Events"
set targetProcess to first process whose unix id is {pid}
if name of targetProcess is not "osascript" then error "Unexpected process"
if name of window 1 of targetProcess is not "Choose a File" then error "Unexpected dialog"
'''
    if action == "visible":
        script(prefix + 'return "visible"\nend tell')
        return "Confirmed actual native AX dialog; no input"
    if action in ("cancel", "cancel-abandoned", "cancel-final"):
        script(prefix + 'click button "Cancel" of UI element 1 of window 1 of targetProcess\nend tell')
        return "AX Cancel button in native split group"
    if action != "select":
        raise ValueError("Unknown fixture action")
    path = str(selected).replace("\\", "\\\\").replace('"', '\\"')
    script(f'''{prefix}
set frontmost of targetProcess to true
tell targetProcess to keystroke "g" using {{command down, shift down}}
delay 0.3
if not (exists sheet 1 of window 1 of targetProcess) then error "Native Go to Folder sheet absent"
set targetSheet to sheet 1 of window 1 of targetProcess
set value of text field 1 of targetSheet to "{path}"
set focused of text field 1 of targetSheet to true
tell targetProcess to key code 36
delay 0.4
if not enabled of button "Choose" of UI element 1 of window 1 of targetProcess then error "Fixture file not selected"
click button "Choose" of UI element 1 of window 1 of targetProcess
end tell''')
    return "AX native Go to Folder text field and enabled Choose button"


def run(timeout):
    if sys.platform != "darwin":
        raise RuntimeError("This opt-in driver qualifies macOS only")
    end = time.monotonic() + timeout
    token = None
    completed = set()
    while time.monotonic() < end:
        marker = FIXTURE / ".reform-form-fixture.json"
        request = FIXTURE / "native-chooser-request.json"
        if not marker.is_file() or not request.is_file():
            if token is not None:
                raise RuntimeError("Owned fixture ended before chooser completion")
            time.sleep(0.2)
            continue
        ownership = json.loads(marker.read_text())
        if ownership.get("purpose") != "independent-native-form" or FIXTURE.is_symlink():
            raise RuntimeError("Not the owned native form fixture")
        if token is None:
            token = ownership["token"]
        if ownership["token"] != token or (FIXTURE / "native-form-mode.txt").read_text().strip() != "chooser":
            raise RuntimeError("Fixture identity changed")
        value = json.loads(request.read_text())
        identity = value["request"]
        if identity in completed:
            time.sleep(0.2)
            continue
        if identity != len(completed) + 1 or not 1 <= identity <= 5:
            raise RuntimeError("Unexpected native chooser request sequence")
        selected = Path(value["selectedPath"]).resolve()
        if not selected.is_relative_to(FIXTURE.resolve()) or not selected.is_file():
            raise RuntimeError("Choice escaped the fixture")
        pid = dialog(int(value["pid"]))
        if pid is None:
            time.sleep(0.2)
            continue
        observed = operate(pid, value["action"], selected)
        result = {"request": identity, "action": value["action"], "process": "osascript",
                  "pid": pid, "window": "Choose a File", "observedAction": observed}
        temporary = FIXTURE / "native-chooser-ack.tmp"
        temporary.write_text(json.dumps(result))
        temporary.replace(FIXTURE / "native-chooser-ack.json")
        print(json.dumps(result), flush=True)
        completed.add(identity)
        if identity == 5:
            return
    raise TimeoutError("Native chooser fixture did not complete within the driver deadline")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 300:
        parser.error("Expected 1..300 second timeout")
    run(args.timeout)
