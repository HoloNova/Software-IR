#!/usr/bin/env bash
set -euo pipefail
umask 022
RUN=/home/labadmin/sir-reference/20261025-pilot
mkdir -p "$RUN"
cd "$RUN"
python3 - <<'PY'
import hashlib,json
from pathlib import Path
manifest=json.loads(Path('transfer-manifest.json').read_text())
for name,expected in manifest.items():
    with Path(name).open('rb') as stream: actual=hashlib.file_digest(stream,'sha256').hexdigest()
    assert actual==expected['sha256'],name
print('TRANSFER_HASHES_VERIFIED',len(manifest))
PY
if test -e repo; then echo 'Refuse to overwrite reference working tree' >&2; exit 1; fi
git clone --no-checkout base.bundle repo
mkdir -p m2 maven evidence
# Only this operator-created archive is extracted; no external model archive is executed.
tar -xzf source.tar.gz -C repo
tar -xzf maven-cache.tar.gz -C m2
tar -xzf maven-tool.tar.gz -C maven
chmod +x maven/bin/mvn
mkdir -p maven/conf
printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n' > maven/conf/settings.xml
python3 - <<'PY'
import hashlib,json
from pathlib import Path
manifest=json.loads(Path('source-manifest.json').read_text())
for name,expected in manifest['inputs'].items():
    with (Path('repo')/name).open('rb') as stream: actual=hashlib.file_digest(stream,'sha256').hexdigest()
    assert actual==expected,name
print('SOURCE_HASHES_VERIFIED',len(manifest['inputs']))
PY
java -version 2>&1
"$RUN/maven/bin/mvn" --version
printf 'SETUP_READY %s\n' "$RUN"
