"""The GUI runner may remove only the disposable tree it created, even after a failed boot."""

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
CONFIG_SPEC = importlib.util.spec_from_file_location("native_config_probe", TOOLS / "native_config_probe.py")
CONFIG = importlib.util.module_from_spec(CONFIG_SPEC)
CONFIG_SPEC.loader.exec_module(CONFIG)
SPEC = importlib.util.spec_from_file_location("reform_form_probe", TOOLS / "native_form_probe.py")
PROBE = importlib.util.module_from_spec(SPEC)
with patch.dict(sys.modules, {"native_config_probe": CONFIG}):
    SPEC.loader.exec_module(PROBE)


class NativeFormFixtureGuards(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="reform-form-tool-test-")
        self.addCleanup(temporary.cleanup)
        self.fixture = Path(temporary.name) / "runs/client"

    def test_existing_client_is_never_adopted(self):
        self.fixture.mkdir(parents=True)
        existing = self.fixture / "options.txt"
        existing.write_text("retained")
        with self.assertRaises(FileExistsError):
            PROBE.create_fixture(self.fixture, "own-token")
        self.assertEqual(existing.read_text(), "retained")
        self.assertFalse((self.fixture / PROBE.MARKER).exists())

    def test_cleanup_requires_the_original_ownership_token(self):
        PROBE.create_fixture(self.fixture, "own-token")
        with self.assertRaisesRegex(ValueError, "ownership"):
            PROBE.cleanup_fixture(self.fixture, "foreign-token")
        self.assertTrue((self.fixture / "options.txt").is_file())
        PROBE.cleanup_fixture(self.fixture, "own-token")
        self.assertFalse(self.fixture.exists())
