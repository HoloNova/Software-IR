"""Experiment-file tampering must fail closed; never turn missing evidence into success."""
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from experiments.slm.corpus import DataError, bind_static, training_record
from experiments.slm.dataset_io import digest_file, load_json, load_jsonl, verify_artifacts, write_new_json
from test_corpus import candidate, check_result, runtime


class TamperingTests(unittest.TestCase):
    def test_changed_source_cannot_reuse_the_old_checker_hash(self):
        row = {**candidate(), "sir": "sir 0.3\nsoftware Altered {}\n"}
        with self.assertRaisesRegex(DataError, "source"):
            bind_static(row, check_result())

    def test_source_changed_after_static_check_cannot_be_exported(self):
        row = bind_static(candidate(), check_result())
        evidence = runtime(row)
        changed = {**row, "sir": "sir 0.3\nsoftware Altered {}\n"}
        with self.assertRaisesRegex(DataError, "source"):
            training_record(changed, evidence)

    def test_missing_jsonl_is_an_error_not_an_empty_successful_batch(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                load_jsonl(Path(directory) / "missing.jsonl")

    def test_json_and_jsonl_both_reject_duplicate_keys_and_nonfinite_numbers(self):
        for content in ('{"id":"a","id":"b"}\n', '{"value":NaN}\n'):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "input.jsonl"
                path.write_text(content, encoding="utf-8")
                for reader in (load_json, load_jsonl):
                    with self.subTest(content=content, reader=reader.__name__), self.assertRaises((DataError, ValueError)):
                        reader(path)

    def test_modified_runtime_artifact_is_not_reusable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "report.txt"
            log.write_text("business=PASSED\n", encoding="utf-8")
            evidence = {"artifacts": [{"path": "report.txt", "sha256": digest_file(log)}]}
            verify_artifacts(evidence, root / "evidence.json")
            log.write_text("business=FAILED\n", encoding="utf-8")
            with self.assertRaises(DataError):
                verify_artifacts(evidence, root / "evidence.json")

    def test_traversing_artifact_path_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(DataError):
                verify_artifacts({"artifacts": [{"path": "../secret", "sha256": "a" * 64}]}, Path(directory) / "proof.json")

    def test_evidence_is_not_silently_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            write_new_json(path, {"revision": 1})
            with self.assertRaises(FileExistsError):
                write_new_json(path, {"revision": 2})
            self.assertEqual(1, load_json(path)["revision"])


if __name__ == "__main__":
    unittest.main()
