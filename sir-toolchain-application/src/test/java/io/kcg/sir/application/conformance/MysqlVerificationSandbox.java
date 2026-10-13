package io.kcg.sir.application.conformance;

import io.kcg.sir.application.api.CleanupProof;
import io.kcg.sir.application.api.VerificationPorts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The database side of a verification, built from the reference environment the conformance harness already
 * trusts: a control account that creates and drops one owned schema under an advisory lock, a runtime account
 * that can only read and write inside it, and test DDL and seed data. It never touches a business database, and it
 * proves on release that the schema, the lock, the connection and the candidate directory are gone.
 *
 * <p>Test code on purpose: the product has no database driver and no database initialisation yet.
 */
final class MysqlVerificationSandbox implements VerificationPorts.Sandbox {
    private final BusinessSliceHarness.Environment environment;
    private final String ddlResource;
    private final String seedResource;
    private final String label;
    private final SchemaSqlRenderer renderer = new SchemaSqlRenderer();
    private final Map<String, String> facts = new TreeMap<>();
    private MysqlRuntimeJdbcUrl runtimeUrl;

    MysqlVerificationSandbox(BusinessSliceHarness.Environment environment, String ddlResource, String seedResource, String label) {
        this.environment = environment;
        this.ddlResource = ddlResource;
        this.seedResource = seedResource;
        this.label = label;
        facts.put("schema", environment.schemaName().value());
        facts.put("ddl.sha256", digest(ddlResource));
        facts.put("seed.sha256", digest(seedResource));
    }

