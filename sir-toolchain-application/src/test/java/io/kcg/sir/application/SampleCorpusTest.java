package io.kcg.sir.application;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pins the corpus of single-file software samples: every valid one compiles through generation, every invalid one stops at its stage. */
class SampleCorpusTest {
    static final List<String> VALID = List.of(
        "campus-market.sir", "campus-market-candidate.sir", "campus-market-actorless-readonly-base.sir",
        "campus-market-candidate-modify-input-field-constraints.sir", "campus-market-minimal.sir",
        "campus-market-minimal-add-search-goods.sir", "campus-market-two-capabilities.sir",
        "campus-market-two-capabilities-remove-publish-goods.sir", "course-admin.sir", "course-catalog.sir",
        "course-enrollment.sir", "course-admin-enrollment.sir", "course-admin-enrollment-filter-any.sir",
        "course-admin-enrollment-tighten.sir", "course-admin-enrollment-remove-update.sir",
        "course-admin-enrollment-readonly.sir", "course-admin-enrollment-add-list-refs.sir",
        "fault-matrix-dirs-base.sir", "fault-matrix-dirs-candidate.sir", "rename-course-search.sir");
    static String resource(String name) throws Exception {
        try (var in = SampleCorpusTest.class.getResourceAsStream("/" + name)) {
            return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test void allTwentyExistingFixturesCompileThroughGeneration() throws Exception {
        for (String file : VALID) {
            var diagnostics = new ArrayList<ExecutionDiagnostic>();
            var snapshot = ProjectCompilation.compile(ProjectTestSupport.oneFile("sample.sir", resource("valid/" + file)),
                new SpringBootGenerator()::generate, diagnostics);
            assertTrue(snapshot.isPresent(), () -> file + ": " + diagnostics);
            assertFalse(snapshot.orElseThrow().generatedFiles().isEmpty());
            assertFalse(diagnostics.stream().anyMatch(ExecutionDiagnostic::isError));
            System.out.println("CORPUS " + file + " files=" + snapshot.orElseThrow().generatedFiles().size());
        }
    }
    @Test void existingInvalidStagesArePinned() throws Exception {
        for (var item : List.of("semantic-unresolved-name.sir:SEMANTIC", "actor-non-identity.sir:LOWERING")) {
            var parts = item.split(":"); var diagnostics = new ArrayList<ExecutionDiagnostic>();
            assertTrue(ProjectCompilation.compile(ProjectTestSupport.oneFile("sample.sir", resource("invalid/" + parts[0])),
                new SpringBootGenerator()::generate, diagnostics).isEmpty());
            assertEquals(parts[1], diagnostics.stream().filter(ExecutionDiagnostic::isError).findFirst().orElseThrow().stage().name());
            System.out.println("Q23_INVALID " + parts[0] + " " + diagnostics);
        }
    }
}
