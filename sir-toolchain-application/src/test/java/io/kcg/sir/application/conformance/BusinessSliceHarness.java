package io.kcg.sir.application.conformance;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * The slice lifecycle one business conformance test needs: an owned schema, a compiled project, a
 * running application, and an evidence report.
 *
 * <p>Everything here is harness machinery. The schema and its rows are <em>test fixtures</em>: the
 * generated application has no INITIALIZE or UPDATE capability, so it can neither create tables nor
 * seed data, and no assertion built on this class may claim otherwise. Each report says so explicitly.
 *
 * <p>A slice is opt-in. Without a complete reference environment {@link Environment#read} reports the
 * missing prerequisites, the test prints {@code NOT_RUN}, and nothing is touched: no database is
 * created, no directory is allocated, no process is started.
 */
final class BusinessSliceHarness {

    /**
     * The fixture-specific facts of a slice.
     *
     * @param label            the slice name used in console lines and the report title
     * @param sirResource      the single-file SIR program under verification
     * @param ddlResource      the fixture DDL applied into the owned schema
     * @param seedResource     the fixture seed applied into the owned schema
     * @param table            the table the assertions read through JDBC
     * @param readinessPath    the route polled until the application answers
     * @param reportFileName   the evidence file name inside the run's evidence directory
     * @param runTokenPrefix   the prefix of the run directory name
     * @param enabledProperty  the system property that opts this slice in
     */
    record Fixture(
            String label,
            String sirResource,
            String ddlResource,
            String seedResource,
            String table,
            String readinessPath,
            String reportFileName,
            String runTokenPrefix,
            String enabledProperty
    ) {
    }

    /**
     * The reference environment, resolved once per test class.
     *
     * <p>The runtime JDBC URL is derived from the control endpoint and the validated schema name, never
     * supplied directly, so a mistyped schema cannot produce a run against the wrong database.
     */
    record Environment(
            boolean enabled,
            boolean mavenOffline,
            Path workParent,
            Path evidenceParent,
            Path mavenExecutable,
            Path mavenRepo,
            SchemaName schemaName,
            String controlJdbcUrl,
            String controlUsername,
            String controlPassword,
            String runtimeUsername,
            String runtimePassword,
            int serverPort,
            List<String> missingPrerequisites
    ) {
        boolean complete() {
            return this.missingPrerequisites.isEmpty();
        }

        static Environment read(
                Function<String, String> property,
                Function<String, String> environment,
                String enabledProperty
        ) {
            boolean enabled = "true".equalsIgnoreCase(property.apply(enabledProperty) == null
                    ? "false" : property.apply(enabledProperty));
            boolean mavenOffline = !"false".equalsIgnoreCase(
                    property.apply("kcg.query-conformance.maven-offline") == null
                            ? "true" : property.apply("kcg.query-conformance.maven-offline"));
            Path workParent = absolutePathOrNull(property.apply("kcg.conformance.work-parent"));
            Path evidenceParent = absolutePathOrNull(property.apply("kcg.conformance.evidence-parent"));
            Path mavenExecutable = absolutePathOrNull(property.apply("kcg.conformance.maven-executable"));
            Path mavenRepo = absolutePathOrNull(property.apply("kcg.conformance.maven-repo"));
            SchemaName schemaName = resolveSchemaName(property);
            int serverPort = 18080;
            String serverPortValue = property.apply("kcg.conformance.server-port");
            if (serverPortValue != null && !serverPortValue.isBlank()) {
                try {
                    serverPort = Integer.parseInt(serverPortValue.trim(), 10);
                } catch (NumberFormatException ignored) {
                    serverPort = 18080;
                }
            }

            String controlJdbcUrl = environment.apply("KCG_CONF_CONTROL_JDBC_URL");
            String controlUsername = environment.apply("KCG_CONF_CONTROL_USERNAME");
            String controlPassword = environment.apply("KCG_CONF_CONTROL_PASSWORD");
            String runtimeUsername = environment.apply("KCG_CONF_RUNTIME_USERNAME");
            String runtimePassword = environment.apply("KCG_CONF_RUNTIME_PASSWORD");
            List<String> missing = new ArrayList<>();
            if (!enabled) {
                missing.add(enabledProperty + "=true");
            }

            if (workParent == null) {
                missing.add("kcg.conformance.work-parent (absolute)");
            }

            if (evidenceParent == null) {
                missing.add("kcg.conformance.evidence-parent (absolute)");
            }

            if (mavenExecutable == null) {
                missing.add("kcg.conformance.maven-executable (absolute)");
            }

            if (mavenRepo == null) {
                missing.add("kcg.conformance.maven-repo (absolute)");
            }

            if (schemaName == null) {
                missing.add("kcg.query-conformance.schema-name or KCG_CONF_SCHEMA_NAME (kcg_conf_*)");
            }

            if (isBlank(controlJdbcUrl)) {
                missing.add("KCG_CONF_CONTROL_JDBC_URL");
            }

            if (isBlank(controlUsername)) {
                missing.add("KCG_CONF_CONTROL_USERNAME");
            }

            if (controlPassword == null || controlPassword.isEmpty()) {
                missing.add("KCG_CONF_CONTROL_PASSWORD");
            }

            if (isBlank(runtimeUsername)) {
                missing.add("KCG_CONF_RUNTIME_USERNAME");
            }

            if (runtimePassword == null || runtimePassword.isEmpty()) {
                missing.add("KCG_CONF_RUNTIME_PASSWORD");
            }

            return new Environment(enabled, mavenOffline, workParent, evidenceParent, mavenExecutable, mavenRepo,
                    schemaName, controlJdbcUrl, controlUsername, controlPassword, runtimeUsername, runtimePassword,
                    serverPort, List.copyOf(missing));
        }
    }

    private static final long READINESS_TIMEOUT_MS = 120_000L;
    private static final long READINESS_POLL_MS = 500L;
    private static final long SPRING_GRACE_MS = 30_000L;

    private final Fixture fixture;
    private final Environment environment;
    private final String runToken;
    private final Path workRoot;
    private final Path evidenceRoot;
    private final Path projectRoot;
    private final SchemaSqlRenderer renderer = new SchemaSqlRenderer();
    private final List<String> passedChecks = new ArrayList<>();
    private final List<String> failedChecks = new ArrayList<>();
    private final StringBuilder httpEvidence = new StringBuilder();
    private final StringBuilder reportLines = new StringBuilder();

    private StreamingSecretRedactor redactor;
    private List<String> secrets = List.of();
    private Connection control;
    private boolean lockAcquired;
    private String lockName;
    private SpringApplicationProcess springProcess;
    private Process process;
    private boolean schemaCreated;
    private String failure;
    private String notRunReason;
    private boolean lockHeldDuringRun;
    private String sirDigest;
    private String ddlDigest;
    private String seedDigest;
    private int generatedFileCount;
    private String generatedDigest;
    private String buildStatus = "NOT_RUN";
    private String httpStatus = "NOT_RUN";
    private String readinessDiagnostic;
    private List<String> rowsBefore = List.of();
    private List<String> rowsAfter = List.of();

    BusinessSliceHarness(Fixture fixture, Environment environment) {
        this.fixture = fixture;
        this.environment = environment;
        this.runToken = fixture.runTokenPrefix() + "-" + Long.toHexString(System.currentTimeMillis())
                + "-" + ThreadLocalRandom.current().nextInt(0x10000);
        this.workRoot = environment.workParent().resolve(this.runToken);
        this.evidenceRoot = environment.evidenceParent().resolve(this.runToken);
        this.projectRoot = this.workRoot.resolve("generated-project");
    }

    Path evidenceRoot() {
        return this.evidenceRoot;
    }

    Process process() {
        return this.process;
    }

    String readinessUrl() {
        return baseUrl(this.environment.serverPort()) + this.fixture.readinessPath();
    }

    void setRowsBefore(List<String> rows) {
        this.rowsBefore = List.copyOf(rows);
    }

    void setRowsAfter(List<String> rows) {
        this.rowsAfter = List.copyOf(rows);
    }

    void setFailure(String failure) {
        this.failure = failure;
    }

    void setNotRunReason(String reason) {
        this.notRunReason = reason;
    }

    String failure() {
        return this.failure;
    }

    String notRunReason() {
        return this.notRunReason;
    }

    void setReadinessDiagnostic(String diagnostic) {
        this.readinessDiagnostic = diagnostic;
    }

    void setBuildStatus(String status) {
        this.buildStatus = status;
    }

    void setHttpStatus(String status) {
        this.httpStatus = status;
    }

    /** Adds a run-specific line to the report, before the check list. */
    void reportLine(String line) {
        this.reportLines.append(line).append('\n');
    }

    void check(String what, boolean ok, String observed) {
        (ok ? this.passedChecks : this.failedChecks).add((ok ? "PASS " : "FAIL ") + what + " (" + observed + ")");
    }

    boolean expectStatus(String what, HttpAssertionClient.Response response, int expected) {
        boolean ok = response.statusCode() == expected;
        this.check(what, ok, "expected=" + expected + " actual=" + response.statusCode());
        return ok;
    }

    void expectField(String what, HttpAssertionClient.Response response, String name, String expected) {
        this.expectFields(what, response, Map.of(name, expected));
    }

    void expectFields(String what, HttpAssertionClient.Response response, Map<String, String> expected) {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actual = field(response.body(), entry.getKey());
            if (!actual.equals(entry.getValue())) {
                mismatches.add(entry.getKey() + "=" + actual + " (expected " + entry.getValue() + ")");
            }
        }

        this.check(what, mismatches.isEmpty(), mismatches.isEmpty() ? expected.toString() : mismatches.toString());
    }

    void expectRecords(String what, HttpAssertionClient.Response response, List<String> expected) {
        List<String> actual = records(response.body());
        this.check(what, actual.equals(expected), "records=" + actual);
    }

    void record(String label, HttpAssertionClient.Response response) {
        this.httpEvidence.append(label).append(": status=").append(response.statusCode())
                .append(" body=").append(response.body()).append('\n');
    }

    /**
     * Compiles the slice and materializes the generated project.
     *
     * @return the generated files, or an empty list when compilation failed
     */
    List<GeneratedFile> compileAndMaterialize() throws IOException {
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        Optional<SirCompilation.CompilationSnapshot> compiled = SirCompilation.compile(
                ConformanceFixtures.readResource(this.fixture.sirResource()),
                SourceId.of(this.fixture.sirResource().substring(this.fixture.sirResource().lastIndexOf('/') + 1)),
                new SpringBootGenerator()::generate, diagnostics);
        this.check("the SIR slice compiles with no diagnostics",
                compiled.isPresent() && diagnostics.isEmpty(), "diagnostics=" + diagnostics);
        if (compiled.isEmpty()) {
            this.failure = "the SIR slice did not compile";
            return List.of();
        }

        List<GeneratedFile> files = compiled.get().generatedFiles();
        this.generatedFileCount = files.size();
        this.generatedDigest = digestOfGeneratedFiles(files);
        this.check("the compiler emitted a generated project", this.generatedFileCount > 0,
                "generatedFileCount=" + this.generatedFileCount);
        this.materialize(files);
        return files;
    }

    /** Creates the owned schema, applies the fixture DDL and seed, and digests the inputs. */
    void prepareSchemaAndFixtures() throws SQLException, IOException {
        this.control = openControl();
        if (!this.acquireAdvisoryLock()) {
            this.notRunReason = "another conformance run holds the advisory lock for schema "
                    + this.environment.schemaName().value() + "; the schema was not touched";
            return;
        }

        this.createSchema();
        this.applyFixture(this.fixture.ddlResource());
        this.applyFixture(this.fixture.seedResource());
        this.sirDigest = ConformanceFixtures.resourceDigest(this.fixture.sirResource());
        this.ddlDigest = ConformanceFixtures.resourceDigest(this.fixture.ddlResource());
        this.seedDigest = ConformanceFixtures.resourceDigest(this.fixture.seedResource());
    }

    /** Connects to the owned schema as the runtime user and takes this run's advisory lock. */
    MysqlRuntimeJdbcUrl runtimeUrl() {
        return MysqlRuntimeJdbcUrl.fromControlEndpoint(this.environment.controlJdbcUrl(), this.environment.schemaName());
    }

    void initializeRedaction(MysqlRuntimeJdbcUrl runtimeUrl) {
        this.secrets = SecretCatalog.build(
                        this.environment.controlUsername(),
                        this.environment.controlPassword(),
                        this.environment.runtimeUsername(),
                        this.environment.runtimePassword(),
                        this.environment.controlJdbcUrl(),
                        runtimeUrl.rendered())
                .allSecretStrings();
        this.redactor = StreamingSecretRedactor.initialize(this.secrets);
    }

    /**
     * Builds the generated project with Maven.
     *
     * @return the executable jar, or {@code null} when the build failed
     */
    Path buildAndLocateJar(MysqlRuntimeJdbcUrl runtimeUrl) throws IOException, InterruptedException {
        ChildEnvironmentBuilder childEnvironment = new ChildEnvironmentBuilder()
                .copyOsAllowlist()
                .mavenRepoLocal(this.environment.mavenRepo().toString())
                .deterministicLocaleTimezone()
                .serverPort(this.environment.serverPort())
                .datasourceUrl(runtimeUrl)
                .datasourceUsername(this.environment.runtimeUsername())
                .datasourcePassword(this.environment.runtimePassword());
        Map<String, String> childEnv = childEnvironment.build();
        this.springProcess = new SpringApplicationProcess(
                Path.of(System.getProperty("java.home"), "bin", "java"), childEnv, this.redactor);
        MavenProjectRunner mavenRunner = new MavenProjectRunner(
                this.environment.mavenExecutable(), this.environment.mavenRepo(), childEnv, this.redactor);
        MavenProjectRunner.BuildResult build = this.runMavenBuild(mavenRunner);
        this.buildStatus = build.isSuccess() ? "PASSED (exit 0)" : "FAILED (exit " + build.exitCode() + ")";
        this.check("the generated project builds (mvn clean verify)", build.isSuccess(),
                "exitCode=" + build.exitCode());
        if (!build.isSuccess()) {
            this.failure = "the generated project did not build";
            return null;
        }

        Path jar = findExecutableJar(this.projectRoot);
        this.check("the build produced an executable jar", jar != null, "projectRoot=" + this.projectRoot);
        if (jar == null) {
            this.failure = "the build produced no executable jar";
        }

        return jar;
    }

    /** Starts the application and waits until the slice's own route answers. */
    boolean startAndWaitUntilReady(Path jar) throws IOException {
        this.start(jar);
        boolean ready = new BusinessEndpointReadiness(200).waitUntilReadyGet(
                this.readinessUrl(), this.process, READINESS_TIMEOUT_MS, READINESS_POLL_MS);
        if (!ready) {
            this.readinessDiagnostic = ReadinessDiagnostic.capture(this, new HttpAssertionClient(), this.readinessUrl());
        }

        this.check("the generated application serves the compiled business route", ready,
                "readinessUrl=" + this.readinessUrl());
        if (!ready) {
            this.failure = "the generated application never became ready";
        }

        return ready;
    }

    /** Opens the fixture's table through an independent JDBC connection. */
    MysqlObserver openObserver(MysqlRuntimeJdbcUrl runtimeUrl) throws SQLException {
        return MysqlObserver.open(runtimeUrl.rendered(),
                this.environment.runtimeUsername(), this.environment.runtimePassword());
    }

    List<String> rowsNow(MysqlObserver observer) throws SQLException {
        return observer.queryRows(this.environment.schemaName(), this.fixture.table(), "id");
    }

    String baseUrl() {
        return baseUrl(this.environment.serverPort());
    }

    List<String> rowsBefore() {
        return this.rowsBefore;
    }

    List<String> rowsAfter() {
        return this.rowsAfter;
    }

    long rowsBeforeSize() {
        return this.rowsBefore.size();
    }

    long rowsAfterSize() {
        return this.rowsAfter.size();
    }

    /**
     * Asserts one row's full column fingerprint, read through an independent connection.
     *
     * <p>A fingerprint rather than a per-column read is what makes "wrote nothing" a real assertion: a
     * wrong implementation that wrote a column it should not have cannot satisfy it.
     */
    void expectRow(String what, MysqlObserver observer, long id, String expected) throws SQLException {
        List<String> rows = this.rowsNow(observer);
        String actual = rows.stream().filter(row -> row.startsWith(id + "|")).findFirst().orElse("<absent>");
        this.check(what, actual.equals(expected), "row=" + actual);
    }

    boolean passed() {
        return this.failure == null && this.failedChecks.isEmpty();
    }

    private void materialize(List<GeneratedFile> files) throws IOException {
        for (GeneratedFile file : files) {
            Path target = this.projectRoot.resolve(file.relativePath()).normalize();
            if (!target.startsWith(this.projectRoot)) {
                throw new IllegalStateException("generated path escaped the project root: " + file.relativePath());
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
    }

    private MavenProjectRunner.BuildResult runMavenBuild(MavenProjectRunner runner)
            throws IOException, InterruptedException {
        Path logs = Files.createDirectories(this.evidenceRoot.resolve("logs"));
        try (OutputStream out = Files.newOutputStream(logs.resolve("maven.stdout.log"));
             OutputStream err = Files.newOutputStream(logs.resolve("maven.stderr.log"))) {
            return runner.cleanVerify(this.projectRoot, this.environment.mavenOffline(), out, err);
        }
    }

    private void start(Path jar) throws IOException {
        Path logs = Files.createDirectories(this.evidenceRoot.resolve("logs"));
        this.process = this.springProcess.start(
                jar,
                this.environment.serverPort(),
                Files.newOutputStream(logs.resolve("spring.stdout.log")),
                Files.newOutputStream(logs.resolve("spring.stderr.log")));
    }

    /**
     * Takes the harness's own advisory lock for this schema.
     *
     * <p>The lock name is derived with {@link AdvisoryLockKey} from the server's own UUID and the exact
     * schema name, so a second run against the same schema is refused instead of interleaving schema
     * creation and DROP with this run.
     */
    private boolean acquireAdvisoryLock() {
        try {
            this.lockName = AdvisoryLockKey.derive(this.serverUuid(), this.environment.schemaName()).lockName();
            try (PreparedStatement statement = this.control.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                statement.setString(1, this.lockName);
                statement.setInt(2, 0);
                try (ResultSet rows = statement.executeQuery()) {
                    this.lockAcquired = rows.next() && rows.getInt(1) == 1;
                }
            }
        } catch (SQLException e) {
            this.lockAcquired = false;
        }

        this.lockHeldDuringRun = this.lockAcquired;
        return this.lockAcquired;
    }

    private String serverUuid() throws SQLException {
        try (Statement statement = this.control.createStatement();
             ResultSet rows = statement.executeQuery("SELECT @@server_uuid")) {
            if (!rows.next()) {
                throw new SQLException("SELECT @@server_uuid returned no row");
            }

            return rows.getString(1);
        }
    }

    private void releaseAdvisoryLock() {
        if (!this.lockAcquired) {
            return;
        }

        try (PreparedStatement statement = this.control.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, this.lockName);
            try (ResultSet rows = statement.executeQuery()) {
                boolean released = rows.next() && rows.getInt(1) == 1;
                this.check("the advisory lock for the owned schema was released", released, "released=" + released);
            }
        } catch (SQLException e) {
            this.check("the advisory lock for the owned schema was released", false, describe(e));
        } finally {
            this.lockAcquired = false;
        }
    }

    /** Stops the application and drops the owned schema, recording both outcomes as checks. */
    void teardown() {
        if (this.springProcess != null && this.process != null) {
            try {
                boolean stopped = this.springProcess.stop(SPRING_GRACE_MS);
                this.check("the generated application process exited", stopped, "stopped=" + stopped);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.check("the generated application process exited", false, describe(e));
            }

            this.settleLogs();
        }

        if (this.schemaCreated) {
            try {
                this.dropSchemaAndProveAbsence();
                this.check("the owned schema was dropped and its absence proved", true,
                        "schema=" + this.environment.schemaName().value());
            } catch (SQLException e) {
                this.check("the owned schema was dropped and its absence proved", false, describe(e));
            }
        }

        if (this.control != null) {
            this.releaseAdvisoryLock();
            try {
                this.control.close();
            } catch (SQLException ignored) {
                // Best effort: the lock is released on connection close in any case.
            }

            this.control = null;
        }
    }

    String verdict() {
        if (this.notRunReason != null) {
            return "NOT_RUN";
        }

        return this.passed() ? "PASSED" : "FAILED";
    }

    String report() {
        StringBuilder report = new StringBuilder();
        report.append("=== ").append(this.fixture.label()).append(" business conformance ===\n");
        report.append("verdict=").append(this.verdict()).append('\n');
        report.append("runId=").append(this.runToken).append('\n');
        report.append("schemaName=").append(this.environment.schemaName().value()).append('\n');
        report.append("advisoryLock=").append(this.lockHeldDuringRun ? "held during the run" : "not held").append('\n');
        report.append("schemaSource=TEST_FIXTURE_DDL\n");
        report.append("productInitializeImplemented=false\n");
        report.append("productUpdateImplemented=false\n");
        report.append("sirResource=").append(this.fixture.sirResource()).append(" sha256=").append(this.sirDigest).append('\n');
        report.append("fixtureDdl=").append(this.fixture.ddlResource()).append(" sha256=").append(this.ddlDigest).append('\n');
        report.append("fixtureSeed=").append(this.fixture.seedResource()).append(" sha256=").append(this.seedDigest).append('\n');
        report.append("generatedFiles=").append(this.generatedFileCount)
                .append(" combinedSha256=").append(this.generatedDigest).append('\n');
        report.append("generatedProjectBuild=").append(this.buildStatus).append('\n');
        report.append("httpReadiness=").append(this.httpStatus).append('\n');
        if (this.readinessDiagnostic != null) {
            report.append("readinessDiagnostic=").append(this.readinessDiagnostic).append('\n');
        }

        report.append("mysqlRowsBefore=").append(this.rowsBefore.size())
                .append(" mysqlRowsAfter=").append(this.rowsAfter.size()).append('\n');
        report.append("mavenOffline=").append(this.environment.mavenOffline()).append('\n');
        report.append("javaVersion=").append(System.getProperty("java.version")).append('\n');
        report.append("osName=").append(System.getProperty("os.name"))
                .append(" osArch=").append(System.getProperty("os.arch")).append('\n');
        report.append("serverPort=").append(this.environment.serverPort()).append('\n');
        if (this.reportLines.length() > 0) {
            report.append(this.reportLines);
        }

        report.append('\n');
        report.append("checksPassed=").append(this.passedChecks.size())
                .append(" checksFailed=").append(this.failedChecks.size()).append('\n');
        for (String line : this.passedChecks) {
            report.append(line).append('\n');
        }

        for (String line : this.failedChecks) {
            report.append(line).append('\n');
        }

        if (this.httpEvidence.length() > 0) {
            report.append('\n').append(this.httpEvidence);
        }

        if (this.failure != null) {
            report.append("\nfailure=").append(this.failure).append('\n');
        }

        if (this.notRunReason != null) {
            report.append("\nnotRunReason=").append(this.notRunReason).append('\n');
        }

        return report.toString();
    }

    /**
     * The application's log streams are flushed by the harness's pump threads when the process's pipes
     * close, and those threads are daemons. Waiting a bounded moment for the log files to stop growing
     * is what keeps the captured output from being lost when the test JVM exits.
     */
    private void settleLogs() {
        Path stdout = this.evidenceRoot.resolve("logs/spring.stdout.log");
        long deadline = System.currentTimeMillis() + 5_000L;
        long lastSize = -1L;
        while (System.currentTimeMillis() < deadline) {
            long size = sizeOf(stdout);
            if (size > 0 && size == lastSize) {
                return;
            }

            lastSize = size;
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Writes the report, redacted, and keeps a failed run's working directory for inspection. */
    void writeReport() {
        try {
            Files.createDirectories(this.evidenceRoot);
            byte[] bytes = this.report().getBytes(StandardCharsets.UTF_8);
            byte[] redacted = this.redactor == null ? bytes : this.redactor.redactBytes(bytes);
            Files.write(this.evidenceRoot.resolve(this.fixture.reportFileName()), redacted);
        } catch (IOException e) {
            // Evidence writing must not mask the verification result: the report is also printed.
            System.out.println("[CONFORMANCE] evidence could not be written: " + describe(e));
        } finally {
            // A failed run keeps its work directory so the generated project, the Maven logs, and the
            // application logs can be inspected; a passing run removes it.
            if (this.passed()) {
                deleteRecursively(this.workRoot);
            } else {
                System.out.println("[CONFORMANCE] work directory retained for inspection: " + this.workRoot);
            }
        }
    }

    // ---------------------------------------------------------------- schema lifecycle (fixtures)

    private void createSchema() throws SQLException {
        try (Statement statement = this.control.createStatement()) {
            statement.executeUpdate(this.renderer.createDatabase(this.environment.schemaName()));

            // The fixture DDL and seed are written without a schema qualifier, so the control session
            // is pointed at the schema this run owns before either is applied.
            statement.execute("USE `" + this.environment.schemaName().value() + "`");
        }

        this.schemaCreated = true;
    }

    private void applyFixture(String resource) throws SQLException, IOException {
        String sql = stripSqlComments(ConformanceFixtures.readResource(resource));
        for (String statementText : sql.split(";")) {
            String trimmed = statementText.strip();
            if (trimmed.isEmpty()) {
                continue;
            }

            try (Statement statement = this.control.createStatement()) {
                statement.execute(trimmed);
            }
        }
    }

    private void dropSchemaAndProveAbsence() throws SQLException {
        try (Statement statement = this.control.createStatement()) {
            statement.executeUpdate(this.renderer.dropDatabase(this.environment.schemaName()));
        }

        try (PreparedStatement statement = this.control.prepareStatement(this.renderer.informationSchemaAbsenceSql())) {
            statement.setString(1, this.environment.schemaName().value());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw new SQLException(
                            "schema still present after DROP: " + this.environment.schemaName().value());
                }
            }
        }
    }

    private Connection openControl() throws SQLException {
        return DriverManager.getConnection(
                this.environment.controlJdbcUrl(), jdbcProperties(
                        this.environment.controlUsername(), this.environment.controlPassword()));
    }

    // ---------------------------------------------------------------- shared helpers

    static Path findExecutableJar(Path projectRoot) throws IOException {
        Path target = projectRoot.resolve("target");
        if (!Files.isDirectory(target)) {
            return null;
        }

        try (var stream = Files.list(target)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
                    .findFirst()
                    .orElse(null);
        }
    }

    static String digestOfGeneratedFiles(List<GeneratedFile> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (GeneratedFile file : files) {
                digest.update(file.relativePath().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.content().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }

        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a retained artifact is not a verification failure.
                }
            }
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /** The value of one JSON field of a flat object, or an empty string when it is absent. */
    static String field(String body, String name) {
        String needle = "\"" + name + "\"";
        int keyIndex = body.indexOf(needle);
        if (keyIndex < 0) {
            return "";
        }

        int colonIndex = body.indexOf(':', keyIndex + needle.length());
        if (colonIndex < 0) {
            return "";
        }

        int start = colonIndex + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }

        if (start >= body.length()) {
            return "";
        }

        char first = body.charAt(start);
        if (first == '"') {
            int end = start + 1;
            while (end < body.length() && body.charAt(end) != '"') {
                if (body.charAt(end) == '\\') {
                    end++;
                }

                end++;
            }

            return body.substring(start + 1, Math.min(end, body.length()));
        }

        int end = start;
        while (end < body.length() && body.charAt(end) != ',' && body.charAt(end) != '}' && body.charAt(end) != ']') {
            end++;
        }

        return body.substring(start, end).strip();
    }

    /**
     * The records of a paged response as {@code code|name|capacity} triples, in the order served.
     *
     * <p>Deliberately a small hand-rolled reader over the response text: it cross-checks the serialized
     * body rather than trusting a mapping the harness would have to share with the application.
     */
    static List<String> records(String body) {
        List<String> records = new ArrayList<>();
        int recordsKey = body.indexOf("\"records\"");
        if (recordsKey < 0) {
            return records;
        }

        int index = body.indexOf('[', recordsKey);
        if (index < 0) {
            return records;
        }

        int depth = 0;
        int recordStart = -1;
        for (int position = index; position < body.length(); position++) {
            char current = body.charAt(position);
            if (current == '{') {
                if (depth == 0) {
                    recordStart = position;
                }

                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0 && recordStart >= 0) {
                    String object = body.substring(recordStart, position + 1);
                    records.add(field(object, "code") + "|" + field(object, "name") + "|" + field(object, "capacity"));
                    recordStart = -1;
                }
            } else if (current == ']' && depth == 0) {
                break;
            }
        }

        return records;
    }

    static String baseUrl(int serverPort) {
        return "http://127.0.0.1:" + serverPort;
    }

    private static Properties jdbcProperties(String username, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("sslMode", "DISABLED");
        properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("connectTimeout", "10000");
        return properties;
    }

    static String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--")) {
                continue;
            }

            out.append(line).append('\n');
        }

        return out.toString();
    }

    static Path absolutePathOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        Path path = Path.of(value.trim());
        return path.isAbsolute() ? path : null;
    }

    static SchemaName parseSchemaNameOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return SchemaName.parse(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SchemaName resolveSchemaName(Function<String, String> property) {
        SchemaName fromProperty = parseSchemaNameOrNull(property.apply("kcg.query-conformance.schema-name"));
        if (fromProperty != null) {
            return fromProperty;
        }

        return parseSchemaNameOrNull(System.getenv("KCG_CONF_SCHEMA_NAME"));
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String describe(Throwable error) {
        StringBuilder text = new StringBuilder(error.getClass().getName());
        if (error.getMessage() != null) {
            text.append(": ").append(error.getMessage());
        }

        Throwable cause = error.getCause();
        while (cause != null) {
            text.append(" <- ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                text.append(": ").append(cause.getMessage());
            }

            cause = cause.getCause();
        }

        return text.toString();
    }

    static long sizeOf(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** What the readiness route answered when it never became ready, captured for the evidence. */
    private static final class ReadinessDiagnostic {

        private ReadinessDiagnostic() {
        }

        static String capture(BusinessSliceHarness slice, HttpAssertionClient http, String url) {
            String processState = slice.process() == null
                    ? "process=null"
                    : "processAlive=" + slice.process().isAlive();
            String logSizes = "springStdoutBytes=" + sizeOf(slice.evidenceRoot().resolve("logs/spring.stdout.log"))
                    + " springStderrBytes=" + sizeOf(slice.evidenceRoot().resolve("logs/spring.stderr.log"));
            try {
                HttpAssertionClient.Response response = http.get(url, Map.of());
                return processState + " " + logSizes + " status=" + response.statusCode() + " body=" + response.body();
            } catch (IOException | InterruptedException e) {
                return processState + " " + logSizes + " unreachable=" + describe(e);
            }
        }
    }
}