    private static String digest(String resource) {
        try {
            return ConformanceFixtures.resourceDigest(resource);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every secret this sandbox knows, for redaction. */
    List<String> secrets() {
        MysqlRuntimeJdbcUrl url = MysqlRuntimeJdbcUrl.fromControlEndpoint(environment.controlJdbcUrl(), environment.schemaName());
        return SecretCatalog.build(environment.controlUsername(), environment.controlPassword(), environment.runtimeUsername(),
                environment.runtimePassword(), environment.controlJdbcUrl(), url.rendered()).allSecretStrings().stream()
                .filter(s -> s.length() >= 4).distinct().toList();
    }

    /** An independent read-only connection as the runtime account, valid while a lease with a database is open. */
    MysqlObserver openObserver() throws SQLException {
        if (runtimeUrl == null) {
            throw new SQLException("no database lease is open");
        }

        return MysqlObserver.open(runtimeUrl.rendered(), environment.runtimeUsername(), environment.runtimePassword());
    }

    SchemaName schema() {
        return environment.schemaName();
    }

    @Override
    public Map<String, String> environment() {
        return Map.copyOf(facts);
    }

    @Override
    public Lease open(boolean needsDatabase) throws Unavailable {
        String token = label + "-" + Long.toHexString(System.currentTimeMillis()) + "-" + ThreadLocalRandom.current().nextInt(0x10000);
        Path candidate = environment.workParent().resolve(token).resolve("candidate");
        Path evidence = environment.evidenceParent().resolve(token);
        try {
            Files.createDirectories(candidate);
            Files.createDirectories(evidence);
        } catch (IOException e) {
            throw new Unavailable("WORK_AREA_UNAVAILABLE", "the owned work area could not be created (" + e.getClass().getSimpleName() + ")");
        }

        MysqlLease lease = new MysqlLease(candidate, evidence);
        if (!needsDatabase) {
            return lease;
        }

        try {
            lease.openDatabase();
            return lease;
        } catch (SQLException | IOException e) {
            lease.release();
            // Only the first line: a driver's full text can carry host details and runs to many lines.
            String message = BusinessSliceHarness.describe(e).lines().findFirst().orElse("database error");
            message = message.length() > 300 ? message.substring(0, 300) : message;
            throw new Unavailable(lease.busy ? "DATABASE_BUSY" : lease.connected ? "DATABASE_FIXTURE_FAILED" : "DATABASE_UNREACHABLE", message);
        }
    }

    private final class MysqlLease implements Lease {
        private final Path candidate;
        private final Path evidence;
        private Connection control;
        private String lockName;
        private boolean lockHeld;
        private boolean schemaCreated;
        boolean connected;
        boolean busy;
        private CleanupProof released;

        MysqlLease(Path candidate, Path evidence) {
            this.candidate = candidate;
            this.evidence = evidence;
        }

        void openDatabase() throws SQLException, IOException {
            Properties properties = new Properties();
            properties.setProperty("user", environment.controlUsername());
            properties.setProperty("password", environment.controlPassword());
            properties.setProperty("sslMode", "DISABLED");
            properties.setProperty("allowPublicKeyRetrieval", "true");
            properties.setProperty("connectTimeout", "10000");
            control = DriverManager.getConnection(environment.controlJdbcUrl(), properties);
            connected = true;
            String uuid;
            try (Statement statement = control.createStatement(); ResultSet rows = statement.executeQuery("SELECT @@server_uuid, VERSION()")) {
                rows.next();
                uuid = rows.getString(1);
                facts.put("database.version", rows.getString(2));
            }

            lockName = AdvisoryLockKey.derive(uuid, environment.schemaName()).lockName();
            try (PreparedStatement statement = control.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, lockName);
                try (ResultSet rows = statement.executeQuery()) {
                    lockHeld = rows.next() && rows.getInt(1) == 1;
                }
            }

            if (!lockHeld) {
                busy = true;
                throw new SQLException("another run holds the advisory lock for schema " + environment.schemaName().value() + "; it was not touched");
            }

            try (Statement statement = control.createStatement()) {
                statement.executeUpdate(renderer.createDatabase(environment.schemaName()));
                schemaCreated = true;
                statement.execute("USE `" + environment.schemaName().value() + "`");
            }

            for (String resource : List.of(ddlResource, seedResource)) {
                for (String sql : BusinessSliceHarness.stripSqlComments(ConformanceFixtures.readResource(resource)).split(";")) {
                    if (!sql.isBlank()) {
                        try (Statement statement = control.createStatement()) {
                            statement.execute(sql.strip());
                        }
                    }
                }
            }

            runtimeUrl = MysqlRuntimeJdbcUrl.fromControlEndpoint(environment.controlJdbcUrl(), environment.schemaName());
        }

        @Override public Path candidateRoot() { return candidate; }
        @Override public Path evidenceRoot() { return evidence; }

        @Override
        public Map<String, String> applicationEnvironment() {
            if (runtimeUrl == null) {
                return Map.of();
            }

            return Map.of("SPRING_DATASOURCE_URL", runtimeUrl.rendered(), "SPRING_DATASOURCE_USERNAME", environment.runtimeUsername(),
                    "SPRING_DATASOURCE_PASSWORD", environment.runtimePassword());
        }

        @Override
        public synchronized CleanupProof release() {
            if (released != null) {
                return released;
            }

            List<String> checks = new ArrayList<>();
            boolean proved = true;
            if (control != null) {
                if (schemaCreated) {
                    try (Statement statement = control.createStatement()) {
                        statement.executeUpdate(renderer.dropDatabase(environment.schemaName()));
                        try (PreparedStatement absent = control.prepareStatement(renderer.informationSchemaAbsenceSql())) {
                            absent.setString(1, environment.schemaName().value());
                            try (ResultSet rows = absent.executeQuery()) {
                                boolean gone = !rows.next();
                                proved &= gone;
                                checks.add(gone ? "schema " + environment.schemaName().value() + " dropped and its absence proved" : "schema still present after DROP");
                            }
                        }
                    } catch (SQLException e) {
                        proved = false;
                        checks.add("schema could not be dropped (" + BusinessSliceHarness.describe(e) + ")");
                    }
                }

                if (lockHeld) {
                    try (PreparedStatement statement = control.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        statement.setString(1, lockName);
                        try (ResultSet rows = statement.executeQuery()) {
                            boolean freed = rows.next() && rows.getInt(1) == 1;
                            proved &= freed;
                            checks.add(freed ? "advisory lock released" : "advisory lock was not released");
                        }
                    } catch (SQLException e) {
                        proved = false;
                        checks.add("advisory lock release failed (" + BusinessSliceHarness.describe(e) + ")");
                    }
                }

                try {
                    control.close();
                    checks.add("control connection closed");
                } catch (SQLException e) {
                    proved = false;
                    checks.add("control connection could not be closed");
                }
            }

            BusinessSliceHarness.deleteRecursively(candidate.getParent());
            boolean removed = !Files.exists(candidate.getParent());
            proved &= removed;
            checks.add(removed ? "candidate directory removed" : "candidate directory still exists");
            runtimeUrl = null;
            released = new CleanupProof(proved, checks);
            return released;
        }
    }
}
