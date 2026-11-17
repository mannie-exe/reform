"""Inspect built artifacts; this does not qualify native runtime behavior."""

from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import tomllib
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OWNED = "design/inherent/reform/"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect_jar(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(not any(n > 1 for n in Counter(names).values()), f"Duplicate entries: {path}")
        require("LICENSE" in names and "NOTICE" in names, f"Missing license/notice: {path}")
        classes = {}
        for name in names:
            require(not name.endswith(".jar"), f"Unexpected bundled dependency: {name}")
            if name.endswith(".class"):
                require(name.startswith(OWNED), f"Foreign class: {name}")
                data = archive.read(name)
                magic, _, major = struct.unpack(">IHH", data[:8])
                require(magic == 0xCAFEBABE and major == 65, f"Not Java 21 bytecode: {name}")
                classes[name] = data
        return names, classes


def verify(root, version):
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version), "Invalid version")
    with (root / "gradle/libs.versions.toml").open("rb") as stream:
        pins = tomllib.load(stream)["versions"]
    platform = root / "neoforge/build/libs"
    binary = platform / f"reform-{version}-mc{pins['minecraft']}-neoforge.jar"
    sources = platform / f"reform-{version}-sources.jar"
    core = root / f"core/build/libs/reform-core-{version}.jar"
    names, classes = inspect_jar(binary)
    _, core_classes = inspect_jar(core)
    require(OWNED + "neoforge/Reform.class" in classes, "Missing loader entry point")
    require(not any("/test/" in n or "/probe/" in n for n in names), "Test/probe content in mod")
    for name, data in core_classes.items():
        require("/neoforge/" not in name and "/client/" not in name, f"Platform class in core: {name}")
        require(b"net/minecraft/" not in data and b"net/neoforged/" not in data,
                f"Platform dependency in core: {name}")
        require(classes.get(name) == data, f"Core class absent/changed in combined JAR: {name}")
    with zipfile.ZipFile(binary) as archive:
        metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
    mod, = metadata["mods"]
    require((mod["modId"], mod["version"], mod["displayName"]) == ("reform", version, "re:form"),
            "Mod metadata identity/version differs")
    require(metadata["license"] == "MIT", "Unexpected mod license")
    dependencies = metadata["dependencies"]["reform"]
    require({d["modId"] for d in dependencies} == {"minecraft", "neoforge"}, "Unexpected mod dependency")
    require(all(d["type"] == "required" and d["side"] == "BOTH" for d in dependencies),
            "Unexpected dependency authority/side")
    by_id = {d["modId"]: d for d in dependencies}
    require(by_id["minecraft"]["versionRange"] == f"[{pins['minecraft']}]", "Minecraft pin differs")
    require(by_id["neoforge"]["versionRange"] == f"[{pins['neoforge']},21.2)", "NeoForge baseline differs")
    with zipfile.ZipFile(sources) as archive:
        source_names = archive.namelist()
        require(not any(n > 1 for n in Counter(source_names).values()), "Duplicate source entries")
        require(OWNED + "neoforge/Reform.java" in source_names, "Missing platform source")
        require(OWNED + "package-info.java" in source_names, "Missing core source")
        require(not any(n.endswith((".class", ".jar")) for n in source_names), "Binary content in sources")
        require("LICENSE" in source_names and "NOTICE" in source_names, "Missing source licensing")
    pom = root / "neoforge/build/publications/mavenJava/pom-default.xml"
    xml = ET.parse(pom).getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    require([xml.findtext(f"m:{k}", namespaces=ns) for k in ["groupId", "artifactId", "version"]]
            == ["design.inherent", "reform", version], "Publication identity differs")
    published = {(d.findtext("m:groupId", namespaces=ns), d.findtext("m:artifactId", namespaces=ns))
                 for d in xml.findall("m:dependencies/m:dependency", ns)}
    require(("com.mojang", "datafixerupper") in published, "Missing public codec dependency metadata")
    require(("com.google.code.gson", "gson") in published, "Missing JSON dependency metadata")
    for dependency in xml.findall("m:dependencies/m:dependency", ns):
        if dependency.findtext("m:artifactId", namespaces=ns) in {"datafixerupper", "gson"}:
            require(dependency.findtext("m:scope", namespaces=ns) == "compile", "Public codec/JSON types need compile-scope dependencies")
    require(not any(g == "design.inherent" or g.startswith(("org.junit", "net.jqwik", "com.diffplug"))
                    for g, _ in published), "Owned-core or build/test runtime dependency in POM")
    return {"version": version, "platformClasses": len(classes), "coreClasses": len(core_classes),
            "files": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in [binary, sources, core, pom]}}


def main():
    try:
        require(len(sys.argv) == 2, "Usage: verify_distribution.py VERSION")
        print(json.dumps(verify(ROOT, sys.argv[1]), indent=2))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, ET.ParseError) as error:
        print(f"Distribution check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
