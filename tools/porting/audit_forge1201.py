#!/usr/bin/env python3
"""Read-only audit of Legacy Connector's current source for the Forge 1.20.1 port.

Run from the repository root:
    python tools/porting/audit_forge1201.py
    python tools/porting/audit_forge1201.py --output build/forge1201-audit.txt

The script deliberately does not rewrite imports or source files. A namespace hit is
not automatically a one-to-one Forge equivalent; each API mapping needs review.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOTS = (
    ROOT / "src" / "main" / "java",
    ROOT / "loader" / "src" / "main" / "java",
    ROOT / "testmods" / "src" / "main" / "java",
)
NEOFORGED = re.compile(r"net\\.neoforged(?:\\.[A-Za-z0-9_*$]+)*")
IMPORT = re.compile(r"^\\s*import\\s+(?:static\\s+)?(net\\.neoforged\\.[^;]+);", re.MULTILINE)
TOKEN = re.compile(r"(?<![A-Za-z0-9_])(?:DeferredRegister|DeferredHolder|RegisterEvent|IEventBus|ModLoadingIssue|IModFileCandidateLocator|IDiscoveryPipeline|ILaunchContext)(?![A-Za-z0-9_])")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="write the report to a file instead of stdout")
    args = parser.parse_args()

    files: list[Path] = []
    for source_root in SOURCE_ROOTS:
        if source_root.exists():
            files.extend(sorted(source_root.rglob("*.java")))

    package_counts: Counter[str] = Counter()
    token_counts: Counter[str] = Counter()
    affected: dict[str, list[str]] = defaultdict(list)
    total_hits = 0

    for path in files:
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            print(f"warning: cannot read {path}: {exc}", file=sys.stderr)
            continue

        imports = IMPORT.findall(content)
        for imported in imports:
            prefix = ".".join(imported.split(".")[:3])
            package_counts[prefix] += 1
            affected[prefix].append(path.relative_to(ROOT).as_posix())

        hits = NEOFORGED.findall(content)
        total_hits += len(hits)

        for token in TOKEN.findall(content):
            token_counts[token] += 1

    lines = [
        "Legacy Connector / Forge 1.20.1 source audit",
        f"Repository: {ROOT}",
        f"Java source files scanned: {len(files)}",
        f"Occurrences of net.neoforged references: {total_hits}",
        "",
        "NeoForge import groups:",
    ]
    if package_counts:
        for package, count in package_counts.most_common():
            lines.append(f"  {package}: {count} imports")
    else:
        lines.append("  (none found)")
    lines.extend(["", "Loader/runtime API touchpoints:"])
    if token_counts:
        for token, count in token_counts.most_common():
            lines.append(f"  {token}: {count} occurrences")
    else:
        lines.append("  (none found)")
    lines.extend(["", "Files by NeoForge import group:"])
    for package, _ in package_counts.most_common():
        lines.append(f"  [{package}]")
        for file_path in sorted(set(affected[package])):
            lines.append(f"    {file_path}")

    report = "\\n".join(lines) + "\\n"
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(report, encoding="utf-8")
        print(f"Wrote {output}")
    else:
        print(report, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
