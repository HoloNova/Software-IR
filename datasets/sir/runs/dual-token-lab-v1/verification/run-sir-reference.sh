#!/usr/bin/env bash
set -euo pipefail
umask 077
RUN=/home/labadmin/sir-reference/20261025-pilot
REPO="$RUN/repo"
MAVEN="$RUN/maven/bin/mvn"
CACHE="$RUN/m2"
UNIT=sir-slm-pilot-20261025
mkdir -p "$RUN/evidence" "$RUN/private"
cleanup() {
  systemctl --user stop "$UNIT-compile.scope" "$UNIT-auth-dual-token-v1-reference.scope" "$UNIT-auth-dual-token-v1-teacher-ef9ddc30eb79.scope" 2>/dev/null || true
  if test -f "$RUN/private/db.json"; then
    python3 - "$RUN/private/db.json" <<'PY' | sudo -n mysql
import json,sys
from pathlib import Path
p=Path(sys.argv[1]);d=json.loads(p.read_text())
print('DROP DATABASE IF EXISTS `'+d['schema']+'`;')
for kind in ('control','runtime'):
    print("DROP USER IF EXISTS '"+d[kind]['user']+"'@'127.0.0.1';")
PY
    rm -f "$RUN/private/db.json"
  fi
}
trap cleanup EXIT HUP INT TERM
cd "$REPO"
export MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2'
set +e
timeout --signal=TERM --kill-after=30s 600s systemd-run --user --scope -q --unit="$UNIT-compile" -p MemoryMax=2304M -p CPUQuota=150% -- \
  "$MAVEN" -B -o -Dmaven.repo.local="$CACHE" -pl kcg-cli -am -DskipTests package > "$RUN/evidence/compile.log" 2>&1
compile_status=$?
set -e
printf 'compile_exit=%s\n' "$compile_status"
if test "$compile_status" -ne 0; then exit "$compile_status"; fi
BUNDLE="$REPO/datasets/sir/runs/dual-token-lab-v1"
python3 -m experiments.slm.data import --from-bundle "$REPO/datasets/sir/pilots/dual-token-v1" --bundle "$BUNDLE" > "$RUN/evidence/import.json"
python3 -m experiments.slm.data check --bundle "$BUNDLE" --maven-repo "$CACHE" > "$RUN/evidence/static.log"
# Provision only a fresh random schema and two users restricted to that schema.
python3 - "$RUN/private/db.json" <<'PY' | sudo -n mysql
import json,os,secrets,sys,subprocess
from pathlib import Path
suffix=secrets.token_hex(4)
d={'schema':'kcg_conf_slm_'+suffix,'control':{'user':'slmctl_'+suffix,'password':secrets.token_hex(24)},'runtime':{'user':'slmrun_'+suffix,'password':secrets.token_hex(24)}}
query="SELECT schema_name FROM information_schema.schemata WHERE schema_name='"+d['schema']+"'; SELECT user FROM mysql.user WHERE user IN ('"+d['control']['user']+"','"+d['runtime']['user']+"');"
existing=subprocess.check_output(['sudo','-n','mysql','-N','-B','-e',query],text=True)
if existing.strip(): raise SystemExit('Refuse to provision over existing reference identities/schema')
# Refuse to overwrite any previously retained credentials.
p=Path(sys.argv[1]);fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as stream:json.dump(d,stream)
for kind,rights in [('control','ALL PRIVILEGES'),('runtime','SELECT, INSERT, UPDATE, DELETE')]:
    u=d[kind]
    print("CREATE USER '"+u['user']+"'@'127.0.0.1' IDENTIFIED BY '"+u['password']+"';")
    print('GRANT '+rights+' ON `'+d['schema']+"`.* TO '"+u['user']+"'@'127.0.0.1';")
PY
# Values pass only through the process environment, never command arguments or logs.
eval "$(python3 - "$RUN/private/db.json" <<'PY'
import json,shlex,sys
from pathlib import Path
d=json.loads(Path(sys.argv[1]).read_text())
v={'KCG_CONF_CONTROL_JDBC_URL':'jdbc:mysql://127.0.0.1:3306/','KCG_CONF_CONTROL_USERNAME':d['control']['user'],'KCG_CONF_CONTROL_PASSWORD':d['control']['password'],'KCG_CONF_RUNTIME_USERNAME':d['runtime']['user'],'KCG_CONF_RUNTIME_PASSWORD':d['runtime']['password'],'KCG_CONF_SCHEMA_NAME':d['schema']}
for k,value in v.items():print('export '+k+'='+shlex.quote(value))
PY
)"
export MAVEN_OPTS='-Xmx256m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2'
for sample in auth-dual-token-v1-reference auth-dual-token-v1-teacher-ef9ddc30eb79; do
  mkdir -p "$RUN/evidence/$sample"
  set +e
  timeout --signal=TERM --kill-after=30s 600s systemd-run --user --scope -q --unit="$UNIT-$sample" -p MemoryMax=2304M -p CPUQuota=150% -- \
    "$MAVEN" -B -o -Dmaven.repo.local="$CACHE" -pl sir-toolchain-application -am test \
    -Dtest=DualTokenBusinessConformanceIT -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2' \
    -Dkcg.dual-token-conformance.enabled=true \
    -Dkcg.conformance.work-parent="$RUN/work" \
    -Dkcg.conformance.evidence-parent="$RUN/evidence/$sample" \
    -Dkcg.conformance.maven-executable="$MAVEN" -Dkcg.conformance.maven-repo="$CACHE" \
    -Dkcg.conformance.server-port=18184 \
    -Dkcg.conformance.maven-xmx-mb=256 -Dkcg.conformance.application-xmx-mb=384 -Dkcg.conformance.child-metaspace-mb=128 \
    -Dkcg.slm.candidate-root="$BUNDLE/samples/$sample" \
    -Dkcg.slm.bundle-root="$BUNDLE" -Dkcg.slm.repo-root="$REPO" > "$RUN/evidence/$sample/reactor.log" 2>&1
  status=$?
  set -e
  printf '%s exit=%s\n' "$sample" "$status"
  printf '%s\n' "$status" > "$RUN/evidence/$sample/exit-code.txt"
  cp -a sir-toolchain-application/target/surefire-reports "$RUN/evidence/$sample/junit"
  if test "$sample" = auth-dual-token-v1-reference && test "$status" -ne 0; then
    echo 'Reference control failed; do not label the teacher candidate with an invalid environment.'
    exit "$status"
  fi
done
printf 'REFERENCE_RUN_FINISHED\n'
