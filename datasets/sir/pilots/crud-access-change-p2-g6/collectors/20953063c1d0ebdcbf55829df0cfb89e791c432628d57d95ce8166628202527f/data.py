"""SIR data pilot: freeze inputs, collect candidates, check, export only proven answers.

Run from repository root: python -m experiments.slm.data --help
No command here builds/starts a generated application or rents GPU resources.
"""
from __future__ import annotations

import argparse
import importlib.metadata
import json
import os
import re
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

from experiments.slm.corpus import (
    DataError, GATES, _check_runtime_binding, _require_runtime_provenance, bind_static, decode_response,
    enforce_split_disjoint, sha256_text, training_record,
)
from experiments.slm.dataset_io import (
    REPO, checker_command, core_inputs, digest_file, fingerprint, git_head,
    json_text, load_json, load_jsonl, repo_file, verify_artifacts, verify_inputs,
    write_jsonl, write_new_json,
)

HERE = Path(__file__).resolve().parent
# v1 stays the default so the commands of a recorded batch reproduce it; a later prompt is a new file chosen explicitly.
DEFAULT_SYSTEM_PROMPT = HERE / "prompts/sir-0.3-auth.md"
DEFAULT_FEEDBACK = HERE / "prompts/repair-auth-v1.md"
DEFAULT_RUNTIME_FEEDBACK = HERE / "prompts/repair-runtime-v1.md"
DEFAULT_STUDENT_PROMPT = HERE / "prompts/student-v1.md"


def prompt_file(path: Path | None, default: Path) -> Path:
    """A prompt is one of the reviewed files under prompts/, never an arbitrary path."""
    chosen = (path or default).resolve()
    if chosen.parent != (HERE / "prompts").resolve() or chosen.suffix != ".md" or not chosen.is_file():
        raise DataError("prompt must be an existing .md file in experiments/slm/prompts: " + str(path))
    return chosen


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_bundle(root: Path):
    manifest = load_json(root / "manifest.json")
    verify_inputs(manifest)
    for name, expected in manifest["bundle_inputs"].items():
        if digest_file(root / name) != expected:
            raise DataError("frozen bundle input changed: " + name)
    if manifest["task_sha256"] != digest_file(root / "task.json"):
        raise DataError("task fingerprint does not match the frozen task file")
    return manifest, load_json(root / "task.json")


def validate_candidates(root: Path, manifest: dict, task: dict, rows: list) -> None:
    enforce_split_disjoint(rows)
    for row in rows:
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,159}", str(row.get("id", ""))):
            raise DataError("invalid candidate id")
        expected = {"task_id": task["id"], "task_revision": task["revision"],
                    "family": task["family"], "split": task["split"],
                    **{key: manifest[key] for key in ("task_sha256", "oracle_sha256", "toolchain_core_sha256")}}
        if any(row.get(key) != value for key, value in expected.items()):
            raise DataError("candidate task/split/toolchain binding mismatch")
        directory = root / "samples" / row["id"]
        original = load_json(directory / "candidate.json")
        if any(row.get(key) != value for key, value in original.items() if key != "gates"):
            raise DataError("candidate metadata changed after collection")
        if digest_file(directory / "candidate.sir") != row["source_sha256"] or sha256_text(row["sir"]) != row["source_sha256"]:
            raise DataError("candidate source changed after collection")
        if row.get("origin") == "teacher":
            if digest_file(directory / "response.json") != row["raw_response_sha256"]:
                raise DataError("raw teacher response changed after collection")
            if digest_file(directory / "request.json") != row["request_sha256"]:
                raise DataError("teacher request changed after collection")
            if decode_response(load_json(directory / "response.json")) != row["sir"]:
                raise DataError("candidate no longer matches the raw teacher response")


def task_message(root: Path, task: dict) -> str:
    """The question itself; teacher and student see the same bytes, only the system prompt differs."""
    user = task["requirement"] + "\n\n固定接口合同：\n" + json_text(task["contract"])
    # The judge's tables are fixed, like the database of a real project; a task without ddl_source keeps its old prompt.
    if task.get("ddl_source") is not None:
        user += ("\n数据库表结构已经确定（表名、列名须与之对应；SIR 中不写 SQL）：\n"
                 + repo_file(task["ddl_source"]).read_text(encoding="utf-8"))
    # A from-scratch task has no existing software: its frozen base.sir is empty and is not shown to the teacher.
    if task["base_source"] is not None:
        user += "\n已有软件（返回修改后的完整文档）：\n" + (root / "base.sir").read_text(encoding="utf-8")
    return user


