"""Bounded local experiment files and reproducible input fingerprints."""
from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

from experiments.slm.corpus import DataError

REPO = Path(__file__).resolve().parents[2]
MAX_JSON_BYTES = 16 * 1024 * 1024


def json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n"


def _unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise DataError("duplicate JSON key: " + key)
        result[key] = value
    return result


def _invalid_number(value):
    raise DataError("non-finite JSON number: " + value)


def decode_json(raw: bytes) -> Any:
    if len(raw) > MAX_JSON_BYTES:
        raise DataError("JSON input exceeds 16 MiB")
    return json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_keys, parse_constant=_invalid_number)


def load_json(path: Path) -> Any:
    if path.stat().st_size > MAX_JSON_BYTES:
        raise DataError("JSON input exceeds 16 MiB")
    return decode_json(path.read_bytes())


def write_new_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json_text(value))


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if path.stat().st_size > MAX_JSON_BYTES:
        raise DataError("JSONL input exceeds the pilot 16 MiB budget")
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            raise DataError(f"blank JSONL line {number}")
        row = json.loads(line, object_pairs_hook=_unique_keys, parse_constant=_invalid_number)
        if not isinstance(row, dict):
            raise DataError(f"JSONL line {number} must be an object")
        rows.append(row)
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]], *, append: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a" if append else "x", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, sort_keys=True, allow_nan=False) + "\n")


def digest_file(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def repo_file(relative: str) -> Path:
    raw = Path(relative)
    if raw.is_absolute() or ".." in raw.parts:
        raise DataError("repository input must be a relative non-traversing path")
    result = (REPO / raw).resolve(strict=True)
    if not result.is_relative_to(REPO) or not result.is_file():
        raise DataError("input escapes the repository or is not a file")
    return result


def fingerprint(inputs: dict[str, str]) -> str:
    value = "".join(path + "\0" + inputs[path] + "\n" for path in sorted(inputs))
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def core_input_paths(names) -> tuple[str, ...]:
    """One selection rule for working-tree and committed input sets."""
    files = frozenset(names)
    modules = {p.split("/")[0] for p in files if p.count("/") == 1 and p.endswith("/pom.xml")}
    return tuple(sorted(p for p in files if p == "pom.xml" or any(
        p == module + "/pom.xml" or p.startswith(module + "/src/main/") for module in modules)))


def core_inputs() -> dict[str, str]:
    paths = [REPO / "pom.xml"]
    for module in sorted(REPO.glob("*/pom.xml")):
        paths.append(module)
        paths.extend(p for p in (module.parent / "src/main").rglob("*") if p.is_file())
    names = [p.relative_to(REPO).as_posix() for p in paths]
    return {name: digest_file(REPO / name) for name in core_input_paths(names)}


def verify_inputs(manifest: dict[str, Any]) -> None:
    for kind in ("toolchain_inputs", "oracle_inputs"):
        inputs = manifest[kind]
        if any(digest_file(repo_file(path)) != expected for path, expected in inputs.items()):
            raise DataError(kind + " changed after the bundle was prepared")
        digest_key = "toolchain_core_sha256" if kind == "toolchain_inputs" else "oracle_sha256"
        if fingerprint(inputs) != manifest[digest_key]:
            raise DataError(kind + " manifest fingerprint is invalid")
    if core_inputs() != manifest["toolchain_inputs"]:
        raise DataError("toolchain input set changed after bundle preparation")


def git_head() -> str:
    result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO, capture_output=True,
                            text=True, check=True, timeout=10)
    return result.stdout.strip()


def checker_command(maven_repo: Path | None = None) -> list[str]:
    repository = maven_repo or Path.home() / ".m2/repository"
    runtime = repository / "org/antlr/antlr4-runtime/4.13.2/antlr4-runtime-4.13.2.jar"
    modules = ("kcg-cli", "sir-toolchain-application", "sir-change", "sir-project-graph",
               "sir-generator-spring-boot", "sir-lowering-spring-boot", "sir-lowering-api",
               "sir-semantic", "sir-parser")
    entries = [REPO / name / "target/classes" for name in modules] + [runtime]
    if any(not path.exists() for path in entries):
        raise DataError("checker classes or ANTLR runtime missing; build the CLI with project resource limits first")
    import os
    return ["java", "-XX:-UsePerfData", "-XX:ActiveProcessorCount=2", "-Xmx256m",
            "-XX:MaxMetaspaceSize=128m", "-cp", os.pathsep.join(map(str, entries)),
            "io.kcg.cli.KcgCli", "check"]


def verify_artifacts(evidence: dict[str, Any], evidence_file: Path) -> None:
    root = evidence_file.resolve().parent
    for artifact in evidence.get("artifacts", []):
        relative = Path(artifact["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise DataError("runtime artifact path must stay within its evidence directory")
        path = (root / relative).resolve(strict=True)
        if not path.is_relative_to(root) or digest_file(path) != artifact["sha256"]:
            raise DataError("runtime evidence artifact fingerprint mismatch")
