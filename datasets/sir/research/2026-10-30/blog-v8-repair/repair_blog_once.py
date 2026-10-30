"""One explicitly approved feedback re-answer, using the original repair implementation."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

root = Path.cwd()
mode = sys.argv[1]
assert mode == 'scratch', 'Only this one failed candidate is approved'
source = root / 'target/slm-blog-v8/source-2e4fa74b-lf'
sys.path.insert(0, str(source))
from experiments.slm import data
from experiments.slm.dataset_io import digest_file, load_json, load_jsonl, write_jsonl, write_new_json

assert data.REPO.resolve() == source.resolve()
assert os.environ.get('DEEPSEEK_API_KEY'), 'DEEPSEEK_API_KEY absent; no request'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip() == '1aa6f5fe01d6f23315b3817b7ec43383419b6f57'
plan = next(p for p in load_json(root / 'datasets/sir/research/2026-10-29/blog-v8-initial/repair-plan.json')['plans']
            if p['task'] == 'content-blog-' + mode + '-v1')
bundle = root / plan['bundle']
research = root / 'datasets/sir/research/2026-10-30/blog-v8-repair'
research.mkdir(parents=True, exist_ok=True)
result_path = research / (mode + '-call.json')
assert not result_path.exists(), 'repair already recorded; never retry automatically'
assert not (research / 'approval.json').exists(), 'Prior request marker exists; inspect before considering any retry'
manifest, task = data.read_bundle(bundle)
rows_before = load_jsonl(bundle / 'candidates.jsonl')
attempts_before = load_jsonl(bundle / 'attempts.jsonl')
assert len(rows_before) == 2 and len(attempts_before) == 1
assert sorted(p.name for p in (bundle / 'samples').iterdir()) == sorted(row['id'] for row in rows_before), 'unrecorded prior request; do not retry'
data.validate_candidates(bundle, manifest, task, rows_before)
parent, = load_jsonl(root / plan['checked'])
data.validate_candidates(bundle, manifest, task, [parent])
assert parent['id'] == plan['parent_id'] and parent['gates']['static'] == 'FAILED'
assert not any(row.get('parent_id') == parent['id'] for row in rows_before)
feedback = source / plan['feedback']
assert digest_file(feedback) == plan['feedback_prefix_sha256']
frozen_names = sorted(set(manifest['bundle_inputs']) | {'manifest.json', 'task.json', 'reference.sir'})
frozen = {name: digest_file(bundle / name) for name in frozen_names}
inputs = {**manifest['toolchain_inputs'], **manifest['oracle_inputs']}
raw = subprocess.check_output(['git', 'cat-file', '--batch'], input=''.join('HEAD:' + p + '\n' for p in sorted(inputs)).encode())
offset = 0
for path in sorted(inputs):
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
with zipfile.ZipFile(root / 'datasets/sir/research/2026-10-29/blog-v8-ci202/original-zips/standalone-cli.zip') as archive:
    assert jar.read_bytes() == archive.read('kcg.jar')
env = {**os.environ, 'PYTHONPATH': str(source), 'PYTHONUTF8': '1'}
for key in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']:
    env.pop(key, None)
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
ci = load_json(root / 'datasets/sir/research/2026-10-29/blog-v8-ci202/reference-summary.json')
assert digest_file(jar) == ci['checker_jar_sha256']
assert ci['commit'] == manifest['git_head']
assert parent['id'] == 'content-blog-scratch-v1-teacher-718c0d88dafa'
write_new_json(research / 'approval.json', {'owner_budget_approved': True, 'approved_estimated_input_tokens': 11000, 'approved_estimated_output_tokens': 2000, 'approved_estimated_total_tokens': 13000, 'max_output_tokens': 8192, 'calls': 1, 'automatic_retries': 0, 'repair_round': 1, 'parent_id': parent['id'], 'source_commit': '1aa6f5fe01d6f23315b3817b7ec43383419b6f57', 'started_at': started})
command = [sys.executable, '-m', 'experiments.slm.data', 'repair', '--bundle', str(bundle),
           '--sample-id', parent['id'], '--checked', str(root / plan['checked']), '--feedback', str(feedback),
           '--model', 'deepseek-flash', '--max-tokens', '8192']
execution = subprocess.run(command, cwd=source, env=env, capture_output=True, timeout=240)
logs = research / 'raw-output'
logs.mkdir(parents=True, exist_ok=True)
(logs / (mode + '-repair.stdout.txt')).write_bytes(execution.stdout)
(logs / (mode + '-repair.stderr.txt')).write_bytes(execution.stderr)
assert frozen == {name: digest_file(bundle / name) for name in frozen_names}
rows_after = load_jsonl(bundle / 'candidates.jsonl')
data.validate_candidates(bundle, manifest, task, rows_after)
assert rows_after[:len(rows_before)] == rows_before
new_rows = rows_after[len(rows_before):]
new_attempts = load_jsonl(bundle / 'attempts.jsonl')[len(attempts_before):]
assert len(new_rows) <= 1 and len(new_attempts) <= 1
usage = (new_attempts[0].get('usage') if new_attempts else None) or {}
cache = usage.get('prompt_cache_hit_tokens')
if cache is None:
    cache = (usage.get('prompt_tokens_details') or {}).get('cached_tokens')
summary = {'task': task['id'], 'bundle': plan['bundle'], 'parent_id': parent['id'], 'owner_budget_approved': True,
           'started_at': started, 'completed_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
           'estimated_input_tokens': plan['estimated_input_tokens'], 'estimated_output_tokens': plan['estimated_output_tokens'],
           'max_output_tokens': 8192, 'requested_model': 'deepseek-flash', 'automatic_retries': 0,
           'repair_round_for_parent': 1, 'request_process_exit_code': execution.returncode,
           'new_candidates': len(new_rows), 'recorded_new_attempts': len(new_attempts),
           'usage': {'input': usage.get('prompt_tokens'), 'cache_hit': cache, 'output': usage.get('completion_tokens'), 'total': usage.get('total_tokens')},
           'frozen_inputs_unchanged': True, 'frozen_git_blobs_verified': len(inputs), 'checker_artifact_run_id': 40877385610}
if new_rows:
    row, = new_rows
    assert row['generation_mode'] == 'feedback-repair' and row['parent_id'] == parent['id']
    checked_path = bundle / 'checked-r1.jsonl'
    assert not checked_path.exists()
    checker = ['java', '-XX:-UsePerfData', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-XX:MaxMetaspaceSize=128m', '-jar', str(jar), 'check']
    checked, check_execution = data._check_rows(bundle, new_rows, checker)
    write_jsonl(checked_path, checked)
    item, = checked
    response = load_json(bundle / 'samples' / row['id'] / 'response.json')
    unchanged = row['source_sha256'] == parent['source_sha256']
    summary = {**summary, 'sample_id': row['id'], 'source_sha256': row['source_sha256'], 'actual_model': row['actual_model'],
               'finish_reason': response['choices'][0].get('finish_reason'), 'unchanged_source_from_parent': unchanged,
               'checked': checked_path.relative_to(root).as_posix(), 'gates': item['gates'], 'static_result': item['static_result'],
               'check_execution': check_execution, 'eligible_for_business_gate': item['gates']['static'] == 'PASSED' and not unchanged,
               'request_sha256': row['request_sha256'], 'response_sha256': row['raw_response_sha256']}
else:
    summary = {**summary, 'eligible_for_business_gate': False,
               'no_response_usage_is_unknown_not_zero': not bool(usage),
               'gates': {gate: 'NOT_RUN' for gate in ['static', 'build', 'start', 'business']}}
write_new_json(result_path, summary)
print(json.dumps(summary, ensure_ascii=False, indent=2))
if execution.returncode:
    sys.exit(execution.returncode)
