"""Explicit GitHub candidate archive; never exports, executes a collector or judges SIR."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from experiments.slm import archive_index
from experiments.slm.corpus import DataError, bind_static
from experiments.slm.dataset_io import core_input_paths, decode_json, digest_file, fingerprint, load_json, load_jsonl, write_new_json
from experiments.slm.gate_artifacts import MAX_ZIP_BYTES, unpack, verdict
from experiments.slm.git_source import GitSource, OID, local_file, relative_path, run_git

REPOSITORY = "HoloNova/Software-IR"
API = "repos/" + REPOSITORY + "/actions"
WORKFLOW = ".github/workflows/slm-business-gate.yml"


def _read_response(stream, limit):
    chunks, size = (), 0
    while True:
        chunk = stream.read(min(65536, limit - size + 1))
        if not chunk:
            return b"".join(chunks)
        size += len(chunk)
        if size > limit:
            raise DataError("GitHub response exceeds the byte budget")
        chunks += (chunk,)


class GitHubClient:
    """gh owns authentication. Bounded bytes/time, no raw stderr or credentials in argv."""
    def _call(self, endpoint: str, limit: int):
        if not endpoint.startswith(API + "/"):
            raise DataError("only the fixed GitHub repository API is allowed")
        env = {k: v for k, v in os.environ.items() if k not in ("GH_DEBUG", "DEBUG")}
        process = None
        pool = ThreadPoolExecutor(max_workers=1)
        try:
            process = subprocess.Popen(["gh", "api", "--hostname", "github.com", endpoint], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env)
            assert process.stdout is not None
            result = pool.submit(_read_response, process.stdout, limit).result(timeout=90)
            if process.wait(timeout=5):
                raise DataError("GitHub request failed; no raw response disclosure or automatic retry")
            return result
        except (OSError, subprocess.SubprocessError, FutureTimeout):
            raise DataError("GitHub transport failed; no automatic retry") from None
        finally:
            if process is not None:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)
            pool.shutdown(wait=True, cancel_futures=True)
            if process is not None and process.stdout is not None:
                process.stdout.close()

    def json(self, endpoint: str):
        from experiments.slm.dataset_io import MAX_JSON_BYTES
        raw = self._call(endpoint, MAX_JSON_BYTES)
        try:
            return decode_json(raw)
        except (ValueError, UnicodeError):
            raise DataError("invalid GitHub JSON response; body not disclosed") from None

    def download(self, artifact_id: int, destination: Path):
        content = self._call(f"{API}/artifacts/{artifact_id}/zip", MAX_ZIP_BYTES)
        with destination.open("xb") as output:
            output.write(content)


def _run(client, run_id):
    if type(run_id) is not int or run_id < 1:
        raise DataError("run id must be a positive integer")
    run = client.json(f"{API}/runs/{run_id}")
    if not isinstance(run, dict) or any(run.get(k) != v for k, v in {
            "id": run_id, "path": WORKFLOW, "event": "workflow_dispatch", "status": "completed"}.items()):
        raise DataError("run is not the requested completed candidate workflow")
    if any(not isinstance(run.get(k), dict) or run[k].get("full_name") != REPOSITORY for k in ("repository", "head_repository")):
        raise DataError("run repository mismatch")
    if not isinstance(run.get("head_sha"), str) or not OID.fullmatch(run["head_sha"]):
        raise DataError("run needs its explicit full tested commit, never implicit HEAD")
    if run.get("conclusion") not in ("success", "failure") or type(run.get("run_attempt")) is not int or run["run_attempt"] < 1:
        raise DataError("cancelled/invalid candidate run has no acceptable verdict")
    if run.get("html_url") != f"https://github.com/{REPOSITORY}/actions/runs/{run_id}":
        raise DataError("run URL does not match its identity")
    return run


def _artifacts(client, run):
    collected = []
    for page in range(1, 101):
        result = client.json(f"{API}/runs/{run['id']}/artifacts?per_page=100&page={page}")
        if not isinstance(result, dict) or not isinstance(result.get("artifacts"), list) or type(result.get("total_count")) is not int:
            raise DataError("invalid GitHub artifact page")
        if any(not isinstance(item, dict) or not isinstance(item.get("name"), str) for item in result["artifacts"]):
            raise DataError("invalid GitHub artifact object")
        collected = [*collected, *result["artifacts"]]
        if len(collected) >= result["total_count"]:
            if len(collected) != result["total_count"]:
                raise DataError("inconsistent artifact pagination")
            break
        if not result["artifacts"]:
            raise DataError("truncated artifact pagination")
    else:
        raise DataError("too many artifact pages")
    evidence = [a for a in collected if a.get("name", "").startswith("slm-gate-") and not a["name"].startswith("slm-gate-surefire-")]
    if len(evidence) != 1:
        raise DataError("expected one candidate evidence artifact, not an arbitrary first match")
    sample = evidence[0]["name"][len("slm-gate-"):]
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,159}", sample):
        raise DataError("invalid artifact sample id")
    suites = [a for a in collected if a.get("name") == "slm-gate-surefire-" + sample]
    if len(suites) != 1:
        raise DataError("expected exactly one matching surefire artifact")
    selected = [evidence[0], suites[0]]
    if selected[0].get("id") == selected[1].get("id"):
        raise DataError("artifact identities collide")
    for item in selected:
        if item.get("expired") is not False or type(item.get("id")) is not int or item["id"] < 1:
            raise DataError("expired or invalid artifact")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(item.get("digest", ""))):
            raise DataError("GitHub artifact SHA256 is required")
        provenance = item.get("workflow_run", {})
        if not isinstance(provenance, dict) or provenance.get("id") != run["id"] or provenance.get("head_sha") != run["head_sha"]:
            raise DataError("artifact workflow identity mismatch")
        if type(item.get("size_in_bytes")) is not int or not 0 < item["size_in_bytes"] <= MAX_ZIP_BYTES:
            raise DataError("artifact download budget exceeded")
    return sample, selected


def _paths(repo, bundle, checked):
    repo = repo.resolve(strict=True)
    bundle, checked = bundle.resolve(), checked.resolve()
    if not bundle.is_relative_to(repo / "datasets/sir") or bundle == repo / "datasets/sir" or not checked.is_relative_to(bundle):
        raise DataError("explicit bundle/checked must stay inside datasets/sir and that bundle")
    prefix = relative_path(bundle.relative_to(repo).as_posix())
    relative = relative_path(checked.relative_to(repo).as_posix())
    local_file(repo, prefix + "/manifest.json")
    local_file(repo, relative)
    return repo, bundle, checked, prefix, relative


def _tested_candidate(repo, bundle, checked, run, sample):
    from experiments.slm.data import validate_candidates
    source = GitSource.at(repo, run["head_sha"])
    prefix = bundle.relative_to(repo).as_posix() + "/"
    key = checked.relative_to(repo).as_posix()
    first = source.blobs([prefix + "manifest.json", prefix + "task.json", key])
    for name, content in first.items():
        if local_file(repo, name).read_bytes() != content:
            raise DataError("local bundle/check differs from its tested Git blob: " + name)
    manifest, task = decode_json(first[prefix + "manifest.json"]), decode_json(first[prefix + "task.json"])
    if set(manifest["bundle_inputs"]) != {"task.json", "base.sir", "reference.sir", "system.md"}:
        raise DataError("unexpected frozen bundle inputs")
    if set(manifest["oracle_inputs"]) != set(task["oracle"]["files"]) or set(manifest["toolchain_inputs"]) != set(core_input_paths(source.entries)):
        raise DataError("tested oracle/toolchain input set does not match the frozen manifest")
    frozen = {**{prefix + n: h for n, h in manifest["bundle_inputs"].items()}, **manifest["toolchain_inputs"], **manifest["oracle_inputs"]}
    blobs = source.blobs(frozen)
    if any(hashlib.sha256(blobs[n]).hexdigest() != h for n, h in frozen.items()):
        raise DataError("tested frozen input fingerprint mismatch")
    for n in manifest["bundle_inputs"]:
        if local_file(bundle, n).read_bytes() != blobs[prefix + n]:
            raise DataError("frozen local bundle input changed")
    if manifest["task_sha256"] != hashlib.sha256(first[prefix + "task.json"]).hexdigest():
        raise DataError("frozen task fingerprint mismatch")
    if any(fingerprint(manifest[k]) != manifest[h] for k, h in (
            ("toolchain_inputs", "toolchain_core_sha256"), ("oracle_inputs", "oracle_sha256"))):
        raise DataError("invalid frozen aggregate fingerprint")
    rows = [r for r in load_jsonl(checked) if r.get("id") == sample]
    if len(rows) != 1 or rows[0].get("origin") != "teacher":
        raise DataError("checked file must select exactly one teacher candidate")
    row = rows[0]
    if bind_static(row, row.get("static_result", {}))["gates"]["static"] != "PASSED":
        raise DataError("candidate has no passed full-generation static result")
    inputs = [prefix + f"samples/{sample}/" + n for n in ("candidate.json", "candidate.sir", "request.json", "response.json")]
    committed = source.blobs(inputs)
    if any(local_file(repo, n).read_bytes() != content for n, content in committed.items()):
        raise DataError("candidate source/metadata differs from the tested Git blob")
    validate_candidates(bundle, manifest, task, [row], collector_repo=repo)
    return row, task


def _ignored(repo: Path, names: list[str]) -> list[str]:
    output = []
    for offset in range(0, len(names), 50):
        raw = run_git(repo, ["-c", "core.quotepath=false", "check-ignore", "--no-index", "--", *names[offset:offset + 50]], allowed=(0, 1))
        output = [*output, *raw.decode("utf-8").splitlines()]
    return output


def _gate(run, artifacts, bundle, checked, result, initial):
    return {"workflow": WORKFLOW, "run_id": run["id"], "run_attempt": run["run_attempt"], "run_url": run["html_url"],
            "head_sha": run["head_sha"], "checked": checked,
            "inputs": {"bundle": bundle, "sample_id": artifacts[0]["name"][len("slm-gate-"):], "it": result["it"]},
            "artifacts": {a["name"]: {"id": a["id"], "zip_sha256": a["digest"][7:]} for a in artifacts},
            "job_conclusion": run["conclusion"], "gates": result["gates"], "checks_passed": result["checks_passed"],
            "checks_failed": result["checks_failed"], "failed_checks": result["failed_checks"], "index_before": initial}


def _existing(target: Path, incoming: Path, expected_gate: dict):
    old = archive_index.verify_archive(target)
    gate = load_json(target / "gate-run.json")
    if {k: v for k, v in gate.items() if k != "index_before"} != {k: v for k, v in expected_gate.items() if k != "index_before"}:
        raise DataError("existing archive identity conflict")
    omit = {"MANIFEST.sha256", "gate-run.json", "github/run.json", "github/artifacts.json"}
    new = archive_index.files(incoming)
    if {p: h for p, h in old.items() if p not in omit} != {p: h for p, h in new.items() if p not in omit}:
        raise DataError("existing archive bytes conflict with the validated artifacts")
    if set(old) != set(new):
        raise DataError("existing archive has missing or unknown files")
    return old, gate["index_before"]


def _materialize(work, client, artifacts, secrets, row, task, run):
    zipped = ()
    for index, artifact in enumerate(artifacts):
        path = work / (str(artifact["id"]) + ".zip")
        client.download(artifact["id"], path)
        if path.stat().st_size != artifact["size_in_bytes"] or "sha256:" + digest_file(path) != artifact["digest"]:
            raise DataError("artifact ZIP does not match GitHub size/SHA256")
        unpack(path, work / str(index), secrets)
        zipped += (path,)
    return verdict(work / "0", work / "1", row, task, run), zipped


def _install(stage, target, existing, plans):
    if not existing:
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".sir-gate-stage-", dir=target.parent) as local:
            pending = Path(local) / "complete"
            shutil.copytree(stage, pending)
            if target.exists():
                raise DataError("archive destination appeared concurrently")
            os.rename(pending, target)
    try:
        archive_index.apply(plans)
    except (OSError, DataError):
        raise DataError("ARCHIVE_WRITTEN_INDEX_PENDING: rerun the same run to verify and finish indexes") from None


def _archive_content(stage, evidence_dir, surefire_dir, zipped, run, artifacts, gate):
    shutil.copytree(evidence_dir, stage / evidence_dir.name)
    shutil.copytree(surefire_dir, stage / "junit")
    for item, source in zip(artifacts, zipped, strict=True):
        target = stage / "github" / (str(item["id"]) + ".zip")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    write_new_json(stage / "github/run.json", run)
    write_new_json(stage / "github/artifacts.json", {"artifacts": artifacts})
    write_new_json(stage / "gate-run.json", gate)
    (stage / "MANIFEST.sha256").write_bytes(archive_index.manifest_bytes(archive_index.files(stage)))


def archive_run(repo: Path, bundle: Path, checked: Path, run_id: int, *, dry_run=False, client=None) -> dict:
    repo, bundle, checked, prefix, checked_name = _paths(repo, bundle, checked)
    client = client or GitHubClient()
    run = _run(client, run_id)
    sample, artifacts = _artifacts(client, run)
    row, task = _tested_candidate(repo, bundle, checked, run, sample)
    target = bundle / "runtime" / sample / f"github-{run_id}-attempt-{run['run_attempt']}"
    # Never follow pre-existing output aliases, even if they resolve inside the repo.
    cursor = bundle
    for part in target.relative_to(bundle).parts:
        cursor = cursor / part
        if cursor.is_symlink() or (cursor.exists() and getattr(cursor.lstat(), "st_file_attributes", 0) & 0x400):
            raise DataError("archive destination contains a linked path")
    secrets = tuple(v.encode() for k, v in os.environ.items() if len(v) >= 4 and (
        k in ("GH_TOKEN", "GITHUB_TOKEN", "DEEPSEEK_API_KEY") or k.startswith("KCG_CONF_") and "PASSWORD" in k))
    with tempfile.TemporaryDirectory(prefix="sir-gate-download-") as work:
        work = Path(work)
        result, zipped = _materialize(work, client, artifacts, secrets, row, task, run)
        initial = archive_index.bases(repo, bundle)
        gate = _gate(run, artifacts, prefix, checked_name, result, initial)
        stage = work / "staged"
        stage.mkdir()
        _archive_content(stage, result["evidence"].parent, work / "1", zipped, run, artifacts, gate)
        existing = target.exists()
        inventory = archive_index.files(stage)
        if existing:
            inventory, initial = _existing(target, stage, gate)
        plans = archive_index.plan(repo, bundle, target, inventory, initial)
        names = [target.relative_to(repo).as_posix() + "/" + p for p in inventory]
        ignored = _ignored(repo, names)
        if not dry_run:
            _install(stage, target, existing, plans)
        return {"run_id": run_id, "run_attempt": run["run_attempt"], "head_sha": run["head_sha"], "sample": sample,
                "bundle": prefix, "checked": checked_name, "gates": result["gates"], "checks_passed": result["checks_passed"],
                "checks_failed": result["checks_failed"], "archive": target.relative_to(repo).as_posix(),
                "already_archived": existing, "dry_run": bool(dry_run), "ignored_files": ignored, "files": names}


def archive_runs(args):
    from experiments.slm.dataset_io import REPO
    for ident in args.runs:
        print(json.dumps(archive_run(REPO, args.bundle, args.checked, ident, dry_run=args.dry_run),
                         ensure_ascii=False, allow_nan=False), flush=True)
