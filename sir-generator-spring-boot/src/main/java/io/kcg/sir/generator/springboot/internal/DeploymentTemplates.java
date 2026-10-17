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
        INSTANCE= STATE= PORT=
        while [ "$#" -gt 0 ]; do
          [ "$#" -ge 2 ] || { echo 'option requires a value' >&2; exit 2; }
          case "$1" in
            --instance) [ -z "$INSTANCE" ] || exit 2; INSTANCE=$2 ;;
            --state-root) [ -z "$STATE" ] || exit 2; STATE=$2 ;;
            --port) [ -z "$PORT" ] || exit 2; PORT=$2 ;;
            *) echo 'unknown deployment option' >&2; exit 2 ;;
          esac
          shift 2
        done
        case "$ACTION" in
          help|--help) echo 'Usage: sh deploy/deploy.sh deploy|start|stop|status|logs --instance <name> --state-root <absolute-new-or-owned-directory> [--port <1024-65535>]'; exit 0 ;;
          deploy|start|stop|status|logs) ;;
          *) echo 'unsupported action; database/schema updates are not available in delivery v1' >&2; exit 2 ;;
        esac
        printf '%s\\n' "$INSTANCE" | LC_ALL=C grep -Eq '^[a-z][a-z0-9_-]{0,39}$' || { echo 'invalid instance name' >&2; exit 2; }
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
          "/state-parent/$LEAF" "$INSTANCE" "$PORT"
        KCG_PACKAGE_ID=$(cat "$PACKAGE/package.id")
        KCG_ENVIRONMENT_ID=$(cat "$STATE/environment.id")
        export KCG_PACKAGE="$PACKAGE" KCG_STATE="$STATE" KCG_INSTANCE="$INSTANCE" KCG_HTTP_PORT="$PORT" KCG_PACKAGE_ID KCG_ENVIRONMENT_ID
        COORDINATOR=$(docker container create --name "$INSTANCE-kcg-lock" --label "kcg.instance=$INSTANCE" --label "kcg.environment=$KCG_ENVIRONMENT_ID" @BUILDER@ true) || { echo 'another deployment owns this Docker instance name' >&2; exit 3; }
        compose() { docker compose --project-name "$INSTANCE" -f "$PACKAGE/deploy/compose.yaml" "$@"; }
        volume_present() { docker volume inspect "$INSTANCE-db" >/dev/null 2>&1; }
        check_volume() {
          volume_present || { echo 'TARGET_MISSING: database volume is absent; refusing to create an empty replacement' >&2; exit 3; }
          OWNER=$(docker volume inspect --format '{{index .Labels "kcg.instance"}}' "$INSTANCE-db")
          BINDING=$(docker volume inspect --format '{{index .Labels "kcg.package"}}' "$INSTANCE-db")
          ENVIRONMENT=$(docker volume inspect --format '{{index .Labels "kcg.environment"}}' "$INSTANCE-db")
          [ "$OWNER" = "$INSTANCE" ] && [ "$BINDING" = "$KCG_PACKAGE_ID" ] && [ "$ENVIRONMENT" = "$KCG_ENVIRONMENT_ID" ] || { echo 'database volume belongs to another delivery or environment' >&2; exit 3; }
        }
        case "$ACTION" in
          deploy)
            # A build never needs a database. The only build process has explicit process-tree limits.
            if volume_present; then check_volume; elif [ -f "$STATE/initialized" ]; then echo 'TARGET_MISSING: initialized database volume was removed' >&2; exit 3; fi
            mkdir -p "$STATE/artifacts"
            docker run --rm --name "$INSTANCE-kcg-build" --label "kcg.instance=$INSTANCE" --memory="${KCG_BUILD_MEMORY:-1536m}" --cpus="${KCG_BUILD_CPUS:-1.5}" \\
              --env 'MAVEN_OPTS=-Xmx512m -XX:MaxMetaspaceSize=256m' \\
              --mount "type=bind,src=$PACKAGE,dst=/package,readonly" \\
              --mount "type=bind,src=$STATE/artifacts,dst=/artifacts" \\
              --mount "type=volume,src=$INSTANCE-maven,dst=/m2" \\
              @BUILDER@ sh /package/deploy/build.sh
            docker build --target migrate --tag "$INSTANCE-migrate:$KCG_PACKAGE_ID" --file "$PACKAGE/deploy/Dockerfile" "$STATE/artifacts"
            docker build --target app --tag "$INSTANCE-app:$KCG_PACKAGE_ID" --file "$PACKAGE/deploy/Dockerfile" "$STATE/artifacts"
            if ! volume_present; then
              [ ! -f "$STATE/initialized" ] || { echo 'TARGET_MISSING: initialized database volume was removed' >&2; exit 3; }
              docker volume create --label "kcg.instance=$INSTANCE" --label "kcg.package=$KCG_PACKAGE_ID" --label "kcg.environment=$KCG_ENVIRONMENT_ID" "$INSTANCE-db" >/dev/null
            fi
            check_volume
            compose up -d --wait --wait-timeout 180 db
            compose run --rm --no-deps migrate
            touch "$STATE/initialized"
            compose up -d --no-deps --wait --wait-timeout 180 app
            echo "KCG_DEPLOYED http://127.0.0.1:$PORT"
            ;;
          start)
            check_volume
            docker image inspect "$INSTANCE-app:$KCG_PACKAGE_ID" "$INSTANCE-migrate:$KCG_PACKAGE_ID" >/dev/null || { echo 'deployment images are missing; rerun deploy' >&2; exit 3; }
            [ -f "$STATE/initialized" ] || { echo 'instance has not completed initialization; rerun deploy' >&2; exit 3; }
            compose up -d --wait --wait-timeout 180 db
            compose run --rm --no-deps migrate
            compose up -d --no-deps --wait --wait-timeout 180 app
            echo "KCG_STARTED http://127.0.0.1:$PORT"
            ;;
          stop) check_volume; compose stop app db ;;
          status) check_volume; compose ps --all ;;
          logs) check_volume; compose logs --no-color --tail 100 app db ;;
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
                source: ${KCG_PACKAGE}/deploy/accounts.sh
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
                source: ${KCG_PACKAGE}/deploy/migrations
                target: /migrations
                read_only: true
              - type: bind
                source: ${KCG_PACKAGE}/deploy/schema.tsv
                target: /schema.tsv
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
                if (!state.isAbsolute() || !state.equals(state.normalize()) || state.startsWith(root)
                        || !instance.matches("[a-z][a-z0-9_-]{0,39}") || !port.matches("[0-9]{1,5}")
                        || Integer.parseInt(port) < 1024 || Integer.parseInt(port) > 65535) throw new IllegalArgumentException("invalid instance configuration");
                for (Path p = state; p != null; p = p.getParent())
                    if (Files.isSymbolicLink(p)) throw new IllegalStateException("state symlink refused");
                if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(state) || !Files.readString(state.resolve("instance")).equals(instance + "\\n")
                            || !Files.readString(state.resolve("package.id")).equals(id + "\\n")
                            || !Files.readString(state.resolve("port")).equals(port + "\\n"))
                        throw new IllegalStateException("existing state belongs to another instance, package or port");
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

        import java.nio.file.*;
        import java.sql.*;
        import java.util.*;
        import org.flywaydb.core.Flyway;

        /** Owns schema privileges only for the deployment step; the business app has DML privileges. */
        public final class Migrate {
            public static void main(String[] args) {
                try {
                    String url = System.getenv("KCG_JDBC_URL");
                    String password = Files.readString(Path.of("/run/secrets/migrate-password"));
                    var flyway = Flyway.configure().dataSource(url, "kcg_migrate", password)
                        .locations("filesystem:/migrations").baselineOnMigrate(false).cleanDisabled(true).load();
                    List<String[]> expected = Files.readAllLines(Path.of("/schema.tsv")).stream().filter(s -> !s.isBlank()).map(s -> s.split("\\t", -1)).toList();
                    try (Connection db = DriverManager.getConnection(url, "kcg_migrate", password)) {
                        Set<String> reserved = new HashSet<>();
                        try (Statement words = db.createStatement(); ResultSet rs = words.executeQuery("SELECT WORD FROM information_schema.KEYWORDS WHERE RESERVED=1")) {
                            while (rs.next()) reserved.add(rs.getString(1).toUpperCase(Locale.ROOT));
                        }
                        for (String[] row : expected) if (reserved.contains(row[0].toUpperCase(Locale.ROOT)) || reserved.contains(row[1].toUpperCase(Locale.ROOT)))
                            throw new IllegalStateException("reserved identifier is not supported by the unquoted Java mapping: " + row[0] + "." + row[1]);
                        try (ResultSet tables = db.getMetaData().getTables("kcg", null, "flyway_schema_history", new String[]{"TABLE"})) {
                            if (tables.next()) verify(db, expected);
                        }
                        flyway.migrate();
                        verify(db, expected);
                        try (Statement grants = db.createStatement()) {
                            grants.execute("GRANT SELECT ON `kcg`.`flyway_schema_history` TO 'kcg_app'@'%'");
                            Set<String> businessTables = new TreeSet<>();
                            for (String[] row : expected) businessTables.add(row[0]);
                            for (String table : businessTables) grants.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `kcg`.`" + table + "` TO 'kcg_app'@'%'");
                        }
                    }
                    System.out.println("KCG_DATABASE_READY");
                } catch (Exception e) {
                    // Driver exceptions can echo connection information; only their category leaves this process.
                    String detail = e instanceof IllegalStateException ? ": " + e.getMessage() : "";
                    System.err.println("KCG_DATABASE_NOT_READY: " + e.getClass().getSimpleName() + detail + "; preserve the database, inspect migration/schema state; no clean or repair was performed");
                    System.exit(3);
                }
            }

            private static void verify(Connection db, List<String[]> expected) throws Exception {
                Map<String, String[]> columns = new TreeMap<>();
                Set<String> tables = new TreeSet<>(), uniques = new TreeSet<>(), references = new TreeSet<>();
                for (String[] row : expected) {
                    if (row.length != 10) throw new IllegalStateException("invalid schema contract");
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
                                || rs.getString(9).contains("unsigned") || rs.getObject(11) != null
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
}
