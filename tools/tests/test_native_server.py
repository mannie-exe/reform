"""Native policy qualification must retain actual shutdown, process and artifact facts."""

import unittest
from unittest.mock import patch

from tools import native_addon_server_probe as SERVER


class NativeServerResultGuards(unittest.TestCase):
    def test_native_registry_scene_refuses_fabric_before_resolution_or_launch(self):
        with patch.object(SERVER, "select_library") as resolve:
            with self.assertRaisesRegex(ValueError, "admitted NeoForge target"):
                SERVER.run(None, 1, registered=True, target="fabric-26_3")
            resolve.assert_not_called()

    def test_additional_target_refuses_an_unimplemented_scene_before_resolution(self):
        with patch.object(SERVER, "select_library") as resolve:
            with self.assertRaisesRegex(ValueError, "registered-owner scene only"):
                SERVER.run(None, 1, policies=True, target="neoforge-26_3")
            resolve.assert_not_called()

    def test_policy_pass_requires_actual_commands_and_shutdown_on_the_selected_artifact(self):
        boot = {"run": {"exitCode": 0, "termination": None},
                "native": {"status": "PASS", "librarySha256": "selected"}}
        self.assertTrue(SERVER.successful_boot(boot, "selected"))
        self.assertFalse(SERVER.successful_boot(boot, "selected", policies=True))
        boot["native"]["serverShutdownClosed"] = True
        self.assertFalse(SERVER.successful_boot(boot, "selected", policies=True))
        boot["native"]["actualCommands"] = ["actual console result"] * 12
        self.assertFalse(SERVER.successful_boot(boot, "selected", policies=True))
        boot["native"]["actualSearch"] = ["actual native search result"] * 15
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
