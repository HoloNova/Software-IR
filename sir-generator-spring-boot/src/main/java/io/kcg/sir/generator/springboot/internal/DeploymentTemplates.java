package io.kcg.sir.generator.springboot.internal;

/** Standalone sources kept as compile-time constants: rendering has no resource/file I/O. */
final class DeploymentTemplates {
    private DeploymentTemplates() {}
    static final String ENTRY = """
        #!/bin/sh
        # Standalone delivery entry: only shell/core utilities and Docker/Compose are required.
        set -eu
        umask 077
        export COMPOSE_PARALLEL_LIMIT=1
        PACKAGE=$(CDPATH= cd -P -- "$(dirname -- "$0")/.." && pwd)
        ACTION=${1:-help}
        [ "$#" -gt 0 ] && shift
        INSTANCE= STATE= PORT= CONFIRM=
        while [ "$#" -gt 0 ]; do
          [ "$#" -ge 2 ] || { echo 'option requires a value' >&2; exit 2; }
          case "$1" in
            --instance) [ -z "$INSTANCE" ] || exit 2; INSTANCE=$2 ;;
            --state-root) [ -z "$STATE" ] || exit 2; STATE=$2 ;;
            --port) [ -z "$PORT" ] || exit 2; PORT=$2 ;;
            --confirm-disposable) [ -z "$CONFIRM" ] || exit 2; CONFIRM=$2 ;;
            *) echo 'unknown deployment option' >&2; exit 2 ;;
          esac
          shift 2
        done
        case "$ACTION" in
          help|--help)
            echo 'Usage: sh deploy/deploy.sh deploy|update|start|stop|status|logs|contract-test --instance <name> --state-root <absolute-new-or-owned-directory> [--port <1024-65535>]'
            echo '       sh deploy/deploy.sh acceptance-test --instance <name> --state-root <dir> --confirm-disposable <the same name>'
            exit 0 ;;
          deploy|update|start|stop|status|logs|contract-test|acceptance-test) ;;
          *) echo 'unsupported action' >&2; exit 2 ;;
        esac
        printf '%s\\n' "$INSTANCE" | LC_ALL=C grep -Eq '^[a-z][a-z0-9_-]{0,39}$' || { echo 'invalid instance name' >&2; exit 2; }
        if [ "$ACTION" = acceptance-test ]; then
          # A business suite writes data: run it only where the caller names this instance as disposable.
          [ "$CONFIRM" = "$INSTANCE" ] || { echo 'acceptance-test writes business data; on a disposable instance repeat its name with --confirm-disposable' >&2; exit 2; }
          [ -f "$PACKAGE/acceptance/pom.xml" ] || { echo 'KCG_ACCEPTANCE_NOT_RUN: this package carries no acceptance suite'; exit 4; }
        elif [ -n "$CONFIRM" ]; then
          echo '--confirm-disposable only applies to acceptance-test' >&2; exit 2
        fi
        case "$STATE" in
          /*) ;;
          *) echo 'state-root must be absolute' >&2; exit 2 ;;
        esac
        case "$STATE/" in
          /|//|*/../*|*/./*|*//*|*','*|*':'*) echo 'state-root must be a normalized path without comma/colon' >&2; exit 2 ;;
        esac
        case "$STATE/" in "$PACKAGE/"*) echo 'state must be outside the package' >&2; exit 2 ;; esac
        PARENT=$(dirname -- "$STATE"); LEAF=$(basename -- "$STATE")
        CURSOR=$STATE
        while :; do
          [ ! -L "$CURSOR" ] || { echo 'state symlink/ancestor refused' >&2; exit 3; }
          [ "$CURSOR" != / ] || break
          CURSOR=$(dirname -- "$CURSOR")
        done
        case "$PACKAGE" in *','*) echo 'Docker bind paths cannot contain commas' >&2; exit 3 ;; esac
        if [ -z "$PORT" ]; then
          if [ -f "$STATE/port" ]; then PORT=$(cat "$STATE/port"); else PORT=8080; fi
        fi
        printf '%s\\n' "$PORT" | LC_ALL=C grep -Eq '^[0-9]{4,5}$' || exit 2
        [ "$PORT" -ge 1024 ] && [ "$PORT" -le 65535 ] || exit 2
        docker info >/dev/null 2>&1 || { echo 'Docker is unavailable' >&2; exit 3; }
        docker compose version >/dev/null 2>&1 || { echo 'Docker Compose is unavailable' >&2; exit 3; }
        PLATFORM=$(docker info --format '{{.OSType}}/{{.Architecture}}')
        case "$PLATFORM" in linux/x86_64|linux/amd64) ;; *) echo 'delivery v1 requires a Linux x86_64 Docker daemon' >&2; exit 3 ;; esac
        mkdir -p -- "$PARENT"
        LOCK="$PARENT/.kcg-$INSTANCE.lock"
        mkdir -- "$LOCK" 2>/dev/null || { echo 'instance is busy or has an unfinished deployment lock; inspect before removing the lock' >&2; exit 3; }
        COORDINATOR=
        cleanup() {
          if [ -n "$COORDINATOR" ]; then docker container rm "$COORDINATOR" >/dev/null; fi
          rmdir -- "$LOCK"
        }
        trap cleanup EXIT
        trap 'exit 130' INT
        trap 'exit 143' TERM
        if [ "$ACTION" != deploy ] && [ ! -d "$STATE" ]; then echo 'TARGET_MISSING: instance state is absent' >&2; exit 3; fi
        # Validate the complete source package before executing its build recipe; no host JDK is required.
        docker run --rm --name "$INSTANCE-kcg-bootstrap" --label "kcg.instance=$INSTANCE" --memory=384m --cpus=0.5 --user "$(id -u):$(id -g)" \\
          --mount "type=bind,src=$PACKAGE,dst=/package,readonly" \\
          --mount "type=bind,src=$PARENT,dst=/state-parent" \\
          @BUILDER@ java -Xmx128m -XX:MaxMetaspaceSize=128m /package/deploy/Bootstrap.java \\
          "/state-parent/$LEAF" "$INSTANCE" "$PORT" "$ACTION" || { echo 'KCG_BLOCKED: package or instance state verification failed' >&2; exit 3; }
        KCG_PACKAGE_ID=$(cat "$PACKAGE/package.id")
        KCG_ENVIRONMENT_ID=$(cat "$STATE/environment.id")
        BOUND_PACKAGE_ID=$(cat "$STATE/package.id")
        # The database container must not depend on any one package directory, so that an update never recreates it.
        if [ ! -f "$STATE/accounts.sh" ]; then cp "$PACKAGE/deploy/accounts.sh" "$STATE/accounts.sh" && chmod 444 "$STATE/accounts.sh"; fi
        export KCG_PACKAGE="$PACKAGE" KCG_STATE="$STATE" KCG_INSTANCE="$INSTANCE" KCG_HTTP_PORT="$PORT" KCG_PACKAGE_ID KCG_ENVIRONMENT_ID
        COORDINATOR=$(docker container create --name "$INSTANCE-kcg-lock" --label "kcg.instance=$INSTANCE" --label "kcg.environment=$KCG_ENVIRONMENT_ID" @BUILDER@ true) || { echo 'another deployment owns this Docker instance name' >&2; exit 3; }
        compose() { docker compose --project-name "$INSTANCE" -f "$PACKAGE/deploy/compose.yaml" "$@"; }
        volume_present() { docker volume inspect "$INSTANCE-db" >/dev/null 2>&1; }
        check_volume() {
          volume_present || { echo 'TARGET_MISSING: database volume is absent; refusing to create an empty replacement' >&2; exit 3; }
          OWNER=$(docker volume inspect --format '{{index .Labels "kcg.instance"}}' "$INSTANCE-db")
          ENVIRONMENT=$(docker volume inspect --format '{{index .Labels "kcg.environment"}}' "$INSTANCE-db")
          # The environment label is this state's random UUID; which package the instance runs lives in state/package.id.
          [ "$OWNER" = "$INSTANCE" ] && [ "$ENVIRONMENT" = "$KCG_ENVIRONMENT_ID" ] || { echo 'database volume belongs to another instance or environment' >&2; exit 3; }
        }
        build_images() {
          mkdir -p "$STATE/artifacts"
          docker run --rm --name "$INSTANCE-kcg-build" --label "kcg.instance=$INSTANCE" --memory="${KCG_BUILD_MEMORY:-1536m}" --cpus="${KCG_BUILD_CPUS:-1.5}" \\
            --env 'MAVEN_OPTS=-Xmx512m -XX:MaxMetaspaceSize=256m' \\
            --mount "type=bind,src=$PACKAGE,dst=/package,readonly" \\
            --mount "type=bind,src=$STATE/artifacts,dst=/artifacts" \\
            --mount "type=volume,src=$INSTANCE-maven,dst=/m2" \\
            @BUILDER@ sh /package/deploy/build.sh &&
          docker build --target migrate --tag "$INSTANCE-migrate:$KCG_PACKAGE_ID" --file "$PACKAGE/deploy/Dockerfile" "$STATE/artifacts" &&
          docker build --target app --tag "$INSTANCE-app:$KCG_PACKAGE_ID" --file "$PACKAGE/deploy/Dockerfile" "$STATE/artifacts"
        }
        wait_healthy() {
          WAITED=0
          while [ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$1" 2>/dev/null)" != healthy ]; do
            [ "$WAITED" -lt 180 ] || return 1
            sleep 5; WAITED=$((WAITED + 5))
          done
        }
        case "$ACTION" in
          deploy)
            # A build never needs a database. The only build process has explicit process-tree limits.
            if volume_present; then check_volume; elif [ -f "$STATE/initialized" ]; then echo 'TARGET_MISSING: initialized database volume was removed' >&2; exit 3; fi
            build_images
            if ! volume_present; then
              [ ! -f "$STATE/initialized" ] || { echo 'TARGET_MISSING: initialized database volume was removed' >&2; exit 3; }
              docker volume create --label "kcg.instance=$INSTANCE" --label "kcg.package=$KCG_PACKAGE_ID" --label "kcg.environment=$KCG_ENVIRONMENT_ID" "$INSTANCE-db" >/dev/null
            fi
            check_volume
            compose up -d --wait --wait-timeout 180 db
            # An initialized instance is only verified here; changing its schema is the update action's job.
            if [ -f "$STATE/initialized" ]; then compose run --rm --no-deps migrate verify; else compose run --rm --no-deps migrate initialize; fi
            touch "$STATE/initialized"
            compose up -d --no-deps --wait --wait-timeout 180 app
            echo "KCG_DEPLOYED http://127.0.0.1:$PORT"
            ;;
          update)
            check_volume
            [ -f "$STATE/initialized" ] || { echo 'TARGET_MISSING: instance has not completed initialization; use deploy' >&2; exit 3; }
            if [ "$BOUND_PACKAGE_ID" = "$KCG_PACKAGE_ID" ]; then
              docker image inspect "$INSTANCE-app:$KCG_PACKAGE_ID" "$INSTANCE-migrate:$KCG_PACKAGE_ID" >/dev/null || { echo 'deployment images are missing; rerun deploy' >&2; exit 3; }
              compose up -d --wait --wait-timeout 180 db
              compose run --rm --no-deps migrate verify
              compose up -d --no-deps --wait --wait-timeout 180 app || { echo 'KCG_APP_START_FAILED: the application did not become ready' >&2; exit 6; }
              echo "KCG_UPDATE_CURRENT $KCG_PACKAGE_ID http://127.0.0.1:$PORT"; exit 0
            fi
            # Build first: a failed build touches neither the database nor the running application.
            build_images || { echo 'KCG_UPDATE_BUILD_FAILED: database unchanged; application=UNTOUCHED' >&2; exit 4; }
            # A running database is never recreated or restarted by an update.
            [ -n "$(compose ps --status running --quiet db)" ] || compose up -d --wait --wait-timeout 180 db
            STATUS=0; compose run --rm --no-deps migrate check || STATUS=$?
            if [ "$STATUS" -ne 0 ]; then
              [ "$STATUS" -eq 5 ] || STATUS=3
              echo "KCG_UPDATE_BLOCKED: database check refused the update; database unchanged; application=RUNNING" >&2; exit "$STATUS"
            fi
            # Managed writes stop here: the instance's only writer is its application.
            OLD_APP=$(compose ps --all --quiet app)
            if [ -n "$OLD_APP" ]; then compose stop app; fi
            STATUS=0; compose run --rm --no-deps migrate update || STATUS=$?
            if [ "$STATUS" -eq 3 ]; then
              APPLICATION=WAS_STOPPED
              if [ -n "$OLD_APP" ]; then
                if docker start "$OLD_APP" >/dev/null && wait_healthy "$OLD_APP"; then APPLICATION=RESTARTED; else APPLICATION=RESTART_FAILED; fi
              fi
              echo "KCG_UPDATE_BLOCKED: database unchanged; application=$APPLICATION" >&2; exit 3
            elif [ "$STATUS" -ne 0 ]; then
              echo "KCG_DB_RECOVERY_REQUIRED: the migration did not complete (exit $STATUS); no clean, repair or rollback was performed; application=STOPPED; still bound to $BOUND_PACKAGE_ID" >&2; exit 5
            fi
            printf '%s\\n' "$KCG_PACKAGE_ID" > "$STATE/package.id.next"
            mv -f "$STATE/package.id.next" "$STATE/package.id"
            compose up -d --no-deps --wait --wait-timeout 180 app || { echo "KCG_APP_START_FAILED: the database is at this package's version and the instance is bound to $KCG_PACKAGE_ID; nothing was reverted; retry start or update" >&2; exit 6; }
            echo "KCG_UPDATED $BOUND_PACKAGE_ID->$KCG_PACKAGE_ID http://127.0.0.1:$PORT"
            ;;
          start)
            check_volume
            docker image inspect "$INSTANCE-app:$KCG_PACKAGE_ID" "$INSTANCE-migrate:$KCG_PACKAGE_ID" >/dev/null || { echo 'deployment images are missing; rerun deploy' >&2; exit 3; }
            [ -f "$STATE/initialized" ] || { echo 'instance has not completed initialization; rerun deploy' >&2; exit 3; }
            compose up -d --wait --wait-timeout 180 db
            compose run --rm --no-deps migrate verify
            compose up -d --no-deps --wait --wait-timeout 180 app
            echo "KCG_STARTED http://127.0.0.1:$PORT"
            ;;
          stop) check_volume; compose stop app db ;;
          status)
            check_volume
            echo "KCG_STATUS bound=$BOUND_PACKAGE_ID volume-created-by=$(docker volume inspect --format '{{index .Labels "kcg.package"}}' "$INSTANCE-db")"
            compose ps --all ;;
          logs) check_volume; compose logs --no-color --tail 100 app db ;;
          contract-test)
            # Read-only: every request is refused before business code runs. It reaches the app over the Compose network.
            check_volume
            [ -n "$(compose ps --status running --quiet app)" ] || { echo 'the application is not running; deploy or start it first' >&2; exit 3; }
            mkdir -p "$STATE/reports/contract"
            if docker run --rm --name "$INSTANCE-kcg-contract" --label "kcg.instance=$INSTANCE" --network "${INSTANCE}_default" \\
                --memory="${KCG_TEST_MEMORY:-1536m}" --cpus="${KCG_TEST_CPUS:-1.5}" \\
                --env 'MAVEN_OPTS=-Xmx512m -XX:MaxMetaspaceSize=256m' \\
                --mount "type=bind,src=$PACKAGE/app,dst=/package/app,readonly" \\
                --mount "type=bind,src=$PACKAGE/deploy/contract-test.sh,dst=/contract-test.sh,readonly" \\
                --mount "type=bind,src=$STATE/reports/contract,dst=/reports" \\
                --mount "type=volume,src=$INSTANCE-maven,dst=/m2" \\
                @BUILDER@ sh /contract-test.sh; then
              echo "KCG_CONTRACT_PASSED $STATE/reports/contract"
            else
              echo "KCG_CONTRACT_FAILED: see $STATE/reports/contract" >&2; exit 5
            fi
            ;;
          acceptance-test)
            # The suite gets the application address and a verification connection to this instance's own database,
            # nothing of the application sources.
            check_volume
            [ -n "$(compose ps --status running --quiet app)" ] || { echo 'the application is not running; deploy or start it first' >&2; exit 3; }
            mkdir -p "$STATE/reports/acceptance"
            if docker run --rm --name "$INSTANCE-kcg-acceptance" --label "kcg.instance=$INSTANCE" --network "${INSTANCE}_default" \\
                --memory="${KCG_TEST_MEMORY:-1536m}" --cpus="${KCG_TEST_CPUS:-1.5}" \\
                --env 'MAVEN_OPTS=-Xmx512m -XX:MaxMetaspaceSize=256m' \\
                --mount "type=bind,src=$PACKAGE/acceptance,dst=/package/acceptance,readonly" \\
                --mount "type=bind,src=$PACKAGE/deploy/acceptance-test.sh,dst=/acceptance-test.sh,readonly" \\
                --mount "type=bind,src=$STATE/root-password,dst=/run/secrets/root-password,readonly" \\
                --mount "type=bind,src=$STATE/reports/acceptance,dst=/reports" \\
                --mount "type=volume,src=$INSTANCE-maven,dst=/m2" \\
                @BUILDER@ sh /acceptance-test.sh; then
              echo "KCG_ACCEPTANCE_PASSED $STATE/reports/acceptance"
            else
              echo "KCG_ACCEPTANCE_FAILED: see $STATE/reports/acceptance" >&2; exit 5
            fi
            ;;
        esac
        """;
    static final String COMPOSE = """
        services:
          db:
            image: @MYSQL@
            environment:
              MYSQL_DATABASE: kcg
              MYSQL_ROOT_PASSWORD_FILE: /run/secrets/root-password
            command: ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_bin", "--default-time-zone=+00:00", "--innodb-buffer-pool-size=128M", "--max-connections=40"]
            mem_limit: ${KCG_DB_MEMORY:-1024m}
            cpus: ${KCG_DB_CPUS:-1.0}
            volumes:
              - db-data:/var/lib/mysql
              - type: bind
                source: ${KCG_STATE}/accounts.sh
                target: /docker-entrypoint-initdb.d/01-accounts.sh
                read_only: true
            secrets: [root-password, migrate-password, app-password]
            healthcheck:
              test: ["CMD-SHELL", "MYSQL_PWD=$$(cat /run/secrets/root-password) mysql --protocol=TCP -h127.0.0.1 -uroot -Nse 'SELECT 1' >/dev/null"]
              interval: 5s
              timeout: 5s
              retries: 36
              start_period: 20s
          migrate:
            image: ${KCG_INSTANCE}-migrate:${KCG_PACKAGE_ID}
            profiles: [tools]
            mem_limit: ${KCG_MIGRATE_MEMORY:-384m}
            cpus: 0.5
            environment:
              KCG_JDBC_URL: 'jdbc:mysql://db:3306/kcg?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useAffectedRows=false'
              JAVA_TOOL_OPTIONS: '-Xmx128m -XX:MaxMetaspaceSize=128m'
            secrets: [migrate-password]
            volumes:
              - type: bind
                source: ${KCG_PACKAGE}/deploy
                target: /deploy
                read_only: true
          app:
            image: ${KCG_INSTANCE}-app:${KCG_PACKAGE_ID}
            mem_limit: ${KCG_APP_MEMORY:-768m}
            cpus: ${KCG_APP_CPUS:-1.0}
            environment:
              SPRING_PROFILES_ACTIVE: kcg-deploy
              SPRING_DATASOURCE_URL: 'jdbc:mysql://db:3306/kcg?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useAffectedRows=false'
              SPRING_DATASOURCE_USERNAME: kcg_app
              JAVA_TOOL_OPTIONS: '-Xmx256m -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=64m -Duser.timezone=UTC'
            secrets:
              - source: app-password
                target: spring.datasource.password
            ports: ["127.0.0.1:${KCG_HTTP_PORT}:8080"]
            healthcheck:
              test: ["CMD", "java", "-Xmx32m", "-XX:MaxMetaspaceSize=64m", "-cp", "/opt/probe", "kcg.delivery.Probe"]
              interval: 10s
              timeout: 10s
              retries: 18
              start_period: 15s
        volumes:
          db-data:
            external: true
            name: ${KCG_INSTANCE}-db
        secrets:
          root-password:
            file: ${KCG_STATE}/root-password
          migrate-password:
            file: ${KCG_STATE}/migrate-password
          app-password:
            file: ${KCG_STATE}/app-password
        """;
    static final String DOCKERFILE = """
        FROM @RUNTIME@ AS migrate
        COPY migrate.jar /opt/migrate.jar
        ENTRYPOINT ["java", "-jar", "/opt/migrate.jar"]

        FROM @RUNTIME@ AS app
        COPY app.jar /opt/app.jar
        COPY Probe.class /opt/probe/kcg/delivery/Probe.class
        USER 10001:10001
        EXPOSE 8080
        ENTRYPOINT ["java", "-jar", "/opt/app.jar"]
        """;
    static final String BUILD = """
        #!/bin/sh
        set -eu
        mkdir -p /tmp/kcg-build
        cp -R /package/app /tmp/kcg-build/app
        cp -R /package/deploy/tools /tmp/kcg-build/tools
        cd /tmp/kcg-build/app
        mvn -B -ntp -Dmaven.repo.local=/m2 -Dmaven.test.skip=true package
        APP=$(find target -maxdepth 1 -type f -name '*.jar')
        [ -n "$APP" ] || { echo 'build produced no application jar' >&2; exit 3; }
        cp "$APP" /artifacts/app.jar
        cd /tmp/kcg-build/tools
        mvn -B -ntp -Dmaven.repo.local=/m2 -Dmaven.test.skip=true package
        cp target/deploy-tools.jar /artifacts/migrate.jar
        cp target/classes/kcg/delivery/Probe.class /artifacts/Probe.class
        """;
    static final String CONTRACT = """
        #!/bin/sh
        # Runs the generated declared-interface contract test against the instance's application; it writes no data.
        set -eu
        find /reports -mindepth 1 -delete
        rm -rf /tmp/kcg-contract
        mkdir -p /tmp/kcg-contract
        cp -R /package/app /tmp/kcg-contract/app
        cd /tmp/kcg-contract/app
        STATUS=0
        mvn -B -ntp -Dmaven.repo.local=/m2 -Dtest=HttpContractIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Xmx256m \\
          -Dkcg.baseUrl=http://app:8080 -Dkcg.reportFile=/reports/contract-report.tsv test || STATUS=$?
        if [ -d target/surefire-reports ]; then cp -R target/surefire-reports /reports/surefire-reports; fi
        exit "$STATUS"
        """;
    static final String ACCEPTANCE = """
        #!/bin/sh
        # Runs the package's separate business acceptance suite against a disposable instance.
        set -eu
        find /reports -mindepth 1 -delete
        rm -rf /tmp/kcg-acceptance
        mkdir -p /tmp/kcg-acceptance
        cp -R /package/acceptance /tmp/kcg-acceptance/suite
        cd /tmp/kcg-acceptance/suite
        STATUS=0
        mvn -B -ntp -Dmaven.repo.local=/m2 -DargLine=-Xmx256m -Dkcg.baseUrl=http://app:8080 \\
          '-Dkcg.jdbcUrl=jdbc:mysql://db:3306/kcg?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' \\
          -Dkcg.jdbcUser=root -Dkcg.jdbcPasswordFile=/run/secrets/root-password -Dkcg.reportDir=/reports test || STATUS=$?
        if [ -d target/surefire-reports ]; then cp -R target/surefire-reports /reports/surefire-reports; fi
        exit "$STATUS"
        """;
    static final String ACCOUNTS = """
        #!/bin/sh
        # Only runs during MySQL's first initialization of the new owned data volume.
        MIGRATE_PASSWORD=$(cat /run/secrets/migrate-password)
        APP_PASSWORD=$(cat /run/secrets/app-password)
        MYSQL_PWD=$(cat /run/secrets/root-password) mysql --protocol=socket -uroot <<SQL
        CREATE USER 'kcg_migrate'@'%' IDENTIFIED BY '$MIGRATE_PASSWORD';
        GRANT ALL PRIVILEGES ON kcg.* TO 'kcg_migrate'@'%' WITH GRANT OPTION;
        CREATE USER 'kcg_app'@'%' IDENTIFIED BY '$APP_PASSWORD';
        SQL
        unset MIGRATE_PASSWORD APP_PASSWORD
        """;
    static final String BOOTSTRAP = """
        import java.nio.file.*;
        import java.nio.file.attribute.PosixFilePermissions;
        import java.security.*;
        import java.util.*;

        /** Checks a source delivery and creates one persistent local configuration, never a database. */
        class Bootstrap {
            public static void main(String[] args) throws Exception {
                Path root = Path.of(System.getProperty("kcg.package.root", "/package")).toAbsolutePath().normalize();
                byte[] manifest = Files.readAllBytes(root.resolve("package.sha256"));
                String id = Files.readString(root.resolve("package.id")).strip();
                if (!id.matches("[0-9a-f]{64}") || !hash(manifest).equals(id)) throw new IllegalStateException("package manifest mismatch");
                Set<Path> expected = new HashSet<>(Set.of(root.resolve("package.sha256"), root.resolve("package.id")));
                for (String line : new String(manifest, java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
                    if (!line.matches("[0-9a-f]{64}  .+")) throw new IllegalStateException("invalid package manifest");
                    Path file = root.resolve(line.substring(66)).normalize();
                    if (!file.startsWith(root) || !expected.add(file)) throw new IllegalStateException("invalid package path");
                    for (Path p = file; p != null && p.startsWith(root); p = p.getParent())
                        if (Files.isSymbolicLink(p)) throw new IllegalStateException("package symlink refused");
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !hash(Files.readAllBytes(file)).equals(line.substring(0,64)))
                        throw new IllegalStateException("package file mismatch: " + root.relativize(file));
                }
                try (var walk = Files.walk(root)) {
                    if (walk.anyMatch(p -> Files.isSymbolicLink(p) || (!Files.isDirectory(p) && !expected.contains(p))))
                        throw new IllegalStateException("unlisted package file");
                }
                Path state = Path.of(args[0]); String instance = args[1], port = args[2];
                // update may run a different package on an existing instance; every other action needs the bound one.
                String action = args.length > 3 ? args[3] : "deploy";
                if (!state.isAbsolute() || !state.equals(state.normalize()) || state.startsWith(root)
                        || !instance.matches("[a-z][a-z0-9_-]{0,39}") || !port.matches("[0-9]{1,5}")
                        || Integer.parseInt(port) < 1024 || Integer.parseInt(port) > 65535) throw new IllegalArgumentException("invalid instance configuration");
                for (Path p = state; p != null; p = p.getParent())
                    if (Files.isSymbolicLink(p)) throw new IllegalStateException("state symlink refused");
                if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(state) || !Files.readString(state.resolve("instance")).equals(instance + "\\n")
                            || !Files.readString(state.resolve("port")).equals(port + "\\n"))
                        throw new IllegalStateException("existing state belongs to another instance or port");
                    String bound = Files.readString(state.resolve("package.id"));
                    if (!bound.matches("[0-9a-f]{64}\\n")) throw new IllegalStateException("invalid state metadata");
                    if (!action.equals("update") && !bound.equals(id + "\\n"))
                        throw new IllegalStateException("existing state is bound to another package; use update");
                    for (String name : List.of("instance", "package.id", "port", "environment.id"))
                        if (!Files.isRegularFile(state.resolve(name), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("invalid state metadata");
                    UUID.fromString(Files.readString(state.resolve("environment.id")).strip());
                    for (String secret : List.of("root-password", "migrate-password", "app-password")) {
                        Path file = state.resolve(secret);
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.readString(file).matches("[0-9a-f]{64}"))
                            throw new IllegalStateException("missing or invalid persistent secret; refusing to regenerate");
                    }
                    return;
                }
                if (!action.equals("deploy")) throw new IllegalStateException("instance state is absent; use deploy");
                Path stage = Files.createTempDirectory(state.getParent(), ".kcg-init-");
                try {
                    Files.setPosixFilePermissions(stage, PosixFilePermissions.fromString("rwx------"));
                    Files.writeString(stage.resolve("instance"), instance + "\\n");
                    Files.writeString(stage.resolve("package.id"), id + "\\n");
                    Files.writeString(stage.resolve("port"), port + "\\n");
                    Files.writeString(stage.resolve("environment.id"), UUID.randomUUID() + "\\n");
                    var random = new SecureRandom();
                    for (String secret : List.of("root-password", "migrate-password", "app-password")) {
                        byte[] bytes = new byte[32]; random.nextBytes(bytes);
                        Path file = stage.resolve(secret); Files.writeString(file, HexFormat.of().formatHex(bytes));
                        // The state directory is private; a separately mounted secret must also be readable by the app UID.
                        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
                    }
                    Files.move(stage, state, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    if (Files.exists(stage)) { try (var files = Files.list(stage)) { for (Path f : files.toList()) Files.delete(f); } Files.delete(stage); }
                }
            }
            private static String hash(byte[] bytes) throws Exception {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            }
        }
        """;
    static final String TOOLS_POM = """
        <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <groupId>io.kcg.delivery</groupId><artifactId>deploy-tools</artifactId><version>1.0.0</version>
          <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
          <dependencies>
            <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId><version>11.8.2</version></dependency>
            <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId><version>11.8.2</version></dependency>
            <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>9.3.0</version></dependency>
          </dependencies>
          <build>
            <finalName>deploy-tools</finalName>
            <plugins>
              <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
              <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.1</version>
                <executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration>
                  <createDependencyReducedPom>false</createDependencyReducedPom>
                  <filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.RSA</exclude><exclude>META-INF/*.DSA</exclude></excludes></filter></filters>
                  <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"><mainClass>kcg.delivery.Migrate</mainClass></transformer>
                  </transformers>
                </configuration></execution></executions>
              </plugin>
            </plugins>
          </build>
        </project>
        """;
    static final String MIGRATE = """
        package kcg.delivery;

        import java.nio.charset.StandardCharsets;
        import java.nio.file.*;
        import java.security.MessageDigest;
        import java.sql.*;
        import java.util.*;
        import org.flywaydb.core.Flyway;

        /**
         * Owns schema privileges only for the deployment step; the business app has DML privileges.
         *
         * <p>The package carries a migration chain V1..Vn and the schema contract after each version (contracts/V<i>.tsv, and
         * schema.tsv for Vn). The live database is classified against that chain before anything runs, so a rerun is decided
         * from the database itself. Modes: initialize (new or partly initialized), verify (must already be at Vn), check
         * (read-only report for an update), update (apply the missing versions of an initialized database).
         */
        public final class Migrate {

            /** Refused before anything changed. */
            static final class Blocked extends IllegalStateException {
                Blocked(String code, String detail) { super(code + ": " + detail); }
            }

            /** The database may have changed and needs a person: no clean, repair or rollback is attempted. */
            static final class Recovery extends IllegalStateException {
                Recovery(String code, String detail) { super(code + ": " + detail); }
            }

            private static final Set<String> MODES = Set.of("initialize", "verify", "check", "update");

            private static boolean changing;

            public static void main(String[] args) {
                String mode = args.length == 1 ? args[0] : "";
                try {
                    if (!MODES.contains(mode)) throw new Blocked("USAGE", "expected one of " + new TreeSet<>(MODES));
                    String url = System.getenv("KCG_JDBC_URL");
                    String password = Files.readString(Path.of("/run/secrets/migrate-password"));
                    Path deploy = Path.of("/deploy");
                    int n = chainLength(deploy.resolve("migrations"));
                    List<List<String[]>> contracts = new ArrayList<>();
                    contracts.add(List.of());
                    for (int version = 1; version < n; version++) contracts.add(contract(deploy.resolve("contracts/V" + version + ".tsv")));
                    contracts.add(contract(deploy.resolve("schema.tsv")));
                    List<String[]> renames = renames(deploy.resolve("renames.tsv"), n);
                    try (Connection db = DriverManager.getConnection(url, "kcg_migrate", password)) {
                        reserved(db, contracts.get(n));
                        int k = classify(db, url, password, contracts, n);
                        if (k == 0 && !mode.equals("initialize")) throw new Blocked("NOT_INITIALIZED", "the database has no managed schema; use deploy");
                        if (mode.equals("verify") && k < n) throw new Blocked("PENDING_UPDATE", "the database is at V" + k + ", this package at V" + n + "; use update");
                        if (mode.equals("check")) {
                            System.out.println("KCG_DB_STATE V" + k + " package=V" + n);
                            return;
                        }
                        if (k < n) apply(db, url, password, contracts, renames, k, n, k > 0);
                        grant(db, contracts.get(n));
                        System.out.println("KCG_DATABASE_READY V" + n + " applied=" + (k < n ? "V" + (k + 1) + "..V" + n : "none"));
                    }
                } catch (Blocked blocked) {
                    System.err.println("KCG_DB_BLOCKED: " + blocked.getMessage() + "; nothing was changed");
                    System.exit(3);
                } catch (Recovery recovery) {
                    System.err.println("KCG_DB_RECOVERY_REQUIRED: " + recovery.getMessage() + "; no clean, repair or rollback was performed");
                    System.exit(5);
                } catch (Exception e) {
                    // Driver exceptions can echo connection information; only their category leaves this process.
                    String detail = e instanceof IllegalStateException ? ": " + e.getMessage() : "";
                    if (changing) {
                        System.err.println("KCG_DB_RECOVERY_REQUIRED: " + e.getClass().getSimpleName() + detail + "; no clean, repair or rollback was performed");
                        System.exit(5);
                    }
                    System.err.println("KCG_DB_BLOCKED: " + e.getClass().getSimpleName() + detail + "; nothing was changed");
                    System.exit(3);
                }
            }

            /** The number of versions, requiring exactly V1..Vn with one file each. */
            private static int chainLength(Path migrations) throws Exception {
                TreeMap<Integer, String> versions = new TreeMap<>();
                try (var files = Files.list(migrations)) {
                    for (Path file : files.toList()) {
                        String name = file.getFileName().toString();
                        if (!name.matches("V[1-9][0-9]{0,5}__[a-z0-9_]+\\\\.sql")) throw new IllegalStateException("unexpected migration file: " + name);
                        if (versions.put(Integer.parseInt(name.substring(1, name.indexOf("__"))), name) != null)
                            throw new IllegalStateException("duplicate migration version: " + name);
                    }
                }
                if (versions.isEmpty() || versions.firstKey() != 1 || versions.lastKey() != versions.size())
                    throw new IllegalStateException("the migration chain must be V1..Vn without gaps");
                return versions.size();
            }

            private static List<String[]> contract(Path file) throws Exception {
                List<String[]> rows = Files.readAllLines(file).stream().filter(s -> !s.isBlank()).map(s -> s.split("\\t", -1)).toList();
                for (String[] row : rows) {
                    if (row.length != 11 || !row[0].matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !row[1].matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                        throw new IllegalStateException("invalid schema contract: " + file.getFileName());
                }
                return rows;
            }

            /** deploy/renames.tsv: version, table, old name and new name of each column a version renamed, in version order. */
            private static List<String[]> renames(Path file, int n) throws Exception {
                if (!Files.exists(file)) return List.of();
                List<String[]> rows = Files.readAllLines(file).stream().filter(s -> !s.isBlank()).map(s -> s.split("\\t", -1)).toList();
                int last = 0;
                for (String[] row : rows) {
                    if (row.length != 4 || !row[0].matches("[1-9][0-9]{0,5}") || Integer.parseInt(row[0]) > n || Integer.parseInt(row[0]) < last
                            || !row[1].matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !row[2].matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                            || !row[3].matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                        throw new IllegalStateException("invalid renames.tsv");
                    last = Integer.parseInt(row[0]);
                }
                return rows;
            }

            private static void reserved(Connection db, List<String[]> expected) throws Exception {
                Set<String> reserved = new HashSet<>();
                try (Statement words = db.createStatement(); ResultSet rs = words.executeQuery("SELECT WORD FROM information_schema.KEYWORDS WHERE RESERVED=1")) {
                    while (rs.next()) reserved.add(rs.getString(1).toUpperCase(Locale.ROOT));
                }
                for (String[] row : expected) if (reserved.contains(row[0].toUpperCase(Locale.ROOT)) || reserved.contains(row[1].toUpperCase(Locale.ROOT)))
                    throw new Blocked("RESERVED_IDENTIFIER", "not supported by the unquoted Java mapping: " + row[0] + "." + row[1]);
            }

            /** The version the live database is at, or a refusal. Rules apply in order; the first that matches decides. */
            private static int classify(Connection db, String url, String password, List<List<String[]>> contracts, int n) throws Exception {
                boolean history;
                try (ResultSet tables = db.getMetaData().getTables("kcg", null, "flyway_schema_history", new String[]{"TABLE"})) {
                    history = tables.next();
                }
                if (!history) {
                    try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='kcg'")) {
                        rs.next();
                        if (rs.getLong(1) != 0) throw new Blocked("UNMANAGED_SCHEMA", "tables exist without a migration history");
                    }
                    return 0;
                }
                TreeSet<Integer> applied = new TreeSet<>();
                try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL")) {
                    while (rs.next()) {
                        if (!rs.getBoolean(2)) throw new Recovery("FAILED_MIGRATION", "version " + rs.getString(1) + " is recorded as failed" + matching(db, contracts));
                        applied.add(Integer.parseInt(rs.getString(1)));
                    }
                }
                int k = applied.isEmpty() ? 0 : applied.last();
                if (k > n) throw new Blocked("AHEAD", "the database is at V" + k + ", newer than this package's V" + n + "; an older package cannot run it");
                if (applied.size() != k) throw new Blocked("DIVERGED", "the applied versions are not V1..V" + k);
                if (k > 0 && !flyway(url, password, null).validateWithResult().validationSuccessful)
                    throw new Blocked("DIVERGED", "the applied migrations differ from this package's V1..V" + k);
                if (matches(db, contracts.get(k))) return k;
                if (k < n && matches(db, contracts.get(k + 1)))
                    throw new Recovery("INTERRUPTED", "V" + (k + 1) + " changed the schema but was not recorded");
                throw new Blocked("DRIFT", "the live schema is not the V" + k + " contract");
            }

            private static String matching(Connection db, List<List<String[]>> contracts) {
                for (int version = contracts.size() - 1; version >= 0; version--) {
                    if (matches(db, contracts.get(version))) return "; the live schema equals the V" + version + " contract";
                }
                return "; the live schema matches no contract of this package";
            }

            private static boolean matches(Connection db, List<String[]> expected) {
                try {
                    verify(db, expected);
                    return true;
                } catch (Exception mismatch) {
                    return false;
                }
            }

            private static Flyway flyway(String url, String password, Integer target) {
                var configuration = Flyway.configure().dataSource(url, "kcg_migrate", password)
                    .locations("filesystem:/deploy/migrations").baselineOnMigrate(false).cleanDisabled(true)
                    .initSql("SET SESSION lock_wait_timeout=60").ignoreMigrationPatterns("*:pending");
                if (target != null) configuration.target(String.valueOf(target));
                return configuration.load();
            }

            /**
             * Applies V(k+1)..Vn one version at a time, verifying each contract. For an existing database it proves that the
             * rows and values of every table and column of V(k) are unchanged (a column those versions renamed is read under
             * its new name), that added columns are null in every old row and that added tables are empty.
             */
            private static void apply(Connection db, String url, String password, List<List<String[]>> contracts, List<String[]> renames,
                    int k, int n, boolean preserve) throws Exception {
                // Under which name each column of V(k) holds its values once V(k+1)..Vn have run.
                Map<String, String> moved = new HashMap<>();
                for (String[] row : contracts.get(k)) moved.put(row[0] + "." + row[1], row[1]);
                for (String[] rename : renames) {
                    int version = Integer.parseInt(rename[0]);
                    if (!preserve || version <= k) continue;
                    String key = null;
                    for (var entry : moved.entrySet())
                        if (entry.getKey().startsWith(rename[1] + ".") && entry.getValue().equals(rename[2])) key = entry.getKey();
                    if (key == null) throw new IllegalStateException("renames.tsv renames a column V" + k + " does not have: " + rename[1] + "." + rename[2]);
                    moved.put(key, rename[3]);
                }
                Map<String, String> before = preserve ? digest(db, contracts.get(k), Map.of()) : Map.of();
                for (int version = k + 1; version <= n; version++) {
                    changing = true;
                    try {
                        flyway(url, password, version).migrate();
                    } catch (Exception failed) {
                        throw new Recovery("MIGRATION_FAILED", "V" + version + " did not complete (" + failed.getClass().getSimpleName() + ")" + matching(db, contracts));
                    }
                    try {
                        verify(db, contracts.get(version));
                    } catch (IllegalStateException mismatch) {
                        throw new Recovery("CONTRACT_MISMATCH", "after V" + version + ": " + mismatch.getMessage());
                    }
                }
                if (!preserve) return;
                Map<String, String> after = digest(db, contracts.get(k), moved);
                if (!before.equals(after)) throw new Recovery("DATA_CHANGED", "rows or values of the previous schema changed during migration");
                Set<String> oldColumns = new HashSet<>(), oldTables = new HashSet<>();
                for (String[] row : contracts.get(k)) oldTables.add(row[0]);
                moved.forEach((column, name) -> oldColumns.add(column.substring(0, column.indexOf('.') + 1) + name));
                Set<String> newTables = new TreeSet<>();
                for (String[] row : contracts.get(n)) {
                    if (!oldTables.contains(row[0])) newTables.add(row[0]);
                    else if (!oldColumns.contains(row[0] + "." + row[1]) && count(db, "SELECT COUNT(*) FROM `" + row[0] + "` WHERE `" + row[1] + "` IS NOT NULL") != 0)
                        throw new Recovery("DATA_CHANGED", "added column " + row[0] + "." + row[1] + " is not null in an existing row");
                }
                for (String table : newTables) if (count(db, "SELECT COUNT(*) FROM `" + table + "`") != 0)
                    throw new Recovery("DATA_CHANGED", "added table " + table + " is not empty");
                long rows = 0;
                for (String value : before.values()) rows += Long.parseLong(value.substring(0, value.indexOf(':')));
                System.out.println("KCG_DATA_VERIFIED tables=" + before.size() + " rows=" + rows);
            }

            /** Per table "<rows>:<sha256>" over its contract columns in primary key order; NULL is encoded apart from any value. */
            private static Map<String, String> digest(Connection db, List<String[]> contract, Map<String, String> moved) throws Exception {
                Map<String, List<String>> columns = new TreeMap<>();
                Map<String, String> keys = new HashMap<>();
                for (String[] row : contract) {
                    columns.computeIfAbsent(row[0], t -> new ArrayList<>()).add(row[1]);
                    if (row[5].equals("true")) keys.put(row[0], row[1]);
                }
                Map<String, String> digests = new TreeMap<>();
                for (var table : columns.entrySet()) {
                    List<String> names = new ArrayList<>(table.getValue());
                    Collections.sort(names);
                    String key = Objects.requireNonNull(keys.get(table.getKey()), "primary key of " + table.getKey());
                    StringBuilder select = new StringBuilder("SELECT ");
                    for (int i = 0; i < names.size(); i++)
                        select.append(i == 0 ? "" : ", ").append('`').append(moved.getOrDefault(table.getKey() + "." + names.get(i), names.get(i))).append('`');
                    select.append(" FROM `").append(table.getKey()).append("` ORDER BY `").append(moved.getOrDefault(table.getKey() + "." + key, key)).append('`');
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    long rows = 0;
                    try (Statement s = db.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                        s.setFetchSize(Integer.MIN_VALUE);
                        try (ResultSet rs = s.executeQuery(select.toString())) {
                            while (rs.next()) {
                                rows++;
                                for (int i = 1; i <= names.size(); i++) {
                                    String value = rs.getString(i);
                                    sha.update(value == null ? "N;".getBytes(StandardCharsets.UTF_8)
                                        : ("V" + value.length() + ":" + value + ";").getBytes(StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                    digests.put(table.getKey(), rows + ":" + HexFormat.of().formatHex(sha.digest()));
                }
                return digests;
            }

            private static long count(Connection db, String sql) throws Exception {
                try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                    rs.next();
                    return rs.getLong(1);
                }
            }

            private static void grant(Connection db, List<String[]> expected) throws Exception {
                try (Statement grants = db.createStatement()) {
                    grants.execute("GRANT SELECT ON `kcg`.`flyway_schema_history` TO 'kcg_app'@'%'");
                    Set<String> businessTables = new TreeSet<>();
                    for (String[] row : expected) businessTables.add(row[0]);
                    for (String table : businessTables) grants.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `kcg`.`" + table + "` TO 'kcg_app'@'%'");
                }
            }

            private static void verify(Connection db, List<String[]> expected) throws Exception {
                Map<String, String[]> columns = new TreeMap<>();
                Set<String> tables = new TreeSet<>(), uniques = new TreeSet<>(), references = new TreeSet<>();
                for (String[] row : expected) {
                    if (row.length != 11) throw new IllegalStateException("invalid schema contract");
                    tables.add(row[0]); columns.put(row[0] + "." + row[1], row);
                    if (row[5].equals("true") || row[7].equals("true")) uniques.add(row[0] + "." + row[1]);
                    if (!row[8].isEmpty()) references.add(row[0] + "." + row[1] + ">" + row[8] + "." + row[9]);
                }
                Set<String> actualTables = new TreeSet<>();
                try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT TABLE_NAME,ENGINE,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME<>'flyway_schema_history'")) {
                    while (rs.next()) {
                        if (!"InnoDB".equals(rs.getString(2)) || !"utf8mb4_0900_bin".equals(rs.getString(3))) throw new IllegalStateException("table engine/collation mismatch");
                        actualTables.add(rs.getString(1));
                    }
                }
                if (!actualTables.equals(tables)) throw new IllegalStateException("schema table mismatch");
                try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='kcg'")) {
                    rs.next(); if (rs.getLong(1) != 0) throw new IllegalStateException("unmanaged trigger refused");
                }
                Set<String> seen = new TreeSet<>();
                try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,IS_NULLABLE,EXTRA,COLUMN_KEY,DATETIME_PRECISION,COLUMN_TYPE,COLLATION_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME<>'flyway_schema_history'")) {
                    while (rs.next()) {
                        String key = rs.getString(1) + "." + rs.getString(2); String[] row = columns.get(key);
                        if (row == null || !seen.add(key) || !row[2].equalsIgnoreCase(rs.getString(3))
                                || !row[4].equals(Boolean.toString(rs.getString(5).equals("YES")))
                                || !row[5].equals(Boolean.toString(rs.getString(7).equals("PRI")))
                                || !row[6].equals(Boolean.toString(rs.getString(6).contains("auto_increment")))
                                || rs.getString(9).contains("unsigned")
                                || !row[10].equals(rs.getString(11) == null ? "" : "=" + java.net.URLEncoder.encode(rs.getString(11), java.nio.charset.StandardCharsets.UTF_8))
                                || !rs.getString(6).equals(row[6].equals("true") ? "auto_increment" : "")) throw new IllegalStateException("column contract mismatch: " + key);
                        if ((row[2].equals("char") || row[2].equals("varchar")) && Long.parseLong(row[3]) != rs.getLong(4))
                            throw new IllegalStateException("column length mismatch: " + key);
                        if (row[2].equals("datetime") && rs.getInt(8) != 6) throw new IllegalStateException("time precision mismatch");
                        if ((row[2].equals("char") || row[2].equals("varchar") || row[2].equals("longtext"))
                                && !"utf8mb4_0900_bin".equals(rs.getString(10))) throw new IllegalStateException("text collation mismatch");
                    }
                }
                if (!seen.equals(columns.keySet())) throw new IllegalStateException("missing schema column");
                Map<String, List<String>> indexes = new TreeMap<>();
                try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME,SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='kcg' AND TABLE_NAME<>'flyway_schema_history' AND NON_UNIQUE=0 ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX")) {
                    while (rs.next()) {
                        if (rs.getObject(4) != null) throw new IllegalStateException("prefix uniqueness is not supported");
                        indexes.computeIfAbsent(rs.getString(1) + "/" + rs.getString(2), x -> new ArrayList<>()).add(rs.getString(1) + "." + rs.getString(3));
                    }
                }
                Set<String> actualUniques = new TreeSet<>();
                for (var index : indexes.values()) { if (index.size() != 1) throw new IllegalStateException("unexpected composite unique index"); actualUniques.add(index.getFirst()); }
                if (!actualUniques.equals(uniques)) throw new IllegalStateException("unique key mismatch");
                Set<String> actualRefs = new TreeSet<>();
                try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='kcg' AND REFERENCED_TABLE_NAME IS NOT NULL")) {
                    while (rs.next()) actualRefs.add(rs.getString(1) + "." + rs.getString(2) + ">" + rs.getString(3) + "." + rs.getString(4));
                }
                if (!actualRefs.equals(references)) throw new IllegalStateException("foreign key mismatch");
                try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT DELETE_RULE,UPDATE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='kcg'")) {
                    while (rs.next()) if (!Set.of("RESTRICT", "NO ACTION").contains(rs.getString(1)) || !Set.of("RESTRICT", "NO ACTION").contains(rs.getString(2)))
                        throw new IllegalStateException("unexpected cascading foreign key");
                }
            }
        }
        """;
    static final String PROBE = """
        package kcg.delivery;

        import java.net.URI;
        import java.net.http.*;
        import java.time.Duration;

        /** A real HTTP readiness probe that does not need curl in the runtime image. */
        public final class Probe {
            public static void main(String[] args) throws Exception {
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200) System.exit(1);
            }
        }
        """;

