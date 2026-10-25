package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What one pass of a bounded loop may do: create, delete, count and accumulate into an outer row, but not overwrite
 * one. The legal source is asserted clean; each counterexample replaces one named fragment and must be refused by
 * exactly the rule under test.
 */
class LoopBodySemanticsTest {
    private static final String FIXTURE = "valid/cart-checkout.sir";
    private static final String ACCUMULATE = "update purchase {\n            total: purchase.total + product.price * entry.quantity;\n          }";

    @Test
    void theLegalSourceAnalyzesClean() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void anOuterRowCannotBeOverwrittenByEveryPass() {
        assertOnly(replace(ACCUMULATE, "update purchase {\n            total: product.price;\n          }"), "SIR-FLOW-004");
    }

    @Test
    void anOuterRowCannotBeDeletedByAPass() {
        // purchase was created, not read, so the general delete rule refuses it as well; the loop rule must be among them.
        String source = replace("          delete entry;\n        }\n        return purchase;",
                "          delete entry;\n          delete purchase;\n        }\n        return purchase;");
        assertTrue(TestSources.errorCodes(TestSources.analyze(source)).contains("SIR-FLOW-004"), source);
    }

    @Test
    void aCreatedRowCanOnlyBeAccumulatedIntoAfterItHasBeenStored() {
        // Moving the first persist after the loop leaves nothing stored to add to when the loop runs.
        String source = replace("        persist purchase;\n        each CartItem", "        each CartItem")
                .replace("        return purchase;\n      }\n    }\n    capability ClearCart", "        persist purchase;\n        return purchase;\n      }\n    }\n    capability ClearCart");

        assertTrue(TestSources.errorCodes(TestSources.analyze(source)).contains("SIR-VALID-007"), source);
    }

    @Test
    void aNestedLoopIsStillRefused() {
        assertOnly(replace("          delete entry;\n        }\n        return unit;",
                "          each CartItem where item.owner == actor order by id ascending limit 2 else CartTooLarge as other {\n"
                        + "            delete other;\n          }\n        }\n        return unit;"), "SIR-FLOW-004");
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
