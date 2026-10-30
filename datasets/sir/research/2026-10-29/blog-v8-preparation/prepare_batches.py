"""Prepare two approved local batches; never calls a model, checker, or remote API."""
import argparse
import contextlib
import datetime
import difflib
import hashlib
import io
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path.cwd().resolve()
SHA = '2e4fa74be63e694b580d991c6a54f60d7d2ce2eb'
WORK = ROOT / 'target/slm-blog-v8'
SOURCE = WORK / 'source-2e4fa74b-lf'
RESEARCH = ROOT / 'datasets/sir/research/2026-10-29/blog-v8-preparation'
MODES = ('change', 'scratch')


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(paths):
    files = [f for p in paths for f in (p.rglob('*') if p.is_dir() else [p]) if f.is_file()]
    return {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(files)}


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n')


def seal(directory):
    files = sorted(p for p in directory.rglob('*') if p.is_file() and p.name != 'MANIFEST.sha256')
    with (directory / 'MANIFEST.sha256').open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(''.join(digest(p) + '  ' + p.relative_to(directory).as_posix() + '\n' for p in files))


assert git('rev-parse', 'HEAD').decode().strip() == SHA
assert not SOURCE.exists() and not RESEARCH.exists(), 'Preparation already exists; inspect rather than overwrite'
for mode in MODES:
    assert not (ROOT / f'datasets/sir/pilots/blog-{mode}-g8').exists()
protected = [ROOT / '.gitignore', ROOT / 'datasets/sir/student', ROOT / 'experiments/slm/prompts',
             ROOT / 'experiments/slm/tasks', *(ROOT / f'datasets/sir/pilots/blog-{m}-g7' for m in MODES)]
before = inventory(protected)
entries = git('ls-tree', '--name-only', SHA).decode().splitlines()
scopes = [p for p in entries if p != 'datasets']
archive = WORK / 'source-2e4fa74b-lf.zip'
subprocess.run(['git', '-c', 'core.autocrlf=false', '-c', 'core.eol=lf', 'archive',
                '--format=zip', '--output=' + str(archive), SHA, *scopes], cwd=ROOT, check=True)
SOURCE.mkdir()
with zipfile.ZipFile(archive) as zipped:
    for member in zipped.infolist():
        assert (SOURCE / member.filename).resolve().is_relative_to(SOURCE)
    zipped.extractall(SOURCE)
