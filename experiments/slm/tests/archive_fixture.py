"""Small real Git/ZIP fixtures. Never calls GitHub, a teacher, Maven or a database."""
from copy import deepcopy
import hashlib
import io
import json
import os
import subprocess
import zipfile
from pathlib import Path

from experiments.slm.corpus import bind_static, sha256_text
from experiments.slm.dataset_io import digest_file, fingerprint, json_text, write_jsonl

REPOSITORY = "HoloNova/Software-IR"
IT = "io.test.BlogBusinessConformanceIT"
BUNDLE = "datasets/sir/pilots/example-gate"


def put(root, name, content):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content if isinstance(content, bytes) else content.encode("utf-8"))
    return path


def git(repo, *args, input=None):
    env = {**os.environ, "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}
    result = subprocess.run(["git", "-c", "core.autocrlf=false", "-c", "commit.gpgsign=false",
                             "-c", "core.hooksPath=" + str(repo / ".no-hooks"), "-c", "user.name=HoloNova",
                             "-c", "user.email=holonova25@gmail.com", *args], cwd=repo,
                            input=input, capture_output=True, timeout=15, check=True, env=env)
    return result.stdout


def commit(repo):
    git(repo, "add", "--all")
    git(repo, "commit", "--no-verify", "-qm", "offline test fixture")
    return git(repo, "rev-parse", "HEAD").decode().strip()


def init_repo(repo):
    repo.mkdir(parents=True, exist_ok=True)
    git(repo, "init", "-q")
    put(repo, ".gitattributes", "* -text\n")
    put(repo, ".gitignore", "*.log\n*.zip\n")
    put(repo, "experiments/slm/data.py", "from experiments.slm.dataset_io import fingerprint\n")
    put(repo, "experiments/slm/dataset_io.py", "from experiments.slm.corpus import DataError\n")
    put(repo, "experiments/slm/corpus.py", "class DataError(ValueError):\n    pass\n")
    return repo


def zipped(files):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in files.items():
            archive.writestr(name, content)
    return stream.getvalue()


class GateFixture:
    def __init__(self, repo, business="PASSED"):
        self.repo = init_repo(repo)
        self.bundle = repo / BUNDLE
        self.checked = self.bundle / "checked.jsonl"
        self.sample = "blog-teacher-example"
        core = {p: digest_file(put(repo, p, text)) for p, text in {
            "pom.xml": "<project/>\n", "toy/pom.xml": "<project/>\n",
            "toy/src/main/A.java": "class A {}\n"}.items()}
        oracle = {p: digest_file(put(repo, p, text)) for p, text in {
            "toy/src/test/java/io/test/BlogBusinessConformanceIT.java": "package io.test; class BlogBusinessConformanceIT {}\n",
            "toy/src/test/resources/blog-ddl.sql": "CREATE TABLE example(id BIGINT);\n"}.items()}
        task = {"id": "blog", "revision": "1", "family": "content.blog", "split": "train",
                "oracle": {"files": list(oracle)}}
        put(self.bundle, "task.json", json_text(task))
        for name in ("base.sir", "reference.sir", "system.md"):
            put(self.bundle, name, "frozen input\n")
        frozen = {n: digest_file(self.bundle / n) for n in ("task.json", "base.sir", "reference.sir", "system.md")}
        manifest = {"schema_version": "sir-data-bundle/1", "toolchain_inputs": core,
                    "toolchain_core_sha256": fingerprint(core), "oracle_inputs": oracle,
                    "oracle_sha256": fingerprint(oracle), "task_sha256": frozen["task.json"], "bundle_inputs": frozen}
        put(self.bundle, "manifest.json", json_text(manifest))
        sir = "sir 0.3\nsoftware Example {}\n"
        messages = [{"role": "user", "content": "question"}]
        response = {"choices": [{"finish_reason": "stop", "message": {"content": sir}}]}
        request_path = put(self.bundle, f"samples/{self.sample}/request.json", json_text({"messages": messages}))
        response_path = put(self.bundle, f"samples/{self.sample}/response.json", json_text(response))
        row = {"id": self.sample, "origin": "teacher", "task_id": "blog", "task_revision": "1",
               "family": "content.blog", "split": "train", "sir": sir, "messages": messages,
               "source_sha256": sha256_text(sir), "raw_response_sha256": digest_file(response_path),
               "request_sha256": digest_file(request_path),
               **{k: manifest[k] for k in ("task_sha256", "oracle_sha256", "toolchain_core_sha256")},
               "gates": {k: "NOT_RUN" for k in ("static", "build", "start", "business")}}
        put(self.bundle, f"samples/{self.sample}/candidate.json", json_text(row))
        put(self.bundle, f"samples/{self.sample}/candidate.sir", sir)
        write_jsonl(self.bundle / "candidates.jsonl", [row])
        self.row = bind_static(row, {"id": self.sample, "sourceSha256": row["source_sha256"], "ok": True,
                                    "stage": "GENERATION", "digest": "a" * 64, "fileCount": 2, "diagnostics": []})
        write_jsonl(self.checked, [self.row])
        put(self.bundle, "MANIFEST.sha256", f"{digest_file(self.bundle / 'manifest.json')}  manifest.json\n")
        put(repo / "datasets/sir", "MANIFEST.sha256",
            f"{digest_file(self.bundle / 'MANIFEST.sha256')}  pilots/example-gate/MANIFEST.sha256\n")
        self.sha = commit(repo)
        failed = int(business == "FAILED")
        verdict = "PASSED" if not failed else "FAILED"
        report = (f"BLOG\nverdict={verdict}\nrunId=slice-1\n\nchecksPassed=1 checksFailed={failed}\n"
                  + "PASS okay\n" + ("FAIL conflict expected=409 actual=500\n" if failed else "") + "\n")
        raw_files = {"blog-report.txt": report.encode(), "logs/maven.stdout.log": b"raw  \r\n",
                     "logs/maven.stderr.log": b"", "logs/spring.stdout.log": b"application\r\n",
                     "logs/spring.stderr.log": b""}
        evidence = {"schema_version": "sir-runtime-evidence/1", "run_id": "slice-1",
                    **{k: self.row[k] for k in ("id", "task_sha256", "oracle_sha256", "toolchain_core_sha256", "source_sha256")},
                    "generated_digest": "a" * 64,
                    "gates": {"static": "PASSED", "build": "PASSED", "start": "PASSED", "business": business},
                    "environment": {"java": "21", "mysql": "8.4", "test_revision": "1"},
                    "artifacts": [{"path": p, "sha256": hashlib.sha256(v).hexdigest()} for p, v in raw_files.items()]}
        self.ev_files = {"slice-1/" + p: v for p, v in raw_files.items()}
        self.ev_files["slice-1/slm-runtime.json"] = json_text(evidence).encode()
        self.sf_files = {f"toy/target/surefire-reports/TEST-{IT}.xml":
                         (f'<testsuite name="{IT}" tests="1" failures="{failed}" errors="0" skipped="0">'
                          f'<testcase classname="{IT}" name="business">'
                          + ('<failure message="business failed"/>' if failed else '')
                          + '</testcase></testsuite>').encode(),
                         f"toy/target/surefire-reports/{IT}.txt": b"JUnit raw \r\n"}
        self.run = {"id": 100, "run_number": 1, "run_attempt": 1, "status": "completed",
                    "conclusion": "success" if not failed else "failure", "event": "workflow_dispatch",
                    "path": ".github/workflows/slm-business-gate.yml", "head_sha": self.sha,
                    "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY},
                    "html_url": f"https://github.com/{REPOSITORY}/actions/runs/100"}
        self.refresh()

    def refresh(self):
        self.zips = {10: zipped(self.ev_files), 11: zipped(self.sf_files)}
        self.artifacts = [{"id": ident, "name": prefix + self.sample, "expired": False,
                           "size_in_bytes": len(self.zips[ident]),
                           "digest": "sha256:" + hashlib.sha256(self.zips[ident]).hexdigest(),
                           "workflow_run": {"id": 100, "head_sha": self.run["head_sha"]}}
                          for ident, prefix in ((10, "slm-gate-"), (11, "slm-gate-surefire-"))]

    def change_evidence(self, **changes):
        old = json.loads(self.ev_files["slice-1/slm-runtime.json"])
        self.ev_files["slice-1/slm-runtime.json"] = json_text({**old, **changes}).encode()
        self.refresh()

    def archive(self, api, **kwargs):
        return api.archive_run(self.repo, self.bundle, self.checked, 100, client=self, **kwargs)

    def json(self, endpoint):
        if "/artifacts?" in endpoint:
            return {"total_count": len(self.artifacts), "artifacts": deepcopy(self.artifacts)}
        return deepcopy(self.run)

    def download(self, artifact_id, destination):
        destination.write_bytes(self.zips[artifact_id])
