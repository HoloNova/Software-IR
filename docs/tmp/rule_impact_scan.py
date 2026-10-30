"""Rough text scan for the two proposed static checks (temporary handoff helper; delete with docs/tmp).

Usage (from the repository root):
    python3 docs/tmp/rule_impact_scan.py            # repository SIR: *.sir, Java text blocks, Markdown code blocks
    python3 docs/tmp/rule_impact_scan.py --history  # every teacher candidate under datasets/sir, marking qualified ones

Rule A: inside any(E, ...), a comparison whose two sides are the same bare name (it binds to E's field on both
        sides, so it is always true and the outer variable of that name is silently ignored).
Rule B: a workflow that runs `exists E where ...`, then creates an E and persists it without `else`
        (E has a unique constraint, so a concurrent duplicate becomes a 500).

This is a regex approximation for sizing the change, not the checker. The real rule is decided by the semantic
passes; re-run the targeted tests after implementing it.
"""
from __future__ import annotations

import glob
import json
import re
import sys
from pathlib import Path


def braces(text: str, start: int) -> str | None:
    i = text.find("{", start)
    if i < 0:
        return None
    depth = 0
    for j in range(i, len(text)):
        depth += {"{": 1, "}": -1}.get(text[j], 0)
        if depth == 0:
            return text[i:j + 1]
    return text[i:]


def parens(text: str, i: int) -> str:
    depth = 0
    for j in range(i, len(text)):
        depth += {"(": 1, ")": -1}.get(text[j], 0)
        if depth == 0:
            return text[i + 1:j]
    return text[i + 1:]


def repository_sources():
    for path in Path(".").rglob("*.sir"):
        name = path.as_posix()
        if not name.startswith(("datasets/", "experiments/")) and "/target/" not in name:
            yield name, path.read_text(encoding="utf-8", errors="replace")
    for path in [*Path(".").rglob("*.java"), *Path(".").rglob("*.md")]:
        name = path.as_posix()
        if name.startswith(("datasets/", "experiments/")) or "/target/" in name:
            continue
        parts = re.split(r'"""|```', path.read_text(encoding="utf-8", errors="replace"))
        for k in range(1, len(parts), 2):
            if "capability " in parts[k]:
                yield f"{name}#block{k // 2}", parts[k]


def history_sources():
    for name in sorted(glob.glob("datasets/sir/**/samples/*teacher*/candidate.sir", recursive=True)):
        yield name, Path(name).read_text(encoding="utf-8")


def scan(text: str):
    unique = set()
    for m in re.finditer(r"\bentity\s+(\w+)", text):
        body = braces(text, m.end())
        if body and re.search(r"\bunique\b", body):
            unique.add(m.group(1))
    for m in re.finditer(r"\bcapability\s+(\w+)", text):
        body = braces(text, m.end())
        if not body:
            continue
        for a in re.finditer(r"\bany\s*\(", body):
            for left, right in re.findall(r"(?<![.\w])(\w+)\s*[!=]=\s*(\w+)(?![.\w(])", parens(body, a.end() - 1)):
                if left == right:
                    yield "A", f"{m.group(1)}: any(...) {left} == {right}"
        created = {var: entity for entity, var in re.findall(r"\bcreate\s+(\w+)\s+as\s+(\w+)", body)}
        checked = set(re.findall(r"\bexists\s+(\w+)\s+where", body))
        for var in re.findall(r"\bpersist\s+(\w+)\s*;", body):
            if created.get(var) in unique and created[var] in checked:
                yield "B", f"{m.group(1)}: persist {var} ({created[var]}) without else after exists"


def main() -> None:
    history = "--history" in sys.argv
    qualified = set()
    for index in glob.glob("datasets/sir/*/*/train-teacher.index.json"):
        qualified |= {row["id"] for row in json.loads(Path(index).read_text(encoding="utf-8"))["rows"]}
    counts, files = {"A": 0, "B": 0}, 0
    for name, text in (history_sources() if history else repository_sources()):
        files += 1
        for rule, where in scan(text):
            counts[rule] += 1
            sample = Path(name).parent.name
            mark = " [QUALIFIED]" if history and sample in qualified else ""
            print(f"{rule} {name} :: {where}{mark}")
    print(f"scanned {files}; rule A {counts['A']}, rule B {counts['B']}", file=sys.stderr)


if __name__ == "__main__":
    main()
