"""Pilot orchestration contract: real artifact writes, mocked paid API, no service/database."""
import argparse
import json
import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from experiments.slm import data
from experiments.slm.corpus import DataError, bind_static
from experiments.slm.dataset_io import load_json, load_jsonl, write_jsonl


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "bundle"
        with contextlib.redirect_stdout(io.StringIO()):
            data.prepare(argparse.Namespace(bundle=self.root, task=data.HERE / "tasks/dual-token-v1.json"))
        self.manifest, self.task = data.read_bundle(self.root)

    def test_reference_is_not_silently_exported_using_its_historical_ci_result(self):
        row = load_jsonl(self.root / "candidates.jsonl")[0]
        self.assertEqual("existing_reference", row["origin"])
        output = {"id": row["id"], "sourceSha256": row["source_sha256"], "stage": "GENERATION",
                  "ok": True, "diagnostics": [], "fileCount": 10, "digest": "a" * 64}
        write_jsonl(self.root / "checked.jsonl", [bind_static(row, output)])
        with contextlib.redirect_stdout(io.StringIO()):
            data.export(argparse.Namespace(bundle=self.root, checked=None, runtime_evidence=[], output=self.root / "train.jsonl"))
        self.assertEqual([], load_jsonl(self.root / "train.jsonl"))
        self.assertEqual(0, load_json(self.root / "train.index.json")["count"])

    def test_raw_request_response_usage_and_extracted_source_remain_bound(self):
        messages = data.prompt_messages(self.root, self.task)
        self.assertNotIn((self.root / "reference.sir").read_text(encoding="utf-8"), str(messages))
        payload = data.request_payload("deepseek-flash", messages, 6144)
        response = {"model": "teacher-test-version", "system_fingerprint": None,
                    "usage": {"prompt_tokens": 100, "completion_tokens": 50},
                    "choices": [{"finish_reason": "stop", "message": {"content": "sir 0.3\nsoftware Sample {}\n"}}]}
        with patch.object(data, "call_teacher", return_value=response) as call:
            result = data.generate_one(self.root, self.manifest, self.task, payload)
        call.assert_called_once_with(payload)
        self.assertEqual("CANDIDATE_NOT_YET_QUALIFIED", result["status"])
        rows = load_jsonl(self.root / "candidates.jsonl")
        data.validate_candidates(self.root, self.manifest, self.task, rows)
        generated = rows[-1]
        self.assertEqual(50, generated["usage"]["completion_tokens"])
        self.assertEqual("NOT_RUN", generated["gates"]["business"])
        source_file = self.root / "samples" / generated["id"] / "candidate.sir"
        source_file.write_text("sir 0.3\nsoftware Changed {}\n", encoding="utf-8")
        with self.assertRaisesRegex(DataError, "source"):
            data.validate_candidates(self.root, self.manifest, self.task, rows)

    def test_truncated_api_output_is_preserved_but_never_added_as_a_candidate(self):
        response = {"model": "teacher-test", "choices": [
            {"finish_reason": "length", "message": {"content": "sir 0.3\nsoftware Incomplete {"}}]}
        payload = data.request_payload("deepseek-flash", data.prompt_messages(self.root, self.task), 10)
        with patch.object(data, "call_teacher", return_value=response):
            result = data.generate_one(self.root, self.manifest, self.task, payload)
        self.assertEqual("REJECTED", result["status"])
        self.assertEqual(1, len(load_jsonl(self.root / "candidates.jsonl")))
        self.assertTrue((self.root / "samples" / result["id"] / "response.json").is_file())

    def test_one_feedback_repair_keeps_failed_parent_and_uses_real_diagnostics(self):
        payload = data.request_payload("deepseek-flash", data.prompt_messages(self.root, self.task), 100)
        response = {"model": "teacher-test", "choices": [{"finish_reason": "stop",
                    "message": {"content": "sir 0.3\nsoftware Sample {}\n"}}]}
        with patch.object(data, "call_teacher", return_value=response):
            first = data.generate_one(self.root, self.manifest, self.task, payload)
        parent = load_jsonl(self.root / "candidates.jsonl")[-1]
        failed = bind_static(parent, {"id": parent["id"], "sourceSha256": parent["source_sha256"],
                    "ok": False, "stage": "SEMANTIC", "fileCount": 0, "digest": None,
                    "diagnostics": [{"severity": "ERROR", "code": "SIR-TYPE-001", "message": "type mismatch"}]})
        write_jsonl(self.root / "checked.jsonl", [failed])
        with patch.object(data, "call_teacher", return_value=response) as call, contextlib.redirect_stdout(io.StringIO()):
            data.repair(argparse.Namespace(bundle=self.root, checked=None, sample_id=parent["id"], max_tokens=100, model="deepseek-flash"))
        call.assert_called_once()
        feedback = call.call_args.args[0]["messages"][-1]["content"]
        self.assertIn("SIR-TYPE-001", feedback)
        self.assertIn("CREATE TABLE token_pair", feedback)
        rows = load_jsonl(self.root / "candidates.jsonl")
        self.assertEqual(parent["id"], rows[-1]["parent_id"])
        self.assertEqual("feedback-repair", rows[-1]["generation_mode"])
        self.assertEqual(parent, rows[-2])
        self.assertNotIn((self.root / "reference.sir").read_text(encoding="utf-8"), feedback)

    def test_import_rebinds_old_host_fingerprints_and_resets_all_gates_without_a_call(self):
        payload = data.request_payload("deepseek-flash", data.prompt_messages(self.root, self.task), 100)
        response = {"model": "teacher-test", "choices": [{"finish_reason": "stop",
                    "message": {"content": "sir 0.3\nsoftware Sample {}\n"}}]}
        with patch.object(data, "call_teacher", return_value=response):
            data.generate_one(self.root, self.manifest, self.task, payload)
        # Emulate an archived bundle whose host source bytes differ from the current host.
        old_inputs = {**self.manifest["toolchain_inputs"], "pom.xml": data.sha256_text("old host source\r\n")}
        old_hash = data.fingerprint(old_inputs)
        old_manifest = {**self.manifest, "toolchain_inputs": old_inputs, "toolchain_core_sha256": old_hash}
        (self.root / "manifest.json").write_text(data.json_text(old_manifest), encoding="utf-8")
        original_rows = load_jsonl(self.root / "candidates.jsonl")
        old_rows = [{**row, "toolchain_core_sha256": old_hash,
                     "gates": {gate: "PASSED" for gate in data.GATES}, "static_result": {"digest": "old"}}
                    for row in original_rows]
        for row in old_rows:
            (self.root / "samples" / row["id"] / "candidate.json").write_text(data.json_text(row), encoding="utf-8")
        (self.root / "candidates.jsonl").write_text("".join(json.dumps(row) + "\n" for row in old_rows), encoding="utf-8")
        with tempfile.TemporaryDirectory() as parent:
            destination = Path(parent) / "new-host"
            with patch.object(data, "call_teacher") as call, contextlib.redirect_stdout(io.StringIO()):
                data.import_candidates(argparse.Namespace(from_bundle=self.root, bundle=destination))
            call.assert_not_called()
            current, task = data.read_bundle(destination)
            rows = load_jsonl(destination / "candidates.jsonl")
            data.validate_candidates(destination, current, task, rows)
            imported = next(row for row in rows if row["origin"] == "teacher")
            self.assertNotEqual(old_hash, imported["toolchain_core_sha256"])
            self.assertTrue(all(value == "NOT_RUN" for value in imported["gates"].values()))
            self.assertNotIn("static_result", imported)
            self.assertFalse(imported["imported_from"]["gates_transferred"])
            self.assertEqual(old_rows[-1]["source_sha256"], imported["source_sha256"])
            self.assertEqual(old_rows, load_jsonl(self.root / "candidates.jsonl"))

    def test_import_rejects_changed_response_before_creating_destination(self):
        payload = data.request_payload("deepseek-flash", data.prompt_messages(self.root, self.task), 100)
        response = {"model": "teacher-test", "choices": [{"finish_reason": "stop",
                    "message": {"content": "sir 0.3\nsoftware Sample {}\n"}}]}
        with patch.object(data, "call_teacher", return_value=response):
            attempt = data.generate_one(self.root, self.manifest, self.task, payload)
        (self.root / "samples" / attempt["id"] / "response.json").write_text("{}", encoding="utf-8")
        with tempfile.TemporaryDirectory() as parent:
            destination = Path(parent) / "rejected"
            with self.assertRaises(DataError):
                data.import_candidates(argparse.Namespace(from_bundle=self.root, bundle=destination))
            self.assertFalse(destination.exists())

    def test_v1_stays_the_default_and_a_later_prompt_is_chosen_explicitly(self):
        v1 = (data.HERE / "prompts/sir-0.3-auth.md").read_bytes()
        self.assertEqual(v1, (self.root / "system.md").read_bytes())
        chosen = Path(self.temp.name) / "v2"
        with contextlib.redirect_stdout(io.StringIO()):
            data.prepare(argparse.Namespace(bundle=chosen, task=data.HERE / "tasks/dual-token-v1.json",
                                            system_prompt=data.HERE / "prompts/sir-0.3-auth-v2.md"))
        self.assertEqual((data.HERE / "prompts/sir-0.3-auth-v2.md").read_bytes(), (chosen / "system.md").read_bytes())
        self.assertNotEqual(v1, (chosen / "system.md").read_bytes())

    def test_a_prompt_outside_the_reviewed_folder_is_refused_before_anything_is_written(self):
        outside = Path(self.temp.name) / "my-prompt.md"
        outside.write_text("anything", encoding="utf-8")
        destination = Path(self.temp.name) / "refused"
        with self.assertRaisesRegex(DataError, "prompts"):
            data.prepare(argparse.Namespace(bundle=destination, task=data.HERE / "tasks/dual-token-v1.json",
                                            system_prompt=outside))
        self.assertFalse((destination / "manifest.json").exists())

    def test_repair_sends_the_chosen_feedback_file(self):
        payload = data.request_payload("deepseek-flash", data.prompt_messages(self.root, self.task), 100)
        response = {"model": "teacher-test", "choices": [{"finish_reason": "stop",
                    "message": {"content": "sir 0.3\nsoftware Sample {}\n"}}]}
        with patch.object(data, "call_teacher", return_value=response):
            data.generate_one(self.root, self.manifest, self.task, payload)
        parent = load_jsonl(self.root / "candidates.jsonl")[-1]
        failed = bind_static(parent, {"id": parent["id"], "sourceSha256": parent["source_sha256"],
                    "ok": False, "stage": "SEMANTIC", "fileCount": 0, "digest": None,
                    "diagnostics": [{"severity": "ERROR", "code": "SIR-FLOW-007", "message": "identity before persist"}]})
        write_jsonl(self.root / "checked.jsonl", [failed])
        with patch.object(data, "call_teacher", return_value=response) as call, contextlib.redirect_stdout(io.StringIO()):
            data.repair(argparse.Namespace(bundle=self.root, checked=None, sample_id=parent["id"], max_tokens=100,
                                           model="deepseek-flash", feedback=data.HERE / "prompts/repair-auth-v2.md"))
        feedback = call.call_args.args[0]["messages"][-1]["content"]
        self.assertTrue(feedback.startswith((data.HERE / "prompts/repair-auth-v2.md").read_text(encoding="utf-8")))

    def test_import_freezes_the_origin_system_prompt_not_the_current_default(self):
        # The origin batch was prepared with v2; its recorded messages carry v2, so the import must too.
        origin = Path(self.temp.name) / "origin-v2"
        with contextlib.redirect_stdout(io.StringIO()):
            data.prepare(argparse.Namespace(bundle=origin, task=data.HERE / "tasks/dual-token-v1.json",
                                            system_prompt=data.HERE / "prompts/sir-0.3-auth-v2.md"))
        destination = Path(self.temp.name) / "imported"
        with patch.object(data, "call_teacher") as call, contextlib.redirect_stdout(io.StringIO()):
            data.import_candidates(argparse.Namespace(from_bundle=origin, bundle=destination))
        call.assert_not_called()
        self.assertEqual((origin / "system.md").read_bytes(), (destination / "system.md").read_bytes())

    def test_import_accepts_a_newer_task_that_only_changes_the_judge_files(self):
        task = load_json(data.HERE / "tasks/dual-token-v1.json")
        old_files = [path.replace("SlmCandidateSupport", "DualTokenCandidateSupport") for path in task["oracle"]["files"]]
        # The origin froze the pre-rename file list; that file no longer exists, so a plain import cannot freeze it.
        (self.root / "task.json").write_text(data.json_text({**task, "oracle": {**task["oracle"], "files": old_files}}),
                                             encoding="utf-8")
        frozen = {**self.manifest["bundle_inputs"], "task.json": data.digest_file(self.root / "task.json")}
        (self.root / "manifest.json").write_text(data.json_text(
            {**self.manifest, "bundle_inputs": frozen, "task_sha256": frozen["task.json"]}), encoding="utf-8")
        rows = [{**row, "task_sha256": frozen["task.json"]} for row in load_jsonl(self.root / "candidates.jsonl")]
        for row in rows:
            (self.root / "samples" / row["id"] / "candidate.json").write_text(data.json_text(row), encoding="utf-8")
        (self.root / "candidates.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
        with self.assertRaises(FileNotFoundError):
            with contextlib.redirect_stdout(io.StringIO()):
                data.import_candidates(argparse.Namespace(from_bundle=self.root, bundle=Path(self.temp.name) / "plain"))
        destination = Path(self.temp.name) / "renamed"
        with patch.object(data, "call_teacher") as call, contextlib.redirect_stdout(io.StringIO()):
            data.import_candidates(argparse.Namespace(from_bundle=self.root, bundle=destination,
                                                      task=data.HERE / "tasks/dual-token-v1.json"))
        call.assert_not_called()
        self.assertEqual(task, load_json(destination / "task.json"))
        replaced = load_json(destination / "import.json")["task_replaced"]
        self.assertEqual((frozen["task.json"], "oracle.files"), (replaced["from_task_sha256"], replaced["changed"]))

    def test_import_refuses_a_task_that_asks_a_different_question(self):
        task = load_json(data.HERE / "tasks/dual-token-v1.json")
        changed = Path(self.temp.name) / "changed-task.json"
        changed.write_text(data.json_text({**task, "requirement": task["requirement"] + "另加一条。"}), encoding="utf-8")
        destination = Path(self.temp.name) / "refused-import"
        with self.assertRaisesRegex(DataError, "only change oracle.files"):
            data.import_candidates(argparse.Namespace(from_bundle=self.root, bundle=destination, task=changed))
        self.assertFalse(destination.exists())

    def test_a_from_scratch_task_freezes_an_empty_base_and_shows_no_existing_software(self):
        task = load_json(data.HERE / "tasks/dual-token-v1.json")
        scratch_file = Path(self.temp.name) / "scratch-task.json"
        scratch_file.write_text(data.json_text({**task, "id": "auth-dual-token-scratch", "base_source": None}), encoding="utf-8")
        scratch = Path(self.temp.name) / "scratch"
        with contextlib.redirect_stdout(io.StringIO()):
            data.prepare(argparse.Namespace(bundle=scratch, task=scratch_file))
        manifest, frozen = data.read_bundle(scratch)
        self.assertEqual(b"", (scratch / "base.sir").read_bytes())
        user = data.prompt_messages(scratch, frozen)[1]["content"]
        self.assertTrue(user.startswith(task["requirement"]))
        self.assertNotIn("已有软件", user)
        base_text = (data.REPO / task["base_source"]).read_text(encoding="utf-8")
        # The change-type prompt keeps its exact bytes: existing bundles must still reproduce their requests.
        change = data.prompt_messages(self.root, self.task)[1]["content"]
        self.assertTrue(change.endswith("\n已有软件（返回修改后的完整文档）：\n" + base_text))

    def test_prompt_changed_after_freeze_is_rejected_before_a_paid_call(self):
        (self.root / "system.md").write_text("changed", encoding="utf-8")
        with patch.object(data, "call_teacher") as call:
            with self.assertRaisesRegex(DataError, "frozen"):
                data.generate(argparse.Namespace(bundle=self.root, count=1, max_tokens=10, model="deepseek-flash"))
        call.assert_not_called()


if __name__ == "__main__":
    unittest.main()
