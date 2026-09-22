package io.kcg.sir.application.conformance;

import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/**
 * Real implementation of {@link RuntimeOps} that delegates to the existing
 * static methods ({@link JdbcVersionInspection}, {@link HarnessJdbcVersion})
 * and injected runners ({@link MavenProjectRunner}, {@link SpringApplicationProcess}).
 *
 * <p>This class is package-private and used by the default
 * {@link ConformanceSuite} constructor. Tests inject fakes instead.
 */
final class RealRuntimeOps implements RuntimeOps {

    @Override
    public boolean inspectTargetDependency(ControlSession control,
                                           ConformanceRun run,
                                           ConformanceScenario scenario,
                                           Optional<SpringBootLoweredModel> loweredModel,
                                           String loweredSourceKind,
                                           Optional<String> candidateSirSha256,
                                           Optional<String> b1BaselineId,
                                           Path outputRoot,
                                           EvidenceWriter evidenceWriter) {
        String harnessVersion;
        try {
            harnessVersion = readHarnessJdbcDriverVersion(control.rawConnection());
        } catch (RuntimeException e) {
            failScenario(run, scenario, "JDBC_HARNESS_VERSION_UNREADABLE",
                    "harness JDBC driver version could not be read: " + sanitizedMessage(e));
            return false;
        }
        if (loweredModel.isEmpty()) {
            failScenario(run, scenario, "TARGET_DEPENDENCY_MODEL_MISSING",
                    "no lowered model is available for source kind " + loweredSourceKind
                            + ", so the target Connector/J version cannot be inspected");
            return false;
        }
        TargetDependencyInspector inspection;
        try {
            inspection = TargetDependencyInspector.inspect(loweredModel.get(),
                    outputRoot.resolve("pom.xml"), harnessVersion);
        } catch (IOException e) {
            failScenario(run, scenario, "TARGET_DEPENDENCY_INSPECTION_IO_ERROR",
                    "cannot read the generated pom.xml: " + sanitizedMessage(e));
            return false;
        }
        writeInspectionEvidence(evidenceWriter, scenario, loweredSourceKind,
                candidateSirSha256, b1BaselineId, inspection);
        if (!inspection.versionsAgree()) {
            failScenario(run, scenario, "TARGET_DEPENDENCY_VERSION_MISMATCH",
                    "target Connector/J=" + inspection.targetRuntimeConnectorVersion()
                            + " generated pom=" + inspection.generatedPomConnectorVersion()
                            + " harness driver=" + inspection.harnessJdbcDriverVersion());
            return false;
        }
        return true;
    }

    /**
     * Read the JDBC driver version of the connection the harness itself opened.
     *
     * <p>This is the "harness driver version" the target dependency inspection
     * records independently, so the evidence distinguishes the driver the run
     * actually used from the version the generated project declares.
     *
     * @param connection the control connection
     * @return the driver version string
     * @throws IllegalStateException if the metadata cannot be read
     */
    private static String readHarnessJdbcDriverVersion(java.sql.Connection connection) {
        try {
            return connection.getMetaData().getDriverVersion();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read JDBC driver version", e);
        }
    }

