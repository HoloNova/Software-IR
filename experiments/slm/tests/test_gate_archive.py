"""Offline archive contract: real Git objects, ZIPs, bindings and files, no network."""
import contextlib
import importlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from experiments.slm import data
from experiments.slm.corpus import DataError
from experiments.slm.dataset_io import digest_file, json_text, load_json
from archive_fixture import BUNDLE, IT, GateFixture, commit, git, put, zipped


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*")
            if p.is_file() and ".git" not in p.relative_to(root).parts}


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.fx = GateFixture(Path(self.temp.name) / "repo")

    def api(self):
        self.assertIsNotNone(importlib.util.find_spec("experiments.slm.gate_archive"), "missing archive-gate implementation")
        return importlib.import_module("experiments.slm.gate_archive")

    def reject(self):
        before = tree(self.fx.repo)
        with self.assertRaises(DataError):
            self.fx.archive(self.api())
        self.assertEqual(before, tree(self.fx.repo))

    def test_success_preserves_raw_zip_logs_junit_and_does_not_export(self):
        result = self.fx.archive(self.api())
        target = self.fx.repo / result["archive"]
        self.assertEqual("PASSED", result["gates"]["business"])
        self.assertEqual(b"raw  \r\n", (target / "slice-1/logs/maven.stdout.log").read_bytes())
        self.assertEqual(self.fx.zips[10], (target / "github/10.zip").read_bytes())
        self.assertTrue((target / f"junit/toy/target/surefire-reports/TEST-{IT}.xml").is_file())
        self.assertEqual("1", load_json(target / "slice-1/slm-runtime.json")["environment"]["test_revision"])
        self.assertFalse((self.fx.bundle / "train-teacher.jsonl").exists())
        self.assertTrue(any(p.endswith(".log") for p in result["ignored_files"]))
        self.assertEqual(b"", git(self.fx.repo, "diff", "--cached", "--name-only"))

    def test_failure_is_archived_without_becoming_a_training_answer(self):
        self.fx = GateFixture(Path(self.temp.name) / "failed", "FAILED")
        result = self.fx.archive(self.api())
        self.assertEqual("FAILED", result["gates"]["business"])
        self.assertEqual(1, result["checks_failed"])
        self.assertFalse((self.fx.bundle / "train-teacher.jsonl").exists())

    def test_dry_run_changes_no_input_index_manifest_or_output_file(self):
        before = tree(self.fx.repo)
        index = git(self.fx.repo, "diff", "--cached", "--binary")
        result = self.fx.archive(self.api(), dry_run=True)
        self.assertTrue(result["dry_run"])
        self.assertEqual(before, tree(self.fx.repo))
        self.assertEqual(index, git(self.fx.repo, "diff", "--cached", "--binary"))

    def test_exact_duplicate_is_noop_even_when_ephemeral_metadata_changes(self):
        api = self.api()
        self.fx.archive(api)
        before = tree(self.fx.repo)
        self.fx.run["updated_at"] = "later"
        self.fx.artifacts[0]["archive_download_url"] = "https://ephemeral.invalid/new"
        result = self.fx.archive(api)
        self.assertTrue(result["already_archived"])
        self.assertEqual(before, tree(self.fx.repo))

    def test_retry_uses_a_separate_attempt_directory(self):
        api = self.api()
        first = self.fx.archive(api)
        self.fx.run["run_attempt"] = 2
        second = self.fx.archive(api)
        self.assertNotEqual(first["archive"], second["archive"])
        self.assertTrue((self.fx.repo / first["archive"]).exists())

    def test_existing_archive_tampering_is_not_overwritten(self):
        api = self.api()
        result = self.fx.archive(api)
        put(self.fx.repo / result["archive"], "slice-1/logs/maven.stdout.log", "changed")
        before = tree(self.fx.repo)
        with self.assertRaises(DataError):
            self.fx.archive(api)
        self.assertEqual(before, tree(self.fx.repo))

    def test_unrelated_historical_manifest_tampering_is_not_blessed(self):
        put(self.fx.bundle, "manifest.json", "changed\n")
        self.reject()

    def test_current_java_can_differ_from_the_actual_tested_commit(self):
        put(self.fx.repo, "toy/src/main/A.java", "class A { int newer; }\n")
        result = self.fx.archive(self.api())
        self.assertEqual(self.fx.sha, result["head_sha"])

    def test_bundle_outside_datasets_is_rejected(self):
        with self.assertRaises(DataError):
            self.api().archive_run(self.fx.repo, self.fx.repo / "elsewhere", self.fx.checked, 100, client=self.fx)

    def test_checked_outside_explicit_bundle_is_rejected(self):
        with self.assertRaises(DataError):
            self.api().archive_run(self.fx.repo, self.fx.bundle, self.fx.repo / "checked.jsonl", 100, client=self.fx)

    def test_uncommitted_checked_file_is_rejected(self):
        self.fx.checked = put(self.fx.bundle, "checked-later.jsonl", self.fx.checked.read_bytes())
        self.reject()

    def test_bundle_from_another_committed_source_cannot_reuse_the_run(self):
        put(self.fx.bundle, f"samples/{self.fx.sample}/candidate.sir", "sir 0.3\nsoftware Other {}\n")
        self.reject()

    def test_extra_main_source_at_tested_commit_is_not_ignored(self):
        put(self.fx.repo, "toy/src/main/Extra.java", "class Extra {}\n")
        self.fx.run["head_sha"] = commit(self.fx.repo)
        self.fx.refresh()
        self.reject()

    def test_missing_local_commit_does_not_fetch_or_use_current_head(self):
        self.fx.run["head_sha"] = "f" * 40
        self.fx.refresh()
        self.reject()

    def test_empty_report_is_not_invented_as_zero_checks(self):
        self.fx.ev_files["slice-1/blog-report.txt"] = b""
        self.fx.refresh()
        self.reject()

    def test_junit_skip_cannot_qualify(self):
        key = next(p for p in self.fx.sf_files if p.endswith(".xml"))
        self.fx.sf_files[key] = self.fx.sf_files[key].replace(b'skipped="0"', b'skipped="1"')
        self.fx.refresh()
        self.reject()

    def test_wrong_or_missing_business_it_cannot_qualify(self):
        self.fx.sf_files = {p: v.replace(IT.encode(), b"io.test.OtherIT") for p, v in self.fx.sf_files.items()}
        self.fx.refresh()
        self.reject()

    def test_junit_doctype_is_refused_without_entity_resolution(self):
        key = next(p for p in self.fx.sf_files if p.endswith(".xml"))
        self.fx.sf_files[key] = b'<!DOCTYPE a [<!ENTITY a "boom">]>' + self.fx.sf_files[key]
        self.fx.refresh()
        self.reject()

    def test_sensitive_text_in_any_zip_entry_refuses_the_entire_archive(self):
        self.fx.sf_files["debug.txt"] = b"kcg-ci-" + b"runtime"
        self.fx.refresh()
        self.reject()

    def test_duplicate_artifact_is_refused_instead_of_selecting_the_last(self):
        self.fx.artifacts = [*self.fx.artifacts, dict(self.fx.artifacts[0])]
        self.reject()

    def test_zip_digest_mismatch_is_refused(self):
        self.fx.artifacts[0]["digest"] = "sha256:" + "0" * 64
        self.reject()

    def test_duplicate_runtime_reports_are_refused(self):
        self.fx.ev_files["other/slm-runtime.json"] = self.fx.ev_files["slice-1/slm-runtime.json"]
        self.fx.refresh()
        self.reject()

    def test_attachment_modification_is_refused(self):
        self.fx.ev_files["slice-1/logs/maven.stdout.log"] = b"tampered\n"
        self.fx.refresh()
        self.reject()

    def test_same_candidate_id_in_another_batch_does_not_make_selection_ambiguous(self):
        put(self.fx.repo, "datasets/sir/pilots/other/samples/" + self.fx.sample + "/candidate.sir", self.fx.row["sir"])
        result = self.fx.archive(self.api())
        self.assertEqual(BUNDLE, result["bundle"])


