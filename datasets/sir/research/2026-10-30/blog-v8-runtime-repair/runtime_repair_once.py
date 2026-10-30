"""Exactly one approved runtime-feedback request; original collector and original checker."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

root = Path.cwd()
source = root / 'target/slm-blog-v8/source-2e4fa74b-lf'
sys.path.insert(0, str(source))
from experiments.slm import data
from experiments.slm.dataset_io import digest_file, load_json, load_jsonl, write_jsonl, write_new_json

base = '1aa6f5fe01d6f23315b3817b7ec43383419b6f57'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip() == base
assert data.REPO.resolve() == source.resolve() and os.environ.get('DEEPSEEK_API_KEY')
bundle = root / 'datasets/sir/pilots/blog-change-g8'
research = root / 'datasets/sir/research/2026-10-30/blog-v8-runtime-repair'
assert research.is_dir() and not (research / 'approval.json').exists() and not (research / 'change-call.json').exists()
manifest, task = data.read_bundle(bundle)
rows_before = load_jsonl(bundle / 'candidates.jsonl')
attempts_before = load_jsonl(bundle / 'attempts.jsonl')
assert len(rows_before) == 2 and len(attempts_before) == 1
assert sorted(p.name for p in (bundle / 'samples').iterdir()) == sorted(r['id'] for r in rows_before)
data.validate_candidates(bundle, manifest, task, rows_before)
parent, = load_jsonl(bundle / 'checked-initial.jsonl')
assert parent['id'] == 'content-blog-change-v1-teacher-906a864f71bf' and parent['gates']['static'] == 'PASSED'
assert not (bundle / 'checked-r1.jsonl').exists()
evidence_path = root / load_json(research / 'gate65-archive.json')['runtime_evidence']
report, lineage = data.business_failure(parent, evidence_path)
assert lineage['generation_mode'] == 'runtime-feedback-repair'
feedback = source / 'experiments/slm/prompts/repair-runtime-v1.md'
inputs = {**manifest['toolchain_inputs'], **manifest['oracle_inputs']}
raw = subprocess.check_output(['git', 'cat-file', '--batch'], input=''.join(base + ':' + p + '\n' for p in inputs).encode())
position = 0
for name, expected in inputs.items():
    end = raw.index(b'\n', position)
    oid, kind, size = raw[position:end].split()
    position = end + 1
    size = int(size)
    content = raw[position:position + size]
    assert kind == b'blob' and hashlib.sha256(content).hexdigest() == expected
    assert (source / name).read_bytes() == content
    position += size + 1
assert position == len(raw)
jar = root / 'target/ci-investigation/202/standalone-cli/kcg.jar'
with zipfile.ZipFile(root / 'datasets/sir/research/2026-10-29/blog-v8-ci202/original-zips/standalone-cli.zip') as archived:
    assert archived.read('kcg.jar') == jar.read_bytes()
ci = load_json(root / 'datasets/sir/research/2026-10-29/blog-v8-ci202/reference-summary.json')
assert digest_file(jar) == ci['checker_jar_sha256'] and ci['commit'] == manifest['git_head']
old_files = {p.relative_to(bundle).as_posix(): digest_file(p) for p in bundle.rglob('*') if p.is_file()}
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
write_new_json(research / 'approval.json', {'source_commit': base, 'parent_id': parent['id'],
    'owner_budget_approved': True, 'estimated_input_tokens': 24000, 'estimated_output_tokens': 2000,
    'estimated_total_tokens': 26000, 'max_output_tokens': 8192, 'calls': 1, 'automatic_retries': 0,
    'runtime_evidence_sha256': digest_file(evidence_path), 'started_at': started})
env = {**os.environ, 'PYTHONPATH': str(source), 'PYTHONUTF8': '1'}
for name in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']:
    env.pop(name, None)
command = [sys.executable, '-m', 'experiments.slm.data', 'repair', '--bundle', str(bundle),
           '--sample-id', parent['id'], '--checked', str(bundle / 'checked-initial.jsonl'),
           '--feedback', str(feedback), '--runtime-evidence', str(evidence_path),
           '--model', 'deepseek-flash', '--max-tokens', '8192']
execution = subprocess.run(command, cwd=source, env=env, capture_output=True, timeout=240)
(research / 'change-repair.stdout.txt').write_bytes(execution.stdout)
(research / 'change-repair.stderr.txt').write_bytes(execution.stderr)
for name, expected in old_files.items():
    if name not in ['candidates.jsonl', 'attempts.jsonl']:
        assert digest_file(bundle / name) == expected, name
rows = load_jsonl(bundle / 'candidates.jsonl')
attempts = load_jsonl(bundle / 'attempts.jsonl')
data.validate_candidates(bundle, manifest, task, rows)
assert rows[:2] == rows_before and attempts[:1] == attempts_before
children = rows[2:]
new_attempts = attempts[1:]
assert len(children) <= 1 and len(new_attempts) <= 1
usage = (new_attempts[0].get('usage') if new_attempts else None) or {}
cache = usage.get('prompt_cache_hit_tokens')
if cache is None:
    cache = (usage.get('prompt_tokens_details') or {}).get('cached_tokens')
summary = {'source_commit': base, 'task': task['id'], 'bundle': bundle.relative_to(root).as_posix(),
           'parent_id': parent['id'], 'started_at': started,
           'completed_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
           'owner_budget_approved': True, 'estimated_input_tokens': 24000, 'estimated_output_tokens': 2000,
           'max_output_tokens': 8192, 'automatic_retries': 0, 'runtime_repair_round_for_parent': 1,
           'recorded_new_attempts': len(new_attempts), 'new_candidates': len(children),
           'request_process_exit_code': execution.returncode,
           'runtime_evidence': evidence_path.relative_to(root).as_posix(), 'runtime_evidence_sha256': digest_file(evidence_path),
           'usage': {'input': usage.get('prompt_tokens'), 'cache_hit': cache, 'output': usage.get('completion_tokens'), 'total': usage.get('total_tokens')},
           'frozen_inputs_unchanged': True, 'old_batch_files_unchanged_except_append_ledgers': len(old_files) - 2,
           'checker_artifact_run_id': 40877385610, 'core_and_oracle_blobs_verified': len(inputs)}
if children:
    child, = children
    assert child['parent_id'] == parent['id'] and child['generation_mode'] == lineage['generation_mode']
    assert child['runtime_feedback'] == lineage['runtime_feedback']
    checker = ['java', '-XX:-UsePerfData', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-XX:MaxMetaspaceSize=128m', '-jar', str(jar), 'check']
    checked, check_execution = data._check_rows(bundle, children, checker)
    write_jsonl(bundle / 'checked-r1.jsonl', checked)
    item, = checked
    response = load_json(bundle / 'samples' / child['id'] / 'response.json')
    summary = {**summary, 'sample_id': child['id'], 'source_sha256': child['source_sha256'],
        'actual_model': child['actual_model'], 'finish_reason': response['choices'][0].get('finish_reason'),
        'unchanged_source_from_parent': child['source_sha256'] == parent['source_sha256'],
        'checked': (bundle / 'checked-r1.jsonl').relative_to(root).as_posix(), 'gates': item['gates'],
        'static_result': item['static_result'], 'check_execution': check_execution,
        'eligible_for_business_gate': item['gates']['static'] == 'PASSED' and child['source_sha256'] != parent['source_sha256'],
        'request_sha256': child['request_sha256'], 'response_sha256': child['raw_response_sha256']}
else:
    summary = {**summary, 'eligible_for_business_gate': False, 'usage_unknown_not_zero': not bool(usage)}
write_new_json(research / 'change-call.json', summary)
print(json.dumps(summary, ensure_ascii=False, indent=2))
if execution.returncode:
    sys.exit(execution.returncode)