def prompt_messages(root: Path, task: dict) -> list[dict]:
    return [{"role": "system", "content": (root / "system.md").read_text(encoding="utf-8")},
            {"role": "user", "content": task_message(root, task)}]


def new_row(manifest: dict, task: dict, sample_id: str, sir: str, messages: list, origin: str) -> dict:
    return {
        "schema_version": "sir-candidate/1", "id": sample_id,
        "task_id": task["id"], "task_revision": task["revision"],
        "family": task["family"], "split": task["split"], "origin": origin,
        **{key: manifest[key] for key in ("task_sha256", "oracle_sha256", "toolchain_core_sha256")},
        "sir": sir, "source_sha256": sha256_text(sir), "messages": messages,
        "gates": {name: "NOT_RUN" for name in GATES}, "created_at": utc_now(),
    }


def store_candidate(root: Path, row: dict) -> None:
    directory = root / "samples" / row["id"]
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "candidate.sir").open("x", encoding="utf-8", newline="") as output:
        output.write(row["sir"])
    write_new_json(directory / "candidate.json", row)
    write_jsonl(root / "candidates.jsonl", [row], append=True)


def prepare(args) -> None:
    from jsonschema import Draft202012Validator
    task = load_json(args.task)
    issues = list(Draft202012Validator(load_json(HERE / "task.schema.json")).iter_errors(task))
    if issues:
        raise DataError("invalid task contract: " + issues[0].message)
    if task.get("ddl_source") is not None and task["ddl_source"] not in task["oracle"]["files"]:
        raise DataError("ddl_source must be one of the oracle files, so its bytes are frozen with the bundle")
    root = args.bundle.resolve()
    root.mkdir(parents=True, exist_ok=False)
    write_new_json(root / "task.json", task)
    # base.sir is always frozen so every bundle has the same inputs; a from-scratch task freezes it empty.
    (root / "base.sir").write_bytes(b"" if task["base_source"] is None else repo_file(task["base_source"]).read_bytes())
    for name, source in (("reference.sir", repo_file(task["reference_source"])), ("system.md", system_source(args))):
        (root / name).write_bytes(source.read_bytes())
    main = core_inputs()
    oracle = {path: digest_file(repo_file(path)) for path in task["oracle"]["files"]}
    frozen = {name: digest_file(root / name) for name in ("task.json", "base.sir", "reference.sir", "system.md")}
    manifest = {
        "schema_version": "sir-data-bundle/1", "created_at": utc_now(), "git_head": git_head(),
        "toolchain_inputs": main, "toolchain_core_sha256": fingerprint(main),
        "oracle_inputs": oracle, "oracle_sha256": fingerprint(oracle),
        "task_sha256": frozen["task.json"], "bundle_inputs": frozen,
        "experiment_inputs": {p.relative_to(REPO).as_posix(): digest_file(p)
                              for p in sorted(HERE.rglob("*")) if p.is_file() and "__pycache__" not in p.parts},
    }
    write_new_json(root / "manifest.json", manifest)
    reference = new_row(manifest, task, task["id"] + "-reference",
                        (root / "reference.sir").read_text(encoding="utf-8"), prompt_messages(root, task), "existing_reference")
    store_candidate(root, {**reference, "reference_evidence": task["reference_evidence"]})
    print(json_text({"bundle": str(root), "reference_seeds": 1, "new_teacher_candidates": 0,
                     "business_qualified_answers": 0}), end="")


def system_source(args) -> Path:
    # An import freezes the origin batch's own system prompt, the one its recorded messages were sent with.
    frozen = getattr(args, "frozen_system", None)
    return frozen if frozen is not None else prompt_file(getattr(args, "system_prompt", None), DEFAULT_SYSTEM_PROMPT)


