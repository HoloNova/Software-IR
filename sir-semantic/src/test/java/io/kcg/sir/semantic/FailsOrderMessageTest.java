package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ordering rule for fails stays, but the message now hands the author the sorted list. */
class FailsOrderMessageTest {
    private static final String FIXTURE = "valid/persist-order.sir";

    @Test
    void anOutOfOrderFailsListIsAnsweredWithTheSortedOne() {
        String source = TestSources.resource(FIXTURE)
                .replace("fails CourseFull; fails CourseMissing; fails Taken;", "fails Taken; fails CourseFull; fails CourseMissing;");
        var result = TestSources.analyze(source);

        assertEquals(List.of("SIR-VALID-001"), TestSources.errorCodes(result), () -> result.diagnostics().toString());
        String message = result.diagnostics().stream().filter(Diagnostic::isError).map(Diagnostic::message).findFirst().orElse("");
        assertTrue(message.contains("fails CourseFull; fails CourseMissing; fails Taken;"), message);
    }
}
