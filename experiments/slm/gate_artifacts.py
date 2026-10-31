"""Portable ZIP preflight and existing candidate evidence/JUnit verification."""
from __future__ import annotations

from pathlib import Path
import re
import stat
import unicodedata
import xml.etree.ElementTree as ET
import zipfile

from experiments.slm.corpus import DataError, GATES, _check_runtime_binding, _require_runtime_provenance
from experiments.slm.dataset_io import load_json, verify_artifacts
from experiments.slm.git_source import relative_path

MAX_ZIP_BYTES = 32 * 1024 * 1024
MAX_UNPACK_BYTES = 96 * 1024 * 1024
MAX_ENTRY_BYTES = 16 * 1024 * 1024
MAX_ENTRIES = 10000


def unpack(archive: Path, target: Path, secrets: tuple[bytes, ...] = ()) -> None:
    if archive.stat().st_size > MAX_ZIP_BYTES:
        raise DataError("artifact ZIP exceeds 32 MiB")
    try:
        with zipfile.ZipFile(archive) as zipped:
            entries = zipped.infolist()
            if len(entries) > MAX_ENTRIES or sum(e.file_size for e in entries) > MAX_UNPACK_BYTES:
                raise DataError("artifact unpack budget exceeded")
            names, files = frozenset(), ()
            for entry in entries:
                raw = entry.filename[:-1] if entry.is_dir() else entry.filename
                name = relative_path(raw)
                key = unicodedata.normalize("NFC", name).casefold()
                mode = (entry.external_attr >> 16) & 0xffff
                if key in names or stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                    raise DataError("duplicate or non-regular ZIP entry")
                if entry.flag_bits & 1 or entry.file_size > MAX_ENTRY_BYTES:
                    raise DataError("encrypted or oversized ZIP entry")
                names = names | {key}
                files += ((entry, name, key),)
            leafs = {key for entry, _, key in files if not entry.is_dir()}
            if any(any("/".join(key.split("/")[:i]) in leafs for i in range(1, len(key.split("/")))) for _, _, key in files):
                raise DataError("ZIP file/directory path collision")
            for entry, name, _ in files:
                path = target / name
                if entry.is_dir():
                    path.mkdir(parents=True, exist_ok=True)
                    continue
                content = zipped.read(entry)
                if len(content) != entry.file_size:
                    raise DataError("ZIP entry length mismatch")
                if any(value and (value in content or value in name.encode()) for value in (b"kcg-ci-", *secrets)):
                    raise DataError("sensitive content found in artifact; raw bytes were not persisted")
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("xb") as output:
                    output.write(content)
    except (zipfile.BadZipFile, RuntimeError, EOFError, NotImplementedError):
        raise DataError("invalid artifact ZIP") from None


def _one(values, message):
    if len(values) != 1:
        raise DataError(message)
    return values[0]