object_format = git('rev-parse', '--show-object-format').decode().strip()
objects = {}
for entry in git('ls-tree', '-r', '-z', SHA, '--', *scopes).split(b'\0'):
    if not entry:
        continue
    metadata, filename = entry.split(b'\t', 1)
    mode, kind, oid = metadata.split()
    assert kind == b'blob' and mode in (b'100644', b'100755')
    relative = filename.decode('utf-8')
    content = (SOURCE / relative).read_bytes()
    actual = hashlib.new(object_format, b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
    assert actual == oid.decode(), relative
    objects[relative] = hashlib.sha256(content).hexdigest()

sys.path.insert(0, str(SOURCE))
from experiments.slm import data
from experiments.slm.dataset_io import load_json, load_jsonl
assert data.REPO.resolve() == SOURCE
RESEARCH.mkdir(parents=True)
plans = []
checks = []
v7 = (SOURCE / 'experiments/slm/prompts/sir-0.3-general-v7.md').read_text(encoding='utf-8')
v8 = (SOURCE / 'experiments/slm/prompts/sir-0.3-general-v8.md').read_text(encoding='utf-8')
matcher = difflib.SequenceMatcher(None, v7.splitlines(keepends=True), v8.splitlines(keepends=True), autojunk=False)
v8_lines = v8.splitlines(keepends=True)
added_chars = sum(len(''.join(v8_lines[j1:j2])) for tag, i1, i2, j1, j2 in matcher.get_opcodes() if tag in ('insert', 'replace'))
historical = load_json(ROOT / 'datasets/sir/research/2026-10-29/blog-v7-initial/summary.json')
for mode in MODES:
    bundle = ROOT / f'datasets/sir/pilots/blog-{mode}-g8'
    previous = ROOT / f'datasets/sir/pilots/blog-{mode}-g7'
    task_path = SOURCE / f'experiments/slm/tasks/blog-{mode}-v1.json'
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        data.prepare(argparse.Namespace(task=task_path, bundle=bundle,
                     system_prompt=SOURCE / 'experiments/slm/prompts/sir-0.3-general-v8.md'))
    (RESEARCH / f'{mode}-prepare.stdout.txt').write_bytes(output.getvalue().encode('utf-8'))
    manifest, task = data.read_bundle(bundle)
    rows = load_jsonl(bundle / 'candidates.jsonl')
    data.validate_candidates(bundle, manifest, task, rows)
    assert len(rows) == 1 and rows[0]['origin'] == 'existing_reference'
    assert set(rows[0]['gates'].values()) == {'NOT_RUN'}
    assert manifest['git_head'] == SHA
    for group in ('toolchain_inputs', 'oracle_inputs', 'experiment_inputs'):
        for path, value in manifest[group].items():
            assert objects[path] == value, (group, path)
    assert (bundle / 'task.json').read_bytes() == task_path.read_bytes() == (previous / 'task.json').read_bytes()
    for name in ('base.sir', 'reference.sir'):
        assert (bundle / name).read_bytes() == (previous / name).read_bytes()
    assert (bundle / 'system.md').read_bytes() == (SOURCE / 'experiments/slm/prompts/sir-0.3-general-v8.md').read_bytes()
    previous_manifest = load_json(previous / 'manifest.json')
    assert manifest['oracle_inputs'] == previous_manifest['oracle_inputs']
    assert manifest['toolchain_core_sha256'] != previous_manifest['toolchain_core_sha256']
    old_row = load_jsonl(previous / 'candidates.jsonl')[0]
    assert rows[0]['messages'][1] == old_row['messages'][1]
    prior = next(r for r in historical['results'] if r['task'] == task['id'])
    estimated_input = math.ceil((prior['usage']['input'] + added_chars) * 1.10 / 500) * 500
    plans.append({'task': task['id'], 'bundle': bundle.relative_to(ROOT).as_posix(), 'calls': 1,
                  'historical_sample_id': prior['sample_id'], 'historical_usage': prior['usage'],
                  'user_chars': len(rows[0]['messages'][1]['content']),
                  'estimated_input_tokens': estimated_input, 'estimated_output_tokens': 2000,
                  'max_output_tokens': 8192, 'cache_hits': 'UNKNOWN', 'automatic_retries': 0})
    checks.append({'bundle': bundle.relative_to(ROOT).as_posix(), 'manifest_sha256': digest(bundle / 'manifest.json'),
                   'toolchain_core_sha256': manifest['toolchain_core_sha256'], 'oracle_sha256': manifest['oracle_sha256'],
                   'verified_inputs': {g: len(manifest[g]) for g in ('toolchain_inputs', 'oracle_inputs', 'experiment_inputs')},
                   'task_sha256': manifest['task_sha256'], 'system_sha256': manifest['bundle_inputs']['system.md'],
                   'reference_only': True, 'gates': rows[0]['gates'], 'same_question_as_g7': True})
    seal(bundle)
assert before == inventory(protected)
save(RESEARCH / 'protected-inputs.json', {'before': before, 'after_equal': True})
save(RESEARCH / 'checkout-verification.json', {
    'source_commit': SHA, 'source_snapshot': SOURCE.relative_to(ROOT).as_posix(),
    'archive_sha256': digest(archive), 'snapshot_files_matching_git_blobs': len(objects),
    'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'bundles': checks,
    'ci_status': 'AWAITING_OWNER_NOTIFICATION_NOT_QUERIED', 'static_checker': 'NOT_RUN',
    'api_calls': 0, 'teacher_candidates': 0, 'qualified_teachers': 48, 'student_version': 3,
    'protected_files_unchanged': len(before)})
save(RESEARCH / 'sampling-plan.json', {
    'status': 'PROPOSED_AWAITING_OWNER_BUDGET_APPROVAL', 'model': 'deepseek-flash',
    'prompt_version': 'sir-0.3-general-v8', 'source_commit': SHA,
    'method': 'Same-question v7 actual input tokens + one token per character in inserted/replacement v8 lines (no credit for removed lines), plus 10%, rounded up to 500. Heuristic, not actual provider tokenization. Output 2000 per call is an estimate, not the 8192 hard cap.',
    'v7_system_chars': len(v7), 'v8_system_chars': len(v8), 'inserted_or_replacement_line_chars': added_chars,
    'plans': plans, 'total_estimated_input_tokens': sum(p['estimated_input_tokens'] for p in plans),
    'total_estimated_output_tokens': 4000, 'max_output_tokens_across_two_calls': 16384,
    'repair_calls_in_this_budget': 0, 'api_calls_already_made': 0,
    'prerequisites': ['Owner notification and verification of CI for source commit', 'Owner approval of sampling budget']})
shutil.copyfile(Path(__file__).resolve(), RESEARCH / 'prepare_batches.py')
seal(RESEARCH)
print(json.dumps({'verified_snapshot_files': len(objects), 'checks': checks, 'sampling': plans,
                  'total_estimated_tokens': sum(p['estimated_input_tokens'] + p['estimated_output_tokens'] for p in plans)}, ensure_ascii=False, indent=2))