    /**
     * Record the target dependency inspection as evidence.
     *
     * <p>Best effort by design: the evidence tree is reconciled separately (the
     * ownership inventory plus the secret scanner require every registered file to
     * exist and the scan fails closed otherwise), and a write failure must not replace
     * the inspection verdict with an I/O exception.
     */
    private static void writeInspectionEvidence(EvidenceWriter evidenceWriter,
                                                ConformanceScenario scenario,
                                                String loweredSourceKind,
                                                Optional<String> candidateSirSha256,
                                                Optional<String> b1BaselineId,
                                                TargetDependencyInspector inspection) {
        String content = "sourceKind=" + loweredSourceKind
                + " candidateSirSha256=" + candidateSirSha256.orElse("NONE")
                + " b1BaselineId=" + b1BaselineId.orElse("NONE")
                + " targetRuntimeConnectorVersion="
                + inspection.targetRuntimeConnectorVersion()
                + " generatedPomConnectorVersion="
                + inspection.generatedPomConnectorVersion()
                + " harnessJdbcDriverVersion=" + inspection.harnessJdbcDriverVersion()
                + " versionsAgree=" + inspection.versionsAgree()
                + "\n";
        try (OutputStream out = evidenceWriter.openStream(
                "scenarios/" + scenario.displayName() + "/target-dependency-inspection.txt")) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // See the method javadoc: the evidence scan is the gate for this.
        }
    }

    @Override
    public boolean buildProject(ConformanceSuiteContext ctx,
                                ConformanceRun run,
                                ConformanceScenario scenario,
                                Path outputRoot,
                                EvidenceWriter evidenceWriter)
            throws InterruptedException {
        try {
            String base = "scenarios/" + scenario.displayName();
            try (OutputStream out = evidenceWriter.openStream(base + "/maven.stdout.log");
                 OutputStream err = evidenceWriter.openStream(base + "/maven.stderr.log")) {
                MavenProjectRunner.BuildResult result = ctx.mavenRunner().cleanVerify(
                        outputRoot, false, out, err);
                if (!result.isSuccess()) {
                    failScenario(run, scenario, "BUILD_FAILED",
                            "Maven clean verify exit code: " + result.exitCode());
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            failScenario(run, scenario, "BUILD_IO_ERROR",
                    "Maven build I/O error: " + sanitizedMessage(e));
            return false;
        }
    }

    @Override
    public boolean startSpringAndWait(ConformanceSuiteContext ctx,
                                      ConformanceRun run,
                                      ConformanceScenario scenario,
                                      Path outputRoot,
                                      EvidenceWriter evidenceWriter) {
        Path jarPath = findBuiltJar(outputRoot);
        if (jarPath == null) {
            failScenario(run, scenario, "JAR_NOT_FOUND",
                    "executable jar not found under " + outputRoot + "/target");
            return false;
        }
        ConformanceSuite.ScenarioEndpoint endpoint = new ConformanceSuite.ScenarioEndpoint(scenario, ctx.serverPort());
        try {
            String base = "scenarios/" + scenario.displayName();
            Process process = ctx.springProcess().start(
                    jarPath, ctx.serverPort(),
                    evidenceWriter.openStream(base + "/spring.stdout.log"),
                    evidenceWriter.openStream(base + "/spring.stderr.log"));
            BusinessEndpointReadiness readiness = new BusinessEndpointReadiness(
                    endpoint.expectedReadinessStatus());
            boolean ready;
            if ("POST".equals(endpoint.readinessMethod())) {
                ready = readiness.waitUntilReadyPost(
                        endpoint.readinessUrl(), endpoint.readinessBody(),
                        Map.of(), process,
                        ctx.readinessTimeoutMs(), ctx.readinessPollMs());
            } else {
                ready = readiness.waitUntilReadyGet(
                        endpoint.readinessUrl(), process,
                        ctx.readinessTimeoutMs(), ctx.readinessPollMs());
            }
            if (!ready) {
                failScenario(run, scenario, "CONTEXT_NOT_READY",
                        "readiness probe failed for " + endpoint.readinessUrl());
                return false;
            }
            return true;
        } catch (IOException e) {
            failScenario(run, scenario, "SPRING_START_IO_ERROR",
                    "Spring start I/O error: " + sanitizedMessage(e));
            return false;
        }
    }

    @Override
    public boolean assertHttp(ConformanceSuiteContext ctx,
                              ConformanceRun run,
                              ConformanceScenario scenario,
                              EvidenceWriter evidenceWriter) {
        ConformanceSuite.ScenarioEndpoint endpoint = new ConformanceSuite.ScenarioEndpoint(scenario, ctx.serverPort());
        HttpAssertionClient http = new HttpAssertionClient();
        try {
            // HTTP_ASSERT
            HttpAssertionClient.Response response;
            if ("POST".equals(endpoint.assertionMethod())) {
                response = http.postJson(endpoint.assertionUrl(), Map.of(),
                        endpoint.assertionBody());
            } else {
                response = http.get(endpoint.assertionUrl(), Map.of());
            }
            if (response.statusCode() != endpoint.expectedAssertionStatus()) {
                failScenario(run, scenario, "HTTP_STATUS_UNEXPECTED",
                        "expected " + endpoint.expectedAssertionStatus()
                                + " got " + response.statusCode());
                return false;
            }

            // VALIDATION_ASSERT
            if (endpoint.invalidBody() != null && endpoint.invalidUrl() != null) {
                HttpAssertionClient.Response invalidResp;
                if ("POST".equals(endpoint.invalidMethod())) {
                    invalidResp = http.postJson(endpoint.invalidUrl(), Map.of(),
                            endpoint.invalidBody());
                } else {
                    invalidResp = http.get(endpoint.invalidUrl(), Map.of());
                }
                if (invalidResp.statusCode() != endpoint.expectedInvalidStatus()) {
                    failScenario(run, scenario, "VALIDATION_NOT_EXPECTED",
                            "invalid request expected " + endpoint.expectedInvalidStatus()
                                    + " got " + invalidResp.statusCode());
                    return false;
                }
            }

            // APPLY-DELETE: removed route must return 404.
            if (endpoint.removedUrl() != null) {
                HttpAssertionClient.Response removedResp = http.get(endpoint.removedUrl(), Map.of());
                if (removedResp.statusCode() != endpoint.expectedRemovedStatus()) {
                    failScenario(run, scenario, "REMOVED_ROUTE_NOT_404",
                            "removed route expected " + endpoint.expectedRemovedStatus()
                                    + " got " + removedResp.statusCode());
                    return false;
                }
            }

            // APPLY-CREATE: secondary route must succeed.
            if (endpoint.secondaryUrl() != null) {
                HttpAssertionClient.Response secondaryResp;
                if ("POST".equals(endpoint.secondaryMethod())) {
                    secondaryResp = http.postJson(endpoint.secondaryUrl(), Map.of(),
                            endpoint.secondaryBody());
                } else {
                    secondaryResp = http.get(endpoint.secondaryUrl(), Map.of());
                }
                if (secondaryResp.statusCode() != endpoint.expectedSecondaryStatus()) {
                    failScenario(run, scenario, "SECONDARY_ROUTE_NOT_EXPECTED",
                            "secondary route expected " + endpoint.expectedSecondaryStatus()
                                    + " got " + secondaryResp.statusCode());
                    return false;
                }
            }
            return true;
        } catch (IOException | InterruptedException e) {
            failScenario(run, scenario, "HTTP_IO_ERROR",
                    "HTTP assertion I/O error: " + sanitizedMessage(e));
            return false;
        }
    }

    @Override
    public int preQueryDbRowCount(ConformanceSuiteContext ctx,
                                  ConformanceScenario scenario,
                                  SchemaName schemaName) {
        ConformanceSuite.ScenarioEndpoint endpoint = new ConformanceSuite.ScenarioEndpoint(scenario, ctx.serverPort());
        if (endpoint.dbTable() == null) {
            return 0;
        }
        try (MysqlObserver observer = MysqlObserver.open(
                ctx.runtimeFixture().runtimeJdbcUrl(),
                ctx.runtimeFixture().runtimeUsername(),
                ctx.runtimeFixture().runtimePassword())) {
            return observer.countRows(schemaName, endpoint.dbTable());
        } catch (SQLException e) {
            return -1;
        }
    }

    @Override
    public boolean assertDbRowCount(ConformanceSuiteContext ctx,
                                    ConformanceRun run,
                                    ConformanceScenario scenario,
                                    SchemaName schemaName,
                                    int expectedDelta,
                                    int rowsBefore) {
        ConformanceSuite.ScenarioEndpoint endpoint = new ConformanceSuite.ScenarioEndpoint(scenario, ctx.serverPort());
        if (endpoint.dbTable() == null) {
            return true;
        }
        try (MysqlObserver observer = MysqlObserver.open(
                ctx.runtimeFixture().runtimeJdbcUrl(),
                ctx.runtimeFixture().runtimeUsername(),
                ctx.runtimeFixture().runtimePassword())) {
            int rowsAfter = observer.countRows(schemaName, endpoint.dbTable());
            int actualDelta = rowsAfter - rowsBefore;
            if (actualDelta != expectedDelta) {
                failScenario(run, scenario, "DB_DELTA_MISMATCH",
                        "table " + endpoint.dbTable() + " delta was " + actualDelta
                                + ", expected " + expectedDelta
                                + " (before=" + rowsBefore + " after=" + rowsAfter + ")");
                return false;
            }
            return true;
        } catch (SQLException e) {
            failScenario(run, scenario, "DB_QUERY_FAILED",
                    "DB assertion query failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Record a scenario failure on the run and return.
     *
     * <p>The call sites (existing code) pass only a stable key, so the failure kind
     * is derived from that key. The mapping keeps the taxonomy meaningful — a build
     * failure is reported as {@code BUILD}, not as a generic harness fault — and
     * anything unrecognised falls back to {@link ConformanceFailureKind#HARNESS}
     * rather than being silently mislabelled as a business failure.
     *
     * @param run      the run to record on
     * @param scenario the scenario being executed
     * @param key      stable machine-readable failure key
     * @param message  short explanation, already sanitized
     */
    private static void failScenario(ConformanceRun run, ConformanceScenario scenario,
                                     String key, String message) {
        run.recordFailure(new ConformanceFailure(kindFor(key), key,
                scenario.displayName() + ": " + message));
    }

    private static ConformanceFailureKind kindFor(String key) {
        return switch (key) {
            case "JAR_NOT_FOUND", "CONTEXT_NOT_READY", "SPRING_START_IO_ERROR" ->
                    ConformanceFailureKind.STARTUP;
            case "BUILD_FAILED", "BUILD_IO_ERROR" -> ConformanceFailureKind.BUILD;
            case "VALIDATION_NOT_EXPECTED" -> ConformanceFailureKind.VALIDATION;
            case "HTTP_STATUS_UNEXPECTED", "REMOVED_ROUTE_NOT_404",
                 "SECONDARY_ROUTE_NOT_EXPECTED", "HTTP_IO_ERROR" -> ConformanceFailureKind.HTTP;
            case "DB_DELTA_MISMATCH", "DB_QUERY_FAILED" -> ConformanceFailureKind.DATABASE;
            default -> ConformanceFailureKind.HARNESS;
        };
    }

    /**
     * Find the generated project's executable Spring Boot jar under
     * {@code outputRoot/target}.
     *
     * <p>Only a regular file ending in {@code .jar} that is not the Maven
     * {@code *-sources.jar} or {@code *.original} artifact qualifies; when several
     * candidates exist the lexicographically first is chosen so the result does not
     * depend on directory iteration order.
     *
     * @param outputRoot the generated project root
     * @return the jar path, or {@code null} when no candidate exists
     */
    private static Path findBuiltJar(Path outputRoot) {
        Path target = outputRoot.resolve("target");
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(target)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".jar")
                        && !name.endsWith("-sources.jar")
                        && !name.endsWith("-javadoc.jar")
                        && Files.isRegularFile(entry)) {
                    candidates.add(entry);
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return candidates.get(0);
    }

    /**
     * Reduce a throwable to a short, credential-free message.
     *
     * @param t the throwable
     * @return the message, truncated to 200 characters, or the class name when the
     *         throwable carries no message
     */
    private static String sanitizedMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