def import_candidates(args) -> None:
    """Rebind preserved responses to this environment, never transplant old gate results."""
    import shutil
    origin = args.from_bundle.resolve()
    manifest = load_json(origin / "manifest.json")
    if set(manifest["bundle_inputs"]) != {"task.json", "base.sir", "reference.sir", "system.md"}:
        raise DataError("source bundle contains unexpected frozen input paths")
    for name, expected in manifest["bundle_inputs"].items():
        if digest_file(origin / name) != expected:
            raise DataError("source bundle input changed: " + name)
    task = load_json(origin / "task.json")
    if digest_file(origin / "task.json") != manifest["task_sha256"]:
        raise DataError("source task fingerprint mismatch")
    for key, digest_key in (("toolchain_inputs", "toolchain_core_sha256"), ("oracle_inputs", "oracle_sha256")):
        if fingerprint(manifest[key]) != manifest[digest_key]:
            raise DataError("source manifest fingerprint mismatch")
    rows = load_jsonl(origin / "candidates.jsonl")
    validate_candidates(origin, manifest, task, rows)
    task_file, replaced = origin / "task.json", None
    if getattr(args, "task", None) is not None:
        task_file, replaced = args.task, same_question(manifest, task, args.task)
    # Old host source bytes need not exist here. Responses/inputs remain verified above;
    # prepare freezes the actual destination toolchain/oracle instead of trusting old gates.
    prepare(argparse.Namespace(task=task_file, bundle=args.bundle, frozen_system=origin / "system.md"))
    current, _ = read_bundle(args.bundle)
    provenance = {"manifest_sha256": digest_file(origin / "manifest.json"),
                  "candidates_sha256": digest_file(origin / "candidates.jsonl"),
                  "toolchain_core_sha256": manifest["toolchain_core_sha256"],
                  "oracle_sha256": manifest["oracle_sha256"], "gates_transferred": False}
    if replaced is not None:
        provenance["task_replaced"] = replaced
    copied = 0
    for row in rows:
        if row["origin"] != "teacher":
            continue
        directory = args.bundle / "samples" / row["id"]
        directory.mkdir(parents=True, exist_ok=False)
        for name in ("request.json", "response.json"):
            shutil.copyfile(origin / "samples" / row["id"] / name, directory / name)
        rebound = {**row, **{key: current[key] for key in ("task_sha256", "oracle_sha256", "toolchain_core_sha256")},
                   "gates": {name: "NOT_RUN" for name in GATES}, "created_at": utc_now(),
                   "imported_from": provenance}
        rebound = {key: value for key, value in rebound.items() if key not in ("static_result", "runtime_result")}
        store_candidate(args.bundle, rebound)
        copied += 1
    copy_collectors(origin, args.bundle, rows)
    write_new_json(args.bundle / "import.json", {**provenance, "teacher_responses": copied, "new_api_calls": 0})
    print(json_text({"imported_teacher_candidates": copied, "all_gates": "NOT_RUN", "new_api_calls": 0}), end="")


def same_question(manifest: dict, task: dict, task_file: Path) -> dict:
    """A newer task definition may only change which judge files are frozen, never the question the teacher answered."""
    current = load_json(task_file)
    def question(value: dict) -> dict:
        return {**value, "oracle": {key: item for key, item in value["oracle"].items() if key != "files"}}
    if question(current) != question(task):
        raise DataError("--task may only change oracle.files; requirement, contract, base and reference must stay the same")
    for name, key in (("base.sir", "base_source"), ("reference.sir", "reference_source")):
        actual = sha256_text("") if current[key] is None else digest_file(repo_file(current[key]))
        if actual != manifest["bundle_inputs"][name]:
            raise DataError("--task " + key + " no longer has the bytes the teacher was shown or judged against")
    return {"from_task_sha256": manifest["task_sha256"], "task_sha256": digest_file(task_file), "changed": "oracle.files"}


def copy_collectors(origin: Path, destination: Path, rows: list[dict]) -> None:
    import shutil
    for digest in sorted({row["collector_sha256"] for row in rows if row.get("collector_sha256")}):
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise DataError("invalid collector snapshot identifier")
        source = origin / "collectors" / digest
        inputs = load_json(source / "inputs.json")
        if set(inputs) != {"data.py", "corpus.py", "dataset_io.py"} or fingerprint(inputs) != digest:
            raise DataError("invalid collector snapshot inputs")
        if any(digest_file(source / name) != expected for name, expected in inputs.items()):
            raise DataError("collector snapshot changed after collection")
        target = destination / "collectors" / digest
        target.mkdir(parents=True, exist_ok=False)
        for name in (*inputs, "inputs.json"):
            shutil.copyfile(source / name, target / name)


