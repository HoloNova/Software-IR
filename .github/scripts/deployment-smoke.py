#!/usr/bin/env python3
"""CI-only first-deployment acceptance. Uses the exported entry, real Docker/MySQL and HTTP, never fixture DDL."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import traceback
import urllib.error
import urllib.parse
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--package', required=True, type=Path)
p.add_argument('--state', required=True, type=Path)
p.add_argument('--evidence', required=True, type=Path)
p.add_argument('--instance', required=True)
p.add_argument('--port', type=int, default=18080)
# Same schema, different bytes: the package a schema-unchanged update moves the instance to (step 3, slice A).
p.add_argument('--next-package', required=True, type=Path)
# Continues the next package's chain with a new table and a new nullable column (step 3, slice B).
p.add_argument('--v2-package', required=True, type=Path)
# Continues version 2's chain with a widened column and, with the data loss confirmed at export, a dropped column and table.
p.add_argument('--v3-package', required=True, type=Path)
a = p.parse_args()
a.evidence.mkdir(parents=True, exist_ok=True)
report = {'commit': os.getenv('GITHUB_SHA'), 'runUrl': f"{os.getenv('GITHUB_SERVER_URL')}/{os.getenv('GITHUB_REPOSITORY')}/actions/runs/{os.getenv('GITHUB_RUN_ID')}",
          'packageId': (a.package / 'package.id').read_text().strip(),
          'deployment': 'NOT_RUN', 'httpSmoke': 'NOT_RUN', 'persistence': 'NOT_RUN', 'privileges': 'NOT_RUN',
          'swagger': 'NOT_RUN', 'contract': 'NOT_RUN', 'missingVolume': 'NOT_RUN', 'business': 'NOT_RUN', 'update': 'NOT_RUN', 'dataUpdate': 'NOT_RUN', 'freshChain': 'NOT_RUN',
          'cleanup': 'NOT_RUN',
          'notRunByContract': ['interruptedMigration', 'schemaDrift'],
          'nextPackageId': (a.next_package / 'package.id').read_text().strip(),
          'v2PackageId': (a.v2_package / 'package.id').read_text().strip(),
          'v3PackageId': (a.v3_package / 'package.id').read_text().strip(), 'requests': []}
sequence = 0
# Business acceptance writes data, so it gets its own disposable instance of the same package.
acceptance_instance, acceptance_state, acceptance_port = a.instance + '-acc', a.state.with_name(a.state.name + ' acceptance'), a.port + 1
# A new instance deployed straight from version 3 replays the whole chain V1..V11.
fresh_instance, fresh_state, fresh_port = a.instance + '-v2', a.state.with_name(a.state.name + ' fresh'), a.port + 2
INSTANCES = (a.instance, acceptance_instance, fresh_instance)
STATES = (a.state, acceptance_state, fresh_state)


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


def deploy(action, expected=0, instance=None, state=None, port=None, extra=(), package=None):
    command = ['sh', str((package or a.package) / 'deploy/deploy.sh'), action, '--instance', instance or a.instance,
               '--state-root', str(state or a.state)]
    if action in ('deploy', 'start'):
        command += ['--port', str(port or a.port)]
    return run(command + list(extra), expected)


def exchange(path, body, token, expected, base=None, method=None):
    """Sends one request and records its shape (never tokens, passwords or response bodies) in the report."""
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request((base or f'http://127.0.0.1:{a.port}') + path,
                                 data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            status, content_type, data = response.status, response.headers.get('Content-Type'), response.read()
    except urllib.error.HTTPError as error:
        status, content_type, data = error.code, error.headers.get('Content-Type'), error.read()
    report['requests'].append({'method': req.get_method(), 'path': path, 'base': base, 'authenticated': bool(token),
                               'expected': expected, 'status': status, 'contentType': content_type,
                               'bodyBytes': len(data)})
    assert status == expected, f'{req.get_method()} {path} returned {status}, expected {expected}'
    return content_type, data


def request(path, body=None, token=None, expected=200, base=None, method=None):
    """A call whose business result is asserted: the body must be JSON."""
    content_type, data = exchange(path, body, token, expected, base, method)
    try:
        return json.loads(data)
    except ValueError as error:
        raise AssertionError(f'{path} returned {expected} with a non-JSON body '
                             f'(Content-Type {content_type}, {len(data)} bytes): {error}') from error


def status_only(path, body=None, token=None, expected=401):
    """A call that only proves a rejection; HTTP allows the body to be empty, so it is not parsed."""
    exchange(path, body, token, expected)


def database_container(instance=None):
    return subprocess.check_output(['docker', 'ps', '-q', '--filter', f'label=com.docker.compose.project={instance or a.instance}',
                                    '--filter', 'label=com.docker.compose.service=db'], text=True).strip()


def sql(statement, app=False, expected=0, instance=None):
    role, secret = ('kcg_app', 'app-password') if app else ('root', 'root-password')
    command = f'MYSQL_PWD=$(cat /run/secrets/{secret}) mysql --protocol=socket -u{role} -Dkcg -N -B -e "$1"'
    return run(['docker', 'exec', database_container(instance), 'sh', '-c', command, 'sh', statement], expected)


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
    status_only('/api/post-note', {'text': 'network smoke'}, expected=401)
    request('/api/post-note', {'text': 'network smoke'}, token=pair['accessToken'], expected=201)
    assert sql("SELECT COUNT(*) FROM `note` WHERE text='network smoke'").strip() == '1'
    report['httpSmoke'] = 'PASSED'
    assert sql('SELECT COUNT(*) FROM `user`', app=True).strip() == '1', 'application credentials must actually work'
    denied = sql('CREATE TABLE forbidden_app_ddl(id INT)', app=True, expected=None)
    assert '1142' in denied or '1044' in denied, 'DDL must fail because of privileges, not an unrelated SQL error'
    assert sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME='forbidden_app_ddl'").strip() == '0'
    report['privileges'] = 'PASSED'
    # What Swagger UI's "Try it out" does: take the server and the operation from the served document, then call it.
    docs = request('/v3/api-docs')
    server = docs['servers'][0]['url'].rstrip('/')
    assert server == f'http://127.0.0.1:{a.port}', f'documented server {server}'
    assert 'post' in docs['paths']['/api/register'] and 'bearer' in str(docs['paths']['/api/post-note']['post'].get('security'))
    assert int(request('/api/register', {'name': 'ci-swagger-user', 'password': 'test-password'}, expected=201, base=server)['id']) != uid
    ui_type, _ = exchange('/swagger-ui/index.html', None, None, 200)
    assert (ui_type or '').startswith('text/html'), f'Swagger UI content type {ui_type}'
    report['swagger'] = 'PASSED'
    # The generated declared-interface contract test, through the package's public entry, against this running instance.
    deploy('contract-test')
    report['contract'] = 'PASSED'
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
    # The package's separate dual-token suite, through the public entry, on a fresh disposable instance.
    deploy('deploy', instance=acceptance_instance, state=acceptance_state, port=acceptance_port)
    deploy('acceptance-test', instance=acceptance_instance, state=acceptance_state,
           extra=('--confirm-disposable', acceptance_instance))
    report['business'] = 'PASSED'
    # Step 3, slice A: a package with the same schema takes over the instance and keeps every row and value.
    tables = '`user`, `note`, `access_token`, `refresh_token`, `token_pair`'
    before = sql('CHECKSUM TABLE ' + tables, instance=acceptance_instance)
    updated = deploy('update', instance=acceptance_instance, state=acceptance_state, package=a.next_package)
    assert f"KCG_UPDATED {report['packageId']}->{report['nextPackageId']}" in updated, 'schema-unchanged update did not complete'
    assert 'applied=none' in updated, 'a schema-unchanged update must not run SQL migrations'
    assert sql('CHECKSUM TABLE ' + tables, instance=acceptance_instance) == before, 'rows or values changed during the update'
    assert sql('SELECT COUNT(*) FROM flyway_schema_history', instance=acceptance_instance).strip() == '1'
    status = deploy('status', instance=acceptance_instance, state=acceptance_state, package=a.next_package)
    assert f"bound={report['nextPackageId']}" in status, 'the instance is not bound to the new package'
    assert 'KCG_UPDATE_CURRENT' in deploy('update', instance=acceptance_instance, state=acceptance_state, package=a.next_package)
    refused = deploy('start', expected=3, instance=acceptance_instance, state=acceptance_state, port=acceptance_port)
    assert 'KCG_BLOCKED' in refused, 'the previous package must not run an updated instance without update'
    # The new application serves the old data: the requirement suite passes again on the updated instance.
    deploy('acceptance-test', instance=acceptance_instance, state=acceptance_state, package=a.next_package,
           extra=('--confirm-disposable', acceptance_instance))
    report['update'] = 'PASSED'
    # Step 3, slice B: version 2 renames note.text to body (the field kept its declared identity), changes the default of
    # note.pinned, adds a table and two columns; old keys, values and relations stay as they were, the renamed column under
    # its new name.
    keep = 'SELECT id, name, password_hash FROM `user` ORDER BY id; SELECT id, author_id, {} FROM `note` ORDER BY id'
    rows_before = sql(keep.format('text'), instance=acceptance_instance)
    updated = deploy('update', instance=acceptance_instance, state=acceptance_state, package=a.v2_package)
    assert f"KCG_UPDATED {report['nextPackageId']}->{report['v2PackageId']}" in updated, 'the additive update did not complete'
    assert 'applied=V2..V6' in updated and 'KCG_DATA_VERIFIED' in updated, 'the migration tool did not apply and verify V2..V6'
    assert sql(keep.format('body'), instance=acceptance_instance) == rows_before, 'old rows or values changed'
    assert sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME='note' AND COLUMN_NAME='text'",
               instance=acceptance_instance).strip() == '0', 'the renamed column still exists under its old name'
    assert sql('SELECT COUNT(*) FROM flyway_schema_history WHERE success=1', instance=acceptance_instance).strip() == '6'
    assert sql('SELECT COUNT(*) FROM `note` WHERE title IS NOT NULL', instance=acceptance_instance).strip() == '0'
    # The required column added with a default: every row that was already there has the default.
    assert int(sql('SELECT COUNT(*) FROM `note`', instance=acceptance_instance).strip()) > 0
    assert sql("SELECT COUNT(*) FROM `note` WHERE state <> 'open'", instance=acceptance_instance).strip() == '0', 'existing rows lack the default'
    # The changed default: the column now defaults to true, and every row already there kept false.
    assert sql("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME='note' AND COLUMN_NAME='pinned'",
               instance=acceptance_instance).strip() == '1', 'the default of note.pinned did not change'
    assert sql('SELECT COUNT(*) FROM `note` WHERE pinned <> 0', instance=acceptance_instance).strip() == '0', 'existing rows changed with the default'
    note_id = int(sql('SELECT MIN(id) FROM `note`', instance=acceptance_instance).strip())
    server = f'http://127.0.0.1:{acceptance_port}'
    label = request('/api/add-label', {'noteId': note_id, 'name': 'kept'}, expected=201, base=server)
    assert sql(f"SELECT note_id FROM `label` WHERE id={int(label['id'])}", instance=acceptance_instance).strip() == str(note_id)
    assert request('/api/add-label', {'noteId': 999999999, 'name': 'none'}, expected=404, base=server)['code'] == 'NoteNotFound'
    # Version 2 also has the create, show, list, change and delete of labels, from one
    # `use Crud { entity: Label; path: labels; }`, at REST-style paths.
    made = request('/api/labels', {'noteId': note_id, 'name': 'crud'}, expected=201, base=server)
    assert made['note'] == note_id and made['name'] == 'crud', made
    label_path = f"/api/labels/{made['id']}"
    assert request(label_path, base=server)['name'] == 'crud'
    listed = request('/api/labels?page=1&size=100', base=server)
    assert made['id'] in [row['id'] for row in listed['records']] and listed['total'] >= 2, listed
    # The identity comes from the path; a different one in the body does not redirect the change.
    changed = request(label_path, {'id': 999999999, 'noteId': note_id, 'name': 'renamed'}, base=server, method='PUT')
    assert changed['id'] == made['id'] and changed['name'] == 'renamed', changed
    assert sql(f"SELECT name FROM `label` WHERE id={int(made['id'])}", instance=acceptance_instance).strip() == 'renamed'
    assert request(label_path, {'noteId': note_id, 'name': ''}, expected=400, base=server, method='PUT')['code'] == 'INVALID_REQUEST'
    exchange(label_path, None, None, 200, base=server, method='DELETE')
    assert request(label_path, expected=404, base=server)['code'] == 'LabelNotFound'
    assert sql(f"SELECT COUNT(*) FROM `label` WHERE id={int(made['id'])}", instance=acceptance_instance).strip() == '0'
    # Notes belong to their author (`use Crud { entity: Note; owner: author; }`): another account does not find them,
    # and the hand-written PinNote answers it 403.
    tokens = {}
    for who in ('v2-owner-a', 'v2-owner-b'):
        request('/api/register', {'name': who, 'password': 'test-password'}, expected=201, base=server)
        tokens[who] = request('/api/login', {'name': who, 'password': 'test-password'}, expected=201, base=server)['accessToken']
    owner, other = tokens['v2-owner-a'], tokens['v2-owner-b']
    mine = request('/api/create-note', {'body': 'owned'}, token=owner, expected=201, base=server)
    assert mine['body'] == 'owned' and mine['pinned'] is True and mine['state'] == 'open', mine
    path = f"/api/get-note?id={mine['id']}"
    assert request(path, token=owner, base=server)['body'] == 'owned'
    assert request(path, token=other, expected=404, base=server)['code'] == 'NoteNotFound'
    exchange(path, None, None, 401, base=server)
    change = {'id': mine['id'], 'body': 'changed', 'pinned': False, 'state': 'open', 'title': None}
    assert request('/api/update-note', change, token=other, expected=404, base=server)['code'] == 'NoteNotFound'
    assert request('/api/delete-note', {'id': mine['id']}, token=other, expected=404, base=server)['code'] == 'NoteNotFound'
    assert request('/api/pin-note', {'id': mine['id']}, token=other, expected=403, base=server)['code'] == 'NotAuthor'
    assert mine['id'] in [row['id'] for row in request('/api/list-note?page=1&size=100', token=owner, base=server)['records']]
    assert mine['id'] not in [row['id'] for row in request('/api/list-note?page=1&size=100', token=other, base=server)['records']]
    assert request('/api/update-note', change, token=owner, base=server)['body'] == 'changed'
    assert request('/api/pin-note', {'id': mine['id']}, token=owner, base=server)['pinned'] is True
    exchange('/api/delete-note', {'id': mine['id']}, owner, 200, base=server)
    assert sql(f"SELECT COUNT(*) FROM `note` WHERE id={int(mine['id'])}", instance=acceptance_instance).strip() == '0'
    assert 'KCG_UPDATE_CURRENT' in deploy('update', instance=acceptance_instance, state=acceptance_state, package=a.v2_package)
    older = deploy('update', expected=3, instance=acceptance_instance, state=acceptance_state, package=a.next_package)
    assert 'AHEAD' in older and 'application=RUNNING' in older, 'an older package must be refused before the application stops'
    assert request('/actuator/health/readiness', base=server)['status'] == 'UP'
    # Step 3, slice C: version 3 widens note.state from 16 to 64 characters, drops note.title and drops the label table
    # (the data loss was confirmed when the package was exported). Every other row and value stays as it was.
    kept = request('/api/create-note', {'body': 'v3-kept'}, token=owner, expected=201, base=server)
    request('/api/update-note', {'id': kept['id'], 'body': 'v3-kept', 'pinned': True, 'state': 'open', 'title': 'gone soon'}, token=owner, base=server)
    assert sql(f"SELECT title FROM `note` WHERE id={int(kept['id'])}", instance=acceptance_instance).strip() == 'gone soon'
    keep3 = 'SELECT id, author_id, body, pinned, state FROM `note` ORDER BY id'
    rows_before3 = sql(keep3, instance=acceptance_instance)
    assert int(sql('SELECT COUNT(*) FROM `label`', instance=acceptance_instance).strip()) > 0
    updated = deploy('update', instance=acceptance_instance, state=acceptance_state, package=a.v3_package)
    assert f"KCG_UPDATED {report['v2PackageId']}->{report['v3PackageId']}" in updated, 'the update that drops data did not complete'
    assert 'applied=V7..V11' in updated and 'KCG_DATA_VERIFIED' in updated, 'the migration tool did not apply and verify V7..V11'
    assert 'KCG_DATA_DROPPED' in updated and 'note.title' in updated and 'label.' in updated, 'the migration tool did not name what it dropped'
    assert sql(keep3, instance=acceptance_instance) == rows_before3, 'rows or values that stay changed'
    assert sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME='note' AND COLUMN_NAME='title'",
               instance=acceptance_instance).strip() == '0', 'the dropped column is still there'
    assert sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME='label'",
               instance=acceptance_instance).strip() == '0', 'the dropped table is still there'
    assert sql("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME='note' AND COLUMN_NAME='state'",
               instance=acceptance_instance).strip() == '64', 'the column was not widened'
    assert sql("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME='note' AND COLUMN_NAME='state'",
               instance=acceptance_instance).strip() == 'open', 'widening lost the default'
    assert sql('SELECT COUNT(*) FROM flyway_schema_history WHERE success=1', instance=acceptance_instance).strip() == '11'
    # The new application serves what stays and no longer has what was dropped.
    owner = request('/api/login', {'name': 'v2-owner-a', 'password': 'test-password'}, expected=201, base=server)['accessToken']
    assert request(f"/api/get-note?id={kept['id']}", token=owner, base=server)['body'] == 'v3-kept'
    exchange('/api/labels?page=1&size=1', None, None, 404, base=server)
    # A unique key over two columns (note, owner), created with the new table: the second favourite of one note by one
    # account is refused by the database and answered with the declared error.
    exchange(f"/api/notes/{kept['id']}/favorite", {}, owner, 201, base=server, method='POST')
    again = request(f"/api/notes/{kept['id']}/favorite", {}, token=owner, expected=409, base=server, method='POST')
    assert again['code'] == 'AlreadyFavorite', again
    assert sql('SELECT COUNT(*) FROM `favorite`', instance=acceptance_instance).strip() == '1', 'the refused favourite was written'
    counted = "SELECT favorites FROM `user` WHERE name='v2-owner-a'"
    assert sql(counted, instance=acceptance_instance).strip() == '1', 'the refused favourite was counted, or the first one was not'
    # The reader's count is added to by the database (`favorites: reader.favorites + 1`): the same account favourites six
    # notes at the same moment and every request is counted; none overwrites another with the value it read.
    fresh_notes = [request('/api/create-note', {'body': f'favourite-{i}'}, token=owner, expected=201, base=server)['id'] for i in range(6)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(lambda note: exchange(f'/api/notes/{note}/favorite', {}, owner, 201, base=server, method='POST'), fresh_notes))
    assert sql('SELECT COUNT(*) FROM `favorite`', instance=acceptance_instance).strip() == '7', 'a concurrent favourite was lost'
    assert sql(counted, instance=acceptance_instance).strip() == '7', 'concurrent favourites were not all counted'
    assert sql("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME='favorite' AND INDEX_NAME='kcg_uq_0' AND NON_UNIQUE=0",
               instance=acceptance_instance).strip() == '2', 'the unique key over two columns is not there'
    # G7: query SQL and Java validation share the same present-only comparison policy. In particular,
    # != excludes a NULL operand, while NOT(==) includes it. Optional filters must be explicitly skipped.
    def favorite_rows(path, suffix=''):
        return request(f'/api/favorites/{path}?page=1&size=100{suffix}', token=owner, base=server)['records']
    favorites = favorite_rows('filter')
    assert len(favorites) == 7 and all(row['score'] is None for row in favorites), favorites
    assert len(favorite_rows('missing')) == 7
    assert favorite_rows('different') == []
    assert len(favorite_rows('not-equal')) == 7
    first, second = favorites[0]['id'], favorites[1]['id']
    exchange('/api/favorites/raise', {'id': first, 'score': 1000}, owner, 201, base=server)
    exchange('/api/favorites/raise', {'id': second, 'score': 1001}, owner, 201, base=server)
    assert {row['score'] for row in favorite_rows('filter', '&minimum=1000')} == {1000, 1001}
    assert [row['score'] for row in favorite_rows('filter', '&maximum=1000')] == [1000]
    assert favorite_rows('filter', '&minimum=1002') == []
    assert len(favorite_rows('missing')) == 5
    assert [row['score'] for row in favorite_rows('different', '&minimum=1000')] == [1001]
    assert len(favorite_rows('not-equal', '&minimum=1000')) == 6
    assert favorite_rows('different') == [] and len(favorite_rows('not-equal')) == 7
    exchange(f'/api/favorites/check?id={first}&score=1001', None, owner, 200, base=server, method='GET')
    request(f'/api/favorites/check?id={first}&score=1000', token=owner, expected=409, base=server)
    request(f'/api/favorites/check?id={first}', token=owner, expected=409, base=server)
    request(f"/api/favorites/check?id={favorites[2]['id']}&score=1000", token=owner, expected=409, base=server)
    request('/api/favorites/raise', {'id': first, 'score': 999}, token=owner, expected=409, base=server)
    request('/api/favorites/raise', {'id': first, 'score': None}, token=owner, expected=409, base=server)
    assert sql(f'SELECT score FROM favorite WHERE id={int(first)}', instance=acceptance_instance).strip() == '1000'
    # G2/G4: scalar reads stay in SQL; correlated request values (including text) stay bound parameters.
    def check_count(path, expected, has_rows, minimum=None, token=owner, status=200):
        query = {'expected': expected, 'hasRows': str(has_rows).lower()}
        if minimum is not None:
            query['minimum'] = minimum
        exchange(f'/api/{path}/check-count?' + urllib.parse.urlencode(query), None, token, status, base=server, method='GET')
    check_count('favorites', 7, True)
    check_count('favorites', 2, True, 1000)
    check_count('favorites', 1, True, 1001)
    check_count('favorites', 0, False, 1002)
    check_count('favorites', 8, True, status=409)
    check_count('favorites', 7, False, status=409)
    other = request('/api/login', {'name': 'v2-owner-b', 'password': 'test-password'}, expected=201, base=server)['accessToken']
    check_count('favorites', 0, False, token=other)
    check_count('notes', 7, True)
    check_count('notes', 0, False, token=other)
    def favorited_notes(text, minimum=None, token=owner):
        query = {'text': text, 'page': 1, 'size': 100}
        if minimum is not None:
            query['minimum'] = minimum
        return request('/api/notes/favorited?' + urllib.parse.urlencode(query), token=token, base=server)['records']
    assert [row['id'] for row in favorited_notes('v3-kept')] == [kept['id']]
    assert [row['id'] for row in favorited_notes('v3-kept', 1000)] == [kept['id']]
    assert favorited_notes('v3-kept', 1001) == []
    assert favorited_notes("v3-kept' OR 1=1 --") == []
    assert favorited_notes('v3-kept', token=other) == []
    # Note's declared scope excludes hidden rows from both scalar reads and correlated root queries.
    request('/api/update-note', {'id': kept['id'], 'body': 'v3-kept', 'pinned': True, 'state': 'hidden'}, token=owner, base=server)
    check_count('notes', 6, True)
    assert favorited_notes('v3-kept') == []
    sql(f"UPDATE `note` SET state='hidden' WHERE author_id=(SELECT id FROM `user` WHERE name='v2-owner-a')", instance=acceptance_instance)
    check_count('notes', 0, False)
    # Hidden rows cannot be loaded by the public API; restore only this conformance fixture through SQL.
    sql(f"UPDATE `note` SET state='open' WHERE author_id=(SELECT id FROM `user` WHERE name='v2-owner-a')", instance=acceptance_instance)
    check_count('notes', 7, True)
    long_state = 'x' * 40
    assert request('/api/update-note', {'id': kept['id'], 'body': 'v3-kept', 'pinned': True, 'state': long_state}, token=owner, base=server)['state'] == long_state
    assert 'KCG_UPDATE_CURRENT' in deploy('update', instance=acceptance_instance, state=acceptance_state, package=a.v3_package)
    older = deploy('update', expected=3, instance=acceptance_instance, state=acceptance_state, package=a.v2_package)
    assert 'AHEAD' in older and 'application=RUNNING' in older, 'an older package must be refused before the application stops'
    report['dataUpdate'] = 'PASSED'
    deploy('deploy', instance=fresh_instance, state=fresh_state, port=fresh_port, package=a.v3_package)
    assert sql('SELECT COUNT(*) FROM flyway_schema_history WHERE success=1', instance=fresh_instance).strip() == '11'
    assert request('/api/register', {'name': 'fresh-user', 'password': 'test-password'}, expected=201, base=f'http://127.0.0.1:{fresh_port}')
    report['freshChain'] = 'PASSED'
except Exception as error:
    # Gates run in this order, so the first one not yet passed is the one that failed; the rest stay NOT_RUN.
    for gate in ('deployment', 'httpSmoke', 'privileges', 'swagger', 'contract', 'persistence', 'missingVolume', 'business', 'update',
                 'dataUpdate', 'freshChain'):
        if report[gate] == 'NOT_RUN':
            report[gate] = 'FAILED'
            break
    report['failure'] = f'{type(error).__name__}: {error}'
    report['failureTrace'] = traceback.format_exc()
finally:
    # Test reports are the only part of a state directory that is evidence; secrets stay behind.
    def rows(file):
        return [line.split('\t') for line in file.read_text().splitlines()[1:] if line] if file.is_file() else None
    for state in STATES:
        if (state / 'reports').is_dir():
            shutil.copytree(state / 'reports', a.evidence / 'reports', dirs_exist_ok=True)
    contract = rows(a.state / 'reports' / 'contract' / 'contract-report.tsv')
    if contract is not None:
        report['contractChecks'] = {outcome: sum(1 for row in contract if row[0] == outcome) for outcome in ('PASSED', 'FAILED', 'NOT_VERIFIED')}
        report['contractNotVerified'] = [f'{row[1]}: {row[2]}' for row in contract if row[0] == 'NOT_VERIFIED']
        report['contractFailed'] = [f'{row[1]}: {row[2]}: {row[3] if len(row) > 3 else ""}' for row in contract if row[0] == 'FAILED']
    business = rows(acceptance_state / 'reports' / 'acceptance' / 'acceptance-report.tsv')
    if business is not None:
        report['businessChecks'] = {outcome: sum(1 for row in business if row[0] == outcome) for outcome in ('PASSED', 'FAILED')}
        report['businessFailed'] = [f'{row[1]}: {row[2]}: {row[3] if len(row) > 3 else ""}' for row in business if row[0] == 'FAILED']
    # Collect only the owned CI instances. Never upload their state directories or secrets.
    try:
        ids = set()
        for label in [f'{key}={name}' for name in INSTANCES for key in ('com.docker.compose.project', 'kcg.instance')]:
            ids.update(subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=' + label], text=True).split())
        for cid in ids:
            with (a.evidence / f'container-{cid}.log').open('w') as output:
                subprocess.run(['docker', 'logs', cid], stdout=output, stderr=subprocess.STDOUT, timeout=20)
        if ids:
            run(['docker', 'rm', '-f', *sorted(ids)])
        for volume in [name + suffix for name in INSTANCES for suffix in ('-db', '-maven')]:
            present = subprocess.run(['docker', 'volume', 'inspect', volume], capture_output=True)
            if present.returncode == 0:
                run(['docker', 'volume', 'rm', volume])
        for label in [f'{key}={name}' for name in INSTANCES for key in ('com.docker.compose.project', 'kcg.instance')]:
            assert not subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=' + label], text=True).strip()
        report['cleanup'] = 'PASSED'
    except Exception as error:
        report['cleanup'] = 'FAILED'
        report['cleanupFailure'] = str(error)
    secrets = []
    for state in STATES:
        for name in ('root-password', 'app-password', 'migrate-password'):
            file = state / name
            if file.is_file():
                secrets.append(file.read_text())
    for file in [*a.evidence.glob('*.log'), *a.evidence.glob('reports/**/*.*')]:
        if not file.is_file():
            continue
        text = file.read_text(errors='replace')
        for secret in secrets:
            text = text.replace(secret, '[REDACTED]')
        file.write_text(text)
    (a.evidence / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
sys.exit(0 if all(report[key] == 'PASSED' for key in ('deployment', 'httpSmoke', 'persistence', 'privileges', 'swagger', 'contract',
                                                     'missingVolume', 'business', 'update', 'dataUpdate', 'freshChain',
                                                     'cleanup')) else 1)
