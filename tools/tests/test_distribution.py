"""Negative controls against copies of the actual built distribution."""

import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parent
SPEC = importlib.util.spec_from_file_location("reform_distribution", TOOLS / "verify_distribution.py")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class DistributionRefusals(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="reform-artifact-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                          if "=" in line)
        self.version = properties["mod_version"]
        for name in ["core/build/libs", "neoforge/build/libs", "neoforge/build/publications", "gradle"]:
            shutil.copytree(ROOT / name, self.root / name)
        self.binary = self.root / f"neoforge/build/libs/reform-{self.version}-mc1.21.1-neoforge.jar"

    def test_real_distribution_accepted(self):
        self.assertEqual(VERIFY.verify(self.root, self.version)["version"], self.version)

    def test_foreign_class_refused(self):
        with zipfile.ZipFile(self.binary, "a") as archive:
            archive.writestr("foreign/Injected.class", b"not admitted")
        with self.assertRaisesRegex(ValueError, "Foreign class"):
            VERIFY.verify(self.root, self.version)

    def test_second_owned_runtime_artifact_refused(self):
        path = self.root / "neoforge/build/publications/mavenJava/pom-default.xml"
        tree = ET.parse(path)
        namespace = "{http://maven.apache.org/POM/4.0.0}"
        dependencies = tree.getroot().find(namespace + "dependencies")
        dependency = ET.SubElement(dependencies, namespace + "dependency")
        ET.SubElement(dependency, namespace + "groupId").text = "design.inherent"
        ET.SubElement(dependency, namespace + "artifactId").text = "reform-core"
        tree.write(path)
        with self.assertRaisesRegex(ValueError, "Owned-core"):
            VERIFY.verify(self.root, self.version)


if __name__ == "__main__":
    unittest.main()
