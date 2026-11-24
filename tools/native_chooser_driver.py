#!/usr/bin/env python3
"""Opt-in macOS driver for the marked native chooser fixture; never targets installed clients."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

if __package__:
    from .targets import load_targets
else:
    from targets import load_targets

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "examples/native/runs/client"
AX_DRIVER = ROOT / "build/native-chooser-ax"


class UnconfirmedPress(RuntimeError):
    def __init__(self, observation):
        super().__init__("The native press has no confirmed action result")
        self.observation = observation


def prepare_ax_driver():
    source = ROOT / "tools/native_chooser_ax.swift"
    if AX_DRIVER.is_symlink():
        raise RuntimeError("Native chooser driver output is a symlink")
    subprocess.run(["swiftc", str(source), "-o", str(AX_DRIVER)], check=True, timeout=30)
    print(json.dumps({"driver": "PID-native AX", "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                      "binarySha256": hashlib.sha256(AX_DRIVER.read_bytes()).hexdigest()}), flush=True)


def ax_action(pid, action, selected, accept="Open"):
    result = subprocess.run([str(AX_DRIVER), str(pid), action, str(selected), accept],
                            capture_output=True, text=True, timeout=8)
    if result.returncode == 2:
        return None
    if result.returncode == 3:
        observed = json.loads(result.stdout)
        if (observed.get("status") == "unconfirmed" and observed.get("pid") == pid
                and observed.get("action") == action and observed.get("axStatus") == -25204
                and action in {"cancel", "cancel-abandoned", "cancel-final"}):
            raise UnconfirmedPress(observed)
        raise RuntimeError("Native chooser press failed without an admitted closure check")
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    observed = json.loads(result.stdout)
    if observed.get("status") != "performed" or observed.get("pid") != pid:
        raise RuntimeError("Native chooser adapter returned a different application identity")
    return observed


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


def dialog(parent, sdl=False):
    descendants = children(parent)  # Verifies the live JVM and exact owned working directory.
    for pid in ([parent] if sdl else descendants):
        observed = ax_action(pid, "visible", "", "Open" if sdl else "Choose")
        if observed is not None:
            return pid, observed
    return None


def operate(pid, action, selected, sdl=False):
    observed = ax_action(pid, action, selected, "Open" if sdl else "Choose")
    if observed is None:
        raise RuntimeError("The owned native dialog disappeared before its action")
    return observed


def confirmed_operation(parent, current, action, selected, sdl=False):
    pid, before = current
    try:
        return operate(pid, action, selected, sdl)
    except UnconfirmedPress as failed:
        # AX can return cannotComplete after Cancel has actually dismissed the native panel.
        # That return alone earns no receipt. Recheck the exact owned JVM/dialog until absent.
        end = time.monotonic() + 3
        while time.monotonic() < end:
            if dialog(parent, sdl) is None:
                return dict(before, action=action,
                            method="native press unconfirmed; exact owned dialog observed before and absent after",
                            unconfirmedPress=failed.observation)
            time.sleep(0.1)
        raise RuntimeError("Unconfirmed native Cancel left the owned dialog present") from failed


def run(timeout, target=None, boots=1, manual_input=False):
    if sys.platform != "darwin":
        raise RuntimeError("This opt-in driver qualifies macOS only")
    end = time.monotonic() + timeout
    token = None
    completed = set()
    finished_sessions = set()
    session = None
    manual_observations = {}
    sdl = False
    if target is not None:
        lane = next((item for item in load_targets(ROOT) if item["project"] == target), None)
        if lane is None:
            raise ValueError("Select an admitted platform target")
        backends = {"1.21.1": False, "26.3": True}
        if lane["minecraftVersion"] not in backends:
            raise ValueError("This game has no qualified native dialog driver")
        sdl = backends[lane["minecraftVersion"]]
    prepare_ax_driver()
    print(json.dumps({"input": "manual-assisted" if manual_input else "automated"}), flush=True)
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
        if ownership["token"] != token:
            raise RuntimeError("Fixture identity changed")
        if target is None:
            if (FIXTURE / "native-form-mode.txt").read_text().strip() != "chooser":
                raise RuntimeError("Fixture mode changed")
        else:
            mode = json.loads((FIXTURE / "platform-input.json").read_text())
            if mode.get("chooserClient") is not True:
                raise RuntimeError("Not the opt-in platform chooser fixture")
            if (FIXTURE / "platform-cancel").exists():
                raise RuntimeError("The owned native fixture was cancelled")
        value = json.loads(request.read_text())
        observed_session = value.get("session", "original-chooser")
        if observed_session in finished_sessions:
            time.sleep(0.2)
            continue
        if session != observed_session:
            if session is not None or value["request"] != 1:
                raise RuntimeError("Unexpected chooser session transition")
            session = observed_session
        identity = value["request"]
        if identity in completed:
            time.sleep(0.2)
            continue
        if identity != len(completed) + 1 or not 1 <= identity <= 5:
            raise RuntimeError("Unexpected native chooser request sequence")
        selected = Path(value["selectedPath"]).resolve()
        if not selected.is_relative_to(FIXTURE.resolve()) or not selected.is_file():
            raise RuntimeError("Choice escaped the fixture")
        current = dialog(int(value["pid"]), sdl)
        pid = current[0] if current is not None else None
        if manual_input and value["action"] != "visible":
            key = (session, identity)
            if key not in manual_observations:
                if pid is None:
                    time.sleep(0.2)
                    continue
                manual_observations[key] = current
                print(json.dumps({"awaitingHuman": value["action"], "request": identity,
                                  "selectedPath": str(selected), "fixtureJvmPid": int(value["pid"]),
                                  "helperPid": pid, "nativeObservation": current[1]}), flush=True)
                time.sleep(0.2)
                continue
            if pid is not None:
                time.sleep(0.2)
                continue
            pid, observed = manual_observations.pop(key)
            observed = dict(observed, action=value["action"],
                            method="manual-assisted; exact native dialog observed before and absent after")
        elif pid is None:
            time.sleep(0.2)
            continue
        else:
            observed = confirmed_operation(int(value["pid"]), current, value["action"], selected, sdl)
        result = {"request": identity, "action": value["action"], "process": "java/SDL" if sdl else "osascript",
                  "pid": pid, "window": "Import settings" if sdl else "Choose a File", "observedAction": observed}
        result["window"] = observed["window"]
        if target is not None:
            result["session"] = session
        temporary = FIXTURE / "native-chooser-ack.tmp"
        temporary.write_text(json.dumps(result))
        temporary.replace(FIXTURE / "native-chooser-ack.json")
        print(json.dumps(result), flush=True)
        completed.add(identity)
        if identity == 5:
            finished_sessions.add(session)
            if len(finished_sessions) == boots:
                return
            completed.clear()
            session = None
    raise TimeoutError("Native chooser fixture did not complete within the driver deadline")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--target", choices=[item["project"] for item in load_targets(ROOT)],
                        help="Opt-in platform fixture; omitted selects the original NeoForge example")
    parser.add_argument("--boots", type=int, choices=[1, 2], default=1,
                        help="Distinct chooser sessions to drive; use 2 for platform save/reopen")
    parser.add_argument("--manual-input", action="store_true",
                        help="Observe native dialog lifetime; a person performs selections and cancellation")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 600:
        parser.error("Expected 1..600 second timeout")
    if args.target is not None:
        FIXTURE = ROOT / "examples/platform/runs" / (args.target + "-client")
    elif args.boots != 1:
        parser.error("The original chooser driver supports one session")
    run(args.timeout, args.target, args.boots, args.manual_input)
