package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.CandidateVerificationApplication;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.SirValidationApplication;
import io.kcg.sir.application.api.SirValidationRequest;
import io.kcg.sir.application.api.StageOutcome;
import io.kcg.sir.application.api.StageStatus;
import io.kcg.sir.application.api.VerificationPorts;
import io.kcg.sir.application.api.VerificationReport;
import io.kcg.sir.application.api.VerificationRequest;
import io.kcg.sir.application.api.VerificationStage;
import io.kcg.sir.application.execution.MavenProjectBuilder;
import io.kcg.sir.application.execution.SpringBootApplicationHost;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The independent verification against the real reference environment: a real Maven build, a real Spring Boot
 * process, a real MySQL schema owned by the run, and a business oracle written from the original requirement.
 *
 * <p>Opt-in ({@code -Dkcg.verification.enabled=true} plus the reference environment of the other conformance
 * tests). Without it the test says NOT_RUN and does nothing. It proves that the four levels are separate and that
 * each can be reached or fail on its own:
 * the correct sample passes all four, a static-valid sample that forgets the status filter passes the first three and
 * fails only the business level, a candidate whose Java does not compile fails the build level and nothing after it
 * runs, and an unreachable database is NOT_RUN rather than a failure of the candidate.
 */
class IndependentVerificationIT {
    private static final String ENABLED = "kcg.verification.enabled";
    private static final String GOOD = "/valid/course-enrollment.sir";
    private static final String BAD = "/conformance/slm/course-enrollment-missing-status.sir";
    private static final String DDL = "/conformance/mysql/course-enrollment-ddl.sql";
    private static final String SEED = "/conformance/mysql/course-enrollment-seed.sql";
    private static final String READINESS = CourseEnrollmentOracle.ROUTE + "?page=1&size=10";

    private final StringBuilder reportText = new StringBuilder();

    private static ResourceLimits limits() {
        return new ResourceLimits(Duration.ofMinutes(20), Duration.ofMinutes(3), Duration.ofMinutes(5), 256, 256, 128, 8L * 1024 * 1024);
    }

