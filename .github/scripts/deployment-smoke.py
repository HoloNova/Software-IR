#!/usr/bin/env python3
"""CI-only first-deployment acceptance. Uses the exported entry, real Docker/MySQL and HTTP, never fixture DDL."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--package', required=True, type=Path)
p.add_argument('--state', required=True, type=Path)
p.add_argument('--evidence', required=True, type=Path)
p.add_argument('--instance', required=True)
p.add_argument('--port', type=int, default=18080)
a = p.parse_args()
a.evidence.mkdir(parents=True, exist_ok=True)
report = {'commit': os.getenv('GITHUB_SHA'), 'runUrl': f"{os.getenv('GITHUB_SERVER_URL')}/{os.getenv('GITHUB_REPOSITORY')}/actions/runs/{os.getenv('GITHUB_RUN_ID')}",
          'packageId': (a.package / 'package.id').read_text().strip(),
          'deployment': 'NOT_RUN', 'httpSmoke': 'NOT_RUN', 'persistence': 'NOT_RUN', 'privileges': 'NOT_RUN',
          'missingVolume': 'NOT_RUN', 'cleanup': 'NOT_RUN'}
sequence = 0


def run(command, expected=0, timeout=1800):
    global sequence
    sequence += 1
    log = a.evidence / f'{sequence:02}-process.log'
    with log.open('w') as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    if expected is None:
        assert result.returncode != 0, 'operation unexpectedly succeeded'
    else:
        assert result.returncode == expected, f'operation failed (exit {result.returncode}); see {log.name}'
    return log.read_text()


def deploy(action, expected=0):
    return run(['sh', str(a.package / 'deploy/deploy.sh'), action, '--instance', a.instance,
                '--state-root', str(a.state), '--port', str(a.port)], expected)


def request(path, body=None, token=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(f'http://127.0.0.1:{a.port}' + path,
                                 data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    assert status == expected, f'{path} returned {status}, expected {expected}'
    return json.loads(data)


def database_container():
    return subprocess.check_output(['docker', 'ps', '-q', '--filter', f'label=com.docker.compose.project={a.instance}',
                                    '--filter', 'label=com.docker.compose.service=db'], text=True).strip()


def sql(statement, app=False, expected=0):
    role, secret = ('kcg_app', 'app-password') if app else ('root', 'root-password')
    command = f'MYSQL_PWD=$(cat /run/secrets/{secret}) mysql --protocol=socket -u{role} -Dkcg -N -B -e "$1"'
    return run(['docker', 'exec', database_container(), 'sh', '-c', command, 'sh', statement], expected)


try:
    deploy('deploy')
    report['deployment'] = 'PASSED'
    deploy('status')
    assert request('/actuator/health/readiness')['status'] == 'UP'
    user = request('/api/register', {'name': 'ci-deploy-user', 'password': 'test-password'}, expected=201)
    uid = int(user['id'])
    assert request('/api/get-user?id=' + str(uid))['name'] == 'ci-deploy-user'
    pair = request('/api/login', {'name': 'ci-deploy-user', 'password': 'test-password'}, expected=201)
    assert pair['accessToken'] != pair['refreshToken']
    request('/api/post-note', {'text': 'network smoke'}, expected=401)
    request('/api/post-note', {'text': 'network smoke'}, token=pair['accessToken'], expected=201)
    assert sql("SELECT COUNT(*) FROM `note` WHERE text='network smoke'").strip() == '1'
    report['httpSmoke'] = 'PASSED'
    assert sql('SELECT COUNT(*) FROM `user`', app=True).strip() == '1', 'application credentials must actually work'
    denied = sql('CREATE TABLE forbidden_app_ddl(id INT)', app=True, expected=None)
    assert '1142' in denied or '1044' in denied, 'DDL must fail because of privileges, not an unrelated SQL error'
    assert sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME='forbidden_app_ddl'").strip() == '0'
    report['privileges'] = 'PASSED'
    secret_before = hashlib.sha256((a.state / 'app-password').read_bytes()).hexdigest()
    deploy('stop')
    deploy('start')
    assert request('/api/get-user?id=' + str(uid))['name'] == 'ci-deploy-user'
    # A repeated deployment must use the same database, migration V1 and credentials.
    deploy('deploy')
    assert request('/api/get-user?id=' + str(uid))['name'] == 'ci-deploy-user'
    assert secret_before == hashlib.sha256((a.state / 'app-password').read_bytes()).hexdigest()
    assert sql('SELECT COUNT(*) FROM flyway_schema_history WHERE success=1').strip() == '1'
    report['persistence'] = 'PASSED'
    deploy('stop')
    # Remove only this disposable CI instance, to prove start cannot silently replace its data volume.
    ids = subprocess.check_output(['docker', 'ps', '-aq', '--filter', f'label=com.docker.compose.project={a.instance}'], text=True).split()
    if ids:
        run(['docker', 'rm', '-f', *ids])
    run(['docker', 'volume', 'rm', a.instance + '-db'])
    deploy('start', expected=None)
    check = subprocess.run(['docker', 'volume', 'inspect', a.instance + '-db'], capture_output=True)
    assert check.returncode != 0, 'start recreated the missing data volume'
    report['missingVolume'] = 'PASSED'
except Exception as error:
    report['failure'] = str(error)
finally:
    # Collect only the owned CI instance. Never upload its state directory or secrets.
    try:
        ids = set()
        for label in (f'com.docker.compose.project={a.instance}', f'kcg.instance={a.instance}'):
            ids.update(subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=' + label], text=True).split())
        for cid in ids:
            with (a.evidence / f'container-{cid}.log').open('w') as output:
                subprocess.run(['docker', 'logs', cid], stdout=output, stderr=subprocess.STDOUT, timeout=20)
        if ids:
            run(['docker', 'rm', '-f', *sorted(ids)])
        for volume in (a.instance + '-db', a.instance + '-maven'):
            present = subprocess.run(['docker', 'volume', 'inspect', volume], capture_output=True)
            if present.returncode == 0:
                run(['docker', 'volume', 'rm', volume])
        for label in (f'com.docker.compose.project={a.instance}', f'kcg.instance={a.instance}'):
            assert not subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=' + label], text=True).strip()
        report['cleanup'] = 'PASSED'
    except Exception as error:
        report['cleanup'] = 'FAILED'
        report['cleanupFailure'] = str(error)
    secrets = []
    for name in ('root-password', 'app-password', 'migrate-password'):
        file = a.state / name
        if file.is_file():
            secrets.append(file.read_text())
    for file in a.evidence.glob('*.log'):
        text = file.read_text(errors='replace')
        for secret in secrets:
            text = text.replace(secret, '[REDACTED]')
        file.write_text(text)
    (a.evidence / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
sys.exit(0 if all(report[key] == 'PASSED' for key in ('deployment', 'httpSmoke', 'persistence', 'privileges', 'missingVolume', 'cleanup')) else 1)
