"""Resolve the checked game/loader matrix against its one version-catalog owner."""

import json
from pathlib import PurePosixPath
import re
import tomllib


def load_targets(root):
    matrix = json.loads((root / "gradle/minecraft-targets.json").read_text(encoding="utf-8"))
    if set(matrix) != {"schemaVersion", "targets"} or matrix["schemaVersion"] != 1:
        raise ValueError("Unsupported Minecraft target matrix")
    with (root / "gradle/libs.versions.toml").open("rb") as stream:
        pins = tomllib.load(stream)["versions"]
    result = []
    projects, directories, pairs = set(), set(), set()
    for raw in matrix["targets"]:
        lane = dict(raw)
        for key, reference in raw.items():
            if key.endswith("VersionRef"):
                if reference not in pins:
                    raise ValueError(f"Unknown target version reference: {reference}")
                lane[key[:-3]] = pins[reference]
        path = PurePosixPath(lane["directory"])
        if path.is_absolute() or len(path.parts) != 1 or not re.fullmatch(r"[a-z][a-z0-9_-]*", lane["directory"]):
            raise ValueError("Target directory must be one repository child")
        pair = (lane["minecraftVersion"], lane["loader"])
        expected_java = {"1.21.1": 21, "26.3": 25}.get(pair[0])
        if expected_java is None or lane["java"] != expected_java or pair[1] not in {"fabric", "neoforge"}:
            raise ValueError("Unsupported game, loader, or Java combination")
        if lane["project"] in projects or lane["directory"] in directories or pair in pairs:
            raise ValueError("Duplicate target identity or output directory")
        if lane["project"] != f"{lane['loader']}-{lane['minecraftVersion'].replace('.', '_')}":
            raise ValueError("Project identity differs from its native target")
        projects.add(lane["project"])
        directories.add(lane["directory"])
        pairs.add(pair)
        lane["artifactId"] = f"reform-{lane['loader']}-{lane['minecraftVersion']}"
        result.append(lane)
    expected = {(game, loader) for game in ("1.21.1", "26.3") for loader in ("neoforge", "fabric")}
    if pairs != expected:
        raise ValueError("The complete four-target matrix is required")
    return tuple(result)


def artifact_names(target, version):
    stem = f"reform-{version}-mc{target['minecraftVersion']}-{target['loader']}"
    return {"binary": stem + ".jar", "sources": stem + "-sources.jar", "pom": stem + ".pom"}


def release_assets(root, version):
    return {name for target in load_targets(root) for name in artifact_names(target, version).values()}
