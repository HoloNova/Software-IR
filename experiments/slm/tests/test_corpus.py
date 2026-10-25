"""Data-label and leakage guards; no network, GPU, Java service or database."""
import copy
import unittest

from experiments.slm.corpus import (
    DataError, bind_static, decode_response, enforce_split_disjoint,
    sha256_text, training_record,
)

SIR = "sir 0.3\nsoftware Test {}\n"


def candidate():
    return {
        "id": "auth-001", "task_id": "auth-dual-token-v1", "task_revision": "1",
        "family": "auth.dual-token.rotation", "split": "train", "origin": "teacher",
        "task_sha256": "a" * 64, "oracle_sha256": "b" * 64,
        "toolchain_core_sha256": "c" * 64, "sir": SIR,
        "source_sha256": sha256_text(SIR),
        "messages": [{"role": "user", "content": "Implement the frozen task."}],
        "gates": {name: "NOT_RUN" for name in ("static", "build", "start", "business")},
    }


def check_result():
    return {
        "id": "auth-001", "ok": True, "stage": "GENERATION",
        "sourceSha256": sha256_text(SIR), "digest": "d" * 64, "fileCount": 25,
        "diagnostics": [{"severity": "WARNING", "code": "SIR-WARN-001"}],
    }


def runtime(row):
    return {
        "schema_version": "sir-runtime-evidence/1",
        **{key: row[key] for key in ("id", "task_sha256", "oracle_sha256",
                                    "toolchain_core_sha256", "source_sha256")},
        "generated_digest": row["static_result"]["digest"],
        "gates": {name: "PASSED" for name in ("static", "build", "start", "business")},
        "run_id": "reference-runtime-01",
        "environment": {"java": "21", "mysql": "8.4", "test_revision": "1"},
        "artifacts": [{"path": "report.txt", "sha256": "e" * 64}],
    }


class ResponseTests(unittest.TestCase):
    def response(self, content=SIR, finish="stop"):
        return {"model": "teacher-version", "choices": [
            {"finish_reason": finish, "message": {"content": content}}]}

    def test_plain_source_and_one_fence_have_the_same_extracted_source(self):
        self.assertEqual(SIR.strip() + "\n", decode_response(self.response()))
        self.assertEqual(SIR.strip() + "\n", decode_response(self.response("```sir\n" + SIR + "```")))

    def test_truncated_completion_is_not_a_candidate_even_if_it_starts_with_sir(self):
        with self.assertRaisesRegex(DataError, "finish_reason"):
            decode_response(self.response(finish="length"))

    def test_multiple_fences_explanation_and_wrong_version_are_rejected(self):
        for text in ("Here is the source:\n" + SIR, "```sir\n" + SIR + "```\n```sir\n" + SIR + "```", "sir 0.2\nsoftware Test {}"):
            with self.subTest(text=text[:30]), self.assertRaises(DataError):
                decode_response(self.response(text))

    def test_missing_choices_and_non_text_content_are_not_silent_empty_answers(self):
        for response in ({"choices": []}, self.response(None), {}):
            with self.subTest(response=response), self.assertRaises(DataError):
                decode_response(response)


class QualificationTests(unittest.TestCase):
    def test_warning_does_not_fail_generation_and_input_is_not_mutated(self):
        original = candidate()
        before = copy.deepcopy(original)
        checked = bind_static(original, check_result())
        self.assertEqual("PASSED", checked["gates"]["static"])
        self.assertEqual(before, original)
        self.assertEqual("NOT_RUN", checked["gates"]["business"])
        self.assertEqual("WARNING", checked["static_result"]["diagnostics"][0]["severity"])

    def test_parse_only_is_not_full_static_qualification(self):
        checked = bind_static(candidate(), {**check_result(), "stage": "PARSE", "digest": None})
        self.assertEqual("FAILED", checked["gates"]["static"])

    def test_wrong_sample_or_source_fingerprint_cannot_be_attached(self):
        for change in ({"id": "different"}, {"sourceSha256": "0" * 64}):
            with self.subTest(change=change), self.assertRaises(DataError):
                bind_static(candidate(), {**check_result(), **change})

    def test_error_diagnostic_prevents_success_even_with_ok_true(self):
        checked = bind_static(candidate(), {**check_result(), "diagnostics": [{"severity": "ERROR"}]})
        self.assertEqual("FAILED", checked["gates"]["static"])

    def test_static_pass_without_runtime_is_never_a_training_answer(self):
        row = bind_static(candidate(), check_result())
        self.assertIsNone(training_record(row, None))

    def test_complete_bound_business_evidence_exports_prompt_completion(self):
        row = bind_static(candidate(), check_result())
        record = training_record(row, runtime(row))
        self.assertEqual(row["messages"], record["prompt"])
        self.assertEqual([{"role": "assistant", "content": SIR}], record["completion"])

    def test_failed_or_not_run_runtime_gate_is_not_exportable(self):
        row = bind_static(candidate(), check_result())
        for gate in ("build", "start", "business"):
            for status in ("FAILED", "NOT_RUN"):
                evidence = runtime(row)
                evidence["gates"] = {**evidence["gates"], gate: status}
                with self.subTest(gate=gate, status=status):
                    self.assertIsNone(training_record(row, evidence))

    def test_runtime_from_another_task_source_toolchain_or_oracle_is_rejected(self):
        row = bind_static(candidate(), check_result())
        for key in ("id", "task_sha256", "oracle_sha256", "toolchain_core_sha256", "source_sha256", "generated_digest"):
            evidence = {**runtime(row), key: "different"}
            with self.subTest(key=key), self.assertRaises(DataError):
                training_record(row, evidence)

    def test_environment_and_artifact_provenance_are_required(self):
        row = bind_static(candidate(), check_result())
        for change in ({"environment": {}}, {"artifacts": []}, {"run_id": ""}):
            with self.subTest(change=change), self.assertRaises(DataError):
                training_record(row, {**runtime(row), **change})

    def test_development_and_test_answers_cannot_enter_sft(self):
        for split in ("development", "test", "public_regression"):
            row = bind_static({**candidate(), "split": split}, check_result())
            with self.subTest(split=split):
                self.assertIsNone(training_record(row, runtime(row)))


class LeakageTests(unittest.TestCase):
    def test_same_family_must_not_cross_train_and_test_even_after_renaming(self):
        second = {**candidate(), "id": "renamed", "split": "test", "sir": SIR.replace("Test", "Other")}
        with self.assertRaisesRegex(DataError, "family"):
            enforce_split_disjoint([candidate(), second])

    def test_identical_source_cannot_cross_otherwise_distinct_families(self):
        second = {**candidate(), "id": "other", "family": "another.family", "split": "test"}
        with self.assertRaisesRegex(DataError, "source"):
            enforce_split_disjoint([candidate(), second])

    def test_multiple_samples_of_one_training_family_are_allowed(self):
        self.assertIsNone(enforce_split_disjoint([candidate(), {**candidate(), "id": "second"}]))


if __name__ == "__main__":
    unittest.main()
