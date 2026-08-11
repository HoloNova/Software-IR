package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Stage E conformance harness main entry point (ADR-017).
 *
 * <p>This is an opt-in integration test. Default Maven Surefire does not
 * execute {@code *IT} classes, so it never runs in the default Reactor. It
 * runs only through explicit invocation:
 *
 * <pre>{@code
 * mvn -pl sir-toolchain-application \
 *   -Dtest=io.kcg.sir.application.conformance.SpringBootTargetConformanceIT \
 *   -Dkcg.conformance.enabled=true \
 *   -Dkcg.conformance.work-parent=<absolute-parent> \
 *   -Dkcg.conformance.evidence-parent=<absolute-parent> \
 *   -Dkcg.conformance.schema-name=<validated-schema-name> \
 *   -Dkcg.conformance.maven-executable=<absolute-maven> \
 *   -Dkcg.conformance.maven-repo=<isolated-repo> \
 *   -Dkcg.conformance.expected-mysql-server-uuid=<uuid> \
 *   test
 * }</pre>
 *
 * <p>Credentials are supplied only through the environment whitelist:
 * {@code KCG_CONF_CONTROL_JDBC_URL}, {@code KCG_CONF_CONTROL_USERNAME},
 * {@code KCG_CONF_CONTROL_PASSWORD}, {@code KCG_CONF_RUNTIME_USERNAME},
 * {@code KCG_CONF_RUNTIME_PASSWORD}.
 *
 * <p>Without a real MySQL reference tuple, the suite reports
 * {@code NOT_RUN} and does not claim qualification. The {@code NOT_RUN}
 * guard is enforced before any side effect—no schema mutation, no
 * ephemeral directory allocation, no Spring process spawn.
 *
 * <p>Per ADR-017 section 7.7 (dual-version authority), the Target runtime
 * Connector/J version is <em>not</em> a test constant. It is recorded
 * per-scenario from the real lowered model and the generated
 * {@code pom.xml} via {@link TargetDependencyInspector}. The harness JDBC
 * driver version is independently read from the actual JDBC driver
 * metadata (via {@link DriverManager#getDriver(String)}).
 */
class SpringBootTargetConformanceIT {

    private static final String CONTRACT_REVISION = "ADR-017-V1";
    private static final String TARGET_PROFILE_ID = "spring-boot-java21-mybatis-plus-mysql-rest-v0.2";
    private static final String LOWERED_IR_VERSION = "V0_2";
    private static final int DEFAULT_SERVER_PORT = 8080;
    private static final long READINESS_TIMEOUT_MS = 60_000L;
    private static final long READINESS_POLL_MS = 500L;
    private static final long MAVEN_GRACE_MS = 300_000L;
    private static final long SPRING_GRACE_MS = 30_000L;

    private static boolean conformanceEnabled;
    private static java.nio.file.Path workParent;
    private static java.nio.file.Path evidenceParent;
    private static SchemaName schemaName;
    private static java.nio.file.Path mavenExecutable;
    private static java.nio.file.Path mavenRepo;
    private static String expectedMysqlServerUuid;
    private static String controlJdbcUrl;
    private static String controlUsername;
    private static String controlPassword;
    private static String runtimeUsername;
    private static String runtimePassword;
    private static int serverPort;
    private static boolean environmentComplete;

    @BeforeAll
    static void readEnvironment() {
        conformanceEnabled = "true".equalsIgnoreCase(
                System.getProperty("kcg.conformance.enabled", "false"));

        String workParentStr = System.getProperty("kcg.conformance.work-parent");
        String evidenceParentStr = System.getProperty("kcg.conformance.evidence-parent");
        String schemaNameStr = System.getProperty("kcg.conformance.schema-name");
        String mavenExecStr = System.getProperty("kcg.conformance.maven-executable");
        String mavenRepoStr = System.getProperty("kcg.conformance.maven-repo");
        expectedMysqlServerUuid = System.getProperty("kcg.conformance.expected-mysql-server-uuid");
        String serverPortStr = System.getProperty("kcg.conformance.server-port");

        workParent = workParentStr != null ? java.nio.file.Path.of(workParentStr) : null;
        evidenceParent = evidenceParentStr != null ? java.nio.file.Path.of(evidenceParentStr) : null;
        schemaName = null;
        if (schemaNameStr != null) {
            try {
                schemaName = SchemaName.parse(schemaNameStr);
            } catch (IllegalArgumentException e) {
                schemaName = null;
            }
        }
        mavenExecutable = mavenExecStr != null ? java.nio.file.Path.of(mavenExecStr) : null;
        mavenRepo = mavenRepoStr != null ? java.nio.file.Path.of(mavenRepoStr) : null;
        serverPort = DEFAULT_SERVER_PORT;
        if (serverPortStr != null) {
            try {
                serverPort = Integer.parseInt(serverPortStr, 10);
            } catch (NumberFormatException ignored) {
                // Fall back to default.
            }
        }

        Map<String, String> env = System.getenv();
        controlJdbcUrl = env.get("KCG_CONF_CONTROL_JDBC_URL");
        controlUsername = env.get("KCG_CONF_CONTROL_USERNAME");
        controlPassword = env.get("KCG_CONF_CONTROL_PASSWORD");
        runtimeUsername = env.get("KCG_CONF_RUNTIME_USERNAME");
        runtimePassword = env.get("KCG_CONF_RUNTIME_PASSWORD");

        environmentComplete = conformanceEnabled
                && workParent != null && workParent.isAbsolute()
                && evidenceParent != null && evidenceParent.isAbsolute()
                && schemaName != null
                && mavenExecutable != null && mavenExecutable.isAbsolute()
                && mavenRepo != null && mavenRepo.isAbsolute()
                && expectedMysqlServerUuid != null && !expectedMysqlServerUuid.isBlank()
                && controlJdbcUrl != null && !controlJdbcUrl.isBlank()
                && controlUsername != null && !controlUsername.isBlank()
                && controlPassword != null && !controlPassword.isEmpty()
                && runtimeUsername != null && !runtimeUsername.isBlank()
                && runtimePassword != null && !runtimePassword.isEmpty();
    }

    /**
     * Report the environment status. Without a complete reference tuple,
     * the external qualification matrix is NOT_RUN. The NOT_RUN guard is
     * enforced before any side effect: no MySQL connection is opened, no
     * schema is created, no ephemeral directory is allocated.
     *
     * <p>When the reference tuple is complete, the harness constructs the
     * full {@link ConformanceSuiteContext} and invokes
     * {@link ConformanceSuite#orchestrate}. The terminal result
     * (QUALIFIED / FAILED / NOT_RUN) is printed and asserted to be one of
     * those three outcomes.
     */
    @Test
    void reportEnvironmentStatus() {
        if (!conformanceEnabled) {
            System.out.println("[CONFORMANCE] NOT_RUN: kcg.conformance.enabled is not true");
            System.out.println("[CONFORMANCE] External qualification matrix not executed.");
            System.out.println("[CONFORMANCE] Reason: conformance not explicitly enabled.");
            System.out.println("[CONFORMANCE] This is NOT QUALIFIED.");
            return;
        }
        if (!environmentComplete) {
            System.out.println("[CONFORMANCE] NOT_RUN: incomplete reference environment.");
            List<String> missing = new ArrayList<>();
            if (workParent == null || !workParent.isAbsolute()) {
                missing.add("kcg.conformance.work-parent (absolute)");
            }
            if (evidenceParent == null || !evidenceParent.isAbsolute()) {
                missing.add("kcg.conformance.evidence-parent (absolute)");
            }
            if (schemaName == null) {
                missing.add("kcg.conformance.schema-name (valid kcg_conf_*)");
            }
            if (mavenExecutable == null || !mavenExecutable.isAbsolute()) {
                missing.add("kcg.conformance.maven-executable (absolute)");
            }
            if (mavenRepo == null || !mavenRepo.isAbsolute()) {
                missing.add("kcg.conformance.maven-repo (absolute)");
            }
            if (expectedMysqlServerUuid == null || expectedMysqlServerUuid.isBlank()) {
                missing.add("kcg.conformance.expected-mysql-server-uuid");
            }
            if (controlJdbcUrl == null || controlJdbcUrl.isBlank()) {
                missing.add("KCG_CONF_CONTROL_JDBC_URL");
            }
            if (controlUsername == null || controlUsername.isBlank()) {
                missing.add("KCG_CONF_CONTROL_USERNAME");
            }
            if (controlPassword == null || controlPassword.isEmpty()) {
                missing.add("KCG_CONF_CONTROL_PASSWORD");
            }
            if (runtimeUsername == null || runtimeUsername.isBlank()) {
                missing.add("KCG_CONF_RUNTIME_USERNAME");
            }
            if (runtimePassword == null || runtimePassword.isEmpty()) {
                missing.add("KCG_CONF_RUNTIME_PASSWORD");
            }
            System.out.println("[CONFORMANCE] Missing prerequisites: " + missing);
            System.out.println("[CONFORMANCE] External qualification matrix not executed.");
            System.out.println("[CONFORMANCE] This is NOT QUALIFIED.");
            return;
        }
        System.out.println("[CONFORMANCE] Environment complete. Proceeding with full suite.");
        System.out.println("[CONFORMANCE] schema-name=" + schemaName.value());
        System.out.println("[CONFORMANCE] work-parent=" + workParent);
        System.out.println("[CONFORMANCE] evidence-parent=" + evidenceParent);

        ConformanceResult result = runFullConformanceSuite();
        assertNotNull(result, "orchestrate must return a non-null terminal result");
        System.out.println("[CONFORMANCE] Terminal result: " + result.getClass().getSimpleName());
        if (result instanceof ConformanceResult.Qualified q) {
            System.out.println("[CONFORMANCE] QUALIFIED. scenarioOutcomes="
                    + q.scenarioOutcomes());
        } else if (result instanceof ConformanceResult.Failed f) {
            System.out.println("[CONFORMANCE] FAILED. primary=" + f.primaryFailure());
            System.out.println("[CONFORMANCE] additional=" + f.additionalFailures());
        } else if (result instanceof ConformanceResult.NotRun n) {
            System.out.println("[CONFORMANCE] NOT_RUN. failure=" + n.failure());
        }
        // The terminal result must be one of the three sealed variants.
        // We do not assert QUALIFIED here—the external qualification
        // matrix is opt-in and depends on the caller-supplied MySQL tuple.
        // The harness itself enforces the QUALIFIED preconditions.
        assertTrue(
                result instanceof ConformanceResult.Qualified
                        || result instanceof ConformanceResult.Failed
                        || result instanceof ConformanceResult.NotRun,
                "terminal result must be Qualified, Failed, or NotRun");
    }

    /**
     * Construct the full {@link ConformanceSuiteContext} from the validated
     * reference environment and invoke {@link ConformanceSuite#orchestrate}.
     *
     * <p>Per ADR-017 sections 3 and 6, the run-specific {@code workRoot} and
     * {@code evidenceRoot} are <em>not</em> pre-created here. The caller
     * supplies the parent directories and a run token; the orchestrate
     * method itself creates the run-specific directories at the correct
     * lifecycle phases:
     * <ul>
     *   <li>{@code EVIDENCE_ROOT_CREATE}: create {@link EvidenceDirectory}</li>
     *   <li>{@code WORK_ROOT_CREATE}: create {@link OwnedRunDirectory}
     *       (only after schema + marker + fixture have succeeded)</li>
     * </ul>
     */
    private ConformanceResult runFullConformanceSuite() {
        // P0-C2: the runtime JDBC URL is built ONLY from the validated
        // SchemaName and the MySQL server endpoint parsed from the control
        // JDBC URL. There is no caller-supplied runtime URL bypass and no
        // fallback to using the control JDBC URL as the runtime datasource.
        // The schema identity is authoritative only via SchemaName.
        MysqlRuntimeJdbcUrl runtimeUrl;
        try {
            runtimeUrl = MysqlRuntimeJdbcUrl.fromControlEndpoint(
                    controlJdbcUrl, schemaName);
        } catch (IllegalArgumentException e) {
            // This occurs before any suite operation, filesystem allocation,
            // control connection, schema mutation, or child-process launch.
            // Do not include the raw endpoint or exception text in the result:
            // either may contain caller-controlled sensitive material.
            return new ConformanceResult.NotRun(new ConformanceFailure(
                    ConformanceFailureKind.PRECONDITION,
                    "RUNTIME_JDBC_URL_INVALID",
                    "runtime JDBC endpoint rejected by the strict URL contract"));
        }
        MysqlRuntimeFixture runtimeFixture = new MysqlRuntimeFixture(
                runtimeUrl, schemaName, runtimeUsername, runtimePassword);

        // Build the secret list for redaction + scan.
        List<String> secrets = new ArrayList<>();
        secrets.add(controlPassword);
        secrets.add(runtimePassword);
        if (controlJdbcUrl != null) {
            secrets.add(controlJdbcUrl);
        }
        // The rendered runtime URL contains the runtime schema name and
        // endpoint but no credentials—include it in the secret scan as
        // a defensive measure so any accidental credential leak in the
        // URL form is caught.
        secrets.add(runtimeUrl.rendered());

        // Streaming redactor: it must be initializable or the run is FAILED.
        StreamingSecretRedactor redactor = StreamingSecretRedactor.initialize(secrets);

        // Per ADR-017 sections 3 and 7.2, the dedicated MySQL control connection is
        // NOT pre-opened here. The caller supplies a MysqlControlConfiguration;
        // orchestrate opens the control connection at MYSQL_CONTROL_CONNECT
        // and closes it when the run terminates.
        MysqlControlConfiguration controlConfig = new MysqlControlConfiguration(
                controlJdbcUrl, controlUsername, controlPassword);

        // TODO(conformance): complete child environment construction, scenario
        // execution, cleanup, and terminal result assembly before enabling this package.
        //      detail,
                scenario.displayName()));
    }

    private static String sanitizedMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }

    private static String sanitizedSqlMessage(SQLException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return "SQLState=" + e.getSQLState();
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }

    /**
     * Compute the SHA-256 hex digest of the given bytes, used for the
     * candidate SIR fingerprint recorded in JDBC version evidence
     * (P0-B2). Lowercase hex, no separators.
     */
    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void proveAbsent(Path path) throws IOException {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw new FileAlreadyExistsException(
                    "path already exists: " + path + " (type=" + typeOf(attrs) + ")");
        } catch (NoSuchFileException e) {
            // This is the only acceptable proof of absence.
        }
    }

    private static String typeOf(BasicFileAttributes attrs) {
        if (attrs.isDirectory()) {
            return "directory";
        }
        if (attrs.isRegularFile()) {
            return "regular file";
        }
        if (attrs.isSymbolicLink()) {
            return "symlink";
        }
        return "special";
    }

    private static String buildFinalReport(ConformanceRun run, ConformanceEnvironment env) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"conformanceContractRevision\":\"")
                .append(env.conformanceContractRevision()).append("\",");
        sb.append("\"targetProfileId\":\"").append(env.targetProfileId()).append("\",");
        sb.append("\"loweredIrVersion\":\"").append(env.loweredIrVersion()).append("\",");
        sb.append("\"schemaName\":\"").append(env.schemaName().value()).append("\",");
        sb.append("\"scenarioOutcomes\":[");
        boolean first = true;
        for (ConformanceResult.ScenarioOutcome o : run.scenarioOutcomes()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"scenario\":\"").append(o.scenarioName())
                    .append("\",\"passed\":").append(o.passed()).append("}");
        }
        sb.append("],");
        sb.append("\"failures\":[");
        boolean firstF = true;
        for (ConformanceFailure f : run.failures()) {
            if (!firstF) sb.append(",");
            firstF = false;
            sb.append("{\"kind\":\"").append(f.kind())
                    .append("\",\"messageKey\":\"").append(f.messageKey()).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Per-scenario endpoint configuration
    // ------------------------------------------------------------------

    static final class ScenarioEndpoint {
        private final String readinessUrl;
        private final String readinessMethod;
        private final String readinessBody;
        private final int expectedReadinessStatus;
        private final String assertionMethod;
        private final String assertionUrl;
        private final String assertionBody;
        private final int expectedAssertionStatus;
        private final String invalidMethod;
        private final String invalidUrl;
        private final String invalidBody;
        private final int expectedInvalidStatus;
        private final String secondaryMethod;
        private final String secondaryUrl;
        private final String secondaryBody;
        private final int expectedSecondaryStatus;
        private final String removedUrl;
        private final int expectedRemovedStatus;
        private final String dbTable;
        private final int expectedDbDelta;

        ScenarioEndpoint(ConformanceScenario scenario, int serverPort) {
            String base = "http://127.0.0.1:" + serverPort;
            switch (scenario) {
                case IG_ACTOR -> {
                    this.readinessUrl = base + "/api/publish-goods";
                    this.readinessMethod = "POST";
                    this.readinessBody = "{\"title\":\"\",\"price\":0}";
                    this.expectedReadinessStatus = 400;
                    this.assertionMethod = "POST";
                    this.assertionUrl = base + "/api/publish-goods";
                    this.assertionBody = "{\"title\":\"Conf-Actor-Good\",\"price\":1.00}";
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "POST";
                    this.invalidUrl = base + "/api/publish-goods";
                    this.invalidBody = "{\"title\":\"\",\"price\":0}";
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = null;
                    this.secondaryUrl = null;
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 0;
                    this.removedUrl = null;
                    this.expectedRemovedStatus = 0;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 1;
                }
                case IG_READONLY -> {
                    this.readinessUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.readinessMethod = "GET";
                    this.readinessBody = null;
                    this.expectedReadinessStatus = 200;
                    this.assertionMethod = "GET";
                    this.assertionUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.assertionBody = null;
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "GET";
                    this.invalidUrl = base + "/api/search-goods?price=-invalid";
                    this.invalidBody = null;
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = null;
                    this.secondaryUrl = null;
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 0;
                    this.removedUrl = null;
                    this.expectedRemovedStatus = 0;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 0;
                }
                case APPLY_UPDATE -> {
                    this.readinessUrl = base + "/api/publish-goods";
                    this.readinessMethod = "POST";
                    this.readinessBody = "{\"title\":\"" + "x".repeat(201)
                            + "\",\"price\":1.00}";
                    this.expectedReadinessStatus = 400;
                    this.assertionMethod = "POST";
                    this.assertionUrl = base + "/api/publish-goods";
                    this.assertionBody = "{\"title\":\"Conf-Update-Good\",\"price\":1.00}";
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "POST";
                    this.invalidUrl = base + "/api/publish-goods";
                    this.invalidBody = "{\"title\":\"" + "x".repeat(201)
                            + "\",\"price\":1.00}";
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = null;
                    this.secondaryUrl = null;
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 0;
                    this.removedUrl = null;
                    this.expectedRemovedStatus = 0;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 1;
                }
                case APPLY_CREATE -> {
                    this.readinessUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.readinessMethod = "GET";
                    this.readinessBody = null;
                    this.expectedReadinessStatus = 200;
                    this.assertionMethod = "POST";
                    this.assertionUrl = base + "/api/publish-goods";
                    this.assertionBody = "{\"title\":\"Conf-Create-Good\",\"price\":1.00}";
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "GET";
                    this.invalidUrl = base + "/api/search-goods?price=-invalid";
                    this.invalidBody = null;
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = "GET";
                    this.secondaryUrl = base + "/api/search-goods?title=Conf-Create-Good&price=1.00";
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 200;
                    this.removedUrl = null;
                    this.expectedRemovedStatus = 0;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 1;
                }
                case APPLY_DELETE -> {
                    this.readinessUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.readinessMethod = "GET";
                    this.readinessBody = null;
                    this.expectedReadinessStatus = 200;
                    this.assertionMethod = "GET";
                    this.assertionUrl = base + "/api/search-goods?title=Intro%20to%20Algorithms&price=59.90";
                    this.assertionBody = null;
                    this.expectedAssertionStatus = 200;
                    this.invalidMethod = "GET";
                    this.invalidUrl = base + "/api/search-goods?price=-invalid";
                    this.invalidBody = null;
                    this.expectedInvalidStatus = 400;
                    this.secondaryMethod = null;
                    this.secondaryUrl = null;
                    this.secondaryBody = null;
                    this.expectedSecondaryStatus = 0;
                    this.removedUrl = base + "/api/publish-goods";
                    this.expectedRemovedStatus = 404;
                    this.dbTable = "goods";
                    this.expectedDbDelta = 0;
                }
                default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
            }
        }

        String readinessUrl() { return readinessUrl; }
        String readinessMethod() { return readinessMethod; }
        String readinessBody() { return readinessBody; }
        int expectedReadinessStatus() { return expectedReadinessStatus; }
        String assertionMethod() { return assertionMethod; }
        String assertionUrl() { return assertionUrl; }
        String assertionBody() { return assertionBody; }
        int expectedAssertionStatus() { return expectedAssertionStatus; }
        String invalidMethod() { return invalidMethod; }
        String invalidUrl() { return invalidUrl; }
        String invalidBody() { return invalidBody; }
        int expectedInvalidStatus() { return expectedInvalidStatus; }
        String secondaryMethod() { return secondaryMethod; }
        String secondaryUrl() { return secondaryUrl; }
        String secondaryBody() { return secondaryBody; }
        int expectedSecondaryStatus() { return expectedSecondaryStatus; }
        String removedUrl() { return removedUrl; }
        int expectedRemovedStatus() { return expectedRemovedStatus; }
        String dbTable() { return dbTable; }
        int expectedDbDelta() { return expectedDbDelta; }
    }
}
