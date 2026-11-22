"""Select one built, staged public artifact without guessing from a development version."""

from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
import xml.etree.ElementTree as ET

if __package__:
    from .targets import artifact_names, load_targets
else:
    from targets import artifact_names, load_targets


@dataclass(frozen=True)
class NativeLibrary:
    project: str
    module: str
    version: str
    jar: Path
    sha256: str


def select_library(root, project, version=None):
    target = next((target for target in load_targets(root) if target["project"] == project), None)
    if target is None:
        raise ValueError("Select an admitted native target")
    product = root / target["directory"] / "build"
    ns = "{http://maven.apache.org/POM/4.0.0}"
    selected = product / "publications/mavenJava/pom-default.xml"
    if version is None:
        version = ET.parse(selected).findtext(ns + "version")
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version):
        raise ValueError("Select a built library version")
    if version.endswith("-SNAPSHOT"):
        raise ValueError("Native qualification requires an immutable library version; build a frozen candidate "
                         "with -Pmod_version=<version>, then select it with --library-version <version>")
    repository = root / "build/repository/design/inherent" / target["artifactId"] / version
    stem = target["artifactId"] + "-" + version
    pom = ET.parse(repository / (stem + ".pom")).getroot()
    if tuple(pom.findtext(ns + field) for field in ("groupId", "artifactId", "version")) != (
            "design.inherent", target["artifactId"], version):
        raise ValueError("Staged library identity differs from the selected target")
    jar = product / "libs" / artifact_names(target, version)["binary"]
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if hashlib.sha256((repository / (stem + ".jar")).read_bytes()).hexdigest() != digest:
        raise ValueError("Staged Maven library differs from the selected packaged product")
    return NativeLibrary(project, target["artifactId"], version, jar, digest)
