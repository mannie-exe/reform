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
SPEC = importlib.util.spec_from_file_location("tools.verify_distribution", TOOLS / "verify_distribution.py")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class DistributionRefusals(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="reform-artifact-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        # verifyDistribution already checks this POM against the selected Gradle version.
        # Negative controls must copy that build, including explicit -Pmod_version builds.
        pom = ROOT / "neoforge-1_21_1/build/publications/mavenJava/pom-default.xml"
        self.version = ET.parse(pom).findtext("{http://maven.apache.org/POM/4.0.0}version")
        if not self.version:
            self.fail("Built publication has no version")
        self.targets = VERIFY.load_targets(ROOT)
        paths = ["core/build/libs", "gradle"]
        for target in self.targets:
            paths.extend([f"{target['directory']}/build/libs", f"{target['directory']}/build/publications"])
        for name in paths:
            shutil.copytree(ROOT / name, self.root / name)
        self.binary = self.root / f"neoforge-1_21_1/build/libs/reform-{self.version}-mc1.21.1-neoforge.jar"

    def test_real_distribution_accepted(self):
        self.assertEqual(VERIFY.verify(self.root, self.version)["version"], self.version)

    def test_old_license_text_refused(self):
        with zipfile.ZipFile(self.binary) as archive:
            entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
        with zipfile.ZipFile(self.binary, "w") as archive:
            for entry, data in entries:
                archive.writestr(entry, b"MIT License\n" if entry.filename == "LICENSE" else data)
        with self.assertRaisesRegex(ValueError, "Apache-2.0 license text differs"):
            VERIFY.verify(self.root, self.version)

    def test_inconsistent_publication_license_refused(self):
        path = self.root / "neoforge-1_21_1/build/publications/mavenJava/pom-default.xml"
        tree = ET.parse(path)
        tree.getroot().find("{*}licenses/{*}license/{*}name").text = "MIT License"
        tree.write(path)
        with self.assertRaisesRegex(ValueError, "Publication Apache-2.0 license differs"):
            VERIFY.verify(self.root, self.version)

    def test_missing_portable_native_view_refused(self):
        with zipfile.ZipFile(self.binary) as archive:
            entries = [(entry, archive.read(entry)) for entry in archive.infolist()
                       if entry.filename != "design/inherent/reform/minecraft/client/SettingsScreen.class"]
        with zipfile.ZipFile(self.binary, "w") as archive:
            for entry, data in entries:
                archive.writestr(entry, data)
        with self.assertRaisesRegex(ValueError, "Missing shared native client API"):
            VERIFY.verify(self.root, self.version)

    def test_missing_owned_control_hook_refused(self):
        with zipfile.ZipFile(self.binary) as archive:
            entries = [(entry, archive.read(entry)) for entry in archive.infolist()
                       if entry.filename != "design/inherent/reform/minecraft/mixin/ControlStyleMixin.class"]
        with zipfile.ZipFile(self.binary, "w") as archive:
            for entry, data in entries:
                archive.writestr(entry, data)
        with self.assertRaisesRegex(ValueError, "Missing admitted native adapter: ControlStyleMixin"):
            VERIFY.verify(self.root, self.version)

    def test_chat_hook_cannot_become_a_common_side_mixin(self):
        import json
        with zipfile.ZipFile(self.binary) as archive:
            entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
        with zipfile.ZipFile(self.binary, "w") as archive:
            for entry, data in entries:
                if entry.filename == "reform.client.mixins.json":
                    value = json.loads(data)
                    value["mixins"] = value.pop("client")
                    data = json.dumps(value).encode("utf-8")
                archive.writestr(entry, data)
        with self.assertRaisesRegex(ValueError, "client-only admission"):
            VERIFY.verify(self.root, self.version)

    def test_missing_portable_server_owner_refused(self):
        with zipfile.ZipFile(self.binary) as archive:
            entries = [(entry, archive.read(entry)) for entry in archive.infolist()
                       if entry.filename != "design/inherent/reform/minecraft/configuration/ServerSettings.class"]
        with zipfile.ZipFile(self.binary, "w") as archive:
            for entry, data in entries:
                archive.writestr(entry, data)
        with self.assertRaisesRegex(ValueError, "Missing shared server API"):
            VERIFY.verify(self.root, self.version)

    def test_foreign_class_refused(self):
        with zipfile.ZipFile(self.binary, "a") as archive:
            archive.writestr("foreign/Injected.class", b"not admitted")
        with self.assertRaisesRegex(ValueError, "Foreign class"):
            VERIFY.verify(self.root, self.version)

    def test_unbounded_fml_family_refused(self):
        with zipfile.ZipFile(self.binary) as archive:
            entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
        with zipfile.ZipFile(self.binary, "w") as archive:
            for entry, data in entries:
                if entry.filename == "META-INF/neoforge.mods.toml":
                    data = data.replace(b'loaderVersion="[4,5)"', b'loaderVersion="[4,)"')
                archive.writestr(entry, data)
        with self.assertRaisesRegex(ValueError, "FML ABI family differs"):
            VERIFY.verify(self.root, self.version)

    def test_second_owned_runtime_artifact_refused(self):
        path = self.root / "neoforge-1_21_1/build/publications/mavenJava/pom-default.xml"
        tree = ET.parse(path)
        namespace = "{http://maven.apache.org/POM/4.0.0}"
        dependencies = tree.getroot().find(namespace + "dependencies")
        dependency = ET.SubElement(dependencies, namespace + "dependency")
        ET.SubElement(dependency, namespace + "groupId").text = "design.inherent"
        ET.SubElement(dependency, namespace + "artifactId").text = "reform-core"
        tree.write(path)
        with self.assertRaisesRegex(ValueError, "Owned-core"):
            VERIFY.verify(self.root, self.version)

    def test_missing_secondary_target_refused(self):
        target = self.targets[-1]
        path = self.root / target["directory"] / "build/libs" / VERIFY.artifact_names(target, self.version)["binary"]
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            VERIFY.verify(self.root, self.version)

    def test_swapped_loader_artifact_refused(self):
        fabric = next(t for t in self.targets if t["project"] == "fabric-1_21_1")
        path = self.root / fabric["directory"] / "build/libs" / VERIFY.artifact_names(fabric, self.version)["binary"]
        path.write_bytes(self.binary.read_bytes())
        with self.assertRaisesRegex(ValueError, "Foreign loader metadata"):
            VERIFY.verify(self.root, self.version)

    def test_swapped_minecraft26_3_artifact_refused(self):
        target_26_3 = next(t for t in self.targets if t["project"] == "neoforge-26_3")
        path = self.root / target_26_3["directory"] / "build/libs" / VERIFY.artifact_names(target_26_3, self.version)["binary"]
        path.write_bytes(self.binary.read_bytes())
        with self.assertRaisesRegex(ValueError, "native Java baseline"):
            VERIFY.verify(self.root, self.version)


if __name__ == "__main__":
    unittest.main()
