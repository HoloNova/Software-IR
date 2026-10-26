"""Pure label/provenance rules. This module does not implement any SIR semantics."""
from __future__ import annotations

import hashlib
import re
from typing import Any

MAX_SOURCE_BYTES = 1024 * 1024
GATES = ("static", "build", "start", "business")
SHA_PATTERN = re.compile(r"[0-9a-f]{64}\Z")


class DataError(ValueError):
    """An input cannot be safely or truthfully included in the corpus."""


def sha256_text(text: str) -> str:
    try:
        return hashlib.sha256(text.encode("utf-8", errors="strict")).hexdigest()
    except (AttributeError, UnicodeError) as error:
        raise DataError("text must be strict Unicode") from error


def decode_response(response: dict[str, Any]) -> str:
    choices = response.get("choices")
    if not isinstance(choices, list) or len(choices) != 1:
        raise DataError("one completion choice is required")
    choice = choices[0]
    if not isinstance(choice, dict) or choice.get("finish_reason") != "stop":
        raise DataError("finish_reason must be stop; truncated/tool-call output is not a candidate")
    message = choice.get("message")
    text = message.get("content") if isinstance(message, dict) else None
    if not isinstance(text, str) or not text.strip():
        raise DataError("completion content must be nonempty text")
    text = text.strip()
    if text.startswith("```"):
        match = re.fullmatch(r"```(?:sir)?[ \t]*\r?\n([\s\S]*?)\r?\n```", text)
        if match is None:
            raise DataError("only one complete SIR fence is accepted")
        text = match.group(1).strip()
    if "```" in text or not re.match(r"sir 0\.3(?:\s|$)", text):
        raise DataError("answer must be a sir 0.3 document without explanation")
    source = text + "\n"
    sha256_text(source)
    if len(source.encode("utf-8")) > MAX_SOURCE_BYTES:
        raise DataError("SIR source exceeds the checker 1 MiB limit")
    return source


def bind_static(row: dict[str, Any], result: dict[str, Any]) -> dict[str, Any]:
    if sha256_text(row["sir"]) != row["source_sha256"]:
        raise DataError("candidate source was changed without updating its fingerprint")
    if result.get("id") != row["id"] or result.get("sourceSha256") != row["source_sha256"]:
        raise DataError("checker sample/source binding does not match the candidate")
    diagnostics = result.get("diagnostics")
    if not isinstance(diagnostics, list):
        raise DataError("checker diagnostics must be an array")
    success = (
        result.get("ok") is True and result.get("stage") == "GENERATION"
        and isinstance(result.get("digest"), str) and SHA_PATTERN.fullmatch(result["digest"])
        and isinstance(result.get("fileCount"), int) and result["fileCount"] > 0
        and all(isinstance(d, dict) and d.get("severity") != "ERROR" for d in diagnostics)
    )
    return {**row, "static_result": result,
            "gates": {**row["gates"], "static": "PASSED" if success else "FAILED"}}


def _check_runtime_binding(row: dict[str, Any], evidence: dict[str, Any]) -> None:
    if evidence.get("schema_version") != "sir-runtime-evidence/1":
        raise DataError("unsupported runtime evidence schema")
    for key in ("id", "task_sha256", "oracle_sha256", "toolchain_core_sha256", "source_sha256"):
        if evidence.get(key) != row.get(key):
            raise DataError("runtime evidence binding mismatch: " + key)
    if evidence.get("generated_digest") != row["static_result"]["digest"]:
        raise DataError("runtime evidence binding mismatch: generated_digest")


def _require_runtime_provenance(evidence: dict[str, Any]) -> None:
    environment = evidence.get("environment", {})
    artifacts = evidence.get("artifacts")
    if not isinstance(evidence.get("run_id"), str) or not evidence["run_id"]:
        raise DataError("runtime run_id is required")
    if not isinstance(environment, dict) or not all(
        isinstance(environment.get(key), str) and environment[key]
        for key in ("java", "mysql", "test_revision")
    ):
        raise DataError("actual runtime environment is required")
    if not isinstance(artifacts, list) or not artifacts:
        raise DataError("runtime artifacts are required")
    for artifact in artifacts:
        if not isinstance(artifact, dict) or not isinstance(artifact.get("path"), str):
            raise DataError("invalid runtime artifact")
        if not SHA_PATTERN.fullmatch(str(artifact.get("sha256", ""))):
            raise DataError("artifact sha256 is required")


def training_record(row: dict[str, Any], evidence: dict[str, Any] | None) -> dict[str, Any] | None:
    if row.get("split") != "train" or row["gates"].get("static") != "PASSED" or evidence is None:
        return None
    if sha256_text(row["sir"]) != row["source_sha256"]:
        raise DataError("candidate source changed after static checking")
    _check_runtime_binding(row, evidence)
    if any(evidence.get("gates", {}).get(gate) != "PASSED" for gate in GATES):
        return None
    _require_runtime_provenance(evidence)
    return {"prompt": row["messages"], "completion": [{"role": "assistant", "content": row["sir"]}]}


def enforce_split_disjoint(rows: list[dict[str, Any]]) -> None:
    family_splits: dict[str, str] = {}
    source_splits: dict[str, str] = {}
    ids: set[str] = set()
    for row in rows:
        if row["id"] in ids:
            raise DataError("duplicate sample id: " + row["id"])
        ids.add(row["id"])
        for key, seen in (("family", family_splits), ("source_sha256", source_splits)):
            value = row[key]
            if value in seen and seen[value] != row["split"]:
                raise DataError(("family" if key == "family" else "source") + " crosses splits")
            seen[value] = row["split"]
