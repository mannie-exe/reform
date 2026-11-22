"""The opt-in OS driver must not confuse a different Java application with its fixture."""

from types import SimpleNamespace
import json
import unittest
from unittest.mock import patch

from tools import native_chooser_driver as driver


class ChooserIdentityTests(unittest.TestCase):
    def test_different_native_application_is_refused(self):
        reply = SimpleNamespace(returncode=0, stdout=json.dumps(
            {"status": "performed", "pid": 202, "window": "Import profile"}), stderr="")
        with patch.object(driver.subprocess, "run", return_value=reply):
            with self.assertRaisesRegex(RuntimeError, "different application identity"):
                driver.ax_action(101, "visible", "")

    def test_absence_is_not_an_action_receipt(self):
        reply = SimpleNamespace(returncode=2, stdout='{"status":"absent"}', stderr="")
        with patch.object(driver.subprocess, "run", return_value=reply):
            self.assertIsNone(driver.ax_action(101, "visible", ""))

    def test_owned_native_application_returns_its_observation(self):
        observed = {"status": "performed", "pid": 101, "role": "AXSheet", "window": ""}
        reply = SimpleNamespace(returncode=0, stdout=json.dumps(observed), stderr="")
        with patch.object(driver.subprocess, "run", return_value=reply):
            self.assertEqual(observed, driver.ax_action(101, "visible", ""))

    def test_jvm_working_directory_guard_precedes_native_ui(self):
        with patch.object(driver, "children", side_effect=RuntimeError("Not the owned fixture")), \
                patch.object(driver, "ax_action") as native:
            with self.assertRaisesRegex(RuntimeError, "Not the owned fixture"):
                driver.dialog(101, sdl=True)
            native.assert_not_called()

    def test_manual_witness_keeps_the_same_native_observation(self):
        observed = {"status": "performed", "pid": 101, "role": "AXSheet", "window": ""}
        with patch.object(driver, "children", return_value=[]), \
                patch.object(driver, "ax_action", return_value=observed) as native:
            self.assertEqual((101, observed), driver.dialog(101, sdl=True))
            native.assert_called_once_with(101, "visible", "", "Open")

    def test_unrelated_java_directory_is_refused(self):
        def output(command, **_):
            return "101 0 java\n202 101 osascript\n" if command[0] == "ps" else "p101\nfcwd\nn/unrelated/client\n"
        with patch.object(driver.subprocess, "check_output", side_effect=output):
            with self.assertRaisesRegex(RuntimeError, "not the owned fixture"):
                driver.children(101)

    def test_native_child_belongs_to_the_exact_fixture_jvm(self):
        def output(command, **_):
            return "101 0 java\n202 101 osascript\n303 0 osascript\n" if command[0] == "ps" else "p101\nfcwd\nn" + str(driver.FIXTURE.resolve()) + "\n"
        with patch.object(driver.subprocess, "check_output", side_effect=output):
            self.assertEqual([202], driver.children(101))

    def test_unconfirmed_cancel_needs_a_closed_owned_dialog(self):
        before = {"status": "performed", "pid": 202, "window": "Choose a File"}
        error = driver.UnconfirmedPress({"status": "unconfirmed", "pid": 202,
                                         "action": "cancel", "axStatus": -25204})
        with patch.object(driver, "operate", side_effect=error), \
                patch.object(driver, "dialog", return_value=None) as observed:
            receipt = driver.confirmed_operation(101, (202, before), "cancel", "")
            self.assertEqual(-25204, receipt["unconfirmedPress"]["axStatus"])
            observed.assert_called_once_with(101, False)

    def test_still_present_dialog_earns_no_cancel_receipt(self):
        error = driver.UnconfirmedPress({"axStatus": -25204})
        with patch.object(driver, "operate", side_effect=error), \
                patch.object(driver, "dialog", return_value=(202, {})), \
                patch.object(driver.time, "monotonic", side_effect=[0, 1, 4]), \
                patch.object(driver.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "left the owned dialog present"):
                driver.confirmed_operation(101, (202, {}), "cancel", "")

    def test_unconfirmed_response_cannot_bypass_identity_action_or_error_checks(self):
        original = {"status": "unconfirmed", "pid": 101, "action": "cancel", "axStatus": -25204}
        for key, value in [("pid", 202), ("action", "select"), ("axStatus", -25202),
                           ("status", "performed")]:
            with self.subTest(key=key):
                reply = SimpleNamespace(returncode=3, stdout=json.dumps(dict(original, **{key: value})), stderr="")
                with patch.object(driver.subprocess, "run", return_value=reply):
                    with self.assertRaisesRegex(RuntimeError, "without an admitted closure check"):
                        driver.ax_action(101, "cancel", "")
        reply = SimpleNamespace(returncode=3, stdout=json.dumps(original), stderr="")
        with patch.object(driver.subprocess, "run", return_value=reply):
            with self.assertRaises(driver.UnconfirmedPress):
                driver.ax_action(101, "cancel", "")
