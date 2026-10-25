package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A created row gets its identity when it is persisted, so reading the identity earlier copies null into the database
 * or the response. The legal source persists every row before its identity is used; each counterexample moves one
 * persist (or widens one view) and must be refused by exactly this rule.
 */
class IdentityAfterPersistSemanticsTest {
    private static final String FIXTURE = "valid/identity-after-persist.sir";

    @Test
    void theLegalSourceAnalyzesClean() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void readingAUuidIdentityBeforeItsPersistIsRefusedButOtherFieldsAreNot() {
        // The receipt also reads ticket.note before the persist; only ticket.code is reported.
        SemanticAnalysis result = TestSources.analyze(replace(
                "        persist ticket;\n        create Receipt as receipt { ticketCode: ticket.code; note: ticket.note; }\n",
                "        create Receipt as receipt { ticketCode: ticket.code; note: ticket.note; }\n        persist ticket;\n"));

        assertEquals(List.of("SIR-FLOW-007"), TestSources.errorCodes(result), () -> result.diagnostics().toString());
    }

    @Test
    void bindingAnUnsavedRowAsAReferenceInsideALoopIsRefused() {
        SemanticAnalysis result = TestSources.analyze(replace(
                "        persist basket;\n        repeat",
                "        repeat").replace("        return basket;", "        persist basket;\n        return basket;"));

        assertEquals(List.of("SIR-FLOW-007"), TestSources.errorCodes(result), () -> result.diagnostics().toString());
    }

    @Test
    void returningAnUnsavedRowThroughAViewThatShowsItsIdentityIsRefused() {
        SemanticAnalysis result = TestSources.analyze(replace(
                "view ReceiptView from Receipt { field ticketCode",
                "view ReceiptView from Receipt { field id: Id; field ticketCode"));

        assertEquals(List.of("SIR-FLOW-007"), TestSources.errorCodes(result), () -> result.diagnostics().toString());
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "fixture fragment missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
