package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code avg} is the same step as {@code sum} with a different measurement; it must obey the same rules. */
class AverageSemanticsTest {
    private static final String FIXTURE = "valid/decimal-average.sir";

    @Test
    void theLegalSourceAnalyzesClean() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void anAverageOfANonDecimalFieldIsRefused() {
        assertOnly(replace("avg Entry.amount where", "avg Entry.quantity where"), "SIR-TYPE-001");
    }

    @Test
    void aGroupedAverageStillNeedsAReferenceKey() {
        assertOnly(replace("avg Entry.amount by category", "avg Entry.amount by occurredAt"), "SIR-TYPE-001");
    }

    private static void assertOnly(String source, String code) {
        SemanticAnalysis result = TestSources.analyze(source);
        assertEquals(List.of(code), TestSources.errorCodes(result).stream().distinct().toList(), () -> result.diagnostics().toString());
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "fixture fragment missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
