#!/usr/bin/env python3
"""Check a staged release against Maven Central's rules before it's uploaded.

usage: check-release.py <staging dir> <version> [--signed]

Central refuses a bundle when a component lacks a POM with name, description, url, licenses,
developers and scm; when a jar component lacks -sources and -javadoc jars; when a file lacks
.md5/.sha1 checksums; or (with --signed) a .asc signature. It never lets a release be deleted,
so it's checked here first.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
REQUIRED = ["name", "description", "url", "licenses/m:license/m:name", "developers/m:developer/m:id", "scm/m:url"]


def main(root: Path, version: str, signed: bool) -> int:
    problems = []
    dirs = sorted(p.parent for p in root.rglob(f"*-{version}.pom"))
    if not dirs:
        print(f"no POMs for version {version} under {root}")
        return 1
    for d in dirs:
        pom = next(d.glob(f"*-{version}.pom"))
        base = pom.name[: -len(".pom")]
        tree = ET.parse(pom).getroot()
        for field in REQUIRED:
            el = tree.find("m:" + field, NS)
            if el is None or not (el.text or "").strip():
                problems.append(f"{pom.name}: missing <{field.replace('m:', '')}>")
        packaging = (tree.findtext("m:packaging", default="jar", namespaces=NS) or "jar").strip()
        if packaging == "jar":
            for suffix in ("", "-sources", "-javadoc"):
                if not (d / f"{base}{suffix}.jar").exists():
                    problems.append(f"{d.name}: missing {base}{suffix}.jar")
        for f in d.iterdir():
            if f.name.startswith("maven-metadata") or f.suffix in (".md5", ".sha1", ".sha256", ".sha512", ".asc"):
                continue
            for ext in (".md5", ".sha1") + ((".asc",) if signed else ()):
                if not Path(str(f) + ext).exists():
                    problems.append(f"{f.name}: missing {ext}")
    for p in problems:
        print("  " + p)
    print(f"{len(dirs)} components, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(Path(args[0]), args[1], "--signed" in sys.argv))
