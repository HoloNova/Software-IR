"""Archive finished slm-business-gate runs into their bundles (temporary handoff helper; delete with docs/tmp).

Usage (from the repository root):
    python3 docs/tmp/archive_gate_runs.py [--dry-run] RUN_ID [RUN_ID ...]

For each run it downloads the two artifacts (slm-gate-<sample>, slm-gate-surefire-<sample>), checks each zip
against GitHub's digest, refuses any file that contains a CI password, checks that the evidence is bound to the
statically passed candidate (task/oracle/toolchain/source hashes and generated digest) and that every evidence
attachment matches its recorded hash, then copies the evidence and surefire reports to
<bundle>/runtime/<sample>/ and writes gate-run.json. A run whose business gate PASSED is exported to
<bundle>/train-teacher.jsonl (skipped with a warning when that file already exists).

Authentication: uses $GITHUB_TOKEN, else `gh auth token`, else no token (works only where a proxy adds it).
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = "HoloNova/Software-IR"
API = "https://api.github.com/repos/" + REPO
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from experiments.slm.corpus import _check_runtime_binding, _require_runtime_provenance  # noqa: E402
from experiments.slm.dataset_io import load_jsonl, verify_artifacts  # noqa: E402


def token() -> str | None:
    if os.environ.get("GITHUB_TOKEN"):
        return os.environ["GITHUB_TOKEN"]
    try:
        return subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, check=True).stdout.strip() or None
    except (OSError, subprocess.CalledProcessError):
        return None


def curl(url: str, output: Path | None, auth: str | None) -> bytes:
    command = ["curl", "-sSfL", "-H", "Accept: application/vnd.github+json"]
    if auth:
        command += ["-H", "Authorization: Bearer " + auth]
    command += (["-o", str(output)] if output else []) + [url]
    return subprocess.run(command, capture_output=True, check=True).stdout


def checked_row(bundle: Path, sample: str) -> tuple[str, dict]:
    """The latest static round that holds this sample (checked-r2 before checked-r1 before checked)."""
    for name in sorted((p.name for p in bundle.glob("checked*.jsonl")), reverse=True):
        rows = [r for r in load_jsonl(bundle / name) if r["id"] == sample]
        if rows:
            if rows[0]["gates"]["static"] != "PASSED":
                raise SystemExit(f"{sample}: {name} says static {rows[0]['gates']['static']}")
            return name, rows[0]
    raise SystemExit(f"{sample}: no checked*.jsonl in {bundle} holds it")


def archive(run_id: str, auth: str | None, dry_run: bool, work: Path) -> dict:
    run = json.loads(curl(f"{API}/actions/runs/{run_id}", None, auth))
    if run["status"] != "completed":
        raise SystemExit(f"run {run_id} is {run['status']}")
    artifacts = {a["name"]: a for a in json.loads(curl(f"{API}/actions/runs/{run_id}/artifacts", None, auth))["artifacts"]}
    evidence_name = next(n for n in artifacts if n.startswith("slm-gate-") and not n.startswith("slm-gate-surefire-"))
    sample = evidence_name[len("slm-gate-"):]
    extracted = {}
    for kind, name in (("ev", evidence_name), ("sf", "slm-gate-surefire-" + sample)):
        artifact = artifacts[name]
        archive_file = work / f"{artifact['id']}.zip"
        curl(f"{API}/actions/artifacts/{artifact['id']}/zip", archive_file, auth)
        digest = hashlib.sha256(archive_file.read_bytes()).hexdigest()
        if "sha256:" + digest != artifact["digest"]:
            raise SystemExit(f"{name}: zip sha256 {digest} does not match GitHub {artifact['digest']}")
        target = work / f"{sample}-{kind}"
        subprocess.run(["unzip", "-q", str(archive_file), "-d", str(target)], check=True)
        extracted[kind] = (target, artifact["id"], digest)
    leaked = subprocess.run(["grep", "-rl", "kcg-ci-", str(work / f"{sample}-ev"), str(work / f"{sample}-sf")],
                            capture_output=True, text=True).stdout.strip()
    if leaked:
        raise SystemExit(f"{sample}: CI password found in {leaked}")

    bundles = [Path(b) for b in glob.glob("datasets/sir/*/*") if (Path(b) / "samples" / sample).is_dir()]
    if len(bundles) != 1:
        raise SystemExit(f"{sample}: expected one bundle, found {bundles}")
    bundle = bundles[0]
    checked, row = checked_row(bundle, sample)
    evidence_file = next(extracted["ev"][0].glob("*/slm-runtime.json"))
    evidence = json.loads(evidence_file.read_text(encoding="utf-8"))
    _check_runtime_binding(row, evidence)
    _require_runtime_provenance(evidence)
    verify_artifacts(evidence, evidence_file)
    report = next(evidence_file.parent.glob("*report*.txt")).read_text(encoding="utf-8")
    passed = int(report.split("checksPassed=")[1].split()[0])
    failed = int(report.split("checksFailed=")[1].split()[0])
    if (evidence["gates"]["business"] == "PASSED") != (run["conclusion"] == "success"):
        raise SystemExit(f"{sample}: job {run['conclusion']} but business {evidence['gates']['business']}")
    reports = sorted(glob.glob(str(extracted["sf"][0] / "**/surefire-reports/*"), recursive=True))
    it = next(Path(r).name[len("TEST-io.kcg.sir.application.conformance."):-len(".xml")]
              for r in reports if Path(r).name.startswith("TEST-") and r.endswith("IT.xml"))
    gate = {"workflow": ".github/workflows/slm-business-gate.yml", "run_id": run["id"], "run_url": run["html_url"],
            "head_sha": run["head_sha"], "inputs": {"bundle": bundle.as_posix(), "sample_id": sample, "it": it},
            "artifacts": {evidence_name: {"id": extracted["ev"][1], "zip_sha256": extracted["ev"][2]},
                          "slm-gate-surefire-" + sample: {"id": extracted["sf"][1], "zip_sha256": extracted["sf"][2]}},
            "job_conclusion": run["conclusion"], "gates": evidence["gates"],
            "checks_passed": passed, "checks_failed": failed}
    if failed:
        gate["failed_checks"] = [line[5:] for line in report.splitlines() if line.startswith("FAIL ")]
    summary = {"run": run["run_number"], "sample": sample, "bundle": bundle.as_posix(), "checked": checked,
               "gates": evidence["gates"], "checks": f"{passed}/{failed}", "failed_checks": gate.get("failed_checks", [])}
    if dry_run:
        return summary
    destination = bundle / "runtime" / sample
    shutil.copytree(evidence_file.parent, destination / evidence_file.parent.name)
    (destination / "junit").mkdir()
    for path in reports:
        shutil.copy(path, destination / "junit")
    (destination / "gate-run.json").write_text(json.dumps(gate, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if evidence["gates"]["business"] == "PASSED":
        output = bundle / "train-teacher.jsonl"
        if output.exists():
            print(f"WARNING {sample}: {output} exists, export skipped", file=sys.stderr)
        else:
            subprocess.run([sys.executable, "-m", "experiments.slm.data", "export", "--bundle", str(bundle),
                            "--checked", str(bundle / checked), "--runtime-evidence",
                            str(destination / evidence_file.parent.name / "slm-runtime.json"),
                            "--output", str(output)], check=True, stdout=subprocess.DEVNULL)
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="download and verify only; write nothing")
    parser.add_argument("runs", nargs="+")
    args = parser.parse_args()
    auth = token()
    for run_id in args.runs:
        with tempfile.TemporaryDirectory() as work:
            print(json.dumps(archive(run_id, auth, args.dry_run, Path(work)), ensure_ascii=False))


if __name__ == "__main__":
    main()
