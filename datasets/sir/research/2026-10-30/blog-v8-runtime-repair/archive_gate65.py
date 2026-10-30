"""Archive already downloaded, terminal #65 evidence without a network call or rejudgement."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

root = Path.cwd()
source = root / 'target/slm-blog-v8/source-2e4fa74b-lf'
sys.path.insert(0, str(source))
from experiments.slm import data
from experiments.slm.dataset_io import load_json, load_jsonl, verify_artifacts, write_new_json

base = '1aa6f5fe01d6f23315b3817b7ec43383419b6f57'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip() == base
work = root / 'target/ci-investigation/blog-v8-gate65'
run = load_json(work / 'gate65-run.json')
verification = load_json(work / 'verify203-run.json')
assert run['head_sha'] == verification['head_sha'] == base
assert run['status'] == verification['status'] == 'completed'
assert run['conclusion'] == 'failure' and verification['conclusion'] == 'success'
parent, = load_jsonl(root / 'datasets/sir/pilots/blog-change-g8/checked-initial.jsonl')
assert parent['id'] == 'content-blog-change-v1-teacher-906a864f71bf'
original, = (work / 'evidence').glob('*/slm-runtime.json')
evidence = load_json(original)
report, lineage = data.business_failure(parent, original)
assert evidence['gates'] == {'static': 'PASSED', 'build': 'PASSED', 'start': 'PASSED', 'business': 'FAILED'}
artifacts = load_json(work / 'artifacts.json')['artifacts']
assert len(artifacts) == 2
zip_records = {}
for artifact in artifacts:
    name = 'surefire' if 'surefire' in artifact['name'] else 'evidence'
    path = work / (name + '.zip')
    sha = hashlib.sha256(path.read_bytes()).hexdigest()
    assert 'sha256:' + sha == artifact['digest']
    zip_records[artifact['name']] = {'id': artifact['id'], 'zip_sha256': sha, 'local_zip': 'original-zips/' + path.name}
    with zipfile.ZipFile(path) as archive:
        for member in archive.infolist():
            if not member.is_dir():
                assert archive.read(member) == (work / name / member.filename).read_bytes()
assert all(b'kcg-ci-' not in p.read_bytes() for directory in [work / 'evidence', work / 'surefire'] for p in directory.rglob('*') if p.is_file())
text = (original.parent / 'blog-slice-report.txt').read_text(encoding='utf-8')
failed = [line[5:] for line in text.splitlines() if line.startswith('FAIL ')]
assert len(failed) == 25 and 'checksPassed=401 checksFailed=25' in text
xml, = (work / 'surefire').rglob('TEST-io.kcg.sir.application.conformance.BlogBusinessConformanceIT.xml')
suite = ET.parse(xml).getroot()
assert {key: int(suite.attrib[key]) for key in ['tests', 'failures', 'errors', 'skipped']} == {'tests': 1, 'failures': 1, 'errors': 0, 'skipped': 0}
bundle = root / 'datasets/sir/pilots/blog-change-g8'
destination = bundle / 'runtime' / parent['id']
assert not destination.exists()
shutil.copytree(original.parent, destination / original.parent.name)
(destination / 'junit').mkdir()
for path in sorted((work / 'surefire').rglob('*')):
    if path.is_file() and 'surefire-reports' in path.parts:
        target = destination / 'junit' / path.name
        assert not target.exists()
        shutil.copyfile(path, target)
(destination / 'original-zips').mkdir()
for name in ['evidence.zip', 'surefire.zip']:
    shutil.copyfile(work / name, destination / 'original-zips' / name)
(destination / 'github').mkdir()
for name in ['gate65-run.json', 'verify203-run.json', 'artifacts.json', 'jobs.json']:
    shutil.copyfile(work / name, destination / 'github' / name)
gate = {'workflow': '.github/workflows/slm-business-gate.yml', 'run_id': run['id'], 'run_url': run['html_url'],
        'head_sha': base, 'inputs': {'bundle': bundle.relative_to(root).as_posix(), 'sample_id': parent['id'], 'it': 'BlogBusinessConformanceIT'},
        'artifacts': zip_records, 'job_conclusion': run['conclusion'], 'gates': evidence['gates'],
        'checks_passed': 401, 'checks_failed': 25, 'failed_checks': failed,
        'downloaded_artifacts_reused': True, 'model_calls': 0, 'qualification_exported': False}
write_new_json(destination / 'gate-run.json', gate)
archived_evidence = destination / original.parent.name / 'slm-runtime.json'
verify_artifacts(evidence, archived_evidence)
archived_report, archived_lineage = data.business_failure(parent, archived_evidence)
assert archived_report == report and archived_lineage == lineage
research = root / 'datasets/sir/research/2026-10-30/blog-v8-runtime-repair'
research.mkdir(parents=True, exist_ok=False)
shutil.copyfile(Path(__file__), research / 'archive_gate65.py')
shutil.copyfile(work / 'findings.json', research / 'gate65-findings.json')
shutil.copyfile(root / 'target/slm-blog-v8/runtime-repair-proposal.json', research / 'budget-proposal.json')
write_new_json(research / 'gate65-archive.json', {'run_id': run['id'], 'head_sha': base,
    'runtime_evidence': archived_evidence.relative_to(root).as_posix(), 'runtime_evidence_sha256': hashlib.sha256(archived_evidence.read_bytes()).hexdigest(),
    'gates': evidence['gates'], 'checks_passed': 401, 'checks_failed': 25,
    'verify203_conclusion': verification['conclusion'], 'network_calls': 0, 'model_calls': 0,
    'unredacted_actions_log_not_archived': True})
print(json.dumps({'archived_evidence': archived_evidence.relative_to(root).as_posix(), 'checks': '401/25',
                  'business': evidence['gates']['business'], 'new_qualified_teachers': 0, 'model_calls': 0}, ensure_ascii=False, indent=2))