    static final String OPENAPI = """
        package @PACKAGE@;

        import io.swagger.v3.core.converter.AnnotatedType;
        import io.swagger.v3.core.converter.ModelConverters;
        import io.swagger.v3.core.converter.ResolvedSchema;
        import io.swagger.v3.oas.models.Components;
        import io.swagger.v3.oas.models.OpenAPI;
        import io.swagger.v3.oas.models.Operation;
        import io.swagger.v3.oas.models.PathItem;
        import io.swagger.v3.oas.models.media.Content;
        import io.swagger.v3.oas.models.media.MediaType;
        import io.swagger.v3.oas.models.media.Schema;
        import io.swagger.v3.oas.models.info.Info;
        import io.swagger.v3.oas.models.responses.ApiResponse;
        import io.swagger.v3.oas.models.responses.ApiResponses;
        import io.swagger.v3.oas.models.security.SecurityRequirement;
        import io.swagger.v3.oas.models.security.SecurityScheme;
        import java.util.HashSet;
        import java.util.LinkedHashMap;
        import java.util.List;
        import java.util.Map;
        import java.util.Set;
        import org.springdoc.core.customizers.OpenApiCustomizer;
        import org.springdoc.core.customizers.OperationCustomizer;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        import org.springframework.core.MethodParameter;
        import org.springframework.web.bind.annotation.RequestAttribute;

        /**
         * Delivery interface documentation. Operations, inputs and outputs come from the running controllers. This class adds
         * what the controllers cannot show: how an authenticated operation receives its caller (an access token in an
         * Authorization Bearer header, which the identity interceptor turns into a request attribute), and which error
         * envelope responses each operation declares. The latter were taken from the same target model the controllers were
         * generated from, when the package was exported.
         */
        @Configuration(proxyBeanMethods = false)
        public class KcgOpenApiConfiguration {

            /** Operation ("METHOD /route") to its error envelope responses: status and the codes answered with it. */
            private static final List<String[]> DECLARED_ERRORS = List.of(
        @ERRORS@);

            @Bean
            OpenAPI kcgOpenApi() {
                return new OpenAPI()
                        .info(new Info().title(@TITLE@).version(@VERSION@))
                        .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("UUID access token")));
            }

            @Bean
            OpenApiCustomizer kcgDeclaredErrors() {
                return openApi -> {
                    ResolvedSchema envelope = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(@ENVELOPE@.class));
                    envelope.referencedSchemas.forEach(openApi.getComponents()::addSchemas);
                    Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + envelope.schema.getName());
                    if (openApi.getPaths() == null) {
                        return;
                    }
                    openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                        Map<String, List<String>> codes = new LinkedHashMap<>();
                        for (String[] error : DECLARED_ERRORS) {
                            if (error[0].equals(method.name() + " " + path)) {
                                codes.computeIfAbsent(error[1], status -> new java.util.ArrayList<>()).add(error[2]);
                            }
                        }
                        codes.forEach((status, names) -> operation.getResponses().addApiResponse(status, new ApiResponse()
                                .description("Error envelope; code " + String.join(" or ", names))
                                .content(new Content().addMediaType("application/json", new MediaType().schema(reference)))));
                    }));
                };
            }

            @Bean
            OperationCustomizer kcgAuthenticatedOperations() {
                return (operation, handler) -> {
                    Set<String> attributes = new HashSet<>();
                    for (MethodParameter parameter : handler.getMethodParameters()) {
                        RequestAttribute attribute = parameter.getParameterAnnotation(RequestAttribute.class);
                        if (attribute != null) {
                            String name = attribute.name().isEmpty() ? attribute.value() : attribute.name();
                            attributes.add(name.isEmpty() ? String.valueOf(parameter.getParameterName()) : name);
                        }
                    }
                    if (attributes.isEmpty()) {
                        return operation;
                    }
                    // The caller identity is resolved from the token; it is never a client-supplied parameter.
                    if (operation.getParameters() != null) {
                        operation.getParameters().removeIf(p -> attributes.contains(p.getName()));
                    }
                    if (operation.getResponses() == null) {
                        operation.setResponses(new ApiResponses());
                    }
                    operation.getResponses().addApiResponse("401", new ApiResponse()
                            .description("Missing, malformed, unknown or expired access token. The response body is empty."));
                    return operation.addSecurityItem(new SecurityRequirement().addList("bearer"));
                };
            }
        }
        """;
    static final String CONTRACT_TEST = """
        package @PACKAGE@.contract;

        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
        import static org.junit.jupiter.api.DynamicTest.dynamicTest;

        import com.fasterxml.jackson.databind.JsonNode;
        import com.fasterxml.jackson.databind.ObjectMapper;
        import java.io.IOException;
        import java.net.URI;
        import java.net.http.HttpClient;
        import java.net.http.HttpRequest;
        import java.net.http.HttpResponse;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.time.Duration;
        import java.util.ArrayList;
        import java.util.Collections;
        import java.util.Iterator;
        import java.util.List;
        import java.util.Map;
        import java.util.OptionalInt;
        import java.util.TreeSet;
        import java.util.UUID;
        import java.util.stream.Stream;
        import org.junit.jupiter.api.AfterAll;
        import org.junit.jupiter.api.Assumptions;
        import org.junit.jupiter.api.DynamicNode;
        import org.junit.jupiter.api.DynamicTest;
        import org.junit.jupiter.api.TestFactory;
        import org.junit.jupiter.api.function.Executable;
        import org.opentest4j.TestAbortedException;

        /**
         * Declared-interface contract of a deployed instance, checked from outside the application over real HTTP.
         *
         * <p>Selected only explicitly: {@code mvn test -Dtest=HttpContractIT -Dkcg.baseUrl=http://host:port}. Every request is
         * refused by the application before any business code runs, so the suite writes no data. What needs business data or an
         * account (a successful call, a declared business error, validation behind authentication) is reported as skipped with a
         * {@code NOT_VERIFIED:} message; it proves the interface shape, not that the requirement is met. Besides the Surefire
         * report, every check is written with its operation, outcome and reason to {@code kcg.reportFile}
         * (default {@code target/kcg-contract-report.tsv}).
         */
        class HttpContractIT {

            record TextRule(String property, boolean notBlank, int minLength, OptionalInt maxLength) {}

            record Endpoint(String method, String route, boolean authenticated, String binding, List<TextRule> textRules, List<String> errors) {}

            /** The interface exactly as the target lowered it when this package was exported. */
            static final List<Endpoint> ENDPOINTS = List.of(
        @ENDPOINTS@);

            /** Longer sample values would only test the server's request size limit, not the declared rule. */
            private static final int LONGEST_SAMPLE = 4096;

            private static final ObjectMapper JSON = new ObjectMapper();

            private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            private static JsonNode documentation;

            private static final List<String> REPORT = Collections.synchronizedList(new ArrayList<>());

            @TestFactory
            Stream<DynamicNode> declaredInterface() {
                String base = System.getProperty("kcg.baseUrl", "");
                assertTrue(base.matches("https?://[^/]+"), "set -Dkcg.baseUrl=http://host:port of the deployed instance");
                List<DynamicNode> nodes = new ArrayList<>();
                nodes.add(check("global", "Swagger UI is served", () -> {
                    HttpResponse<String> ui = send(base, "GET", "/swagger-ui/index.html", null, null);
                    assertEquals(200, ui.statusCode(), "status");
                    assertTrue(ui.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), "content type");
                }));
                nodes.add(check("global", "OpenAPI documents exactly the generated operations, their authentication and error responses", () -> {
                    JsonNode paths = documentation(base).path("paths");
                    TreeSet<String> documented = new TreeSet<>(), secured = new TreeSet<>(), expected = new TreeSet<>(), authenticated = new TreeSet<>();
                    TreeSet<String> documentedErrors = new TreeSet<>(), declaredErrors = new TreeSet<>();
                    for (Iterator<Map.Entry<String, JsonNode>> path = paths.fields(); path.hasNext(); ) {
                        Map.Entry<String, JsonNode> entry = path.next();
                        for (Iterator<Map.Entry<String, JsonNode>> operation = entry.getValue().fields(); operation.hasNext(); ) {
                            Map.Entry<String, JsonNode> method = operation.next();
                            String key = method.getKey().toUpperCase() + " " + entry.getKey();
                            documented.add(key);
                            if (method.getValue().path("security").toString().contains("\\"bearer\\"")) secured.add(key);
                            for (Iterator<String> status = method.getValue().path("responses").fieldNames(); status.hasNext(); ) {
                                String code = status.next();
                                if (code.startsWith("4")) documentedErrors.add(key + " " + code);
                            }
                        }
                    }
                    for (Endpoint endpoint : ENDPOINTS) {
                        expected.add(endpoint.method() + " " + endpoint.route());
                        if (endpoint.authenticated()) {
                            authenticated.add(endpoint.method() + " " + endpoint.route());
                            declaredErrors.add(endpoint.method() + " " + endpoint.route() + " 401");
                        }
                        for (String error : endpoint.errors()) declaredErrors.add(endpoint.method() + " " + endpoint.route() + " " + error.split(" ")[0]);
                    }
                    assertEquals(expected, documented, "documented operations");
                    assertEquals(authenticated, secured, "operations that declare the bearer requirement");
                    assertEquals(declaredErrors, documentedErrors, "documented error envelope responses");
                }));
                for (Endpoint endpoint : ENDPOINTS) {
                    nodes.add(dynamicContainer(endpoint.method() + " " + endpoint.route(), checks(base, endpoint)));
                }
                return nodes.stream();
            }

            private static List<DynamicNode> checks(String base, Endpoint endpoint) {
                List<DynamicNode> checks = new ArrayList<>();
                String scope = endpoint.method() + " " + endpoint.route();
                String empty = endpoint.binding().equals("BODY") ? "{}" : null;
                if (endpoint.authenticated()) {
                    checks.add(check(scope, "refuses a request without credential with 401", () ->
                            assertEquals(401, send(base, endpoint.method(), endpoint.route(), empty, null).statusCode())));
                    checks.add(check(scope, "refuses a malformed bearer credential with 401", () ->
                            assertEquals(401, send(base, endpoint.method(), endpoint.route(), empty, "Bearer not-a-token").statusCode())));
                    checks.add(check(scope, "refuses a well-formed token that was never issued with 401", () ->
                            assertEquals(401, send(base, endpoint.method(), endpoint.route(), empty, "Bearer " + UUID.randomUUID()).statusCode())));
                }
                if (endpoint.binding().equals("BODY") && !endpoint.authenticated()) {
                    checks.add(check(scope, "answers a malformed JSON body with the 400 error envelope", () ->
                            envelope(send(base, endpoint.method(), endpoint.route(), "{", null), null, null)));
                    checks.add(check(scope, "answers an undeclared property with the 400 error envelope naming it", () ->
                            envelope(send(base, endpoint.method(), endpoint.route(), "{\\"kcgUndeclaredProperty\\":true}", null),
                                    "kcgUndeclaredProperty", null)));
                    for (TextRule rule : endpoint.textRules()) {
                        if (rule.notBlank()) {
                            checks.add(check(scope, rule.property() + ": a blank value is refused as NotBlank", () ->
                                    envelope(send(base, endpoint.method(), endpoint.route(), body(rule.property(), "   "), null), rule.property(), "NotBlank")));
                        }
                        if (rule.minLength() > 1) {
                            checks.add(check(scope, rule.property() + ": " + (rule.minLength() - 1) + " characters are refused as Size", () ->
                                    envelope(send(base, endpoint.method(), endpoint.route(), body(rule.property(), "x".repeat(rule.minLength() - 1)), null), rule.property(), "Size")));
                        }
                        if (rule.maxLength().isPresent() && rule.maxLength().getAsInt() < LONGEST_SAMPLE) {
                            int length = rule.maxLength().getAsInt() + 1;
                            checks.add(check(scope, rule.property() + ": " + length + " characters are refused as Size", () ->
                                    envelope(send(base, endpoint.method(), endpoint.route(), body(rule.property(), "x".repeat(length)), null), rule.property(), "Size")));
                        }
                    }
                }
                if (endpoint.authenticated() && !endpoint.binding().equals("NONE")) {
                    checks.add(notVerified(scope, "input validation behind authentication", "needs an account and an issued access token"));
                }
                checks.add(notVerified(scope, "a successful call and its declared business errors", "need business data; supply an acceptance package"));
                return checks;
            }

            private static DynamicNode notVerified(String scope, String name, String reason) {
                return check(scope, name, () -> Assumptions.abort("NOT_VERIFIED: " + name + " " + reason));
            }

            /** A named check whose outcome is also recorded for the report: PASSED, FAILED or NOT_VERIFIED. */
            private static DynamicTest check(String scope, String name, Executable body) {
                return dynamicTest(name, () -> {
                    try {
                        body.execute();
                        REPORT.add(line("PASSED", scope, name, ""));
                    } catch (TestAbortedException aborted) {
                        REPORT.add(line("NOT_VERIFIED", scope, name, aborted.getMessage()));
                        throw aborted;
                    } catch (Throwable failure) {
                        REPORT.add(line("FAILED", scope, name, failure.getMessage()));
                        throw failure;
                    }
                });
            }

            private static String line(String outcome, String scope, String name, String detail) {
                return String.join("\\t", outcome, scope, name, String.valueOf(detail).replaceAll("\\\\s+", " "));
            }

            @AfterAll
            static void writeReport() throws IOException {
                Path report = Path.of(System.getProperty("kcg.reportFile", "target/kcg-contract-report.tsv"));
                if (report.getParent() != null) Files.createDirectories(report.getParent());
                List<String> lines = new ArrayList<>(List.of("outcome\\toperation\\tcheck\\tdetail"));
                synchronized (REPORT) { lines.addAll(REPORT); }
                Files.write(report, lines);
            }

            private static void envelope(HttpResponse<String> response, String path, String code) throws Exception {
                assertEquals(400, response.statusCode(), "status");
                JsonNode body = JSON.readTree(response.body());
                assertEquals("INVALID_REQUEST", body.path("code").asText(), "envelope code");
                assertTrue(body.path("fields").isArray(), "envelope fields");
                if (path != null) {
                    boolean named = false;
                    for (JsonNode field : body.path("fields")) {
                        named |= field.path("path").asText().equals(path) && (code == null || field.path("code").asText().equals(code));
                    }
                    assertTrue(named, "fields name " + path + (code == null ? "" : " with " + code) + ": " + response.body());
                }
            }

            private static String body(String property, String value) throws Exception {
                return JSON.writeValueAsString(Map.of(property, value));
            }

            private static synchronized JsonNode documentation(String base) throws Exception {
                if (documentation == null) {
                    HttpResponse<String> docs = send(base, "GET", "/v3/api-docs", null, null);
                    assertEquals(200, docs.statusCode(), "OpenAPI status");
                    documentation = JSON.readTree(docs.body());
                }
                return documentation;
            }

            private static HttpResponse<String> send(String base, String method, String path, String body, String authorization) throws Exception {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
                if (body != null) request.header("Content-Type", "application/json");
                if (authorization != null) request.header("Authorization", authorization);
                return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            }
        }
        """;
}
