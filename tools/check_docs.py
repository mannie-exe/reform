"""Check local Markdown destinations without reading outside the project."""

from pathlib import Path
import os
import re
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
IGNORED = {".git", ".gradle", ".cache", "build", "runs", "node_modules", ".idea"}
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def documents():
    for directory, children, files in os.walk(ROOT):
        children[:] = sorted(child for child in children if child not in IGNORED)
        for name in sorted(files):
            if name.endswith(".md"):
                yield Path(directory) / name


def anchors(text):
    result, counts = set(), {}
    fenced = False
    for line in text.splitlines():
        if line.lstrip().startswith(("```", "~~~")):
            fenced = not fenced
        if fenced or not re.match(r"^#{1,6} ", line):
            continue
        title = re.sub(r"^#{1,6} +", "", line).strip().rstrip("#").strip()
        slug = "".join(c for c in title.lower() if c.isalnum() or c in " _-")
        slug = slug.replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(slug if count == 0 else f"{slug}-{count}")
    return result


def main():
    errors, checked = [], 0
    docs = list(documents())
    for source in docs:
        for match in LINK.finditer(source.read_text(encoding="utf-8")):
            target = match.group(1).strip().strip("<>")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc:
                continue
            path = (source.parent / unquote(parsed.path)).resolve() if parsed.path else source
            if not path.is_relative_to(ROOT):
                errors.append(f"{source.relative_to(ROOT)}: destination leaves project: {target}")
                continue
            checked += 1
            if not path.exists():
                errors.append(f"{source.relative_to(ROOT)}: missing destination: {target}")
            elif parsed.fragment and path.suffix == ".md":
                if unquote(parsed.fragment) not in anchors(path.read_text(encoding="utf-8")):
                    errors.append(f"{source.relative_to(ROOT)}: missing heading: {target}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Documentation: {len(docs)} files, {checked} local links checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