def run_case(field, value):
    def test(self):
        self.fx.run[field] = value
        self.reject()
    return test


for name, field, value in (
    ("not_completed", "status", "in_progress"), ("cancelled", "conclusion", "cancelled"),
    ("wrong_workflow", "path", ".github/workflows/verify.yml"), ("wrong_event", "event", "push"),
    ("wrong_id", "id", 101), ("wrong_repository", "repository", {"full_name": "other/repo"}),
    ("fork_head", "head_repository", {"full_name": "other/repo"}),
    ("green_run_failed_report", "conclusion", "failure"), ("invalid_attempt", "run_attempt", 0),
):
    setattr(ArchiveTests, "test_rejects_" + name, run_case(field, value))


def binding_case(field, value):
    def test(self):
        self.fx.change_evidence(**{field: value})
        self.reject()
    return test


for name, field, value in (
    ("source", "source_sha256", "b" * 64), ("task", "task_sha256", "b" * 64),
    ("oracle", "oracle_sha256", "b" * 64), ("core", "toolchain_core_sha256", "b" * 64),
    ("generated", "generated_digest", "b" * 64), ("id", "id", "another-sample"),
    ("revision_is_not_sha", "environment", {"java": "21", "mysql": "8.4", "test_revision": "b" * 40}),
):
    setattr(ArchiveTests, "test_rejects_wrong_" + name, binding_case(field, value))


def zip_case(path):
    def test(self):
        self.fx.ev_files[path] = b"bad"
        self.fx.refresh()
        self.reject()
    return test


for i, path in enumerate(("../escape", "/absolute", "C:/drive", "a\\..\\escape", "slice-1/CON.txt",
                           "slice-1/trailing.", "slice-1/LOGS/MAVEN.STDOUT.LOG", "slice-1/logs/maven.stdout.log/child")):
    setattr(ArchiveTests, "test_rejects_nonportable_zip_path_" + str(i), zip_case(path))


class ArchiveCliTests(unittest.TestCase):
    def test_cli_requires_explicit_bundle_and_checked(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            data.parser().parse_args(["archive-gate", "100"])

    def test_cli_exposes_an_archive_command_without_export_side_effects(self):
        with contextlib.redirect_stderr(io.StringIO()):
            try:
                args = data.parser().parse_args(["archive-gate", "--bundle", "datasets/sir/x", "--checked", "datasets/sir/x/c.jsonl", "100"])
            except SystemExit:
                self.fail("missing explicit archive-gate CLI")
        self.assertEqual("archive-gate", args.command)
        self.assertEqual([100], args.runs)


if __name__ == "__main__":
    unittest.main()
