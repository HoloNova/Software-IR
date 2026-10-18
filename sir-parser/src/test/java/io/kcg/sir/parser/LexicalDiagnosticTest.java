package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.SourceUnitParseResult;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import org.junit.jupiter.api.Test;

class LexicalDiagnosticTest {
    private final DefaultSirParser parser = new DefaultSirParser();

    @Test
    void classifiesInvalidEscapeAsOneStableDiagnostic() {
        SourceUnitParseResult result = parse(base().replace("DISPLAY", "bad\\q"));
        assertOnlyCode(result, "SIR-LEX-003");
    }

    @Test
    void classifiesUnclosedStringAsOneStableDiagnostic() {
        SourceUnitParseResult result = parse(base().replace("\"DISPLAY\"", "\"unterminated"));
        assertOnlyCode(result, "SIR-LEX-002");
    }

    @Test
    void classifiesUnclosedBlockCommentAsOneStableDiagnostic() {
        SourceUnitParseResult result = parse(base() + "\n/* unterminated");
        assertOnlyCode(result, "SIR-LEX-004");
    }

    @Test
    void acceptsClosedBlockCommentAtTokenBoundary() {
        SourceUnitParseResult result = parse(base().replace("software", "/* closed */ software"));
        assertTrue(result.isSuccess(), result.diagnostics().toString());
    }

    @Test
    void rejectsUnescapedControlCharactersInString() {
        SourceUnitParseResult result = parse(base().replace("DISPLAY", "bad\tvalue"));
        assertOnlyCode(result, "SIR-LEX-003");
    }

    @Test
    void rejectsLoneCarriageReturnAsNonCanonicalNewline() {
        SourceUnitParseResult result = parse(base().replace("software", "\rsoftware"));
        assertOnlyCode(result, "SIR-LEX-001");
    }

    @Test
    void rejectsUnicodeEscapeAsAlternativeStringSpelling() {
        SourceUnitParseResult result = parse(base().replace("DISPLAY", "\\u0044ISPLAY"));
        assertOnlyCode(result, "SIR-LEX-003");
    }

    private SourceUnitParseResult parse(String content) {
        return parser.parseProject(new SirSource(SourceId.of("tests/lexical.sir"), content));
    }

    private static void assertOnlyCode(SourceUnitParseResult result, String code) {
        assertFalse(result.isSuccess());
        assertTrue(result.unit().isEmpty());
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        assertEquals(code, result.diagnostics().getFirst().code().value());
    }

    private static String base() {
        return """
                sir 0.3
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
                  sources { }
                  imports { }
                  declarations {}
                }
                """;
    }
}
