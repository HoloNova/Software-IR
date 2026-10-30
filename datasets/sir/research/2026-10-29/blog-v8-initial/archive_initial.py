"""Validate completed v8 calls and prepare next-step budgets; no network calls."""
import hashlib
import json
import math
from pathlib import Path
import shutil
import sys

root = Path.cwd()
source = root / 'target/slm-blog-v8/source-2e4fa74b-lf'
sys.path.insert(0, str(source))
from experiments.slm import data
from experiments.slm.dataset_io import digest_file, json_text, load_json, load_jsonl, write_new_json

research = root / 'datasets/sir/research/2026-10-29/blog-v8-initial'
preparation = root / 'datasets/sir/research/2026-10-29/blog-v8-preparation'
assert not (research / 'summary.json').exists()
prepared = load_json(preparation / 'checkout-verification.json')
protected = load_json(preparation / 'protected-inputs.json')['before']
assert all(digest_file(root / name) == value for name, value in protected.items())
prior_plan = load_json(root / 'datasets/sir/research/2026-10-29/blog-v7-initial/repair-plan.json')
ratio = prior_plan['median_tokens_per_added_character']
results, repairs, gates = [], [], []
for mode in ['change', 'scratch']:
    relative = 'datasets/sir/pilots/blog-' + mode + '-g8'
    bundle = root / relative
    manifest, task = data.read_bundle(bundle)
    prepared_bundle, = [p for p in prepared['bundles'] if p['bundle'] == relative]
    assert digest_file(bundle / 'manifest.json') == prepared_bundle['manifest_sha256']
    candidates = load_jsonl(bundle / 'candidates.jsonl')
    data.validate_candidates(bundle, manifest, task, candidates)
    assert len(candidates) == 2 and candidates[0]['origin'] == 'existing_reference'
    teacher = candidates[1]
    assert teacher['origin'] == 'teacher' and teacher['generation_mode'] == 'initial' and teacher['parent_id'] is None
    checked, = load_jsonl(bundle / 'checked-initial.jsonl')
    data.validate_candidates(bundle, manifest, task, [checked])
    assert all(checked['gates'][g] == 'NOT_RUN' for g in ['build', 'start', 'business'])
    record = load_json(research / (mode + '-call.json'))
    raw_stdout = Path(record['check_execution']['directory']) / 'stdout.jsonl'
    assert digest_file(raw_stdout) == record['check_execution']['stdout_sha256']
    raw_result, = load_jsonl(raw_stdout)
    assert data.bind_static(teacher, raw_result) == checked
    assert '\ufffd' not in json_text(raw_result), 'diagnostic text must retain Unicode'
    reference, = load_jsonl(bundle / 'checked-reference.jsonl')
    data.validate_candidates(bundle, manifest, task, [reference])
    assert reference['gates']['static'] == 'PASSED' and reference['static_result']['fileCount'] == 55
    usage = teacher['usage']
    assert usage['prompt_tokens'] + usage['completion_tokens'] == usage['total_tokens']
    assert 0 <= record['usage']['cache_hit'] <= usage['prompt_tokens']
    assert record['sample_id'] == teacher['id'] and record['usage']['total'] == usage['total_tokens']
    assert record['finish_reason'] == 'stop' and record['recorded_attempts'] == 1
    assert len(load_jsonl(bundle / 'attempts.jsonl')) == 1
    request = load_json(bundle / 'samples' / teacher['id'] / 'request.json')
    assert request == data.request_payload('deepseek-flash', data.prompt_messages(bundle, task), 8192)
    result = {'task': task['id'], 'bundle': relative, 'sample_id': teacher['id'], 'source_sha256': teacher['source_sha256'],
              'requested_model': teacher['requested_model'], 'actual_model': teacher['actual_model'], 'sdk': teacher['sdk'],
              'usage': record['usage'], 'gates': checked['gates'], 'static_result': checked['static_result'],
              'request_sha256': teacher['request_sha256'], 'response_sha256': teacher['raw_response_sha256'],
              'collector_sha256': teacher['collector_sha256'], 'reference_gates': reference['gates'],
              **{k: manifest[k] for k in ['toolchain_core_sha256', 'oracle_sha256', 'task_sha256']}}
    results.append(result)
    if checked['gates']['static'] == 'PASSED':
        gates.append({'bundle': relative, 'sample_id': teacher['id'], 'it': 'BlogBusinessConformanceIT',
                      'checked': relative + '/checked-initial.jsonl', 'source_sha256': teacher['source_sha256'],
                      'generated_sha256': checked['static_result']['digest'], 'toolchain_core_sha256': manifest['toolchain_core_sha256'],
                      'oracle_sha256': manifest['oracle_sha256']})
        continue
    feedback_path = source / 'experiments/slm/prompts/repair-general-v1.md'
    feedback = (feedback_path.read_text(encoding='utf-8') + '\n\n原始静态诊断：\n' + json_text(checked['static_result'])
                + '\n\n题目冻结时已有的 DDL（仅约束物理形状，不允许在 SIR 内嵌 SQL）：\n'
                + (source / task['ddl_source']).read_text(encoding='utf-8'))
    added_chars = len(teacher['sir']) + len(feedback)
    estimated_input = math.ceil((usage['prompt_tokens'] + added_chars * ratio) * 1.10 / 500) * 500
    estimated_output = max(2000, math.ceil(usage['completion_tokens'] * 1.20 / 500) * 500)
    repairs.append({'task': task['id'], 'parent_id': teacher['id'], 'bundle': relative,
                    'checked': relative + '/checked-initial.jsonl', 'repair_calls': 1,
                    'feedback': 'experiments/slm/prompts/repair-general-v1.md', 'feedback_prefix_sha256': digest_file(feedback_path),
                    'additional_message_characters': added_chars, 'estimated_input_tokens': estimated_input,
                    'estimated_output_tokens': estimated_output, 'max_output_tokens': 8192,
                    'automatic_retries': 0, 'cache_hits': 'UNKNOWN'})
