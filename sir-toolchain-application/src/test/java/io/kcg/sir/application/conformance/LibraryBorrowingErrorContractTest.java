package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The IT's fixed expected codes must match the actual topic, not a generic uppercase-code convention. */
class LibraryBorrowingErrorContractTest {
    private static ProjectCompilation.Compiled compiled;

    @BeforeAll static void compileActualTopic() throws Exception {
        byte[] bytes;
        try (var input = LibraryBorrowingErrorContractTest.class.getResourceAsStream("/topics/library/project.sir")) {
            assertNotNull(input); bytes = input.readAllBytes();
        }
        var id = SourceId.of("project.sir");
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        compiled = ProjectCompilation.compile(new SourceSnapshot(id, Map.of(id, bytes)),
                new SpringBootGenerator()::generate, diagnostics).orElseThrow(() -> new AssertionError(diagnostics.toString()));
    }

    @ParameterizedTest
    @EnumSource(LibraryBorrowingChecks.Failure.class)
    void everyExpectedFailureIsAnActualDeclaredCodeWithItsDeclaredStatus(LibraryBorrowingChecks.Failure expected) {
        var declared = compiled.semanticModel().declarations().stream().filter(NormalizedError.class::isInstance)
                .map(NormalizedError.class::cast).filter(e -> e.name().equals(expected.code())).findFirst()
                .orElseThrow(() -> new AssertionError("IT expects an undeclared error code: " + expected.code()));
        assertEquals(declared.httpStatus(), expected.status());
        var exception = compiled.generatedFiles().stream()
                .filter(f -> f.relativePath().endsWith("/" + declared.name() + "Exception.java"))
                .findFirst().orElseThrow().content();
        assertTrue(exception.contains("super(\"" + expected.code() + "\", HttpStatus."), exception);
    }
}
