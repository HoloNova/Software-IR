package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of a column changed by an amount. The legal source is asserted clean; each counterexample replaces one
 * named fragment of it and must be refused by exactly the rule under test.
 */
class AtomicCounterSemanticsTest {
    private static final String FIXTURE = "valid/atomic-counter.sir";
    private static final String TAKE = "update course when course.enrolled < course.capacity else CourseFull {\n          enrolled: course.enrolled + 1;\n        }";
    private static final String LEAVE = "update course {\n          enrolled: course.enrolled - input.seats;\n        }";

    @Test
    void theLegalSourceAnalyzesClean() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void theGuardMayCompareTwoColumnsButNotAColumnWithAnExpressionOnTheRow() {
        assertOnly(replace("course.enrolled < course.capacity", "course.enrolled < course.capacity + 1"));
    }

    @Test
    void theAmountMustNotReadTheRowUnderAGuard() {
        assertOnly(replace(TAKE, TAKE.replace("course.enrolled + 1", "course.enrolled + course.capacity")));
    }

    @Test
    void aGuardedUpdateStillCannotReadItsRowInOtherWays() {
        assertOnly(replace(TAKE, TAKE.replace("course.enrolled + 1", "course.capacity + 1")));
    }

    @Test
    void aRowCreatedByTheWorkflowHasNothingStoredToChange() {
        assertOnly(replace("load Course by input.id as course else CourseNotFound;\n        update course {\n          enrolled: course.enrolled - input.seats;\n        }\n        persist course;",
                "create Course as course {\n          name: \"x\";\n          capacity: 1;\n        }\n        update course {\n          enrolled: course.enrolled - input.seats;\n        }\n        persist course;"));
    }

    @Test
    void aVersionedEntityIsAlreadyProtectedByItsVersion() {
        // A versioned entity is refused other things for an update without a change set, so only this rule is looked for.
        SemanticAnalysis result = TestSources.analyze(replace("field fee: Money default 0.00;", "field fee: Money default 0.00;\n      field version: Version;"));

        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().value().equals("SIR-VALID-007")
                && diagnostic.message().contains("version")), () -> result.diagnostics().toString());
    }

    @Test
    void theColumnMustBeAnInteger() {
        assertOnly(replace(LEAVE, "update course {\n          fee: course.fee - 1.00;\n        }"));
    }

    @Test
    void theRowHasOneStatementSoItHasOneUpdate() {
        assertOnly(replace(LEAVE, "update course {\n          enrolled: course.enrolled - input.seats;\n        }\n        update course {\n          name: course.name;\n        }"));
    }

    private static void assertOnly(String source) {
        SemanticAnalysis result = TestSources.analyze(source);

        assertTrue(TestSources.hasError(result, "SIR-VALID-007"), () -> result.diagnostics().toString());
        assertEquals(List.of("SIR-VALID-007"), TestSources.errorCodes(result).stream().distinct().toList(), () -> result.diagnostics().toString());
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "the fragment to replace is missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
