"""Inspect all packaged targets; this does not qualify native runtime behavior."""

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

if __package__:
    from .targets import artifact_names, load_targets
else:
    from targets import artifact_names, load_targets

ROOT = Path(__file__).resolve().parents[1]
OWNED = "design/inherent/reform/"
APACHE_LICENSE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect_jar(path, native_major=65):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(not any(n > 1 for n in Counter(names).values()), f"Duplicate entries: {path}")
        require("LICENSE" in names and "NOTICE" in names, f"Missing license/notice: {path}")
        require(hashlib.sha256(archive.read("LICENSE")).hexdigest() == APACHE_LICENSE_SHA256,
                "Packaged Apache-2.0 license text differs")
        classes = {}
        for name in names:
            require(not name.endswith(".jar"), f"Unexpected bundled dependency: {name}")
            if name.endswith(".class"):
                require(name.startswith(OWNED), f"Foreign class: {name}")
                data = archive.read(name)
                magic, _, major = struct.unpack(">IHH", data[:8])
                require(magic == 0xCAFEBABE and major in {65, native_major},
                        f"Unexpected Java bytecode: {name}")
                classes[name] = data
        return names, classes


def metadata(archive, target, version):
    jei_minimum = target.get("jeiMinimumVersion", target["jeiVersion"])
    if target["loader"] == "neoforge":
        require("fabric.mod.json" not in archive.namelist(), "Foreign loader metadata")
        value = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        mod, = value["mods"]
        require((mod["modId"], mod["version"], mod["displayName"]) == ("reform", version, "re:form"),
                "Mod metadata identity/version differs")
        require(value["license"] == "Apache-2.0", "Unexpected mod license")
        require(value["loaderVersion"] == target["fmlVersionRange"], "FML ABI family differs")
        dependencies = value["dependencies"]["reform"]
        require({d["modId"] for d in dependencies} == ({"minecraft", "neoforge", "jei"} | ({"emi"} if target.get("emiVersion") else set())),
                "Unexpected mod dependency")
        require(all(d["type"] == "required" and d["side"] == "BOTH" for d in dependencies if d["modId"] not in {"jei", "emi"}),
                "Unexpected dependency authority/side")
        by_id = {d["modId"]: d for d in dependencies}
        require(by_id["jei"]["type"] == "optional" and by_id["jei"]["side"] == "CLIENT"
                and by_id["jei"]["versionRange"] == f"[{jei_minimum},{int(target['jeiVersion'].split('.')[0]) + 1})",
                "Optional JEI dependency authority/side/version differs")
        if target.get("emiVersion"):
            require(by_id["emi"]["type"] == "optional" and by_id["emi"]["side"] == "CLIENT"
                    and by_id["emi"]["versionRange"] == f"[{target['emiMinimumVersion']},{target['emiUpperBound']})",
                    "Optional EMI dependency authority/side/version differs")
        require(by_id["minecraft"]["versionRange"] == f"[{target['minecraftVersion']}]", "Minecraft pin differs")
        require(by_id["neoforge"]["versionRange"] ==
                f"[{target['loaderVersion']},{target['loaderUpperBound']})", "NeoForge baseline differs")
        require(value["features"]["reform"]["javaVersion"] == f"[{target['java']},)", "Java admission differs")
        require(value["mixins"] == [{"config": "reform.client.mixins.json"}], "Client invocation mixin admission differs")
        return OWNED + "neoforge/Reform"
    require("META-INF/neoforge.mods.toml" not in archive.namelist(), "Foreign loader metadata")
    value = json.loads(archive.read("fabric.mod.json"))
    require((value["id"], value["version"], value["name"]) == ("reform", version, "re:form"),
            "Mod metadata identity/version differs")
    require(value["license"] == "Apache-2.0" and value["environment"] == "*", "Fabric license/side differs")
    require(value["depends"] == {
        "fabricloader": ">=" + target["loaderVersion"],
        "fabric-api": ">=" + target["fabricApiVersion"],
        "minecraft": target["minecraftVersion"], "java": f">={target['java']}"
    }, "Fabric dependency admission differs")
    require(value["entrypoints"]["main"] == ["design.inherent.reform.fabric.ReformFabric"],
            "Fabric entry point differs")
    require(value["entrypoints"]["modmenu"] == ["design.inherent.reform.fabric.client.ReformModMenu"],
            "Optional Mod Menu entry point differs")
    require(value["entrypoints"].get("jei_mod_plugin") == ["design.inherent.reform.minecraft.optional.jei.ReformJeiPlugin"],
            "Optional JEI client plugin differs")
    require(value.get("breaks", {}).get("jei") == ["<" + jei_minimum, ">=" + str(int(target["jeiVersion"].split('.')[0]) + 1)],
            "Optional JEI API admission differs")
    if target.get("emiVersion"):
        require(value["entrypoints"].get("emi") == ["design.inherent.reform.minecraft.optional.emi.ReformEmiPlugin"]
                and value["breaks"].get("emi") == ["<" + target["emiMinimumVersion"], ">=" + target["emiUpperBound"]],
                "Optional EMI client admission differs")
    else:
        require("emi" not in value["entrypoints"] and "emi" not in value["breaks"], "Unselected EMI adapter admitted")
    require(value["mixins"] == ["reform.client.mixins.json"], "Client invocation mixin admission differs")
    declaration = archive.read(value["accessWidener"]).decode("utf-8")
    namespace = target["productionNamespace"]
    require(declaration.splitlines()[0].split() == ["accessWidener", "v2", namespace],
            "Fabric artifact has the wrong native mapping namespace")
    return OWNED + "fabric/ReformFabric"


