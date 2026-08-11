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
            harnessVersion = HarnessJdbcVersion.readFromConnection(control.rawConnection());
        } catch (RuntimeException e) {
            failScenario(run, scenario, "JDBC_HARNESS_VERSION_UNREADABLE",
                    "harness JDBC driver version could not be read: " + sanitizedMessage(e));
            return false;
        }
        JdbcVersionInspection.Result result = JdbcVersionInspection.inspect(
                loweredModel,
                loweredSourceKind,
                candidateSirSha256,
                b1BaselineId,
                outputRoot.resolve("pom.xml"),
                harnessVersion,
                evidenceWriter,
                scenario.displayName());
        if (!result.passed()) {
            failScenario(run, scenario, result.messageKey(), result.detail());
            return false;
        }
        return true;
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
        // TODO(conformance): complete runtime cleanup and terminal verification.
        }
    }
