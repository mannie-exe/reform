"""Native policy qualification must retain actual shutdown, process and artifact facts."""

import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


CONFIG = module("native_config_probe", "native_config_probe.py")
with patch.dict(sys.modules, {"native_config_probe": CONFIG}):
    FORM = module("native_form_probe", "native_form_probe.py")
    with patch.dict(sys.modules, {"native_form_probe": FORM}):
        SERVER = module("native_addon_server_probe", "native_addon_server_probe.py")


class NativeServerResultGuards(unittest.TestCase):
    def test_policy_pass_requires_actual_shutdown_on_the_selected_artifact(self):
        boot = {"run": {"exitCode": 0, "termination": None},
                "native": {"status": "PASS", "librarySha256": "selected"}}
        self.assertTrue(SERVER.successful_boot(boot, "selected"))
        self.assertFalse(SERVER.successful_boot(boot, "selected", policies=True))
        boot["native"]["serverShutdownClosed"] = True
        self.assertTrue(SERVER.successful_boot(boot, "selected", policies=True))
        self.assertFalse(SERVER.successful_boot(boot, "different", policies=True))
        boot["run"]["exitCode"] = 1
        self.assertFalse(SERVER.successful_boot(boot, "selected", policies=True))
        boot["run"]["exitCode"] = 0
        boot["run"]["termination"] = "deadline"
        self.assertFalse(SERVER.successful_boot(boot, "selected", policies=True))
        self.assertFalse(SERVER.successful_boot({}, "selected", policies=True))


if __name__ == "__main__":
    unittest.main()
