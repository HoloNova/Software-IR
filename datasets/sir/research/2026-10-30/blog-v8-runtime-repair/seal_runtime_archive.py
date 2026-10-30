"""Seal the authorized combined data archive and write an exact precommit review; no network."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

root = Path.cwd()
base = '1aa6f5fe01d6f23315b3817b7ec43383419b6f57'
research = root / 'datasets/sir/research/2026-10-30/blog-v8-runtime-repair'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip() == base
assert not subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z'])
assert not (research / 'verification.json').exists()

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_manifest(directory, complete=True):
    entries = {}
    for line in (directory / 'MANIFEST.sha256').read_text(encoding='utf-8').splitlines():
        sha, name = line.split('  ', 1)
        assert name not in entries and digest(directory / name) == sha, name
        entries[name] = sha
    if complete:
        assert set(entries) == {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file() and p.name != 'MANIFEST.sha256'}
    return len(entries)


protected = json.loads((root / 'datasets/sir/research/2026-10-29/blog-v8-preparation/protected-inputs.json').read_text(encoding='utf-8'))['before']
assert all(digest(root / name) == value for name, value in protected.items())
for name in ['blog-v8-preparation', 'blog-v8-initial', 'blog-v8-ci202']:
    check_manifest(root / 'datasets/sir/research/2026-10-29' / name)
check_manifest(root / 'datasets/sir/research/2026-10-30/blog-v8-repair')
check_manifest(root / 'datasets/sir/pilots/blog-scratch-g8')
check_manifest(root / 'datasets/sir', False)
shutil.copyfile(Path(__file__), research / Path(__file__).name)
folders = [root / 'datasets/sir/pilots/blog-change-g8', root / 'datasets/sir/pilots/blog-scratch-g8',
           root / 'datasets/sir/research/2026-10-30/blog-v8-repair', research]
secrets = [value.encode() for key in ['DEEPSEEK_API_KEY', 'GH_TOKEN', 'GITHUB_TOKEN'] if (value := os.environ.get(key)) and len(value) > 8]
for directory in folders:
    for path in directory.rglob('*'):
        if not path.is_file():
            continue
        raw = path.read_bytes()
        assert not any(secret in raw for secret in secrets), 'credential in artifact'
        if path.suffix == '.json':
            json.loads(raw)
        elif path.suffix == '.jsonl':
            [json.loads(line) for line in raw.decode('utf-8').splitlines()]
        elif path.suffix == '.py':
            compile(raw, str(path), 'exec')
        if path.suffix in {'.json', '.jsonl', '.sir', '.md', '.py', '.patch', '.sha256'}:
            assert b'\r' not in raw, str(path)
            assert all(line.rstrip(b' \t') == line for line in raw.splitlines()), str(path)
proof = {'source_commit': base, 'approved_estimated_total_tokens': 26000, 'runtime_repair_api_calls': 1,
         'actual_runtime_repair_tokens': 24257, 'automatic_retries': 0, 'new_business_gate_dispatches': 0,
         'gate65_archived': '401/25 BUSINESS FAILED', 'verify203': 'SUCCESS_METADATA_VERIFIED',
         'scratch_single_repair_stopped': True, 'change_single_runtime_repair_stopped': True,
         'protected_old_files_unchanged': len(protected), 'student_version': 3, 'qualified_teachers': 48,
         'credential_scan': 'PASSED', 'json_and_python_syntax': 'PASSED',
         'old_task_manifest_and_candidate_bytes_preserved': True, 'ledgers_append_only': True,
         'raw_ci_logs_and_native_stdout_preserved': True, 'candidate_or_production_edits': 0}
(research / 'verification.json').write_bytes((json.dumps(proof, ensure_ascii=False, indent=2) + '\n').encode())
for directory in [folders[0], research]:
    paths = sorted(p for p in directory.rglob('*') if p.is_file() and p.name != 'MANIFEST.sha256')
    text = ''.join(digest(p) + '  ' + p.relative_to(directory).as_posix() + '\n' for p in paths)
    (directory / 'MANIFEST.sha256').write_bytes(text.encode())
counts = {d.relative_to(root).as_posix(): check_manifest(d) for d in folders}
counts['datasets/sir legacy root scope'] = check_manifest(root / 'datasets/sir', False)
tracked = [p for p in subprocess.check_output(['git', 'diff', '--name-only', '-z']).decode().split('\0') if p]
new = [p for p in subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z']).decode().split('\0') if p]
allowed = ('datasets/sir/pilots/blog-change-g8/', 'datasets/sir/pilots/blog-scratch-g8/', 'datasets/sir/research/2026-10-30/')
assert all(p.startswith(allowed) for p in new), new
assert set(tracked) == {'datasets/sir/MANIFEST.sha256', 'datasets/sir/README.md',
    'datasets/sir/pilots/blog-change-g8/MANIFEST.sha256', 'datasets/sir/pilots/blog-change-g8/candidates.jsonl', 'datasets/sir/pilots/blog-change-g8/attempts.jsonl',
    'datasets/sir/pilots/blog-scratch-g8/MANIFEST.sha256', 'datasets/sir/pilots/blog-scratch-g8/candidates.jsonl', 'datasets/sir/pilots/blog-scratch-g8/attempts.jsonl',
    'docs/qualification/SLM_DATA_PILOT_REPORT.md', 'experiments/slm/README.md'}
runtime_dir = folders[0] / 'runtime/content-blog-change-v1-teacher-906a864f71bf'
raw_candidates = sorted(p.relative_to(root).as_posix() for p in runtime_dir.rglob('*') if p.is_file())
result = subprocess.run(['git', 'check-ignore', '--no-index', '--', *raw_candidates], capture_output=True)
assert result.returncode in [0, 1]
ignored = result.stdout.decode().splitlines()
assert len(ignored) == 6 and all(name.endswith(('.log', '.zip')) for name in ignored)
paths = sorted(set(tracked + new + ignored))
object_ids = subprocess.check_output(['git', 'hash-object', '--stdin-paths'], input=('\n'.join(paths) + '\n').encode()).decode().splitlines()
records = []
for name, oid in zip(paths, object_ids, strict=True):
    raw = (root / name).read_bytes()
    clean = raw if name.startswith('datasets/') else raw.replace(b'\r\n', b'\n')
    assert hashlib.sha1(b'blob ' + str(len(clean)).encode() + b'\0' + clean).hexdigest() == oid, name
    records.append({'path': name, 'working_sha256': hashlib.sha256(raw).hexdigest(),
                    'git_blob_sha256': hashlib.sha256(clean).hexdigest(), 'git_blob': oid})
exceptions = [name for name in ignored if name.endswith('.log')] + [
    'datasets/sir/research/2026-10-30/blog-v8-repair/raw-output/scratch-repair.stdout.txt',
    'datasets/sir/research/2026-10-30/blog-v8-runtime-repair/change-repair.stdout.txt']
for name in exceptions[-2:]:
    assert (root / name).read_bytes().endswith(b'\r\n')
review = {'base_commit': base, 'approved_file_count': len(records), 'records': records,
          'force_add_raw_evidence': ignored, 'raw_format_exceptions': exceptions,
          'manifest_counts': counts, 'protected_old_files': len(protected),
          'git_blob_bytes_verified': True, 'credential_scan': 'PASSED',
          'new_gate_dispatches': 0, 'model_calls_in_sealer': 0}
(root / 'target/slm-blog-v8/combined-commit-review.json').write_bytes((json.dumps(review, ensure_ascii=False, indent=2) + '\n').encode())
print(json.dumps({key: value for key, value in review.items() if key != 'records'}, ensure_ascii=False, indent=2))