totals = {name: sum(row['usage'][name] for row in results) for name in ['input', 'cache_hit', 'output', 'total']}
assert totals == {'input': 14843, 'cache_hit': 4352, 'output': 3046, 'total': 17889}
assert len(repairs) == len(gates) == 1
write_new_json(research / 'summary.json', {'source_commit': prepared['source_commit'],
    'model_api_calls': 2, 'automatic_retries': 0, 'repair_calls': 0, 'owner_budget_approved': True,
    'approved_estimated_total_tokens': 25500, 'received_responses': 2, 'static_passed': 1,
    'qualified_new_teachers': 0, 'qualified_teachers_unchanged': 48, 'student_version_unchanged': 3,
    'total_usage': totals, 'results': results, 'business_gate_dispatches': [],
    'frozen_inputs_unchanged': True, 'protected_files_unchanged': len(protected),
    'ci_standalone_checker_run_id': 40877385610, 'unicode_diagnostics_verified': True})
write_new_json(research / 'repair-plan.json', {'status': 'PROPOSED_AWAITING_OWNER_BUDGET_APPROVAL',
    'calls_already_made_by_this_estimator': 0, 'requested_model': 'deepseek-flash', 'plans': repairs,
    'total_estimated_input_tokens': sum(p['estimated_input_tokens'] for p in repairs),
    'total_estimated_output_tokens': sum(p['estimated_output_tokens'] for p in repairs),
    'method': prior_plan['method'], 'median_tokens_per_added_character': ratio,
    'calibration_source': 'datasets/sir/research/2026-10-29/blog-v7-initial/repair-plan.json',
    'calibration_source_sha256': digest_file(root / 'datasets/sir/research/2026-10-29/blog-v7-initial/repair-plan.json')})
write_new_json(research / 'business-gate-plan.json', {'status': 'AWAITING_OWNER_COMMIT_PUSH_AND_DISPATCH_APPROVAL',
    'workflow': 'slm-business-gate.yml', 'source_commit': prepared['source_commit'],
    'dispatch_count': 0, 'candidates': gates,
    'note': 'The new bundle and candidate are not committed; dispatch only after owner approves commit/push and exact remote blob verification.'})
for name in ['sample_blog_once.py', 'archive_initial.py']:
    shutil.copyfile(root / 'target/slm-blog-v8' / name, research / name)
print(json.dumps({'actual_usage': totals, 'static_passed': 1, 'repair_plans': repairs,
                  'estimated_repair_total_tokens': sum(p['estimated_input_tokens'] + p['estimated_output_tokens'] for p in repairs),
                  'business_candidates': gates}, ensure_ascii=False, indent=2))
