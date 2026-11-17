"""Stage identical CI artifacts and optionally create a draft release, never publish."""

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
RUNNERS = ("ubuntu-24.04", "windows-2025", "macos-15-intel")


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def stage(tag):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("Release requires v<major>.<minor>.<patch>, without a snapshot suffix")
    version = tag[1:]
    properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                      if line and not line.startswith("#") and "=" in line)
    if properties.get("mod_version") != version:
        raise ValueError("Tag does not match gradle.properties mod_version")
    if git("rev-parse", "HEAD") != git("rev-parse", "--verify", tag + "^{commit}"):
        raise ValueError("Tag must identify the checked-out source commit")
    if git("status", "--porcelain", "--untracked-files=normal"):
        raise ValueError("Release source worktree must be clean")
    expected = {f"reform-{version}-mc1.21.1-neoforge.jar", f"reform-{version}-sources.jar",
                f"reform-{version}.pom"}
    baseline = None
    for runner in RUNNERS:
        directory = ROOT / "build/ci" / ("build-" + runner)
        files = {p.name: p.read_bytes() for p in directory.iterdir() if p.is_file()}
        if set(files) != expected:
            raise ValueError(f"Unexpected/missing CI artifacts for {runner}")
        if baseline is not None and files != baseline:
            raise ValueError(f"Non-reproducible CI artifacts for {runner}")
        baseline = files
    destination = ROOT / "build/release"
    destination.mkdir(parents=True, exist_ok=False)
    for name, data in baseline.items():
        (destination / name).write_bytes(data)
    source = destination / f"reform-{version}-source.zip"
    with source.open("wb") as stream:
        subprocess.run(["git", "-C", str(ROOT), "archive", "--format=zip",
                        f"--prefix=reform-{version}/", tag], stdout=stream, check=True)
    assets = sorted(destination.iterdir())
    for algorithm, filename in [("sha256", "SHA256SUMS"), ("sha512", "SHA512SUMS")]:
        lines = [f"{hashlib.new(algorithm, p.read_bytes()).hexdigest()}  {p.name}\n" for p in assets]
        (destination / filename).write_text("".join(lines), encoding="utf-8")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--stage-only", action="store_true")
    args = parser.parse_args()
    try:
        directory = stage(args.tag)
        if not args.stage_only:
            subprocess.run(["gh", "release", "create", args.tag, "--repo", "mannie-exe/reform",
                            "--verify-tag", "--draft", "--title", f"re:form {args.tag[1:]}",
                            "--notes-file", str(ROOT / "CHANGELOG.md"),
                            *map(str, sorted(directory.iterdir()))], check=True)
        print(f"{'Staged' if args.stage_only else 'Created draft for'} {args.tag}: {directory}")
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release preparation refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
