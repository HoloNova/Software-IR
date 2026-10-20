package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OptionalValueSemanticsTest {
    private static final String FIXTURE = "valid/optional-values.sir";

    @Test
    void nullTestsComparisonsOptionalFiltersAndGuardsAnalyzeCleanly() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"==", "!=", "<", "<=", ">", ">="})
    void comparisonsAcceptCompatibleOptionalAndRequiredValues(String operator) {
        SemanticAnalysis result = TestSources.analyze(source().replace("row.score != input.minimum", "row.score " + operator + " 1"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"row.score is none", "row.score is present", "not (row.score is none)", "(row.score) is none"})
    void nullTestsComposeWithExistingBooleanExpressions(String condition) {
        SemanticAnalysis result = TestSources.analyze(source().replace("row.score != input.minimum", condition));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"row.id is none", "row.score == row.dueAt", "row.score + 1 > 0", "row.dueAt + 1 days < now()"})
    void invalidNullTestsIncompatibleTypesAndOptionalArithmeticRemainTypeErrors(String condition) {
        SemanticAnalysis result = TestSources.analyze(source().replace("row.score != input.minimum", condition));
        assertEquals(List.of("SIR-TYPE-001"), TestSources.errorCodes(result).stream().distinct().toList(),
                () -> result.diagnostics().toString());
    }

    @Test
    void optionalReferencesCompareByEntityIdentityButCannotBeDereferenced() {
        String references = source()
                .replace("    entity Entry persistent", "    entity User persistent { identity id: Id generated auto; }\n    entity Entry persistent")
                .replace("      field score: Optional<Count>;", "      field score: Optional<Count>;\n      field owner: Optional<Ref<User>>;")
                .replace("    capability Check {", "    capability Check {\n      actor Ref<User>;")
                .replace("      expose command at \"/entries/check\";", "      requires authenticated;\n      expose command at \"/entries/check\";")
                .replace("row.score != input.minimum", "row.owner == actor");
        SemanticAnalysis valid = TestSources.analyze(references);
        assertTrue(valid.isSuccess(), () -> valid.diagnostics().toString());
        SemanticAnalysis invalid = TestSources.analyze(references.replace("row.owner == actor", "row.owner.id == input.id"));
        assertEquals(List.of("SIR-TYPE-001"), TestSources.errorCodes(invalid).stream().distinct().toList());
    }

    private static String source() {
        return TestSources.resource(FIXTURE);
    }
}
