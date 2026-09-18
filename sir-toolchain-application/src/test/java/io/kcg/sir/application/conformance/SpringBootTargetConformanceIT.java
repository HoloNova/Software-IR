package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /** The local-fixed actor identity used for actor scenarios (User.id). */
    private static final long LOCAL_ACTOR_ID = 1L;

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
    // INTERIM (Q4 scaffold): real assembly is part of the merged Q4+Q5 work order.
    private ConformanceResult runFullConformanceSuite() {
        // Precondition (fail closed, before any schema or lock work): the runtime
        // datasource endpoint must be derivable from the control JDBC URL plus the
        // validated schema name. An endpoint that cannot be derived is a precondition
        // conflict, not a run failure — nothing has been created yet, so the run is
        // NOT_RUN and the caller can correct the environment and retry.
        MysqlRuntimeJdbcUrl runtimeUrl;
        try {
            runtimeUrl = MysqlRuntimeJdbcUrl.fromControlEndpoint(controlJdbcUrl, schemaName);
        } catch (RuntimeException e) {
            return new ConformanceResult.NotRun(new ConformanceFailure(
                    ConformanceFailureKind.PRECONDITION,
                    "RUNTIME_JDBC_URL_INVALID",
                    "the runtime datasource endpoint could not be derived from the control "
                            + "JDBC URL: " + sanitizedMessage(e)));
        }

        List<String> secrets;
        StreamingSecretRedactor redactor;
        EvidenceSecretScanner secretScanner;
        try {
            SecretCatalog catalog = SecretCatalog.build(controlUsername, controlPassword,
                    runtimeUsername, runtimePassword, controlJdbcUrl, runtimeUrl.rendered());
            secrets = catalog.allSecretStrings();
            redactor = StreamingSecretRedactor.initialize(secrets);
            secretScanner = EvidenceSecretScanner.from(catalog);
        } catch (RuntimeException e) {
            // No side effect has happened yet: the redactor/scanner boundary is a
            // precondition, so the run is NOT_RUN rather than FAILED.
            return new ConformanceResult.NotRun(new ConformanceFailure(
                    ConformanceFailureKind.HARNESS_CREDENTIAL_BOUNDARY,
                    "SECRET_BOUNDARY_NOT_INITIALIZABLE",
                    "the credential boundary could not be initialized: " + sanitizedMessage(e)));
        }

        java.nio.file.Path javaExecutable = java.nio.file.Path.of(
                System.getProperty("java.home"), "bin", "java");
        // The run token names the owned work root and evidence root. It is unique per
        // invocation (both roots are created with CREATE_NEW semantics, so a repeat run
        // must never reuse a previous run's directory).
        String runToken = "run-" + Long.toHexString(System.currentTimeMillis())
                + "-" + java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000);

        try (MysqlControlSession control = MysqlControlSession.open(
                controlJdbcUrl, controlUsername, controlPassword)) {
            ChildEnvironmentBuilder childEnvironment = new ChildEnvironmentBuilder()
                    .copyOsAllowlist()
                    .mavenRepoLocal(mavenRepo.toString())
                    .deterministicLocaleTimezone()
                    .serverPort(serverPort)
                    .datasourceUrl(runtimeUrl)
                    .datasourceUsername(runtimeUsername)
                    .datasourcePassword(runtimePassword)
                    .actorMode("local-fixed")
                    .actorLocalId(LOCAL_ACTOR_ID)
                    .springProfilesActive("kcg-actor-local");
            Map<String, String> childEnv = childEnvironment.build();
            MavenProjectRunner mavenRunner = new MavenProjectRunner(
                    mavenExecutable, mavenRepo, childEnv, redactor);
            SpringApplicationProcess springProcess = new SpringApplicationProcess(
                    javaExecutable, childEnv, redactor);
            String mavenVersion = mavenRunner.mavenVersion();

            ConformanceEnvironment environment = new ConformanceEnvironment(
                    CONTRACT_REVISION,
                    TARGET_PROFILE_ID,
                    LOWERED_IR_VERSION,
                    System.getProperty("java.vendor"),
                    System.getProperty("java.version"),
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"),
                    filesystemProvider(),
                    mavenExecutable,
                    mavenVersion,
                    mavenRepo,
                    true,
                    expectedMysqlServerUuid,
                    control.observedServerUuid(),
                    control.observedServerVersion(),
                    // Recorded per scenario from the real lowered model and generated POM.
                    null,
                    resolveHarnessJdbcDriverVersion(controlJdbcUrl),
                    schemaName,
                    workParent,
                    evidenceParent,
                    serverPort);

            ConformanceSuiteContext ctx = new ConformanceSuiteContext(
                    environment,
                    workParent,
                    evidenceParent,
                    runToken,
                    control,
                    new MysqlRuntimeFixture(runtimeUrl.rendered(), runtimeUsername, runtimePassword),
                    redactor,
                    secretScanner,
                    mavenRunner,
                    springProcess,
                    childEnvironment,
                    serverPort,
                    MAVEN_GRACE_MS,
                    SPRING_GRACE_MS,
                    READINESS_TIMEOUT_MS,
                    READINESS_POLL_MS,
                    secrets);
            return new ConformanceSuite().orchestrate(ctx);
        } catch (Exception e) {
            return new ConformanceResult.Failed(new ConformanceFailure(
                    ConformanceFailureKind.HARNESS,
                    "SUITE_ASSEMBLY_FAILED",
                    "the suite context could not be assembled or the run failed: "
                            + sanitizedMessage(e)), List.of(), java.util.Optional.empty());
        }
    }

    /**
     * The file-store type of the work parent, recorded in the environment tuple.
     *
     * <p>The tuple records the host filesystem provider because path identity and
     * symlink behavior are provider-specific, so the evidence must name the provider the
     * run actually used.
     *
     * @return the file store type, or {@code "unknown"} when it cannot be read
     */
    private static String filesystemProvider() {
        try {
            return java.nio.file.Files.getFileStore(workParent).type();
        } catch (java.io.IOException e) {
            return "unknown";
        }
    }

    /**
     * Resolve the harness JDBC driver version for a JDBC URL <em>without</em> opening
     * any connection.
     *
     * <p>The version is read from the driver that accepts the URL: the registered
     * driver's implementation version when its jar declares one, otherwise its
     * declared major/minor version. Driver identification goes through
     * {@link DriverManager#getDriver}, which only asks each registered driver whether
     * it {@code acceptsURL} — it never establishes a connection.
     *
     * <p>This is deliberate: a run must never open a connection just to learn a
     * version string, and a URL that no registered driver accepts must yield
     * {@code "unknown"} rather than a speculative value.
     *
     * @param jdbcUrl the JDBC URL whose accepting driver is inspected
     * @return the driver version, or {@code "unknown"} when it cannot be determined
     */
    private static String resolveHarnessJdbcDriverVersion(String jdbcUrl) {
        try {
            Driver driver = DriverManager.getDriver(jdbcUrl);
            Package driverPackage = driver.getClass().getPackage();
            String implementationVersion = driverPackage == null
                    ? null
                    : driverPackage.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.isBlank()) {
                return implementationVersion;
            }
            return driver.getMajorVersion() + "." + driver.getMinorVersion();
        } catch (SQLException | RuntimeException e) {
            return "unknown";
        }
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

}
