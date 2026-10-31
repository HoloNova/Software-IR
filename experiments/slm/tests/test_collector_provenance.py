"""Git-addressed collector provenance, including legacy snapshots, without any paid API."""
import importlib
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from experiments.slm import data
from experiments.slm.corpus import DataError
from experiments.slm.dataset_io import digest_file, fingerprint, json_text
from archive_fixture import commit, init_repo, put


class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = init_repo(Path(self.temp.name) / "repo")
        self.sha = commit(self.repo)

    def api(self):
        self.assertIsNotNone(importlib.util.find_spec("experiments.slm.collector_provenance"), "missing Git collector provenance")
        return importlib.import_module("experiments.slm.collector_provenance")

    def test_current_reference_covers_real_dependencies_with_raw_blob_hashes(self):
        api = self.api()
        metadata = api.capture(self.repo)
        reference = metadata["collector_reference"]
        self.assertEqual(self.sha, reference["commit"])
        self.assertEqual({"data.py", "dataset_io.py", "corpus.py"}, set(reference["files"]))
        self.assertEqual(metadata["collector_sha256"], fingerprint({n: v["sha256"] for n, v in reference["files"].items()}))
        api.validate(self.repo, metadata, repo=self.repo)
        self.assertFalse((self.repo / "collectors").exists())

    def test_new_internal_dependency_is_not_omitted(self):
        put(self.repo, "experiments/slm/data.py", "from experiments.slm import added\n")
        put(self.repo, "experiments/slm/added.py", "value = 1\n")
        commit(self.repo)
        self.assertEqual({"data.py", "added.py"}, set(self.api().capture(self.repo)["collector_reference"]["files"]))

    def test_dirty_source_is_refused(self):
        put(self.repo, "experiments/slm/corpus.py", "changed = True\n")
        with self.assertRaises(DataError):
            self.api().capture(self.repo)

    def test_crlf_is_not_misrepresented_as_committed_lf(self):
        path = self.repo / "experiments/slm/data.py"
        path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
        with self.assertRaises(DataError):
            self.api().capture(self.repo)

    def test_unrelated_changes_do_not_block_collection(self):
        put(self.repo, "notes.md", "uncommitted unrelated note\n")
        self.api().capture(self.repo)

    def test_uncommitted_dependency_is_refused(self):
        put(self.repo, "experiments/slm/data.py", "from experiments.slm import added\n")
        put(self.repo, "experiments/slm/added.py", "x = 1\n")
        with self.assertRaises(DataError):
            self.api().capture(self.repo)

    def test_null_commit_in_a_reference_is_not_current_head(self):
        api = self.api()
        metadata = api.capture(self.repo)
        changed = {**metadata, "collector_reference": {**metadata["collector_reference"], "commit": None}}
        with self.assertRaises(DataError):
            api.validate(self.repo, changed, repo=self.repo)

    def test_untracked_dependency_of_a_committed_import_is_refused(self):
        put(self.repo, "experiments/slm/data.py", "from experiments.slm import added\n")
        commit(self.repo)
        put(self.repo, "experiments/slm/added.py", "x = 1\n")
        with self.assertRaises(DataError):
            self.api().capture(self.repo)

    def test_missing_git_object_is_not_replaced_with_current_head(self):
        api = self.api()
        metadata = api.capture(self.repo)
        changed = {**metadata, "collector_reference": {**metadata["collector_reference"], "commit": "f" * 40}}
        with self.assertRaises(DataError):
            api.validate(self.repo, changed, repo=self.repo)

    def test_tampered_file_hash_is_refused(self):
        api = self.api()
        metadata = api.capture(self.repo)
        ref = metadata["collector_reference"]
        files = {**ref["files"], "corpus.py": {**ref["files"]["corpus.py"], "sha256": "0" * 64}}
        changed = {**metadata, "collector_reference": {**ref, "files": files}}
        with self.assertRaises(DataError):
            api.validate(self.repo, changed, repo=self.repo)

    def test_omitting_a_dependency_cannot_redefine_the_digest(self):
        api = self.api()
        metadata = api.capture(self.repo)
        ref = metadata["collector_reference"]
        files = {n: v for n, v in ref["files"].items() if n != "corpus.py"}
        changed = {"collector_sha256": fingerprint({n: v["sha256"] for n, v in files.items()}),
                   "collector_reference": {**ref, "files": files}}
        with self.assertRaises(DataError):
            api.validate(self.repo, changed, repo=self.repo)

    def legacy(self):
        bundle = self.repo / "datasets/sir/pilots/old"
        hashes = {name: digest_file(self.repo / "experiments/slm" / name) for name in ("data.py", "dataset_io.py", "corpus.py")}
        digest = fingerprint(hashes)
        for name in hashes:
            put(bundle, f"collectors/{digest}/{name}", (self.repo / "experiments/slm" / name).read_bytes())
        put(bundle, f"collectors/{digest}/inputs.json", json_text(hashes))
        commit(self.repo)
        return bundle, {"id": "legacy", "collector_sha256": digest}

    def test_legacy_snapshots_validate_and_import_as_git_references_without_copy(self):
        api = self.api()
        bundle, row = self.legacy()
        api.validate(bundle, row, repo=self.repo)
        refs = api.for_import(bundle, [row], repo=self.repo)
        self.assertEqual(row["collector_sha256"], refs["legacy"]["collector_sha256"])
        self.assertEqual("legacy-snapshot", refs["legacy"]["collector_reference"]["kind"])
        api.validate(self.repo / "new-bundle", refs["legacy"], repo=self.repo)
        self.assertEqual(refs, api.for_import(self.repo / "new-bundle", [{"id": "legacy", **refs["legacy"]}], repo=self.repo))

    def test_legacy_tampering_does_not_make_a_new_reference(self):
        api = self.api()
        bundle, row = self.legacy()
        put(bundle, f"collectors/{row['collector_sha256']}/data.py", "changed\n")
        with self.assertRaises(DataError):
            api.for_import(bundle, [row], repo=self.repo)

    def test_old_rows_without_collector_fields_keep_their_supported_contract(self):
        api = self.api()
        self.assertIsNone(api.validate(self.repo, {"id": "old"}, repo=self.repo))
        self.assertEqual({}, api.for_import(self.repo, [{"id": "old"}], repo=self.repo))

    def test_bad_reference_is_refused_before_any_teacher_request_or_candidate_write(self):
        self.api()
        with patch("experiments.slm.collector_provenance.REPO", self.repo):
            put(self.repo, "experiments/slm/corpus.py", "dirty\n")
            root = self.repo / "bundle"
            root.mkdir()
            with patch.object(data, "call_teacher") as call, self.assertRaises(DataError):
                data.generate_one(root, {}, {"id": "example"}, {"model": "deepseek-flash"})
            call.assert_not_called()
            self.assertEqual([], list(root.iterdir()))


if __name__ == "__main__":
    unittest.main()
