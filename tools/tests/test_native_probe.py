"""Fixture cleanup boundaries. Native callback behavior is tested only by the actual game run."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("reform_native_probe", Path(__file__).resolve().parents[1] / "native_config_probe.py")
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


class NativeFixtureGuards(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="reform-native-tool-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_existing_fixture_is_never_adopted_or_overwritten(self):
        fixture = self.root / "neoforge/runs/native-config"
        fixture.mkdir(parents=True)
        marker = fixture / "personal-data"
        marker.write_text("retained")
        with self.assertRaises(FileExistsError):
            PROBE.create_fixture(self.root, "new-token")
        self.assertEqual(marker.read_text(), "retained")
        self.assertFalse((fixture / PROBE.MARKER).exists())

    def test_cleanup_requires_the_exact_task_token(self):
        fixture = PROBE.create_fixture(self.root, "own-token")
        with self.assertRaisesRegex(ValueError, "ownership"):
            PROBE.cleanup_fixture(self.root, "foreign-token")
        self.assertTrue((fixture / "eula.txt").exists())
        PROBE.cleanup_fixture(self.root, "own-token")
        self.assertFalse(fixture.exists())