def _junit(surefire: Path, task: dict, gates: dict) -> str:
    its = [p.split("/src/test/java/", 1)[1][:-5].replace("/", ".")
           for p in task["oracle"]["files"] if "/src/test/java/" in p and p.endswith("IT.java")]
    expected = _one(its, "oracle must identify exactly one business IT")
    found = ()
    for path in sorted(surefire.rglob("TEST-*.xml")):
        # The upload glob also catches JUnit already archived in the checkout under datasets/; that is another
        # run's evidence (kept in the raw ZIP), never this run's result.
        if path.relative_to(surefire).parts[0] == "datasets":
            continue
        try:
            text = path.read_bytes().decode("utf-8")
        except UnicodeError:
            raise DataError("JUnit must be UTF-8") from None
        if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
            raise DataError("JUnit declarations/entities are not accepted")
        try:
            element = ET.fromstring(text)
        except ET.ParseError:
            raise DataError("invalid JUnit XML") from None
        suites = [element] if element.tag == "testsuite" else list(element.iter("testsuite"))
        found += tuple(suite for suite in suites if suite.get("name") == expected)
    suite = _one(found, "expected one executed business IT JUnit suite")
    cases = list(suite.findall("testcase"))
    try:
        counts = {key: int(suite.get(key, "-1")) for key in ("tests", "failures", "errors", "skipped")}
    except ValueError:
        raise DataError("invalid JUnit counters") from None
    actual = {"tests": len(cases), **{key: sum(case.find(tag) is not None for case in cases)
               for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped"))}}
    if counts != actual or not cases or counts["skipped"] or any(c.get("classname") != expected for c in cases):
        raise DataError("business IT was missing, skipped or has inconsistent JUnit counters")
    passed = all(gates[k] == "PASSED" for k in GATES)
    if passed != (counts["failures"] == 0 and counts["errors"] == 0):
        # Some NOT_RUN gates return successfully from JUnit. They remain NOT_RUN, never PASSED.
        if passed or gates["business"] != "NOT_RUN":
            raise DataError("JUnit outcome contradicts candidate gates")
    return expected.rsplit(".", 1)[-1]


def verdict(evidence_root: Path, surefire_root: Path, row: dict, task: dict, run: dict) -> dict:
    path = _one(list(evidence_root.rglob("slm-runtime.json")), "expected exactly one candidate runtime evidence")
    evidence = load_json(path)
    _check_runtime_binding(row, evidence)
    _require_runtime_provenance(evidence)
    artifacts = evidence["artifacts"]
    names = [relative_path(a["path"]) for a in artifacts]
    if len({n.casefold() for n in names}) != len(names):
        raise DataError("duplicate runtime artifact")
    verify_artifacts(evidence, path)
    if evidence["environment"]["test_revision"] != row["task_revision"]:
        raise DataError("runtime task revision mismatch (this is not the Git commit)")
    gates = evidence.get("gates", {})
    if set(gates) != set(GATES) or any(g not in ("PASSED", "FAILED", "NOT_RUN") for g in gates.values()):
        raise DataError("invalid gate labels")
    if row["gates"]["static"] != "PASSED" or gates["static"] != "PASSED":
        raise DataError("a candidate gate requires its matching passed static result")
    states = [gates[k] for k in GATES]
    for i, state in enumerate(states):
        if state != "PASSED" and any(s != "NOT_RUN" for s in states[i + 1:]):
            raise DataError("later gate cannot run after a nonpassed earlier gate")
    reports = [n for n in names if not n.startswith("logs/")]
    report_name = _one(reports, "runtime must register exactly one business report")
    report = (path.parent / report_name).read_text(encoding="utf-8")
    header = lambda key: _one(re.findall(r"^" + key + r"=(.+)$", report, re.M), "missing or duplicate report field: " + key).strip()
    status = header("verdict")
    if header("runId") != evidence["run_id"] or path.parent.name != evidence["run_id"]:
        raise DataError("runtime/report slice identity mismatch")
    checks = _one(re.findall(r"^checksPassed=(\d+) checksFailed=(\d+)\s*$", report, re.M), "missing or ambiguous business check counters")
    passed, failed = map(int, checks)
    if failed != len(re.findall(r"^FAIL .+$", report, re.M)):
        raise DataError("failed business check count does not match its report")
    if gates["business"] == "PASSED" and (status != "PASSED" or failed or not passed):
        raise DataError("passed business gate contradicts its report")
    if gates["business"] == "FAILED" and (status != "FAILED" or failed < 1):
        raise DataError("failed business gate contradicts its report")
    if gates["business"] == "NOT_RUN" and status not in ("FAILED", "NOT_RUN"):
        raise DataError("missing business gate cannot have a PASSED report")
    if (run["conclusion"] == "success") != all(gates[k] == "PASSED" for k in GATES):
        raise DataError("GitHub conclusion contradicts candidate evidence")
    it = _junit(surefire_root, task, gates)
    return {"evidence": path, "gates": gates, "it": it, "checks_passed": passed, "checks_failed": failed,
            "failed_checks": [line[5:] for line in report.splitlines() if line.startswith("FAIL ")]}
