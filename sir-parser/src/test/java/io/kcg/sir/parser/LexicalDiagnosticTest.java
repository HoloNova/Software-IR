package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import org.junit.jupiter.api.Test;

class LexicalDiagnosticTest {
    private final DefaultSirParser parser = new DefaultSirParser();

    @Test
    void classifiesInvalidEscapeAsOneStableDiagnostic() {
        ParseResult result = parse(base().replace("DISPLAY", "bad\\q"));
        assertOnlyCode(result, "SIR-LEX-003");
    }

    @Test
    void classifiesUnclosedStringAsOneStableDiagnostic() {
        ParseResult result = parse(base().replace("\"DISPLAY\"", "\"unterminated"));
        assertOnlyCode(result, "SIR-LEX-002");
    }

    @Test
    void classifiesUnclosedBlockCommentAsOneStableDiagnostic() {
        ParseResult result = parse(base() + "\n/* unterminated");
        assertOnlyCode(result, "SIR-LEX-004");
    }

    @Test
    void acceptsClosedBlockCommentAtTokenBoundary() {
        ParseResult result = parse(base().replace("software", "/* closed */ software"));
        assertTrue(result.isSuccess(), result.diagnostics().toString());
    }

    @Test
    void rejectsUnescapedControlCharactersInString() {
        ParseResult result = parse(base().replace("DISPLAY", "bad\tvalue"));
        assertOnlyCode(result, "SIR-LEX-003");
    }

    @Test
    void rejectsLoneCarriageReturnAsNonCanonicalNewline() {
        ParseResult result = parse(base().replace("software", "\rsoftware"));
        assertOnlyCode(result, "SIR-LEX-001");
    }

    @Test
    void rejectsUnicodeEscapeAsAlternativeStringSpelling() {
        ParseResult result = parse(base().replace("DISPLAY", "\\u0044ISPLAY"));
        assertOnlyCode(result, "SIR-LEX-003");
    }

    private ParseResult parse(String content) {
        return parser.parse(new SirSource(SourceId.of("tests/lexical.sir"), content));
    }

    private static void assertOnlyCode(ParseResult result, String code) {
        assertFalse(result.isSuccess());
        assertTrue(result.document().isEmpty());
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        assertEquals(code, result.diagnostics().getFirst().code().value());
    }

    private static String base() {
        return """
                sir 0.1
                software LexicalSample {
                  metadata { displayName "DISPLAY"; namespace "example.lexical"; }
                  target {
                    language java 21;
                    framework spring_boot;
                    persistence mybatis_plus;
                    database mysql;
                    build maven;
                    interface rest;
                  }
                  declarations {}
                }
                """;
    }
}