def request_payload(model: str, messages: list, max_tokens: int) -> dict:
    return {"model": model, "messages": messages, "max_tokens": max_tokens,
            "temperature": 0.6, "stream": False, "thinking": {"type": "disabled"}}


def call_teacher(payload: dict) -> dict:
    from openai import APIError, OpenAI
    key = os.environ.get("DEEPSEEK_API_KEY")
    if not key:
        raise DataError("DEEPSEEK_API_KEY is not configured")
    try:
        import logging
        logging.getLogger("openai").setLevel(logging.WARNING)
        logging.getLogger("httpx").setLevel(logging.WARNING)
        with OpenAI(api_key=key, base_url="https://api.deepseek.com", max_retries=0, timeout=180.0) as client:
            response = client.chat.completions.create(
                **{key: value for key, value in payload.items() if key != "thinking"},
                extra_body={"thinking": payload["thinking"]},
            )
            return response.model_dump(mode="json")
    except APIError as error:
        # SDK messages may contain response bodies: never persist them or Authorization headers.
        raise DataError(f"teacher request failed: {type(error).__name__} HTTP={getattr(error, 'status_code', None)}; no automatic retry") from None


def generate_one(root: Path, manifest: dict, task: dict, payload: dict, parent_id: str | None = None,
                 lineage: dict | None = None) -> dict:
    sample_id = task["id"] + "-teacher-" + uuid.uuid4().hex[:12]
    directory = root / "samples" / sample_id
    directory.mkdir(parents=True, exist_ok=False)
    write_new_json(directory / "request.json", payload)
    response = call_teacher(payload)
    write_new_json(directory / "response.json", response)
    usage = response.get("usage")
    attempt = {"id": sample_id, "requested_model": payload["model"], "actual_model": response.get("model"),
               "system_fingerprint": response.get("system_fingerprint"), "usage": usage,
               "raw_response_sha256": digest_file(directory / "response.json"), "created_at": utc_now()}
    try:
        source = decode_response(response)
    except DataError as error:
        rejected = {**attempt, "status": "REJECTED", "reason": str(error)}
        write_new_json(directory / "attempt.json", rejected)
        write_jsonl(root / "attempts.jsonl", [rejected], append=True)
        return rejected
    row = {**new_row(manifest, task, sample_id, source, payload["messages"], "teacher"),
           **attempt, "request_sha256": digest_file(directory / "request.json"),
           "sdk": "openai-python/" + importlib.metadata.version("openai"), "extraction": "strip-one-fence-and-final-newline",
           "generation_mode": "feedback-repair" if parent_id else "initial", "parent_id": parent_id,
           "collector_sha256": snapshot_collector(root), **(lineage or {})}
    store_candidate(root, row)
    accepted = {**attempt, "status": "CANDIDATE_NOT_YET_QUALIFIED"}
    write_new_json(directory / "attempt.json", accepted)
    write_jsonl(root / "attempts.jsonl", [accepted], append=True)
    return accepted


def snapshot_collector(root: Path) -> str:
    import shutil
    inputs = {name: digest_file(HERE / name) for name in ("data.py", "corpus.py", "dataset_io.py")}
    digest = fingerprint(inputs)
    directory = root / "collectors" / digest
    if not directory.exists():
        directory.mkdir(parents=True, exist_ok=False)
        for name in inputs:
            shutil.copyfile(HERE / name, directory / name)
        write_new_json(directory / "inputs.json", inputs)
    return digest


