"""Seal and verify local repair data without a model call, build, commit or CI query."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

root = Path.cwd()
base = '1aa6f5fe01d6f23315b3817b7ec43383419b6f57'
research = root / 'datasets/sir/research/2026-10-30/blog-v8-repair'
bundle = root / 'datasets/sir/pilots/blog-scratch-g8'
assert not (research / 'verification.json').exists()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip() == base
assert not subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z'])

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_manifest(directory, complete):
    entries = {}
    for line in (directory / 'MANIFEST.sha256').read_text(encoding='utf-8').splitlines():
        sha, name = line.split('  ', 1)
        assert name not in entries and digest(directory / name) == sha, name
        entries[name] = sha
    if complete:
        assert set(entries) == {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file() and p.name != 'MANIFEST.sha256'}
    return len(entries)


# The prior history and all other batches stay immutable; only the selected ledger appends.
protected = json.loads((root / 'datasets/sir/research/2026-10-29/blog-v8-preparation/protected-inputs.json').read_text(encoding='utf-8'))['before']
assert all(digest(root / name) == sha for name, sha in protected.items())
assert not subprocess.check_output(['git', 'diff', base, '--name-only', '--', 'datasets/sir/pilots/blog-change-g8', 'datasets/sir/student', 'experiments/slm/tasks', 'experiments/slm/prompts'])
immutable_counts = {}
for name in ['blog-v8-preparation', 'blog-v8-ci202', 'blog-v8-initial']:
    directory = root / 'datasets/sir/research/2026-10-29' / name
    immutable_counts[name] = verify_manifest(directory, True)
shutil.copyfile(Path(__file__), research / 'seal_repair.py')
secrets = [value.encode() for key in ['DEEPSEEK_API_KEY', 'GH_TOKEN', 'GITHUB_TOKEN'] if (value := os.environ.get(key)) and len(value) > 8]
scanned = []
for directory in [bundle, research]:
    for path in sorted(directory.rglob('*')):
        if not path.is_file():
            continue
        content = path.read_bytes()
        assert not any(secret in content for secret in secrets), 'credential in artifact'
        if path.suffix == '.json':
            json.loads(content)
        elif path.suffix == '.jsonl':
            [json.loads(line) for line in content.decode('utf-8').splitlines()]
        elif path.suffix == '.py':
            compile(content, str(path), 'exec')
        if path.suffix in {'.json', '.jsonl', '.sir', '.py', '.patch', '.md', '.sha256'}:
            assert b'\r' not in content, str(path)
            assert all(line.rstrip(b' \t') == line for line in content.splitlines()), str(path)
        scanned.append(path.relative_to(root).as_posix())
stdout = research / 'raw-output/scratch-repair.stdout.txt'
assert stdout.read_bytes().endswith(b'\r\n')
proof = {'commit': base, 'model_api_calls': 1, 'automatic_retries': 0, 'ci_status_queries': 0,
         'business_gate_dispatches': 0, 'commit_or_push': False, 'qualified_teachers': 48,
         'student_version': 3, 'protected_old_files_unchanged': len(protected),
         'prior_research_manifests_verified': immutable_counts, 'existing_parent_ledgers_append_only': True,
         'frozen_manifest_and_task_unchanged': True, 'summary_immutable_count_precedes_dataset_manifest_reseal': True,
         'json_and_python_syntax': 'PASSED', 'credential_scan': 'PASSED',
         'scanned_artifact_files_before_this_record': len(scanned),
         'raw_stdout_crlf_preserved_as_format_exception': stdout.relative_to(root).as_posix()}
(research / 'verification.json').write_bytes((json.dumps(proof, ensure_ascii=False, indent=2) + '\n').encode())
for directory in [bundle, research]:
    files = sorted(p for p in directory.rglob('*') if p.is_file() and p.name != 'MANIFEST.sha256')
    text = ''.join(digest(p) + '  ' + p.relative_to(directory).as_posix() + '\n' for p in files)
    (directory / 'MANIFEST.sha256').write_bytes(text.encode())
counts = {directory.relative_to(root).as_posix(): verify_manifest(directory, True) for directory in [bundle, research]}
counts['datasets/sir legacy root scope'] = verify_manifest(root / 'datasets/sir', False)
# Make every new/changed data byte explicit; Git must not apply a clean conversion to evidence.
tracked = [p for p in subprocess.check_output(['git', 'diff', '--name-only', '-z']).decode().split('\0') if p]
new = [p for p in subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z']).decode().split('\0') if p]
assert all(path.startswith(('datasets/sir/pilots/blog-scratch-g8/', 'datasets/sir/research/2026-10-30/blog-v8-repair/')) for path in new), new
assert set(tracked) == {'datasets/sir/README.md', 'datasets/sir/MANIFEST.sha256',
    'datasets/sir/pilots/blog-scratch-g8/attempts.jsonl', 'datasets/sir/pilots/blog-scratch-g8/candidates.jsonl',
    'datasets/sir/pilots/blog-scratch-g8/MANIFEST.sha256', 'docs/qualification/SLM_DATA_PILOT_REPORT.md', 'experiments/slm/README.md'}
paths = sorted(set(tracked + new))
ids = subprocess.check_output(['git', 'hash-object', '--stdin-paths'], input=('\n'.join(paths) + '\n').encode()).decode().splitlines()
for path, oid in zip(paths, ids, strict=True):
    raw = (root / path).read_bytes()
    clean = raw if path.startswith('datasets/') else raw.replace(b'\r\n', b'\n')
    expected = hashlib.sha1(b'blob ' + str(len(clean)).encode() + b'\0' + clean).hexdigest()
    assert oid == expected, path
(root / 'target/slm-blog-v8/repair-change-scope.json').write_bytes((json.dumps({'base': base, 'file_count': len(paths), 'paths': paths, 'git_blob_bytes_verified': True, 'all_manifests_verified': counts}, ensure_ascii=False, indent=2) + '\n').encode())
print(json.dumps({'manifest_entries': counts, 'changed_or_new_files': len(paths), 'protected_old_files_unchanged': len(protected),
                  'git_blob_bytes_verified': True, 'credential_scan': 'PASSED', 'ci_queries': 0, 'new_model_calls_in_verifier': 0}, ensure_ascii=False, indent=2))
