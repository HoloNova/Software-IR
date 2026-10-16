package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How wide an integer literal is, and which comparisons a find predicate can carry. Both rules exist
 * because the failure used to appear only when the generated project was compiled or generated: an
 * Int64 field could not be compared with {@code 0}, and a literal too large for its type was written
 * into the Java source unchanged.
 */
class IntegerWidthSemanticsTest {
    private static final String TEMPLATE = """
            sir 0.2
            software Width {
              metadata { displayName "Width"; namespace "example.width"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus; database mysql; build maven; interface rest; }
              sources { }
              imports { }
              declarations {
                entity Item persistent {
                  identity id: Int64 generated auto;
                  field qty: Int64;
                  field n: Int32;
                }
                view ItemView from Item { field id: Int64; field qty: Int64; }
                input In { field amount: Int64; field small: Int32; }
                input PageIn { field page: Int32; field size: Int32; field min: Int64; }
                error Bad;
                capability Make {
                  input In; output ItemView; fails Bad; requires atomic; expose command;
                  workflow {
                    %s
                    create Item as made { qty: input.amount; n: input.small; }
                    persist made;
                    return made;
                  }
                }
                capability Browse {
                  input PageIn; output Page<ItemView>; fails Bad; requires readonly; expose query;
                  workflow {
                    find Item where %s order by id descending Page input.page, input.size else Bad as found;
                    return found;
                  }
                }
              }
            }
            """;

    private static final String LEGAL_VALIDATE = "validate input.amount > 0 else Bad;";
    private static final String LEGAL_WHERE = "item.qty > 0";

    @Test
    void aLiteralTakesTheWidthOfTheInt64ItIsComparedWithOrAddedTo() {
        assertClean("validate input.amount > 0 else Bad; validate 0 < input.amount else Bad; validate input.amount - 1 >= 0 else Bad;"
                + " validate input.amount + (1) == -(2) + input.amount else Bad;", LEGAL_WHERE);
        assertClean(LEGAL_VALIDATE, "item.qty > input.min + 1 and item.qty != 0");
    }

    @Test
    void anInt32ValueIsStillNotAnInt64() {
        assertOnly("SIR-TYPE-001", "validate input.small > input.amount else Bad;", LEGAL_WHERE);
        assertOnly("SIR-TYPE-001", "validate input.small + input.amount > 0 else Bad;", LEGAL_WHERE);
    }

    @Test
    void aLiteralTooLargeForAnInt32IsRefusedUnlessTheOtherSideIsAnInt64() {
        assertOnly("SIR-TYPE-001", "validate input.small < 5000000000 else Bad;", LEGAL_WHERE);
        assertOnly("SIR-TYPE-001", "validate input.small < 2147483648 else Bad;", LEGAL_WHERE);
        assertClean("validate input.small < 2147483647 else Bad; validate input.amount < 5000000000 else Bad;", LEGAL_WHERE);
    }

    @Test
    void aLiteralTooLargeForAnInt64IsRefused() {
        assertOnly("SIR-TYPE-001", "validate input.amount < 9223372036854775808 else Bad;", LEGAL_WHERE);
        assertClean("validate input.amount < 9223372036854775807 else Bad;", LEGAL_WHERE);
    }

    @Test
    void aFindComparisonNeedsTheColumnOnTheLeftAndAValueOnTheRight() {
        assertOnly("SIR-VALID-001", LEGAL_VALIDATE, "0 < item.qty");
        assertOnly("SIR-VALID-001", LEGAL_VALIDATE, "input.min < item.qty");
        assertOnly("SIR-VALID-001", LEGAL_VALIDATE, "item.n == item.n");
        assertOnly("SIR-VALID-001", LEGAL_VALIDATE, "input.min == input.min");
        assertOnly("SIR-VALID-001", LEGAL_VALIDATE, "item.n > 0 and 1 < item.qty");
    }

    @Test
    void aFindComparisonWithTheColumnOnTheLeftIsFine() {
        assertClean(LEGAL_VALIDATE, "item.qty >= input.min and (item.n == 1 or item.n != 2)");
    }

    private static void assertClean(String validateSteps, String where) {
        SemanticAnalysis result = TestSources.analyze(TEMPLATE.formatted(validateSteps, where));

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    private static void assertOnly(String code, String validateSteps, String where) {
        SemanticAnalysis result = TestSources.analyze(TEMPLATE.formatted(validateSteps, where));

        assertEquals(List.of(code), TestSources.errorCodes(result), () -> result.diagnostics().toString());
    }
}
