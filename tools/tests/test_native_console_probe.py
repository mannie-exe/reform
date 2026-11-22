"""Console shutdown is actual input; clean process exit alone is not native qualification."""

from pathlib import Path
import os
import sys
import tempfile
import unittest

from tools.native_config_probe import execute
from tools.native_bare_probe import successful_boot, verify_fixture_network
from tools.native_form_probe import create_fixture, cleanup_fixture


@unittest.skipUnless(os.name == "posix", "Native fixture execution requires POSIX process groups")
class NativeConsoleExecutionTests(unittest.TestCase):
    def test_actual_child_receives_stop_only_after_readiness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "console.log"
            def ready(_, output):
                return {"status": "PASS"} if "READY" in output.read_text() else None
            result = execute([sys.executable, "-u", "-c",
                              'print("READY", flush=True); assert input() == "stop"; print("STOPPED", flush=True)'],
                             root, log, 5, ready_console=ready)
            self.assertTrue(successful_boot(result, []), result)
            self.assertIn("STOPPED", log.read_text())

    def test_console_qualification_failure_stops_owned_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def failed(_, output):
                if "READY" in output.read_text():
                    raise ValueError("controlled native refusal")
            result = execute([sys.executable, "-u", "-c", 'print("READY", flush=True); input()'],
                             root, root / "console.log", 5, ready_console=failed)
            self.assertIn("native console qualification failed", result["termination"])
            self.assertFalse(successful_boot(result, []))

    def test_early_clean_exit_does_not_qualify(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = execute([sys.executable, "-c", "pass"], root, root / "console.log", 5,
                             ready_console=lambda *_: None)
            self.assertEqual(0, result["exitCode"])
            self.assertFalse(successful_boot(result, []))


class NativeConsoleProbeTests(unittest.TestCase):
    def test_new_library_settings_fail_even_with_a_native_receipt(self):
        result = {"exitCode": 0, "termination": None, "nativeConsole": {"status": "PASS"}}
        self.assertTrue(successful_boot(result, []))
        self.assertFalse(successful_boot(result, ["config/reform/preferences.json"]))

    def test_dedicated_factory_cannot_silently_use_vanilla_network_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "owned"
            create_fixture(fixture, "console-test", client=False)
            try:
                verify_fixture_network(fixture)
                properties = fixture / "server.properties"
                original = properties.read_text()
                for key, unsafe in [("server-ip", ""), ("server-port", "25565"),
                                    ("enable-rcon", "true"), ("enable-query", "true")]:
                    with self.subTest(key=key):
                        lines = original.splitlines()
                        properties.write_text("\n".join(key + "=" + unsafe if line.startswith(key + "=") else line
                                                        for line in lines) + "\n")
                        with self.assertRaisesRegex(ValueError, "requires loopback"):
                            verify_fixture_network(fixture)
                properties.unlink()
                with self.assertRaises(FileNotFoundError):
                    verify_fixture_network(fixture)
            finally:
                cleanup_fixture(fixture, "console-test")
