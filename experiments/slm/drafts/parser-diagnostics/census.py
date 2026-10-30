"""Read-only historical parse census; never invokes the compiler or a model.

python experiments/slm/drafts/parser-diagnostics/census.py --revision 33c2f4bc \
    --output target/parser-diagnostics-design/reproduced-census.json

The classification file is reviewed evidence, not a text-based SIR parser.
All dataset input bytes come from the specified Git revision, not worktree EOLs.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import subprocess


def git(repo: Path, *args: str, data: bytes | None = None) -> bytes:
    return subprocess.check_output(["git", "-C", str(repo), *args], input=data)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def diagnostic_objects(value):
    if isinstance(value, dict):
        code = value.get("code")
        if isinstance(code, dict):
            code = code.get("value")
        if code == "SIR-PARSE-001":
            yield value
        for child in value.values():
            yield from diagnostic_objects(child)
    elif isinstance(value, list):
        for child in value:
            yield from diagnostic_objects(child)


def read_blobs(repo: Path, revision: str) -> dict[str, bytes]:
    names = git(repo, "ls-tree", "-r", "--name-only", "-z", revision, "--", "datasets/sir")
    paths = [p.decode("utf-8") for p in names.split(b"\0") if p.endswith((b".json", b".jsonl"))]
    payload = git(repo, "cat-file", "--batch", data="".join(f"{revision}:{p}\n" for p in paths).encode())
    blobs = {}
    position = 0
    for path in paths:
        end = payload.index(b"\n", position)
        header = payload[position:end].split()
        assert len(header) == 3 and header[1] == b"blob", path
        size = int(header[2])
        blobs[path] = payload[end + 1:end + 1 + size]
        position = end + size + 2
    assert position == len(payload)
    return blobs


def check_rows(blobs):
    for path, raw in sorted(blobs.items()):
        if path.endswith(".jsonl") and Path(path).name.startswith("checked"):
            for line, content in enumerate(raw.decode("utf-8").splitlines(), 1):
                if content.strip():
                    yield path, line, json.loads(content)


def carriers(blobs):
    inventory = []
    for path, raw in sorted(blobs.items()):
        if b"SIR-PARSE-001" not in raw:
            continue
        values = [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()] \
            if path.endswith(".jsonl") else [json.loads(raw)]
        count = sum(1 for value in values for _ in diagnostic_objects(value))
        if count:
            kind = "checked" if Path(path).name.startswith("checked") else \
                "research_copy" if "/research/" in path else "command_stdout"
            inventory.append({"path": path, "sha256": digest(raw), "parse001_objects": count, "kind": kind})
    return inventory


def build(repo: Path, revision: str, labels_path: Path) -> dict:
    blobs = read_blobs(repo, revision)
    labels = json.loads(labels_path.read_text(encoding="utf-8"))
    rows = list(check_rows(blobs))
    failed = []
    for path, line, row in rows:
        result = row.get("static_result", {})
        if row.get("origin") != "teacher":
            assert not list(diagnostic_objects(result)), (path, line, "reference parse error")
            continue
        assert digest(row["sir"].encode("utf-8")) == row["source_sha256"], (path, line)
        if list(diagnostic_objects(result)):
            failed.append((path, line, row))
    by_id = defaultdict(list)
    by_source = defaultdict(list)
    evaluations = defaultdict(set)
    for path, line, row in failed:
        by_id[row["id"]].append((path, line, row))
        by_source[row["source_sha256"]].append((path, line, row))
        evaluations[(row["id"], row["toolchain_core_sha256"])].add(
            json.dumps(row["static_result"], ensure_ascii=False, sort_keys=True))
    assert set(by_source) == set(labels["sources"]), "Classification coverage differs from input revision"
    assert all(len(values) == 1 for values in evaluations.values()), "Same candidate/core has conflicting evaluations"
    responses = []
    for identity, occurrences in sorted(by_id.items()):
        hashes = {row["source_sha256"] for _, _, row in occurrences}
        assert len(hashes) == 1, identity
        sha, = hashes
        first = occurrences[0][2]
        assert all(row["sir"] == first["sir"] for _, _, row in occurrences)
        responses.append({"id": identity, "source_sha256": sha,
                          "category": labels["sources"][sha]["category"],
                          "task_id": first["task_id"], "generation_mode": first.get("generation_mode"),
                          "parent_id": first.get("parent_id"),
                          "evaluations": [{"checked": path, "line": line,
                                           "toolchain_core_sha256": row["toolchain_core_sha256"],
                                           "diagnostics": row["static_result"]["diagnostics"]}
                                          for path, line, row in occurrences]})
    source_rows = []
    for sha, occurrences in sorted(by_source.items()):
        path, line, row = occurrences[0]
        snippets = [{"code": d["code"], "message": d["message"], "span": d["span"],
                     "source_line": row["sir"].splitlines()[d["span"]["startLine"] - 1]}
                    for d in row["static_result"]["diagnostics"]]
        source_rows.append({"source_sha256": sha, "category": labels["sources"][sha]["category"],
                            "candidate_ids": sorted({r["id"] for _, _, r in occurrences}),
                            "canonical_checked": path, "canonical_line": line, "diagnostic_snippets": snippets})
    ranked = []
    for category, reason in labels["categories"].items():
        selected = [row for row in responses if row["category"] == category]
        raw = [r for _, _, r in failed if labels["sources"][r["source_sha256"]]["category"] == category]
        ranked.append({"category": category, "reason": reason, "responses": len(selected),
                       "unique_sources": len({row["source_sha256"] for row in selected}),
                       "checked_rows": len(raw), "raw_parse001_items": sum(len(list(diagnostic_objects(r["static_result"]))) for r in raw),
                       "candidate_ids": [row["id"] for row in selected]})
    ranked.sort(key=lambda row: (-row["responses"], row["category"]))
    inputs = [{"path": path, "sha256": digest(raw)} for path, raw in sorted(blobs.items())
              if path.endswith(".jsonl") and Path(path).name.startswith("checked")]
    copies = carriers(blobs)
    return {"schema_version": 1, "git_commit": revision,
            "ranking_unit": "distinct teacher response id, first observed parse-root category; repeat checks/imports do not add responses",
            "source_sensitivity": "Identical code from different responses remains multiple responses, but unique-source counts are shown separately.",
            "checked_files": len(inputs), "checked_rows": len(rows),
            "origins": dict(Counter(row.get("origin") for _, _, row in rows)),
            "parse001_checked_rows": len(failed), "parse001_raw_items": sum(len(list(diagnostic_objects(r["static_result"]))) for _, _, r in failed),
            "distinct_responses_with_parse001": len(responses), "distinct_source_bytes_with_parse001": len(by_source),
            "distinct_response_core_evaluations_with_parse001": len(evaluations),
            "same_response_core_conflicts": 0, "categories": ranked,
            "source_based_ranking": sorted(ranked, key=lambda row: (-row["unique_sources"], row["category"])),
            "all_json_carrier_files": len(copies),
            "all_json_carrier_object_counts": dict(sum((Counter({row["kind"]: row["parse001_objects"]}) for row in copies), Counter())),
            "carrier_inventory": copies, "checked_input_sha256": inputs,
            "classifications_sha256": digest(labels_path.read_bytes()),
            "responses": responses, "source_snippets": source_rows,
            "model_calls": 0, "maven_runs": 0, "qualification_recomputed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--revision", default="33c2f4bc09e88b91631066ae14f8edd70bb19fae")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    revision = git(args.repo, "rev-parse", args.revision + "^{commit}").decode().strip()
    result = build(args.repo, revision, Path(__file__).with_name("classifications.json"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    summary = {key: result[key] for key in ["checked_files", "checked_rows", "parse001_checked_rows", "parse001_raw_items", "distinct_responses_with_parse001", "distinct_source_bytes_with_parse001"]}
    summary["top_three"] = [{k: row[k] for k in ["category", "responses", "unique_sources"]} for row in result["categories"][:3]]
    print(json.dumps(summary, ensure_ascii=True))


if __name__ == "__main__":
    main()
