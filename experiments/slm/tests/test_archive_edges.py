"""Failure atomicity, API transport and archive budget edges; all inputs remain offline."""
from copy import deepcopy
import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from experiments.slm import archive_index, gate_archive, gate_artifacts
from experiments.slm.corpus import DataError
from experiments.slm.dataset_io import decode_json, digest_file
from experiments.slm.git_source import GitSource, relative_path
from archive_fixture import GateFixture, IT, commit, git, init_repo, put


class EdgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.fx = GateFixture(Path(self.temp.name) / "repo")

    def reject(self):
        from test_gate_archive import tree
        before = tree(self.fx.repo)
        with self.assertRaises(DataError):
            self.fx.archive(gate_archive)
        self.assertEqual(before, tree(self.fx.repo))

    def test_two_pages_are_all_verified(self):
        original = self.fx.json
        def pages(endpoint):
            if "/artifacts?" in endpoint:
                page = int(endpoint.rsplit("page=", 1)[1])
                return {"total_count": 2, "artifacts": deepcopy(self.fx.artifacts[page - 1:page])}
            return original(endpoint)
        with patch.object(self.fx, "json", side_effect=pages) as transport:
            self.fx.archive(gate_archive, dry_run=True)
        self.assertEqual(3, transport.call_count)

    def test_truncated_pagination_refuses(self):
        original = self.fx.json
        def pages(endpoint):
            return {"total_count": 2, "artifacts": []} if "/artifacts?" in endpoint else original(endpoint)
        with patch.object(self.fx, "json", side_effect=pages):
            self.reject()

    def test_index_interruption_is_resumed_without_rewriting_original_archive(self):
        original = archive_index.apply
        def interrupted(plans):
            original(plans[:1])
            raise OSError("simulated outer-index interruption")
        with patch.object(archive_index, "apply", side_effect=interrupted), self.assertRaisesRegex(DataError, "ARCHIVE_WRITTEN_INDEX_PENDING"):
            self.fx.archive(gate_archive)
        target = self.fx.bundle / "runtime" / self.fx.sample / "github-100-attempt-1"
        evidence = (target / "MANIFEST.sha256").read_bytes()
        result = self.fx.archive(gate_archive)
        self.assertTrue(result["already_archived"])
        self.assertEqual(evidence, (target / "MANIFEST.sha256").read_bytes())
        root_index = self.fx.repo / "datasets/sir/MANIFEST.sha256"
        archive_index._verify(root_index, root_index.read_bytes())

    def test_unknown_file_in_completed_archive_is_not_deleted_or_accepted(self):
        result = self.fx.archive(gate_archive)
        put(self.fx.repo / result["archive"], "unexpected.txt", "extra")
        self.reject()

    def test_expired_artifact_is_not_downloaded(self):
        self.fx.artifacts[0]["expired"] = True
        with patch.object(self.fx, "download") as call:
            self.reject()
        call.assert_not_called()

    def test_null_commit_in_run_metadata_is_not_head(self):
        self.fx.run["head_sha"] = None
        self.fx.artifacts = [{**a, "workflow_run": {"id": 100, "head_sha": None}} for a in self.fx.artifacts]
        self.reject()

    def test_malformed_repository_metadata_is_a_domain_error(self):
        self.fx.run["repository"] = None
        self.reject()

    def test_nonobject_artifact_is_a_domain_error(self):
        self.fx.artifacts = [None]
        self.reject()

    def test_artifact_from_wrong_commit_is_not_reused(self):
        self.fx.artifacts[0]["workflow_run"]["head_sha"] = "f" * 40
        self.reject()

    def test_oversized_declared_artifact_is_not_downloaded(self):
        self.fx.artifacts[0]["size_in_bytes"] = gate_artifacts.MAX_ZIP_BYTES + 1
        with patch.object(self.fx, "download") as call:
            self.reject()
        call.assert_not_called()

    def custom_zip(self, content):
        self.fx.zips[10] = content
        self.fx.artifacts[0] = {**self.fx.artifacts[0], "size_in_bytes": len(content),
                                "digest": "sha256:" + hashlib.sha256(content).hexdigest()}

    def test_corrupt_but_correctly_hashed_zip_is_refused(self):
        self.custom_zip(b"not a ZIP")
        self.reject()

    def test_duplicate_zip_entry_is_refused(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w") as z:
            z.writestr("same", b"a")
            with __import__("warnings").catch_warnings():
                __import__("warnings").simplefilter("ignore", UserWarning)
                z.writestr("same", b"b")
        self.custom_zip(raw.getvalue())
        self.reject()

    def test_zip_symlink_is_refused_on_windows_without_creating_one(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w") as z:
            item = zipfile.ZipInfo("link")
            item.create_system = 3
            item.external_attr = 0o120777 << 16
            z.writestr(item, "outside")
        self.custom_zip(raw.getvalue())
        self.reject()

    def test_unpacked_entry_limit_is_enforced(self):
        with patch.object(gate_artifacts, "MAX_ENTRY_BYTES", 2):
            self.reject()

    def test_unpacked_total_limit_is_enforced(self):
        with patch.object(gate_artifacts, "MAX_UNPACK_BYTES", 2):
            self.reject()

    def test_sensitive_value_from_environment_is_never_persisted(self):
        secret = "unit-test-sensitive-value"
        self.fx.ev_files["debug.txt"] = secret.encode()
        self.fx.refresh()
        with patch.dict("os.environ", {"GITHUB_TOKEN": secret}):
            self.reject()

    def test_report_count_cannot_disagree_with_failed_lines(self):
        self.fx = GateFixture(Path(self.temp.name) / "failed", "FAILED")
        report = self.fx.ev_files["slice-1/blog-report.txt"].replace(b"checksFailed=1", b"checksFailed=2")
        self.fx.ev_files["slice-1/blog-report.txt"] = report
        ev = json.loads(self.fx.ev_files["slice-1/slm-runtime.json"])
        artifacts = [{**a, "sha256": hashlib.sha256(report).hexdigest()} if a["path"] == "blog-report.txt" else a for a in ev["artifacts"]]
        self.fx.change_evidence(artifacts=artifacts)
        self.reject()

    def test_later_gate_cannot_pass_after_failed_build(self):
        self.fx.change_evidence(gates={"static": "PASSED", "build": "FAILED", "start": "PASSED", "business": "PASSED"})
        self.reject()

    def test_duplicate_evidence_attachment_is_refused(self):
        ev = json.loads(self.fx.ev_files["slice-1/slm-runtime.json"])
        self.fx.change_evidence(artifacts=[*ev["artifacts"], ev["artifacts"][0]])
        self.reject()

    def test_future_run_failure_retains_prior_completed_summary(self):
        args = __import__("argparse").Namespace(runs=[100, 101], bundle=self.fx.bundle, checked=self.fx.checked, dry_run=False)
        original = gate_archive.archive_run
        def once(repo, bundle, checked, ident, **kwargs):
            if ident == 101:
                raise DataError("next run is not finished")
            return original(self.fx.repo, bundle, checked, ident, client=self.fx)
        output = io.StringIO()
        with patch.object(gate_archive, "archive_run", side_effect=once), contextlib.redirect_stdout(output), self.assertRaises(DataError):
            gate_archive.archive_runs(args)
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([100], [r["run_id"] for r in rows])
        self.assertTrue((self.fx.repo / rows[0]["archive"]).is_dir())

    def test_real_cli_parse_dispatch_and_archive_without_any_export(self):
        from experiments.slm import data, dataset_io
        output = io.StringIO()
        args = ["data", "archive-gate", "--bundle", str(self.fx.bundle), "--checked", str(self.fx.checked), "100"]
        with patch("sys.argv", args), patch.object(dataset_io, "REPO", self.fx.repo), \
                patch.object(gate_archive, "GitHubClient", return_value=self.fx), contextlib.redirect_stdout(output):
            self.assertEqual(0, data.main())
        result = json.loads(output.getvalue())
        self.assertEqual(100, result["run_id"])
        self.assertFalse((self.fx.bundle / "train-teacher.jsonl").exists())

    def test_checksum_parse_rejects_duplicates_self_and_traversal(self):
        for raw in (b"bad\n", ("0" * 64 + "  MANIFEST.sha256\n").encode(),
                    ("0" * 64 + "  ../outside\n").encode(), ("0" * 64 + "  a\n").encode() * 2):
            with self.subTest(raw=raw), self.assertRaises(DataError):
                archive_index.parse(raw)

    def test_empty_or_abbreviated_commit_is_not_head(self):
        for value in ("", self.fx.sha[:8], "HEAD"):
            with self.subTest(value=value), self.assertRaises(DataError):
                GitSource.at(self.fx.repo, value)

    def test_nonportable_paths_are_refused_before_file_access(self):
        for name in ("", ".", "a//b", "a/../b", "a\nb", 'a/quote"', "a/aux", "a/trailing "):
            with self.subTest(name=name), self.assertRaises(DataError):
                relative_path(name)

    def test_json_duplicate_keys_and_nan_are_refused_from_api_and_git_bytes(self):
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}'):
            with self.subTest(raw=raw), self.assertRaises((DataError, ValueError)):
                decode_json(raw)


class FakeProcess:
    def __init__(self, output=b'{"ok":true}', returncode=0):
        self.stdout = io.BytesIO(output)
        self.returncode = returncode
        self.killed = False

    def wait(self, timeout=None):
        return self.returncode

    def poll(self):
        return None if self.killed is False else self.returncode

    def kill(self):
        self.killed = True


class TransportTests(unittest.TestCase):
    def test_gh_owns_authentication_and_debug_environment_is_not_forwarded(self):
        client = gate_archive.GitHubClient()
        result = FakeProcess()
        with patch.dict("os.environ", {"GH_DEBUG": "api"}), patch.object(gate_archive.subprocess, "Popen", return_value=result) as run:
            self.assertEqual({"ok": True}, client.json(gate_archive.API + "/runs/100"))
        self.assertEqual(["gh", "api", "--hostname", "github.com", gate_archive.API + "/runs/100"], run.call_args.args[0])
        self.assertNotIn("GH_DEBUG", run.call_args.kwargs["env"])

    def test_transport_failure_hides_raw_stderr_and_does_not_retry(self):
        client = gate_archive.GitHubClient()
        result = FakeProcess(b"unit-test-sensitive-value", 1)
        with patch.object(gate_archive.subprocess, "Popen", return_value=result) as call:
            with self.assertRaises(DataError) as caught:
                client.json(gate_archive.API + "/runs/100")
        self.assertNotIn("unit-test-sensitive-value", str(caught.exception))
        self.assertEqual(1, call.call_count)

    def test_transport_timeout_does_not_retry(self):
        with patch.object(gate_archive.subprocess, "Popen", side_effect=subprocess.TimeoutExpired("gh", 90)) as call:
            with self.assertRaises(DataError):
                gate_archive.GitHubClient().json(gate_archive.API + "/runs/100")
        self.assertEqual(1, call.call_count)

    def test_response_budget_stops_and_reaps_the_owned_process(self):
        result = FakeProcess(b"oversized")
        with patch.object(gate_archive.subprocess, "Popen", return_value=result), self.assertRaises(DataError):
            gate_archive.GitHubClient()._call(gate_archive.API + "/runs/100", 2)
        self.assertTrue(result.killed)
        self.assertTrue(result.stdout.closed)

    def test_invalid_json_body_is_not_printed(self):
        result = FakeProcess(b"not-json-unit-test-sensitive-value")
        with patch.object(gate_archive.subprocess, "Popen", return_value=result), self.assertRaises(DataError) as caught:
            gate_archive.GitHubClient().json(gate_archive.API + "/runs/100")
        self.assertNotIn("unit-test-sensitive-value", str(caught.exception))

    def test_download_writes_exact_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "artifact.zip"
            with patch.object(gate_archive.subprocess, "Popen", return_value=FakeProcess(b"raw ZIP bytes")):
                gate_archive.GitHubClient().download(1, path)
            self.assertEqual(b"raw ZIP bytes", path.read_bytes())

    def test_arbitrary_download_url_is_never_forwarded(self):
        with patch.object(gate_archive.subprocess, "Popen") as call, self.assertRaises(DataError):
            gate_archive.GitHubClient().json("https://another.invalid/private")
        call.assert_not_called()
