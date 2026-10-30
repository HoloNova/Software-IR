"""Audit one completed repair; does not call models, CI, builds or application code."""
from collections import Counter
import difflib
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

root = Path.cwd()
base = '1aa6f5fe01d6f23315b3817b7ec43383419b6f57'
source = root / 'target/slm-blog-v8/source-2e4fa74b-lf'
sys.path.insert(0, str(source))
from experiments.slm import data
from experiments.slm.dataset_io import digest_file, json_text, load_json, load_jsonl, write_new_json

research = root / 'datasets/sir/research/2026-10-30/blog-v8-repair'
bundle = root / 'datasets/sir/pilots/blog-scratch-g8'
assert not (research / 'summary.json').exists()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip() == base
manifest, task = data.read_bundle(bundle)
candidates = load_jsonl(bundle / 'candidates.jsonl')
parent, = load_jsonl(bundle / 'checked-initial.jsonl')
child, = load_jsonl(bundle / 'checked-r1.jsonl')
data.validate_candidates(bundle, manifest, task, candidates)
data.validate_candidates(bundle, manifest, task, [parent, child])
assert len(candidates) == 3 and candidates[2]['id'] == child['id']
assert parent['id'] == 'content-blog-scratch-v1-teacher-718c0d88dafa'
assert child['generation_mode'] == 'feedback-repair' and child['parent_id'] == parent['id']
assert child['source_sha256'] != parent['source_sha256']
assert child['gates'] == {'static': 'FAILED', 'build': 'NOT_RUN', 'start': 'NOT_RUN', 'business': 'NOT_RUN'}
request = load_json(bundle / 'samples' / child['id'] / 'request.json')
response = load_json(bundle / 'samples' / child['id'] / 'response.json')
feedback = ((source / 'experiments/slm/prompts/repair-general-v1.md').read_text(encoding='utf-8')
            + '\n\n原始静态诊断：\n' + json_text(parent['static_result'])
            + '\n\n题目冻结时已有的 DDL（仅约束物理形状，不允许在 SIR 内嵌 SQL）：\n'
            + (source / task['ddl_source']).read_text(encoding='utf-8'))
messages = parent['messages'] + [{'role': 'assistant', 'content': parent['sir']}, {'role': 'user', 'content': feedback}]
assert child['messages'] == messages and request == data.request_payload('deepseek-flash', messages, 8192)
assert response['usage'] == child['usage'] and response['choices'][0]['finish_reason'] == 'stop'
assert '\ufffd' not in json_text(child['static_result']) + feedback
attempts = load_jsonl(bundle / 'attempts.jsonl')
assert len(attempts) == 2 and attempts[1]['id'] == child['id']
collector = bundle / 'collectors' / child['collector_sha256']
hashes = load_json(collector / 'inputs.json')
assert data.fingerprint(hashes) == child['collector_sha256']
assert all(digest_file(collector / name) == value for name, value in hashes.items())
call = load_json(research / 'scratch-call.json')
assert call['sample_id'] == child['id'] and call['eligible_for_business_gate'] is False
raw_result, = load_jsonl(Path(call['check_execution']['directory']) / 'stdout.jsonl')
assert data.bind_static(candidates[2], raw_result) == child
usage = call['usage']
assert usage == {'input': 10080, 'cache_hit': 8448, 'output': 1551, 'total': 11631}
assert usage['input'] + usage['output'] == usage['total'] and call['recorded_new_attempts'] == 1
# All already committed batch files are immutable, except the two append-only ledgers.
immutable_files = 0
for name in subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', base, '--', bundle.relative_to(root).as_posix()]).decode().splitlines():
    old = subprocess.check_output(['git', 'show', base + ':' + name])
    current = (root / name).read_bytes()
    if name.endswith(('/attempts.jsonl', '/candidates.jsonl')):
        assert current.startswith(old) and len(current.splitlines()) == len(old.splitlines()) + 1
    else:
        assert current == old, name
        immutable_files += 1
protected = load_json(root / 'datasets/sir/research/2026-10-29/blog-v8-preparation/protected-inputs.json')['before']
assert all(digest_file(root / name) == expected for name, expected in protected.items())
assert not subprocess.check_output(['git', 'diff', base, '--name-only', '--', 'datasets/sir/pilots/blog-change-g8', 'datasets/sir/student'])
patch = ''.join(difflib.unified_diff(parent['sir'].splitlines(True), child['sir'].splitlines(True),
                                    fromfile=parent['id'] + '.sir', tofile=child['id'] + '.sir', n=0))
(research / 'scratch-teacher-changes.patch').write_bytes(patch.encode('utf-8'))
initial = load_json(root / 'datasets/sir/research/2026-10-29/blog-v8-initial/summary.json')
cumulative = {name: usage[name] + initial['total_usage'][name] for name in usage}
assert cumulative == {'input': 24923, 'cache_hit': 12800, 'output': 4597, 'total': 29520}
diagnostics = child['static_result']['diagnostics']
assert len(diagnostics) == 2 and all(d['code'] == 'SIR-TYPE-001' and d['severity'] == 'ERROR' for d in diagnostics)
result = {'task': task['id'], 'bundle': bundle.relative_to(root).as_posix(), 'sample_id': child['id'],
          'parent_id': parent['id'], 'source_sha256': child['source_sha256'], 'gates': child['gates'],
          'static_result': child['static_result'], 'usage': usage, 'source_diff': 'scratch-teacher-changes.patch',
          'source_diff_sha256': digest_file(research / 'scratch-teacher-changes.patch'),
          'request_sha256': child['request_sha256'], 'response_sha256': child['raw_response_sha256'],
          'collector_sha256': child['collector_sha256'], 'diagnostic_code_counts': dict(Counter(d['code'] for d in diagnostics)),
          **{key: manifest[key] for key in ['task_sha256', 'toolchain_core_sha256', 'oracle_sha256']}}
summary = {'source_commit': base, 'frozen_source_commit': manifest['git_head'], 'owner_budget_approved': True,
           'approved_estimated_total_tokens': 13000, 'model_api_calls': 1, 'automatic_retries': 0,
           'repair_rounds_for_parent': 1, 'received_responses': 1, 'static_passed': 0,
           'qualified_new_teachers': 0, 'qualified_teachers_unchanged': 48, 'student_version_unchanged': 3,
           'total_usage': usage, 'v8_cumulative_api_calls': 3, 'v8_cumulative_usage': cumulative,
           'result': result, 'business_gate_dispatches': [], 'ci_status_queries': 0,
           'original_committed_batch_files_unchanged': immutable_files, 'protected_old_files_unchanged': len(protected),
           'parent_ledgers_append_only': True, 'unicode_diagnostics_verified': True,
           'failed_trajectory_is_not_a_correct_sft_completion': True,
           'next_action': 'Stop after this one approved repair. Await owner CI notification for the other candidate; no further repair or training.'}
write_new_json(research / 'summary.json', summary)
for name in ['repair_blog_once.py', 'summarize_repair.py']:
    shutil.copyfile(root / 'target/slm-blog-v8' / name, research / name)
print(json.dumps({'usage': usage, 'v8_cumulative_usage': cumulative, 'sample_id': child['id'],
                  'remaining_errors': len(diagnostics), 'original_batch_files_unchanged': immutable_files,
                  'protected_old_files_unchanged': len(protected)}, ensure_ascii=False, indent=2))