def repair(args) -> None:
    manifest, task = read_bundle(args.bundle)
    rows = load_jsonl(args.checked or args.bundle / "checked.jsonl")
    validate_candidates(args.bundle, manifest, task, rows)
    parent = next((row for row in rows if row["id"] == args.sample_id), None)
    evidence_file = getattr(args, "runtime_evidence", None)
    if parent is None or parent["gates"]["static"] != ("FAILED" if evidence_file is None else "PASSED"):
        raise DataError("repair requires an explicitly selected statically failed candidate, or a statically "
                        "passed one together with its business-gate evidence")
    if not 1 <= args.max_tokens <= 16384:
        raise DataError("repair allows at most 16384 output tokens")
    ddl_path = next(path for path in task["oracle"]["files"] if path.endswith("-ddl.sql"))
    if evidence_file is None:
        feedback = (prompt_file(getattr(args, "feedback", None), DEFAULT_FEEDBACK).read_text(encoding="utf-8")
                    + "\n\n原始静态诊断：\n" + json_text(parent["static_result"]))
        lineage = None
    else:
        report, lineage = business_failure(parent, evidence_file)
        feedback = (prompt_file(getattr(args, "feedback", None), DEFAULT_RUNTIME_FEEDBACK).read_text(encoding="utf-8")
                    + "\n\n业务判卷报告（失败的检查与判卷时的 HTTP 记录）：\n" + report)
    feedback += ("\n\n题目冻结时已有的 DDL（仅约束物理形状，不允许在 SIR 内嵌 SQL）：\n"
                 + repo_file(ddl_path).read_text(encoding="utf-8"))
    messages = parent["messages"] + [{"role": "assistant", "content": parent["sir"]},
                                    {"role": "user", "content": feedback}]
    result = generate_one(args.bundle, manifest, task,
                          request_payload(args.model, messages, args.max_tokens), parent["id"], lineage)
    print(json.dumps(result, ensure_ascii=False), flush=True)


def business_failure(parent: dict, evidence_file: Path) -> tuple[str, dict]:
    """The judge's own words for a candidate that built and started but failed the business gate.

    Only the failed checks and the HTTP exchanges the judge recorded go to the teacher; the passed checks and
    the logs stay out, and nothing hints at a fix. The evidence must be this exact candidate's, unaltered.
    """
    evidence = load_json(evidence_file)
    _check_runtime_binding(parent, evidence)
    _require_runtime_provenance(evidence)
    verify_artifacts(evidence, evidence_file)
    gates = evidence.get("gates", {})
    if [gates.get(gate) for gate in ("static", "build", "start", "business")] != ["PASSED", "PASSED", "PASSED", "FAILED"]:
        raise DataError("runtime repair needs evidence of a candidate that built and started but failed the business gate")
    reports = [a["path"] for a in evidence["artifacts"] if not a["path"].startswith("logs/")]
    if len(reports) != 1:
        raise DataError("runtime evidence must carry exactly one judge report")
    blocks = (evidence_file.resolve().parent / reports[0]).read_text(encoding="utf-8").split("\n\n")
    checks = next((i for i, block in enumerate(blocks) if block.startswith("checksPassed=")), None)
    failed = [] if checks is None else [line for line in blocks[checks].splitlines() if line.startswith("FAIL ")]
    if not failed:
        raise DataError("judge report lists no failed check")
    http = "\n\n".join(block.strip() for block in blocks[checks + 1:] if block.strip())
    lineage = {"generation_mode": "runtime-feedback-repair",
               "runtime_feedback": {"run_id": evidence["run_id"], "evidence_sha256": digest_file(evidence_file)}}
    return "\n".join(failed) + "\n\n" + http + "\n", lineage


def generate(args) -> None:
    if not 1 <= args.count <= 5 or not 1 <= args.max_tokens <= 16384:
        raise DataError("pilot allows 1..5 calls per invocation and 1..16384 output tokens per call")
    manifest, task = read_bundle(args.bundle)
    payload = request_payload(args.model, prompt_messages(args.bundle, task), args.max_tokens)
    for _ in range(args.count):
        print(json.dumps(generate_one(args.bundle, manifest, task, payload), ensure_ascii=False), flush=True)


def _check_rows(root: Path, rows: list, command: list[str]) -> tuple[list, dict]:
    directory = root / "checks" / uuid.uuid4().hex
    directory.mkdir(parents=True, exist_ok=False)
    text = "".join(json.dumps({"id": row["id"], "sir": row["sir"], "stopAfter": "GENERATION"},
                              ensure_ascii=False) + "\n" for row in rows)
    (directory / "input.jsonl").write_text(text, encoding="utf-8", newline="\n")
    result = subprocess.run(command, input=text.encode("utf-8"), capture_output=True, timeout=180, cwd=REPO)
    (directory / "stdout.jsonl").write_bytes(result.stdout)
    (directory / "stderr.txt").write_bytes(result.stderr)
    write_new_json(directory / "execution.json", {"command": command, "returncode": result.returncode,
                                                  "input_sha256": digest_file(directory / "input.jsonl")})
    if result.returncode != 0:
        raise DataError(f"checker transport/internal failure: exit={result.returncode}; evidence={directory}")
    results = load_jsonl(directory / "stdout.jsonl")
    if len(results) != len(rows):
        raise DataError("checker did not return exactly one row for every input")
    checked = [bind_static(row, output) for row, output in zip(rows, results, strict=True)]
    return checked, {"directory": str(directory), "stdout_sha256": digest_file(directory / "stdout.jsonl")}


