package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import org.junit.jupiter.api.Test;

class ParserDiagnosticSpanTest {
    private final DefaultSirParser parser = new DefaultSirParser();

    @Test
    void missingTokenDiagnosticUsesZeroWidthInsertionSpan() {
        String marker = "displayName \"Example\"";
        String content = validSource().replace(marker + ";", marker);
        var result = parser.parse(new SirSource(SourceId.of("tests/missing-token.sir"), content));

        assertFalse(result.isSuccess());
        Diagnostic diagnostic = result.diagnostics().stream()
                .filter(value -> value.code().value().equals("SIR-PARSE-002"))
                .findFirst()
                .orElseThrow();
        int expectedOffset = content.indexOf(marker) + marker.length();
        assertEquals(expectedOffset, diagnostic.primarySpan().start().codePointOffset());
        assertEquals(diagnostic.primarySpan().start(), diagnostic.primarySpan().end());
    }

    private static String validSource() {
        return """
                sir 0.1
                software DiagnosticSample {
                  metadata {
                    displayName "Example";
                    namespace "tests.diagnostic";
                  }
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
