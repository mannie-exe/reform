"""Target identity and output-boundary controls without native launches."""

import json
from pathlib import Path
import shutil
import tempfile
import unittest

from tools.targets import artifact_names, load_targets, release_assets

ROOT = Path(__file__).resolve().parents[2]


class TargetMatrix(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="reform-targets-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "gradle").mkdir()
        for name in ("minecraft-targets.json", "libs.versions.toml"):
            shutil.copy2(ROOT / "gradle" / name, self.root / "gradle" / name)
        self.path = self.root / "gradle/minecraft-targets.json"
        self.matrix = json.loads(self.path.read_text())

    def write(self):
        self.path.write_text(json.dumps(self.matrix))

    def test_all_four_products_have_distinct_delivery_names(self):
        targets = load_targets(self.root)
        self.assertEqual(len(targets), 4)
        self.assertEqual(len(release_assets(self.root, "0.2.0")), 12)
        for lane in targets:
            self.assertIn(lane["minecraftVersion"], artifact_names(lane, "0.2.0")["binary"])

    def test_missing_lane_is_not_a_complete_matrix(self):
        self.matrix["targets"].pop()
        self.write()
        with self.assertRaisesRegex(ValueError, "complete four-target"):
            load_targets(self.root)

    def test_colliding_output_directories_are_refused(self):
        self.matrix["targets"][1]["directory"] = self.matrix["targets"][0]["directory"]
        self.write()
        with self.assertRaisesRegex(ValueError, "Duplicate target"):
            load_targets(self.root)

    def test_output_paths_cannot_escape_the_repository(self):
        for directory in ("../outside", "/tmp/outside", "C:outside", "nested/output"):
            with self.subTest(directory=directory):
                self.matrix["targets"][0]["directory"] = directory
                self.write()
                with self.assertRaisesRegex(ValueError, "repository child"):
                    load_targets(self.root)

    def test_unknown_version_reference_is_refused(self):
        self.matrix["targets"][0]["loaderVersionRef"] = "invented"
        self.write()
        with self.assertRaisesRegex(ValueError, "Unknown target version"):
            load_targets(self.root)

    def test_minecraft26_3_product_cannot_advertise_java21(self):
        self.matrix["targets"][2]["java"] = 21
        self.write()
        with self.assertRaisesRegex(ValueError, "Java combination"):
            load_targets(self.root)

    def test_product_name_cannot_hide_a_different_loader(self):
        self.matrix["targets"][0]["project"] = "fabric-1_21_1"
        self.write()
        with self.assertRaisesRegex(ValueError, "Project identity"):
            load_targets(self.root)


if __name__ == "__main__":
    unittest.main()