def verify(root, version):
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version), "Invalid version")
    core = root / f"core/build/libs/reform-core-{version}.jar"
    _, core_classes = inspect_jar(core)
    for name, data in core_classes.items():
        require(not any(part in name for part in ("/neoforge/", "/minecraft/", "/fabric/", "/client/")),
                f"Platform class in core: {name}")
        require(not any(prefix in data for prefix in (b"net/minecraft/", b"net/neoforged/", b"net/fabricmc/")),
                f"Platform dependency in core: {name}")
    files, results = {str(core.relative_to(root)): hashlib.sha256(core.read_bytes()).hexdigest()}, []
    for target in load_targets(root):
        names_by_role = artifact_names(target, version)
        product = root / target["directory"] / "build"
        binary = product / "libs" / names_by_role["binary"]
        sources = product / "libs" / names_by_role["sources"]
        pom = product / "publications/mavenJava/pom-default.xml"
        native_major = target["java"] + 44
        names, classes = inspect_jar(binary, native_major)
        require(not any("/test/" in n or "/probe/" in n for n in names), "Test/probe content in mod")
        for name, data in core_classes.items():
            require(classes.get(name) == data, f"Core class absent/changed in combined JAR: {name}")
        for name, data in classes.items():
            if b"dev/emi/" in data:
                require(target.get("emiVersion") and name.startswith(OWNED + "minecraft/optional/emi/"), "EMI linkage outside selected optional client adapter")
            if b"mezz/jei/" in data:
                require(name.startswith(OWNED + "minecraft/optional/jei/"), "JEI linkage outside optional client adapter")
            require(name in core_classes or struct.unpack(">H", data[6:8])[0] == native_major,
                    f"Wrong native Java baseline: {name}")
        with zipfile.ZipFile(binary) as archive:
            entry = metadata(archive, target, version)
            mixins = json.loads(archive.read("reform.client.mixins.json"))
        require(mixins == {"required": True, "minVersion": "0.8",
                           "package": "design.inherent.reform.minecraft.mixin",
                           "compatibilityLevel": f"JAVA_{target['java']}",
                           "client": ["ChatInputMixin", "ControlStyleMixin", "ScreenMountStateAccessor", "ControlCaptionMixin", "ControlInputFontMixin"] + (["ControlInputColorMixin", "GraphicShaderStateAccessor"] if target["minecraftVersion"] == "1.21.1" else []) + ["GraphicRuntimeMixin", "LoadedGraphicResourcesMixin", "GraphicSpriteContentsAccessor", "GraphicGuiRendererMixin", "GraphicStateMixin"] + (["GraphicCanvasAccessor", "GraphicBlockResolverAccessor", "GraphicEquipmentAssetsAccessor"] if target["minecraftVersion"] == "26.3" else []) + ["RecipeBookButtonSpritesAccessor"], "injectors": {"defaultRequire": 1}},
                "Invocation hook must retain its actual game Java target and client-only admission")
        for mixin in mixins["client"]:
            require(OWNED + f"minecraft/mixin/{mixin}.class" in classes,
                    f"Missing admitted native adapter: {mixin}")
        require(entry + ".class" in classes, "Missing loader entry point")
        require(OWNED + "minecraft/command/SettingsCommands.class" in classes, "Missing shared command API")
        references = OWNED + "minecraft/reference/NativeReferences.class"
        require(references in classes, "Missing shared native reference API")
        require(not any(prefix in classes[references] for prefix in (b"net/neoforged/", b"net/fabricmc/")),
                "Loader-specific type in portable reference API")
        for api in ("ServerSettings", "ServerRegistration", "ServerPolicyCommands"):
            path = OWNED + f"minecraft/configuration/{api}.class"
            require(path in classes, "Missing shared server API: " + api)
            require(not any(prefix in classes[path] for prefix in (b"net/neoforged/", b"net/fabricmc/")),
                    "Loader-specific type in portable server API: " + api)
        for api in ("SettingsScreen", "ClientFileSettings", "ClientKeyBindings", "KeyBindingOptions",
                    "KeyBindingScreen", "ClientForm", "ClientCommands", "IntegratedSettings", "WorldRecipe"):
            path = OWNED + f"minecraft/client/{api}.class"
            require(path in classes, "Missing shared native client API: " + api)
            require(not any(prefix in classes[path] for prefix in (b"net/neoforged/", b"net/fabricmc/")),
                    "Loader-specific type in portable client API: " + api)
        if target["loader"] == "fabric":
            require(not any(name.startswith(OWNED + "neoforge/") for name in classes),
                    "NeoForge source in Fabric artifact")
            for name, data in classes.items():
                if b"com/terraformersmc/modmenu/" in data:
                    require(name == OWNED + "fabric/client/ReformModMenu.class",
                            "Optional Mod Menu linkage outside its entry point")
        else:
            require(not any(name.startswith(OWNED + "fabric/") for name in classes),
                    "Fabric source in NeoForge artifact")
            require(OWNED + "neoforge/client/ClientRegisteredSettings.class" in classes,
                    "Missing native registered-config client adapter")
        if target["remapMinecraft"]:
            bridge = classes[OWNED + "minecraft/command/NativeCommandSupport.class"]
            require(b"net/minecraft/commands/CommandSourceStack" not in bridge,
                    "Named development classes in remapped Fabric artifact")
        with zipfile.ZipFile(sources) as archive:
            source_names = archive.namelist()
            require(not any(n > 1 for n in Counter(source_names).values()), "Duplicate source entries")
            require(entry + ".java" in source_names, "Missing platform source")
            require(OWNED + "package-info.java" in source_names, "Missing core source")
            require(not any(n.endswith((".class", ".jar")) for n in source_names), "Binary content in sources")
            require("LICENSE" in source_names and "NOTICE" in source_names, "Missing source licensing")
            require(hashlib.sha256(archive.read("LICENSE")).hexdigest() == APACHE_LICENSE_SHA256,
                    "Source Apache-2.0 license text differs")
        xml = ET.parse(pom).getroot()
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        licenses = xml.findall("m:licenses/m:license", ns)
        require(len(licenses) == 1
                and licenses[0].findtext("m:name", namespaces=ns) == "Apache License, Version 2.0"
                and licenses[0].findtext("m:url", namespaces=ns) == "https://www.apache.org/licenses/LICENSE-2.0.txt",
                "Publication Apache-2.0 license differs")
        require([xml.findtext(f"m:{k}", namespaces=ns) for k in ("groupId", "artifactId", "version")]
                == ["design.inherent", target["artifactId"], version], "Publication identity differs")
        dependencies = xml.findall("m:dependencies/m:dependency", ns)
        by_id = {(d.findtext("m:groupId", namespaces=ns), d.findtext("m:artifactId", namespaces=ns)): d
                 for d in dependencies}
        require(len(by_id) == len(dependencies), "Duplicate dependency metadata")
        for coordinate, pin in ((('com.mojang', 'datafixerupper'), target['dfuVersion']),
                                (('com.google.code.gson', 'gson'), target['gsonVersion'])):
            dependency = by_id.get(coordinate)
            require(dependency is not None, "Missing public codec/JSON dependency metadata")
            require(dependency.findtext("m:scope", namespaces=ns) == "compile", "Public codec/JSON types need compile scope")
            require(dependency.findtext("m:version", namespaces=ns) == pin, "Game serialization dependency differs")
        require(not any(g == "design.inherent" or g.startswith(("org.junit", "net.jqwik", "com.diffplug"))
                        for g, _ in by_id), "Owned-core or build/test runtime dependency in POM")
        for path in (binary, sources, pom):
            files[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
        results.append({"project": target["project"], "nativeClasses": len(classes) - len(core_classes)})
    return {"version": version, "targets": results, "coreClasses": len(core_classes), "files": files}


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