def check(args) -> None:
    manifest, task = read_bundle(args.bundle)
    rows = load_jsonl(args.bundle / "candidates.jsonl")
    validate_candidates(args.bundle, manifest, task, rows)
    checked, execution = _check_rows(args.bundle, rows, checker_command(args.maven_repo))
    output = args.output or args.bundle / "checked.jsonl"
    write_jsonl(output, checked)
    print(json_text({"output": str(output), "samples": len(checked),
                     "static_passed": sum(row["gates"]["static"] == "PASSED" for row in checked),
                     "business": "NOT_RUN", "execution": execution}), end="")


def export(args) -> None:
    manifest, task = read_bundle(args.bundle)
    rows = load_jsonl(args.checked or args.bundle / "checked.jsonl")
    validate_candidates(args.bundle, manifest, task, rows)
    proofs = {}
    for path in args.runtime_evidence:
        evidence = load_json(path)
        verify_artifacts(evidence, path)
        if evidence["id"] in proofs:
            raise DataError("multiple runtime evidence files for one sample; choose one explicitly")
        proofs[evidence["id"]] = evidence
    records, index, seen = [], [], set()
    for row in rows:
        record = training_record(row, proofs.get(row["id"]))
        if record is None:
            continue
        identity = (row["task_sha256"], row["static_result"]["digest"])
        if identity in seen:
            continue
        seen.add(identity)
        records.append(record)
        index.append({"id": row["id"], "source_sha256": row["source_sha256"],
                      "task_sha256": row["task_sha256"], "oracle_sha256": row["oracle_sha256"],
                      "runtime_run_id": proofs[row["id"]]["run_id"]})
    write_jsonl(args.output, records)
    write_new_json(args.output.with_suffix(".index.json"), {"count": len(records), "rows": index,
                   "corpus_sha256": digest_file(args.output), "all_four_gates_required": True})
    print(json_text({"checked_samples": len(rows), "runtime_evidence_files": len(proofs),
                     "exported_unique_answers": len(records), "output": str(args.output)}), end="")


