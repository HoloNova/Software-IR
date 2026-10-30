"""One approved teacher request; original collector and checker, no retries or repairs."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path.cwd()
mode = sys.argv[1]
assert mode in {'change', 'scratch'}
source = root / 'target/slm-blog-v8/source-2e4fa74b-lf'
assert source.is_dir()
sys.path.insert(0, str(source))
from experiments.slm import data
from experiments.slm.dataset_io import digest_file, load_json, load_jsonl, write_jsonl, write_new_json

assert data.REPO.resolve() == source.resolve()
assert os.environ.get('DEEPSEEK_API_KEY'), 'DEEPSEEK_API_KEY is absent; do not call'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip() == '2e4fa74be63e694b580d991c6a54f60d7d2ce2eb'
bundle = root / ('datasets/sir/pilots/blog-' + mode + '-g8')
research = root / 'datasets/sir/research/2026-10-29/blog-v8-initial'
research.mkdir(parents=True, exist_ok=True)
result_path = research / (mode + '-call.json')
assert not result_path.exists(), 'this approved call has already been recorded'
assert not (bundle / 'attempts.jsonl').exists(), 'a prior attempt exists; no automatic retry'
manifest, task = data.read_bundle(bundle)
rows_before = load_jsonl(bundle / 'candidates.jsonl')
assert len(rows_before) == 1 and rows_before[0]['origin'] == 'existing_reference'
assert sorted(p.name for p in (bundle / 'samples').iterdir()) == [rows_before[0]['id']], 'an incomplete prior request exists; do not retry'
assert task['id'] == 'content-blog-' + mode + '-v1'
assert task['family'] == 'content.blog' and task['split'] == 'train'
data.validate_candidates(bundle, manifest, task, rows_before)
frozen_names = sorted(set(manifest['bundle_inputs']) | {'manifest.json', 'task.json', 'reference.sir'})
frozen_before = {name: digest_file(bundle / name) for name in frozen_names}
inputs = {**manifest['toolchain_inputs'], **manifest['oracle_inputs']}
paths = sorted(inputs)
raw = subprocess.check_output(['git', 'cat-file', '--batch'], input=''.join('HEAD:' + p + '\n' for p in paths).encode())
offset = 0
for path in paths:
    end = raw.index(b'\n', offset)
    header = raw[offset:end].split()
    assert len(header) == 3 and header[1] == b'blob', path
    size = int(header[2])
    content = raw[end + 1:end + 1 + size]
    assert hashlib.sha256(content).hexdigest() == inputs[path], path
    assert (source / path).read_bytes() == content, path
    offset = end + size + 2
assert offset == len(raw)
jar = root / 'target/ci-investigation/202/standalone-cli/kcg.jar'
with __import__('zipfile').ZipFile(root / 'datasets/sir/research/2026-10-29/blog-v8-ci202/original-zips/standalone-cli.zip') as archive:
    assert jar.read_bytes() == archive.read('kcg.jar')
env = {**os.environ, 'PYTHONPATH': str(source), 'PYTHONUTF8': '1'}
for name in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']:
    env.pop(name, None)
ci = load_json(root / 'datasets/sir/research/2026-10-29/blog-v8-ci202/reference-summary.json')
assert ci['commit'] == manifest['git_head'] and set(ci['gates'].values()) == {'PASSED'}
assert digest_file(jar) == ci['checker_jar_sha256']
checker = ['java', '-XX:-UsePerfData', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-XX:MaxMetaspaceSize=128m', '-jar', str(jar), 'check']
reference_checked, reference_execution = data._check_rows(bundle, rows_before, checker)
write_jsonl(bundle / 'checked-reference.jsonl', reference_checked)
assert reference_checked[0]['gates']['static'] == 'PASSED', 'reference must pass before any paid request'
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
write_new_json(research / (mode + '-approval.json'), {'owner_budget_approved': True, 'estimated_total_tokens': 25500, 'max_calls_for_this_task': 1, 'max_output_tokens': 8192, 'automatic_retries': 0, 'repair_calls': 0, 'started_at': started, 'source_commit': manifest['git_head']})
command = [sys.executable, '-m', 'experiments.slm.data', 'generate', '--bundle', str(bundle),
           '--model', 'deepseek-flash', '--count', '1', '--max-tokens', '8192']
execution = subprocess.run(command, cwd=source, env=env, capture_output=True, timeout=240)
(research / (mode + '-generate.stdout.txt')).write_bytes(execution.stdout)
(research / (mode + '-generate.stderr.txt')).write_bytes(execution.stderr)
assert frozen_before == {name: digest_file(bundle / name) for name in frozen_names}, 'frozen inputs changed'
manifest, task = data.read_bundle(bundle)
rows_after = load_jsonl(bundle / 'candidates.jsonl')
data.validate_candidates(bundle, manifest, task, rows_after)
assert rows_after[:1] == rows_before
new_rows = rows_after[1:]
assert len(new_rows) <= 1
attempts = load_jsonl(bundle / 'attempts.jsonl') if (bundle / 'attempts.jsonl').exists() else []
assert len(attempts) <= 1
usage = attempts[0].get('usage') if attempts else None
usage = usage or {}
cache = usage.get('prompt_cache_hit_tokens')
if cache is None:
    cache = (usage.get('prompt_tokens_details') or {}).get('cached_tokens')
summary = {'task': task['id'], 'bundle': bundle.relative_to(root).as_posix(), 'started_at': started,
           'completed_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'owner_budget_approved': True,
           'estimated_input_tokens': 11000 if mode == 'change' else 10500, 'estimated_output_tokens': 2000,
           'max_output_tokens': 8192, 'requested_model': 'deepseek-flash', 'automatic_retries': 0, 'repair_calls': 0,
           'request_process_exit_code': execution.returncode, 'recorded_attempts': len(attempts),
           'new_candidates': len(new_rows), 'usage': {'input': usage.get('prompt_tokens'), 'cache_hit': cache,
           'output': usage.get('completion_tokens'), 'total': usage.get('total_tokens')},
           'frozen_inputs_unchanged': True, 'frozen_git_blobs_verified': len(inputs),
           'source_snapshot': source.relative_to(root).as_posix(), 'checker_artifact_run_id': 40877385610}
if new_rows:
    row, = new_rows
    assert row['origin'] == 'teacher' and row['generation_mode'] == 'initial' and row['parent_id'] is None
    checked_path = bundle / 'checked-initial.jsonl'
    assert not checked_path.exists()
    checker = ['java', '-XX:-UsePerfData', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-XX:MaxMetaspaceSize=128m', '-jar', str(jar), 'check']
    checked, check_execution = data._check_rows(bundle, new_rows, checker)
    write_jsonl(checked_path, checked)
    item, = checked
    response = load_json(bundle / 'samples' / row['id'] / 'response.json')
    summary = {**summary, 'sample_id': row['id'], 'source_sha256': row['source_sha256'],
               'actual_model': row['actual_model'], 'finish_reason': response['choices'][0].get('finish_reason'),
               'checked': checked_path.relative_to(root).as_posix(), 'gates': item['gates'],
               'static_result': item['static_result'], 'check_execution': check_execution,
               'response_sha256': row['raw_response_sha256'], 'request_sha256': row['request_sha256']}
else:
    summary = {**summary, 'gates': {gate: 'NOT_RUN' for gate in ['static', 'build', 'start', 'business']},
               'no_response_usage_is_unknown_not_zero': not bool(usage),
               'request_directories': sorted(p.name for p in (bundle / 'samples').iterdir() if p.name != rows_before[0]['id'])}
write_new_json(result_path, summary)
print(json.dumps(summary, ensure_ascii=False, indent=2))
if execution.returncode:
    print('Teacher command failed; original sanitized stdout/stderr retained; this call is not retried.', file=sys.stderr)
    sys.exit(execution.returncode)
