package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstFeatureArgument;
import io.kcg.sir.ast.AstFeatureDefinitionDecl;
import io.kcg.sir.ast.AstFeatureUseDecl;
import io.kcg.sir.ast.AstSemanticTypeDecl;
import io.kcg.sir.ast.AstTimeUnit;
import io.kcg.sir.source.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The three declarations that let a software say how it is written: {@code use}, {@code type} and {@code feature}. */
class FeatureGrammarTest {
    private final SirParser parser = SirParser.create();

    private List<io.kcg.sir.ast.AstDeclaration> declarations(String body) {
        var result = parser.parseFragment(new SirSource(SourceId.of("a.sir"), "sir 0.3\nimports { }\ndeclarations {\n" + body + "\n}\n"));
        assertTrue(result.isSuccess(), result.diagnostics()::toString);
        return result.unit().orElseThrow().declarations();
    }

    private boolean rejected(String body) {
        return !parser.parseFragment(new SirSource(SourceId.of("a.sir"), "sir 0.3\nimports { }\ndeclarations {\n" + body + "\n}\n")).isSuccess();
    }

    @Test void aUseDeclarationRecordsTheFeatureAndItsArgumentsAsWritten() {
        var use = assertInstanceOf(AstFeatureUseDecl.class, declarations("use Tagging { owner: Note; limit: 3 days; }").get(0));
        assertEquals("Tagging", use.name().text());
        assertEquals("owner", use.arguments().get(0).parameter().text());
        assertEquals("Note", use.arguments().get(0).value().text());
        var limit = assertInstanceOf(AstFeatureArgument.Duration.class, use.arguments().get(1).value());
        assertEquals(AstTimeUnit.DAYS, limit.unit());
        assertEquals("3 days", limit.text());
    }

    @Test void aTypeDeclarationRecordsItsBaseAndRules() {
        var type = assertInstanceOf(AstSemanticTypeDecl.class, declarations("type Percent = Count where min(0), max(100);").get(0));
        assertEquals("Percent", type.name().text());
        assertEquals("Count", type.base().text());
        assertEquals(List.of("min", "max"), type.constraints().stream().map(c -> c.name().text()).toList());
        assertInstanceOf(AstSemanticTypeDecl.class, declarations("type Handle = Name;").get(0));
    }

    @Test void aFeatureDefinitionKeepsItsTextExactlyAsWritten() {
        String template = "\n    entity ${prefix}Tag persistent {\n      identity id: Id generated auto;\n    }\n    ";
        var feature = assertInstanceOf(AstFeatureDefinitionDecl.class, declarations(
            "feature Tagging {\n parameter owner: name;\n parameter prefix: name = \"\";\n parameter limit: duration = 7 days;\n template ```" + template + "```;\n}").get(0));
        assertEquals(template, feature.template());
        assertEquals(List.of("owner", "prefix", "limit"), feature.parameters().stream().map(p -> p.name().text()).toList());
        assertTrue(feature.parameters().get(1).emptyDefault());
        assertEquals("7 days", feature.parameters().get(2).defaultValue().orElseThrow().text());
    }

    @Test void theWordsStayLegalNamesAndMalformedDeclarationsAreRejected() {
        for (String word : List.of("use", "type", "feature", "parameter", "template")) {
            assertEquals(1, declarations("entity " + word + " persistent { identity id: Id generated auto; }").size(), word);
        }
        assertTrue(rejected("use Tagging { owner Note; }"));
        assertTrue(rejected("use Tagging { owner: Note }"));
        assertTrue(rejected("type Percent Count;"));
        assertTrue(rejected("type Percent = ;"));
        assertTrue(rejected("feature Tagging { template ```x``` ; extra }"));
        assertTrue(rejected("feature Tagging { parameter a: name; }"));          // the text is required
        assertTrue(rejected("feature Tagging { template ```never closed; }"));    // an unclosed text block
        assertFalse(rejected("feature Tagging { template ``````; }"));
    }
}
