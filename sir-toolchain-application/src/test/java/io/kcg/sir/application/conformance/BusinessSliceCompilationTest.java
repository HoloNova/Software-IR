package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The actual IT compiler must retain nonblocking warnings without accepting errors or losing files. */
class BusinessSliceCompilationTest {
    @TempDir Path root;

    @Test void theRealReadBeforeLockFixtureStillCompilesAndKeepsItsWarningVisible() throws Exception {
        var harness = harness(MultiplicationBusinessConformanceIT.FIXTURE);

        var files = harness.compileAndMaterialize();

        assertFalse(files.isEmpty());
        assertTrue(harness.passed(), harness.report());
        assertTrue(harness.report().contains("SIR-WARN-001"), harness.report());
        assertTrue(harness.report().contains("severity=WARNING"), harness.report());
        assertTrue(harness.report().contains("sourceSpan=Optional[SourceSpan["), harness.report());
        String service = files.stream().filter(f -> f.relativePath().endsWith("/BookAfterReadService.java"))
                .findFirst().orElseThrow().content();
        assertTrue(service.contains("selectById("), "keep the deliberate pre-lock read");
        int lock = service.indexOf(".last(\"FOR UPDATE\")");
        assertTrue(lock > service.indexOf("selectById("), "ordinary entities lock through their query wrapper");
        int preliminaryCheck = service.indexOf("boolean beforeLock = ");
        assertTrue(preliminaryCheck >= 0 && preliminaryCheck < lock && service.indexOf("boolean taken = ") > lock,
                "keep both the pre-lock read and the definitive post-lock recheck: " + service);
        for (var file : files) {
            assertArrayEquals(file.content().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(harness.projectRoot().resolve(file.relativePath())), file.relativePath());
        }
        assertTrue(harness.report().contains("generatedProjectBuild=NOT_RUN"));
        assertTrue(harness.report().contains("httpReadiness=NOT_RUN"));
    }

    @ParameterizedTest @MethodSource("ordinaryFixtures")
    void ordinarySharedHarnessConsumersStillMaterialize(BusinessSliceHarness.Fixture fixture) throws Exception {
        var harness = harness(fixture);
        assertFalse(harness.compileAndMaterialize().isEmpty());
        assertTrue(harness.passed(), harness.report());
        assertFalse(harness.report().contains("severity=WARNING"), harness.report());
    }

    static Stream<BusinessSliceHarness.Fixture> ordinaryFixtures() {
        return Stream.of(MoneyBusinessConformanceIT.FIXTURE, LibraryBorrowingBusinessConformanceIT.FIXTURE,
                CourseSelectionBusinessConformanceIT.FIXTURE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"semantic-unresolved-name.sir", "lowering-unsupported-any.sir"})
    void actualErrorsStillFailAndNeverMaterializeAProject(String resource) throws Exception {
        var harness = harness(new BusinessSliceHarness.Fixture("invalid compile", "/invalid/" + resource,
                "/conformance/mysql/money-ddl.sql", "/conformance/mysql/money-seed.sql", "wallet",
                "/unused", "invalid-report.txt", "invalid", "kcg.invalid-compile.enabled"));

        assertTrue(harness.compileAndMaterialize().isEmpty());
        assertFalse(harness.passed(), harness.report());
        assertEquals("FAILED", harness.verdict());
        assertTrue(harness.report().contains("severity=ERROR"), harness.report());
        assertFalse(Files.exists(harness.projectRoot()), "failed source must not leave a generated project");
    }

    private BusinessSliceHarness harness(BusinessSliceHarness.Fixture fixture) {
        return new BusinessSliceHarness(fixture, BusinessSliceHarnessEvidenceTest.environment(
                root.resolve("work"), root.resolve("evidence"), fixture.enabledProperty()));
    }
}