    private BusinessSliceHarness.Environment environment() {
        return BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, ENABLED);
    }

    private CandidateVerificationApplication application(BusinessSliceHarness.Environment env, MysqlVerificationSandbox sandbox,
            VerificationPorts.Builder builder) {
        List<String> secrets = sandbox.secrets();
        return new CandidateVerificationApplication(sandbox, builder,
                new SpringBootApplicationHost(Path.of(System.getProperty("java.home"), "bin", "java"), env.serverPort(), READINESS, secrets),
                new CourseEnrollmentOracle(sandbox), limits());
    }

    private MavenProjectBuilder maven(BusinessSliceHarness.Environment env, MysqlVerificationSandbox sandbox) {
        return new MavenProjectBuilder(env.mavenExecutable(), env.mavenRepo(), env.mavenOffline(), Optional.empty(), sandbox.secrets());
    }

    private VerificationReport verify(CandidateVerificationApplication app, String id, String resource, VerificationStage through) throws IOException {
        var report = app.verify(new VerificationRequest(id, io.kcg.sir.application.MultiSourceTestSupport.oneFile(id + ".sir", ConformanceFixtures.readResource(resource)), through));
        reportText.append(report.canonicalText()).append("reportSha256=").append(report.sha256Hex()).append("\n\n");
        System.out.println(report.canonicalText());
        return report;
    }

    private void writeReport(BusinessSliceHarness.Environment env, String name) throws IOException {
        Files.createDirectories(env.evidenceParent());
        Files.writeString(env.evidenceParent().resolve("verification-" + name + "-report.txt"), reportText.toString(), StandardCharsets.UTF_8);
    }

    private static void assertLevels(VerificationReport report, StageStatus... expected) {
        assertEquals(List.of(expected), report.stages().stream().map(s -> s.status()).toList(), report::canonicalText);
    }

    private static void assertNothingLeft(VerificationReport report) {
        assertTrue(report.cleanup().proved(), "cleanup must be proved: " + report.cleanup().checks());
    }

    @Test void theCorrectSamplePassesAllFourLevelsAndTheStaticButWrongSampleFailsOnlyTheBusinessLevel() throws Exception {
        var env = environment();
        if (!env.enabled() || !env.complete()) {
            System.out.println("[INDEPENDENT-VERIFICATION] NOT_RUN: " + env.missingPrerequisites());
            return;
        }

        try {
            var sandbox = new MysqlVerificationSandbox(env, DDL, SEED, "verify-both");
            var app = application(env, sandbox, maven(env, sandbox));

            var correct = verify(app, "correct", GOOD, VerificationStage.BUSINESS);
            assertLevels(correct, StageStatus.PASSED, StageStatus.PASSED, StageStatus.PASSED, StageStatus.PASSED);
            assertNothingLeft(correct);
            var checked = new SirValidationApplication().check(new SirValidationRequest("correct", ConformanceFixtures.readResource(GOOD)));
            assertEquals(checked.digest(), correct.binding().orElseThrow().generatedDigest(), "the same generated project the static checker approves");
            assertEquals(CourseEnrollmentOracle.REVISION, correct.environment().get("oracle.revision"));
            assertNotNull(correct.environment().get("sandbox.database.version"));
            assertEquals(1, correct.stage(VerificationStage.BUILD).outcome().logs().size());
            assertEquals(1, correct.stage(VerificationStage.START).outcome().logs().size());
            assertEquals(1, correct.stage(VerificationStage.BUSINESS).outcome().logs().size());

            var wrong = verify(app, "missing-status", BAD, VerificationStage.BUSINESS);
            assertLevels(wrong, StageStatus.PASSED, StageStatus.PASSED, StageStatus.PASSED, StageStatus.FAILED);
            assertEquals("BUSINESS_ASSERTION_FAILED", wrong.stage(VerificationStage.BUSINESS).outcome().code());
            assertTrue(wrong.stage(VerificationStage.BUSINESS).outcome().diagnostics().stream().anyMatch(d -> d.contains("ART101") && d.contains("ENG101")),
                    "the concrete leak of cancelled-only courses must be named: " + wrong.stage(VerificationStage.BUSINESS).outcome().diagnostics());
            assertNothingLeft(wrong);
            assertNotEquals(correct.binding().orElseThrow().generatedDigest(), wrong.binding().orElseThrow().generatedDigest());
        } finally {
            writeReport(env, "samples");
        }
    }

    /** Generates the project like the real builder would see it, then breaks one Java file: a legal SIR with a controlled generation fault. */
    private static final class CorruptingBuilder implements VerificationPorts.Builder {
        private final VerificationPorts.Builder delegate;

        CorruptingBuilder(VerificationPorts.Builder delegate) {
            this.delegate = delegate;
        }

        @Override
        public VerificationPorts.BuildOutcome build(Path projectRoot, Path evidenceRoot, ResourceLimits limits) {
            try (Stream<Path> files = Files.walk(projectRoot.resolve("src/main/java"))) {
                Path victim = files.filter(p -> p.toString().endsWith(".java")).sorted().findFirst().orElseThrow();
                Files.writeString(victim, "\nthis is not java;\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }

            return delegate.build(projectRoot, evidenceRoot, limits);
        }

        @Override
        public java.util.Map<String, String> environment() {
            return delegate.environment();
        }
    }

    @Test void aCandidateWhoseJavaDoesNotCompileFailsTheBuildLevelAndNothingAfterItRuns() throws Exception {
        var env = environment();
        if (!env.enabled() || !env.complete()) {
            System.out.println("[INDEPENDENT-VERIFICATION] NOT_RUN: " + env.missingPrerequisites());
            return;
        }

        try {
            var sandbox = new MysqlVerificationSandbox(env, DDL, SEED, "verify-build-fault");
            var app = application(env, sandbox, new CorruptingBuilder(maven(env, sandbox)));

            var report = verify(app, "broken-build", GOOD, VerificationStage.BUSINESS);

            assertLevels(report, StageStatus.PASSED, StageStatus.FAILED, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
            assertEquals("BUILD_EXIT_NONZERO", report.stage(VerificationStage.BUILD).outcome().code());
            assertTrue(report.stage(VerificationStage.BUILD).outcome().diagnostics().stream().anyMatch(d -> d.contains("COMPILATION ERROR")),
                    "the failure must be the Java compiler rejecting the corrupted file, not an unrelated build problem: "
                            + report.stage(VerificationStage.BUILD).outcome().diagnostics());
            assertEquals("PREVIOUS_STAGE_NOT_PASSED", report.stage(VerificationStage.START).outcome().code());
            assertNothingLeft(report);
        } finally {
            writeReport(env, "build-fault");
        }
    }

    /** A builder that only claims success, so the unreachable-database case does not pay for a real build. */
    private static final class StubBuilder implements VerificationPorts.Builder {
        @Override
        public VerificationPorts.BuildOutcome build(Path projectRoot, Path evidenceRoot, ResourceLimits limits) {
            try {
                Files.createDirectories(projectRoot.resolve("target"));
                Path jar = Files.writeString(projectRoot.resolve("target/app.jar"), "stub");
                return new VerificationPorts.BuildOutcome(StageOutcome.passed("stub build"), Optional.of(jar));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public java.util.Map<String, String> environment() {
            return java.util.Map.of("builder", "stub");
        }
    }

    @Test void anUnreachableDatabaseIsNotRunAndIsNeverReportedAsTheCandidateFailing() throws Exception {
        var env = environment();
        if (!env.enabled() || !env.complete()) {
            System.out.println("[INDEPENDENT-VERIFICATION] NOT_RUN: " + env.missingPrerequisites());
            return;
        }

        try {
            var unreachable = new BusinessSliceHarness.Environment(env.enabled(), env.mavenOffline(), env.workParent(), env.evidenceParent(),
                    env.mavenExecutable(), env.mavenRepo(), env.schemaName(), "jdbc:mysql://127.0.0.1:1/", env.controlUsername(), env.controlPassword(),
                    env.runtimeUsername(), env.runtimePassword(), env.serverPort(), List.of());
            var sandbox = new MysqlVerificationSandbox(unreachable, DDL, SEED, "verify-no-database");
            var app = application(unreachable, sandbox, new StubBuilder());

            var report = verify(app, "no-database", GOOD, VerificationStage.BUSINESS);

            assertLevels(report, StageStatus.PASSED, StageStatus.PASSED, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
            assertEquals("DATABASE_UNREACHABLE", report.stage(VerificationStage.START).outcome().code());
            assertNothingLeft(report);
        } finally {
            writeReport(env, "no-database");
        }
    }
}
