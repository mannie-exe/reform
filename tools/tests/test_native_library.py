"""Candidate selection is independent of the unbuilt development version in gradle.properties."""

import hashlib
from pathlib import Path
import shutil
import tempfile
import unittest

from tools.native_library import select_library

ROOT = Path(__file__).resolve().parents[2]


class NativeLibrarySelection(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="reform-native-selection-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        shutil.copytree(ROOT / "gradle", self.root / "gradle",
                        ignore=shutil.ignore_patterns("wrapper"))
        self.product = self.root / "neoforge-1_21_1/build"
        selected = self.product / "publications/mavenJava/pom-default.xml"
        selected.parent.mkdir(parents=True)
        self.pom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>design.inherent</groupId><artifactId>reform-neoforge-1.21.1</artifactId><version>0.2.0-dev.11</version></project>'
        selected.write_text(self.pom)
        self.staging = self.root / "build/repository/design/inherent/reform-neoforge-1.21.1/0.2.0-dev.11"
        self.staging.mkdir(parents=True)
        self.staged_pom = self.staging / "reform-neoforge-1.21.1-0.2.0-dev.11.pom"
        self.staged_pom.write_text(self.pom)
        self.staged_jar = self.staging / "reform-neoforge-1.21.1-0.2.0-dev.11.jar"
        self.staged_jar.write_bytes(b"candidate-selection fixture")
        jar = self.product / "libs/reform-0.2.0-dev.11-mc1.21.1-neoforge.jar"
        jar.parent.mkdir(parents=True)
        jar.write_bytes(self.staged_jar.read_bytes())

    def test_unbuilt_development_version_does_not_replace_selected_candidate(self):
        (self.root / "gradle.properties").write_text("mod_version=0.2.0-SNAPSHOT\n")
        selected = select_library(self.root, "neoforge-1_21_1")
        self.assertEqual(selected.version, "0.2.0-dev.11")
        self.assertEqual(selected.sha256, hashlib.sha256(self.staged_jar.read_bytes()).hexdigest())

    def test_explicit_frozen_candidate_survives_another_selected_build(self):
        (self.product / "publications/mavenJava/pom-default.xml").write_text(
            self.pom.replace("0.2.0-dev.11", "0.2.0-dev.12"))
        selected = select_library(self.root, "neoforge-1_21_1", "0.2.0-dev.11")
        self.assertEqual(selected.version, "0.2.0-dev.11")

    def test_default_snapshot_requires_a_frozen_candidate_before_staging_lookup(self):
        (self.product / "publications/mavenJava/pom-default.xml").write_text(
            self.pom.replace("0.2.0-dev.11", "0.2.0-SNAPSHOT"))
        with self.assertRaisesRegex(ValueError, "immutable library version.*-Pmod_version=.*--library-version"):
            select_library(self.root, "neoforge-1_21_1")

    def test_explicit_snapshot_requires_a_frozen_candidate_before_staging_lookup(self):
        with self.assertRaisesRegex(ValueError, "immutable library version"):
            select_library(self.root, "neoforge-1_21_1", "0.2.0-SNAPSHOT")

    def test_staged_loader_identity_must_match(self):
        self.staged_pom.write_text(self.pom.replace("reform-neoforge-1.21.1", "reform-fabric-1.21.1"))
        with self.assertRaisesRegex(ValueError, "identity differs"):
            select_library(self.root, "neoforge-1_21_1")

    def test_staged_and_built_bytes_cannot_diverge(self):
        self.staged_jar.write_bytes(b"different candidate")
        with self.assertRaisesRegex(ValueError, "differs from the selected"):
            select_library(self.root, "neoforge-1_21_1")

    def test_version_cannot_be_a_path(self):
        with self.assertRaisesRegex(ValueError, "built library version"):
            select_library(self.root, "neoforge-1_21_1", "../outside")


if __name__ == "__main__":
    unittest.main()
