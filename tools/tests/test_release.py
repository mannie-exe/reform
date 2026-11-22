"""Release tooling controls; no GitHub requests or personal repositories."""

import hashlib
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("tools.release", TOOLS / "release.py")
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)


class ReleaseStaging(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="reform-release-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.addCleanup(patch.stopall)
        patch.object(RELEASE, "ROOT", self.root).start()
        self.command("init", "-q")
        (self.root / ".gitattributes").write_bytes(b"* text=auto eol=lf\n")
        (self.root / ".gitignore").write_text("build/\n")
        (self.root / "gradle.properties").write_bytes(b"mod_version=1.2.3\n")
        (self.root / "gradle").mkdir()
        for name in ("minecraft-targets.json", "libs.versions.toml"):
            shutil.copy2(TOOLS.parent / "gradle" / name, self.root / "gradle" / name)
        self.command("add", ".")
        self.command("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                     "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")
        self.command("tag", "v1.2.3")
        self.files = {name: ("fixture " + name).encode("utf-8")
                      for name in RELEASE.release_assets(self.root, "1.2.3")}
        for runner in RELEASE.RUNNERS:
            path = self.root / "build/ci" / ("build-" + runner)
            path.mkdir(parents=True)
            for name, data in self.files.items():
                (path / name).write_bytes(data)

    def command(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.STDOUT)

    def test_identical_artifacts_source_and_manifests(self):
        path = RELEASE.stage("v1.2.3")
        for name, data in self.files.items():
            self.assertEqual((path / name).read_bytes(), data)
        with zipfile.ZipFile(path / "reform-1.2.3-source.zip") as archive:
            self.assertEqual(archive.read("reform-1.2.3/gradle.properties"), b"mod_version=1.2.3\n")
        for line in (path / "SHA256SUMS").read_text().splitlines():
            digest, name = line.split("  ", 1)
            self.assertEqual(digest, hashlib.sha256((path / name).read_bytes()).hexdigest())
        with self.assertRaises(FileExistsError):
            RELEASE.stage("v1.2.3")

    def test_runner_difference_refused_before_staging(self):
        changed = self.root / "build/ci/build-windows-2025/reform-1.2.3-mc1.21.1-neoforge-sources.jar"
        changed.write_bytes(b"different")
        with self.assertRaisesRegex(ValueError, "Non-reproducible"):
            RELEASE.stage("v1.2.3")
        self.assertFalse((self.root / "build/release").exists())

    def test_missing_artifact_refused(self):
        (self.root / "build/ci/build-ubuntu-24.04/reform-1.2.3-mc1.21.1-neoforge.pom").unlink()
        with self.assertRaisesRegex(ValueError, "missing CI"):
            RELEASE.stage("v1.2.3")

    def test_dirty_source_refused(self):
        (self.root / ".gitignore").write_text("build/\nother/\n")
        with self.assertRaisesRegex(ValueError, "clean"):
            RELEASE.stage("v1.2.3")

    def test_snapshot_tag_refused(self):
        with self.assertRaisesRegex(ValueError, "without a snapshot"):
            RELEASE.stage("v1.2.3-SNAPSHOT")


if __name__ == "__main__":
    unittest.main()
