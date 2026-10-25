package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Writing a row that points at an unlocked row before the row itself is changed deadlocks two concurrent requests on
 * the foreign key's shared lock. The legal source persists the counted row first; each counterexample changes one
 * fragment and must be refused by exactly this rule, or be accepted when the danger is gone.
 */
class PersistOrderSemanticsTest {
    private static final String FIXTURE = "valid/persist-order.sir";
    private static final String ORDER = "        persist course;\n        persist made else Taken;";

    @Test
    void theLegalSourceAnalyzesClean() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void persistingTheNewRowBeforeTheChangedRowIsRefused() {
        SemanticAnalysis result = TestSources.analyze(replace(ORDER, "        persist made else Taken;\n        persist course;"));

        assertEquals(List.of("SIR-VALID-015"), TestSources.errorCodes(result), () -> result.diagnostics().toString());
    }

    @Test
    void lockingTheRowWhereItIsLoadedMakesEitherOrderSafe() {
        String source = replace(ORDER, "        persist made else Taken;\n        persist course;")
                .replace("load Course by input.courseId as course else", "load Course by input.courseId as course for update else");
        SemanticAnalysis result = TestSources.analyze(source);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void aRowThatIsNeverChangedHasNothingToWaitFor() {
        String source = replace(ORDER, "        persist made else Taken;")
                .replace("        update course when course.enrolled < course.capacity else CourseFull {\n          enrolled: course.enrolled + 1;\n        }\n", "");
        SemanticAnalysis result = TestSources.analyze(source);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "fixture fragment missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
