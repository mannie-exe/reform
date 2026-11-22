"""A native chooser may escape the launcher group; cleanup must retain actual ownership."""

import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from tools import native_config_probe as probe


class ChooserCleanupTests(unittest.TestCase):
    def test_only_observed_descendants_are_admitted(self):
        rows = {10: (1, "born", "S", "java"), 20: (10, "born", "S", "java"),
                30: (20, "helper", "S", "osascript"), 40: (1, "other", "S", "osascript")}
        self.assertEqual({30: ("helper", "osascript")}, probe.observe_choosers(10, rows))

    def test_reused_pid_is_never_signalled(self):
        with patch.object(probe, "process_table", return_value={30: (1, "new", "S", "osascript")}), \
                patch.object(probe.os, "kill") as kill:
            self.assertEqual([], probe.retire_choosers({30: ("old", "osascript")}))
            kill.assert_not_called()

    def test_orphan_is_retired_only_with_its_observed_birth_identity(self):
        before = {30: (1, "old", "S", "osascript")}
        after = {30: (1, "old", "Z", "osascript")}
        with patch.object(probe, "process_table", side_effect=[before, after]), \
                patch.object(probe.os, "kill") as kill, patch.object(probe.time, "sleep"):
            self.assertEqual([{"pid": 30, "signal": "SIGTERM"}],
                             probe.retire_choosers({30: ("old", "osascript")}))
            kill.assert_called_once_with(30, signal.SIGTERM)

    def test_incomplete_or_nonobject_report_cannot_trigger_abort(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            for value in ["{", "[]", '"FAIL"', '{"status":"PASS"}']:
                path.write_text(value)
                self.assertFalse(probe.native_failed(path))
            path.write_text('{"status":"FAIL"}')
            self.assertTrue(probe.native_failed(path))

    @unittest.skipUnless(os.name == "posix" and shutil.which("sleep"), "POSIX fixture process required")
    def test_real_child_in_another_session_is_closed_after_native_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output, child_file, log = root / "report.json", root / "child.pid", root / "log.txt"
            sleeper = shutil.which("sleep")
            script = (
                "import subprocess,time,json;from pathlib import Path;"
                f"p=subprocess.Popen([{sleeper!r},'30'],start_new_session=True);"
                f"Path({str(child_file)!r}).write_text(str(p.pid));"
                "time.sleep(1.5);"
                f"Path({str(output)!r}).write_text(json.dumps({{'status':'FAIL'}}));"
                "time.sleep(30)"
            )
            child = None
            try:
                with patch.object(probe, "CHOOSER_EXECUTABLES", frozenset({"sleep"})):
                    result = probe.execute([sys.executable, "-c", script], root, log, 10,
                                           chooser_report=output)
                child = int(child_file.read_text())
                self.assertEqual("native failure", result["termination"])
                self.assertLess(result["seconds"], 8)
                self.assertTrue(any(entry.get("pid") == child for entry in result["nativeChooserCleanup"]))
                remaining = probe.process_table().get(child)
                self.assertTrue(remaining is None or remaining[2].startswith("Z"))
            finally:
                if child is None and child_file.is_file():
                    child = int(child_file.read_text())
                if child is not None:
                    try:
                        os.kill(child, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
