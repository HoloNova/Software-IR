package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * "Check that nothing conflicts, then insert" is only safe when two requests queue on the same lock or the database
 * has a unique key. Without either, the source is still legal but earns a warning that never stops compilation.
 */
class CheckThenInsertWarningTest {
    private static final String FIXTURE = "valid/check-then-insert.sir";
    private static final String LOCKED = "load Room by input.roomId as room for update else RoomMissing;";
    private static final String UNLOCKED = "load Room by input.roomId as room else RoomMissing;";

    @Test
    void aLockedParentNeedsNoWarning() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        assertEquals(List.of(), warnings(result));
    }

    @Test
    void withoutTheLockTheSourceStaysLegalButIsWarnedAbout() {
        SemanticAnalysis result = TestSources.analyze(replace(LOCKED, UNLOCKED));

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        assertEquals(List.of("SIR-WARN-001"), warnings(result));
    }

    @Test
    void aUniqueKeyOnTheInsertedEntityIsEnoughProtection() {
        String source = replace(LOCKED, UNLOCKED).replace("field endsAt: Moment;\n    }", "field endsAt: Moment;\n      unique (room, startsAt);\n    }");
        SemanticAnalysis result = TestSources.analyze(source);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        assertEquals(List.of(), warnings(result));
    }

    @Test
    void aCheckThatMentionsNoLoadedRowIsNotGuessedAbout() {
        String source = replace(LOCKED, UNLOCKED).replace("item.room == room and ", "");
        SemanticAnalysis result = TestSources.analyze(source);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        assertEquals(List.of(), warnings(result));
    }

    private static List<String> warnings(SemanticAnalysis result) {
        return result.diagnostics().stream().filter(d -> !d.isError()).map(Diagnostic::code).map(code -> code.value()).toList();
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "fixture fragment missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