def student(args) -> None:
    """Re-wrap exported answers for the student: short fixed system prompt, the frozen question, the final SIR.

    Which answers qualify is decided only by export (four gates); this command trusts nothing but its outputs,
    checked by their recorded hashes, and never re-judges. Repair history is dropped: question -> final answer.
    """
    system = prompt_file(args.system_prompt, DEFAULT_STUDENT_PROMPT).read_text(encoding="utf-8")
    records, index, seen = [], [], set()
    for bundle in args.bundle:
        manifest, task = read_bundle(bundle)
        if task["split"] != "train":
            raise DataError("student data takes train bundles only: " + str(bundle))
        corpus = bundle / args.teacher_file
        summary = load_json(corpus.with_suffix(".index.json"))
        if summary.get("corpus_sha256") != digest_file(corpus):
            raise DataError("exported answers changed after export: " + str(corpus))
        answers = load_jsonl(corpus)
        if len(answers) != summary["count"] or len(summary["rows"]) != summary["count"]:
            raise DataError("export index does not match its answers: " + str(corpus))
        for answer, proof in zip(answers, summary["rows"]):
            sir = answer["completion"][0]["content"]
            if sha256_text(sir) != proof["source_sha256"] or proof["task_sha256"] != manifest["task_sha256"]:
                raise DataError("exported answer does not match its index row: " + proof["id"])
            if digest_file(bundle / "samples" / proof["id"] / "candidate.sir") != proof["source_sha256"]:
                raise DataError("exported answer is not the collected candidate: " + proof["id"])
            if (task["id"], proof["source_sha256"]) in seen:
                continue
            seen.add((task["id"], proof["source_sha256"]))
            user = task_message(bundle, task)
            records.append({"prompt": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                            "completion": [{"role": "assistant", "content": sir}]})
            index.append({"id": proof["id"], "bundle": bundle.as_posix(), "task_id": task["id"],
                          "family": task["family"], "split": task["split"], "source_sha256": proof["source_sha256"],
                          "runtime_run_id": proof["runtime_run_id"],
                          "prompt_chars": len(system) + len(user), "completion_chars": len(sir)})
    enforce_split_disjoint([{"id": row["id"], "family": row["family"], "split": row["split"],
                             "source_sha256": row["source_sha256"]} for row in index])
    write_jsonl(args.output, records)
    write_new_json(args.output.with_suffix(".index.json"), {
        "count": len(records), "system_prompt_sha256": sha256_text(system), "rows": index,
        "corpus_sha256": digest_file(args.output)})
    print(json_text({"bundles": len(args.bundle), "student_records": len(records), "output": str(args.output)}), end="")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    prepare_args = commands.add_parser("prepare", help="freeze one task and reference seed without model calls")
    prepare_args.add_argument("--task", type=Path, default=HERE / "tasks/dual-token-v1.json")
    prepare_args.add_argument("--bundle", type=Path, required=True)
    prepare_args.add_argument("--system-prompt", type=Path, help="file in prompts/; default sir-0.3-auth.md (v1)")
    prepare_args.set_defaults(run=prepare)
    import_args = commands.add_parser("import", help="freeze current host and import raw responses with all gates reset")
    import_args.add_argument("--from-bundle", type=Path, required=True)
    import_args.add_argument("--bundle", type=Path, required=True)
    import_args.add_argument("--task", type=Path,
                             help="newer definition of the same task; only oracle.files may differ (e.g. a renamed judge file)")
    import_args.set_defaults(run=import_candidates)
    generate_args = commands.add_parser("generate", help="bounded DeepSeek teacher calls; no automatic retries")
    generate_args.add_argument("--bundle", type=Path, required=True)
    generate_args.add_argument("--model", choices=("deepseek-flash", "deepseek-v4-pro"), default="deepseek-flash")
    generate_args.add_argument("--count", type=int, default=3)
    generate_args.add_argument("--max-tokens", type=int, default=6144)
    generate_args.set_defaults(run=generate)
    repair_args = commands.add_parser("repair", help="one explicit compiler-feedback repair; keeps the failed parent")
    repair_args.add_argument("--bundle", type=Path, required=True)
    repair_args.add_argument("--sample-id", required=True)
    repair_args.add_argument("--checked", type=Path)
    repair_args.add_argument("--feedback", type=Path,
                             help="file in prompts/; default repair-auth-v1.md, or repair-runtime-v1.md with --runtime-evidence")
    repair_args.add_argument("--runtime-evidence", type=Path,
                             help="slm-runtime.json of a statically passed candidate that failed the business gate")
    repair_args.add_argument("--model", choices=("deepseek-flash", "deepseek-v4-pro"), default="deepseek-flash")
    repair_args.add_argument("--max-tokens", type=int, default=6144)
    repair_args.set_defaults(run=repair)
    check_args = commands.add_parser("check", help="one JVM, full static GENERATION; no build/start/database")
    check_args.add_argument("--bundle", type=Path, required=True)
    check_args.add_argument("--maven-repo", type=Path)
    check_args.add_argument("--output", type=Path)
    check_args.set_defaults(run=check)
    export_args = commands.add_parser("export", help="only four-gate proven training answers; empty export is valid")
    export_args.add_argument("--bundle", type=Path, required=True)
    export_args.add_argument("--checked", type=Path)
    export_args.add_argument("--runtime-evidence", type=Path, nargs="*", default=[])
    export_args.add_argument("--output", type=Path, required=True)
    export_args.set_defaults(run=export)
    student_args = commands.add_parser("student", help="re-wrap exported train answers with the short student prompt")
    student_args.add_argument("--bundle", type=Path, nargs="+", required=True)
    student_args.add_argument("--teacher-file", default="train-teacher.jsonl",
                              help="export output inside each bundle; its .index.json must sit beside it")
    student_args.add_argument("--system-prompt", type=Path, help="file in prompts/; default student-v1.md")
    student_args.add_argument("--output", type=Path, required=True)
    student_args.set_defaults(run=student)
    return result


def main() -> int:
    try:
        args = parser().parse_args()
        args.run(args)
        return 0
    except (DataError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"ERROR: {type(error).__name__}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
