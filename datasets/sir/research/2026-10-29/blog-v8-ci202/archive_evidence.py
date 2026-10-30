import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = Path('target/ci-investigation/202')
sha = '2e4fa74be63e694b580d991c6a54f60d7d2ce2eb'
destination = Path('datasets/sir/research/2026-10-29/blog-v8-ci202')
assert not destination.exists()
run = json.loads((root / 'run.json').read_text(encoding='utf-8'))
assert run['head_sha'] == sha and run['conclusion'] == 'success'
report, = list((root / 'conformance-evidence').rglob('blog-slice-report.txt'))
text = report.read_text(encoding='utf-8')
assert text.splitlines()[1] == 'verdict=PASSED' and '\nFAIL ' not in text
checks = re.search(r'^checksPassed=(\d+) checksFailed=(\d+)$', text, re.M)
assert checks and checks[2] == '0'
bindings = {}
for label, path in [('sirResource', 'sir-toolchain-application/src/test/resources/valid/blog.sir'),
                    ('fixtureDdl', 'sir-toolchain-application/src/test/resources/conformance/mysql/blog-ddl.sql'),
                    ('fixtureSeed', 'sir-toolchain-application/src/test/resources/conformance/mysql/blog-seed.sql')]:
    expected = re.search(r'^' + label + r'=.* sha256=([a-f0-9]{64})$', text, re.M)[1]
    actual = hashlib.sha256(subprocess.check_output(['git', 'show', sha + ':' + path])).hexdigest()
    assert actual == expected, path
    bindings[path] = actual
proofs = {}
for label in ['X01', 'X02', 'X03', 'X04']:
    proof = re.search(r'^blogContentionProof\[' + label + r'\] connections=\[([^]]+)\] transactions=\[([^]]+)\]$', text, re.M)
    assert proof
    connections, transactions = (part.split(', ') for part in proof.groups())
    assert len(set(connections)) == 6 and len(set(transactions)) == 6
    proofs[label] = {'connections': connections, 'transactions': transactions}
for expected in ['PASS the generated application process exited (stopped=true)',
                 'PASS the owned schema was dropped and its absence proved (schema=kcg_conf_run)',
                 'PASS the advisory lock for the owned schema was released (released=true)',
                 'generatedProjectBuild=PASSED (exit 0)', 'httpReadiness=READY (200']:
    assert expected in text, expected
xmls = sorted((root / 'surefire-reports').rglob('TEST-*.xml'))
totals = {key: sum(int(ET.parse(p).getroot().attrib[key]) for p in xmls) for key in ['tests', 'failures', 'errors', 'skipped']}
assert all(totals[key] == 0 for key in ['failures', 'errors', 'skipped'])
selected = []
suites = {}
for name in ['QueryDiagnosticMessageTest', 'BlogReferenceContractTest', 'BlogOracleTest', 'BlogBusinessConformanceIT', 'SlmCandidateSupportTest']:
    path, = [p for p in xmls if p.name.endswith('.' + name + '.xml')]
    suite = ET.parse(path).getroot()
    assert all(int(suite.attrib[k]) == 0 for k in ['failures', 'errors', 'skipped'])
    suites[name] = {k: int(suite.attrib[k]) for k in ['tests', 'failures', 'errors', 'skipped']}
    selected.append(path)
assert suites['QueryDiagnosticMessageTest']['tests'] == 22
assert all(b'kcg-ci-' not in p.read_bytes() for p in [*report.parent.rglob('*'), *selected] if p.is_file())
destination.mkdir(parents=True)
shutil.copytree(report.parent, destination / 'blog-reference')
(destination / 'junit').mkdir()
for path in selected:
    shutil.copyfile(path, destination / 'junit' / path.name)
for name in ['run.json', 'jobs.json', 'artifacts.json']:
    shutil.copyfile(root / name, destination / name)
(destination / 'original-zips').mkdir()
artifacts = json.loads((root / 'artifacts.json').read_text(encoding='utf-8'))['artifacts']
for name in ['conformance-evidence', 'surefire-reports', 'standalone-cli']:
    path = root / (name + '.zip')
    artifact, = [a for a in artifacts if a['name'] == name]
    assert 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest() == artifact['digest']
    shutil.copyfile(path, destination / 'original-zips' / path.name)
generated = re.search(r'^generatedFiles=(\d+) combinedSha256=([a-f0-9]{64})$', text, re.M)
summary = {'kind': 'existing-reference-only-not-candidate-qualification', 'commit': sha,
           'run_id': run['id'], 'run_number': run['run_number'], 'run_url': run['html_url'],
           'gates': dict.fromkeys(['static', 'build', 'start', 'business'], 'PASSED'),
           'checks_passed': int(checks[1]), 'checks_failed': 0, 'generated_files': int(generated[1]),
           'legacy_business_combined_sha256': generated[2], 'source_bindings': bindings,
           'contention_proofs': proofs, 'selected_junit': suites,
           'uploaded_surefire_totals': {'xml_files': len(xmls), **totals},
           'checker_jar_sha256': hashlib.sha256((root / 'standalone-cli/kcg.jar').read_bytes()).hexdigest(),
           'raw_artifacts': {p.relative_to(destination).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                             for p in sorted(destination.rglob('*')) if p.is_file()}}
(destination / 'reference-summary.json').write_bytes((json.dumps(summary, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
shutil.copyfile(Path(__file__), destination / 'archive_evidence.py')
print(json.dumps({k: summary[k] for k in ['commit', 'checks_passed', 'checks_failed', 'generated_files', 'uploaded_surefire_totals', 'selected_junit']}, indent=2))
