"""Verify one completed runtime-feedback repair; no API calls or additional evaluation."""
from collections import Counter
import difflib
import json
from pathlib import Path
import shutil
import subprocess
import sys

root = Path.cwd()
source = root / 'target/slm-blog-v8/source-2e4fa74b-lf'
sys.path.insert(0, str(source))
from experiments.slm import data
from experiments.slm.dataset_io import digest_file, json_text, load_json, load_jsonl, verify_artifacts, write_new_json

base = '1aa6f5fe01d6f23315b3817b7ec43383419b6f57'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip() == base
bundle = root / 'datasets/sir/pilots/blog-change-g8'
research = root / 'datasets/sir/research/2026-10-30/blog-v8-runtime-repair'
assert not (research / 'summary.json').exists()
manifest, task = data.read_bundle(bundle)
rows = load_jsonl(bundle / 'candidates.jsonl')
parent, = load_jsonl(bundle / 'checked-initial.jsonl')
child, = load_jsonl(bundle / 'checked-r1.jsonl')
data.validate_candidates(bundle, manifest, task, rows)
data.validate_candidates(bundle, manifest, task, [parent, child])
assert len(rows) == 3 and rows[2]['id'] == child['id']
assert parent['id'] == 'content-blog-change-v1-teacher-906a864f71bf'
assert child['id'] == 'content-blog-change-v1-teacher-af2c42118880'
assert child['parent_id'] == parent['id'] and child['generation_mode'] == 'runtime-feedback-repair'
call = load_json(research / 'change-call.json')
evidence_path = root / call['runtime_evidence']
report, lineage = data.business_failure(parent, evidence_path)
assert child['runtime_feedback'] == lineage['runtime_feedback']
feedback = ((source / 'experiments/slm/prompts/repair-runtime-v1.md').read_text(encoding='utf-8')
            + '\n\n业务判卷报告（失败的检查与判卷时的 HTTP 记录）：\n' + report
            + '\n\n题目冻结时已有的 DDL（仅约束物理形状，不允许在 SIR 内嵌 SQL）：\n'
            + (source / task['ddl_source']).read_text(encoding='utf-8'))
messages = parent['messages'] + [{'role': 'assistant', 'content': parent['sir']}, {'role': 'user', 'content': feedback}]
request = load_json(bundle / 'samples' / child['id'] / 'request.json')
response = load_json(bundle / 'samples' / child['id'] / 'response.json')
assert request == data.request_payload('deepseek-flash', messages, 8192) and child['messages'] == messages
assert response['usage'] == child['usage'] and response['choices'][0]['finish_reason'] == 'stop'
assert '\ufffd' not in feedback + json_text(child['static_result'])
assert call['usage'] == {'input': 22799, 'cache_hit': 9088, 'output': 1458, 'total': 24257}
assert child['gates'] == {'static': 'FAILED', 'build': 'NOT_RUN', 'start': 'NOT_RUN', 'business': 'NOT_RUN'}
assert child['static_result']['stage'] == 'PARSE' and len(child['static_result']['diagnostics']) == 3
assert call['eligible_for_business_gate'] is False and call['recorded_new_attempts'] == 1
raw_result, = load_jsonl(Path(call['check_execution']['directory']) / 'stdout.jsonl')
assert data.bind_static(rows[2], raw_result) == child
assert len(load_jsonl(bundle / 'attempts.jsonl')) == 2
collector = bundle / 'collectors' / child['collector_sha256']
inputs = load_json(collector / 'inputs.json')
assert data.fingerprint(inputs) == child['collector_sha256']
assert all(digest_file(collector / name) == sha for name, sha in inputs.items())
# Preserve every original sample, request and check. Only the ledgers append; data index reseals later.
for batch in ['blog-change-g8', 'blog-scratch-g8']:
    prefix = 'datasets/sir/pilots/' + batch
    for name in subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', base, '--', prefix]).decode().splitlines():
        old = subprocess.check_output(['git', 'show', base + ':' + name])
        current = (root / name).read_bytes()
        if name.endswith('/MANIFEST.sha256'):
            continue
        if name.endswith(('/candidates.jsonl', '/attempts.jsonl')):
            assert current.startswith(old) and len(current.splitlines()) == len(old.splitlines()) + 1
        else:
            assert current == old, name
protected = load_json(root / 'datasets/sir/research/2026-10-29/blog-v8-preparation/protected-inputs.json')['before']
assert all(digest_file(root / name) == sha for name, sha in protected.items())
scratch_manifest = root / 'datasets/sir/research/2026-10-30/blog-v8-repair/MANIFEST.sha256'
for line in scratch_manifest.read_text(encoding='utf-8').splitlines():
    sha, name = line.split('  ', 1)
    assert digest_file(scratch_manifest.parent / name) == sha
assert child['source_sha256'] != parent['source_sha256']
patch = ''.join(difflib.unified_diff(parent['sir'].splitlines(True), child['sir'].splitlines(True),
                                    fromfile=parent['id'] + '.sir', tofile=child['id'] + '.sir', n=0))
(research / 'change-teacher-changes.patch').write_bytes(patch.encode('utf-8'))
prior = load_json(root / 'datasets/sir/research/2026-10-30/blog-v8-repair/summary.json')
usage = call['usage']
cumulative = {key: usage[key] + prior['v8_cumulative_usage'][key] for key in usage}
assert cumulative == {'input': 47722, 'cache_hit': 21888, 'output': 6055, 'total': 53777}
result = {'source_commit': base, 'frozen_source_commit': manifest['git_head'], 'model_api_calls': 1,
          'owner_budget_approved': True, 'approved_estimated_total_tokens': 26000, 'automatic_retries': 0,
          'runtime_repair_round_for_parent': 1, 'received_responses': 1, 'static_passed': 0,
          'qualified_new_teachers': 0, 'qualified_teachers_unchanged': 48, 'student_version_unchanged': 3,
          'total_usage': usage, 'v8_cumulative_api_calls': 4, 'v8_cumulative_usage': cumulative,
          'sample_id': child['id'], 'parent_id': parent['id'], 'source_sha256': child['source_sha256'],
          'gates': child['gates'], 'static_result': child['static_result'],
          'request_sha256': child['request_sha256'], 'response_sha256': child['raw_response_sha256'],
          'collector_sha256': child['collector_sha256'], 'runtime_feedback': child['runtime_feedback'],
          'source_diff': 'change-teacher-changes.patch', 'source_diff_sha256': digest_file(research / 'change-teacher-changes.patch'),
          'request_is_original_runtime_repair_payload': True, 'unicode_feedback_verified': True,
          'protected_old_files_unchanged': len(protected), 'business_gate_dispatches': [], 'ci_status_queries': 0,
          'next_action': 'Stop both v8 branches; archive all current records in one authorized commit. No candidate is eligible for a new gate.'}
write_new_json(research / 'summary.json', result)
for name in ['runtime_repair_once.py', 'summarize_runtime_repair.py']:
    shutil.copyfile(root / 'target/slm-blog-v8' / name, research / name)
print(json.dumps({'sample_id': child['id'], 'static': 'PARSE FAILED', 'diagnostics': 3,
                  'actual_usage': usage, 'v8_cumulative_usage': cumulative, 'model_calls': 1,
                  'protected_files_unchanged': len(protected), 'new_gate_dispatches': 0}, ensure_ascii=False, indent=2))
