package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evidence side of a conformance run, exercised without a database, an application or a build.
 *
 * <p>CI #163 failed before the Money application was ever built, because the warning probe wrote into a
 * directory only created later, and the resulting report still claimed PASSED. Both properties are
 * asserted here against the real installer and the real report, not against a copy of their logic.
 */
class BusinessSliceHarnessEvidenceTest {
    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "evidence fixture", "/valid/money.sir", "/conformance/mysql/money-ddl.sql",
            "/conformance/mysql/money-seed.sql", "wallet", "/api/wallet/show?id=1",
            "evidence-report.txt", "evidence", "kcg.evidence-conformance.enabled");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ExactAggregateWarningFixture","GroupedSumFixture","CalendarSnapshotFixture"})
    void warningProbeIsWrittenWhenTheRunHasNotCreatedItsEvidenceDirectoryYet(String name,@TempDir Path root) throws Exception {
        var work = root.resolve("work-absent");
        var evidence = root.resolve("evidence-absent");
        assertFalse(Files.exists(evidence), "the fixture must start without the directory CI failed to create");
        var harness = harness(work, evidence);

        if (name.equals("CalendarSnapshotFixture")) CalendarLedgerChecks.install(harness);
        else if (name.equals("GroupedSumFixture")) GroupedSumChecks.install(harness);
        else DecimalSumChecks.installWarningFixture(harness);

        var copy = harness.evidenceRoot().resolve(name+".java.txt");
        assertTrue(harness.evidenceRoot().startsWith(evidence), harness.evidenceRoot().toString());
        assertTrue(Files.isRegularFile(copy), "the probe copy is part of the run evidence: " + copy);
        assertArrayEquals(Files.readAllBytes(Path.of("src/test/resources/conformance/mysql/"+name+".java.txt")), Files.readAllBytes(copy));
        assertTrue(Files.isRegularFile(harness.projectRoot().resolve("src/main/java/example/money/fixture/"+name+".java")));
    }

    @Test void anErrorBeforeTheFirstCheckCannotLeaveAReportClaimingSuccess(@TempDir Path root) {
        var harness = harness(root.resolve("work"), root.resolve("evidence"));
        var thrown = assertThrows(IllegalStateException.class,
                () -> harness.verify(() -> { throw new IllegalStateException("probe could not be installed"); }));

        assertEquals("probe could not be installed", thrown.getMessage());
        assertFalse(harness.passed(), "an aborted run verified nothing");
        assertEquals("FAILED", harness.verdict());
        assertTrue(harness.report().contains("unexpected error before the run completed"), harness.report());
        assertTrue(harness.report().contains("probe could not be installed"), harness.report());
    }

    @Test void aCompletedBodyIsStillReportedAsSuccess(@TempDir Path root) throws Exception {
        var harness = harness(root.resolve("work"), root.resolve("evidence"));
        harness.check("one real check", true, "observed");

        harness.verify(() -> { });

        assertTrue(harness.passed(), harness.report());
        assertEquals("PASSED", harness.verdict());
    }

    static BusinessSliceHarness harness(Path work, Path evidence) {
        return new BusinessSliceHarness(FIXTURE, environment(work, evidence, FIXTURE.enabledProperty()));
    }

    static BusinessSliceHarness.Environment environment(Path work, Path evidence, String enabledProperty) {
        Map<String, String> properties = Map.of(
                enabledProperty, "true",
                "kcg.conformance.work-parent", work.toAbsolutePath().toString(),
                "kcg.conformance.evidence-parent", evidence.toAbsolutePath().toString(),
                "kcg.conformance.maven-executable", work.resolve("mvn").toAbsolutePath().toString(),
                "kcg.conformance.maven-repo", work.resolve("m2").toAbsolutePath().toString(),
                "kcg.query-conformance.schema-name", "kcg_conf_evidence");
        Map<String, String> variables = Map.of(
                "KCG_CONF_CONTROL_JDBC_URL", "jdbc:mysql://127.0.0.1:3306/",
                "KCG_CONF_CONTROL_USERNAME", "control",
                "KCG_CONF_CONTROL_PASSWORD", "control",
                "KCG_CONF_RUNTIME_USERNAME", "runtime",
                "KCG_CONF_RUNTIME_PASSWORD", "runtime");
        var environment = BusinessSliceHarness.Environment.read(properties::get, variables::get, enabledProperty);
        assertTrue(environment.complete(), environment.missingPrerequisites()::toString);
        return environment;
    }
}
