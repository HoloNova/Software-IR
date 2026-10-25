package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.api.SourceUnitParseResult;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A word the language owns, written where a name belongs, is named as the problem instead of reported as junk. */
class KeywordAsNameDiagnosticTest {
    private final DefaultSirParser parser = new DefaultSirParser();

    @ParameterizedTest
    @ValueSource(strings = {"order", "item", "from"})
    void aKeywordUsedAsAFieldNameIsNamedInTheMessage(String word) {
        var result = parse("entity Thing persistent { identity id: Id generated auto; field " + word + ": Text; }");

        assertFalse(result.isSuccess());
        String message = result.diagnostics().stream().map(Diagnostic::message)
                .filter(text -> text.contains("关键字")).findFirst().orElse("");
        assertTrue(message.contains("“" + word + "”"), result.diagnostics()::toString);
    }

    @Test
    void anOrdinaryMistakeKeepsTheGenericMessage() {
        var result = parse("entity Thing persistent { identity id: Id generated auto; field : Text; }");

        assertFalse(result.isSuccess());
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.message().contains("关键字")), result.diagnostics()::toString);
    }

    private SourceUnitParseResult parse(String declarations) {
        return parser.parseProject(new SirSource(SourceId.of("tests/keyword.sir"), """
                sir 0.3
                software KeywordName {
                  metadata { displayName "Keyword Name"; namespace "tests.keyword"; }
                  target { language java 21; framework spring_boot; persistence mybatis_plus; database mysql; build maven; interface rest; }
                  sources { }
                  imports { }
                  declarations {
                    %s
                  }
                }
                """.formatted(declarations)));
    }
}
