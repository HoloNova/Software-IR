package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstDocument;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstEnumDecl;
import io.kcg.sir.ast.AstInputDecl;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstUnitLiteral;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstUnitLiteral;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SirParserTest {
    private final SirParser parser = new DefaultSirParser();

    @Test
    void parsesCampusMarketIntoImmutableAst() {
        ParseResult result = parseResource("valid/campus-market.sir");

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
        AstDocument document = result.document().orElseThrow();
        assertEquals(new BigInteger("0"), document.sirVersion().major());
        assertEquals(new BigInteger("1"), document.sirVersion().minor());
        assertEquals("CampusMarket", document.software().name().text());
        assertEquals(6, document.software().declarations().size());
        assertInstanceOf(AstEnumDecl.class, document.software().declarations().get(0));
        assertInstanceOf(AstEntityDecl.class, document.software().declarations().get(1));
        assertInstanceOf(AstInputDecl.class, document.software().declarations().get(3));
        assertInstanceOf(AstCapabilityDecl.class, document.software().declarations().get(5));
    }

    @Test
    void parsesAllSupportedWorkflowShapesAcrossGoldFiles() {
        ParseResult result = parseResource("valid/all-workflow-steps.sir");
        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));

        AstCapabilityDecl capability = result.document().orElseThrow().software().declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .map(AstCapabilityDecl.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(5, capability.workflow().steps().size());
    }

    @Test
    void rejectsUnsupportedSirVersionWithoutAst() {
        ParseResult result = parseResource("invalid/unsupported-version.sir");
        assertFailedWith(result, "SIR-VERSION-001");
    }

    @Test
    void rejectsUnsupportedTargetWithoutAst() {
        ParseResult result = parseResource("invalid/unsupported-target.sir");
        assertFailedWith(result, "SIR-TARGET-001");
    }

    @Test
    void grammarRejectsArbitraryCallsAndChainedComparisons() {
        assertFailedWith(parseResource("invalid/arbitrary-call.sir"), "SIR-PARSE-001");
        assertFailedWith(parseResource("invalid/chained-comparison.sir"), "SIR-PARSE-001");
    }

    @Test
    void grammarRejectsSplitDecimalTokens() {
        String campusMarket = resource("valid/campus-market.sir");
        assertGrammarFailed(campusMarket.replace("0.01", "0 . 01"));
    }

    @Test
    void grammarRejectsParenthesizedZeroArgumentConstraint() {
        String campusMarket = resource("valid/campus-market.sir");
        assertGrammarFailed(campusMarket.replace("where notBlank;", "where notBlank();"));
    }

    @Test
    void unitReturnHasDedicatedLiteralNode() {
        ParseResult result = parseResource("valid/unit-capability.sir");
        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));

        AstCapabilityDecl capability = (AstCapabilityDecl) result.document().orElseThrow()
                .software().declarations().getFirst();
        AstReturnStep step = (AstReturnStep) capability.workflow().steps().getFirst();
        assertInstanceOf(AstUnitLiteral.class, step.value());
    }

    @Test
    void sameInputProducesSameAstNodeIds() {
        String content = resource("valid/campus-market.sir");
        SirSource source = new SirSource(SourceId.of("examples/campus-market.sir"), content);

        AstDocument first = parser.parse(source).document().orElseThrow();
        AstDocument second = parser.parse(source).document().orElseThrow();

        assertEquals(first.id(), second.id());
        assertEquals(first.software().id(), second.software().id());
        assertEquals(
                first.software().declarations().stream().map(declaration -> declaration.id().value()).toList(),
                second.software().declarations().stream().map(declaration -> declaration.id().value()).toList());
    }

    @Test
    void bomIsRemovedBeforePublicOffsetsAreCalculated() {
        String content = "\uFEFF" + resource("valid/campus-market.sir");
        ParseResult result = parser.parse(new SirSource(SourceId.of("examples/bom.sir"), content));

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
        assertEquals(0, result.document().orElseThrow().span().start().codePointOffset());
    }

    @Test
    void sourceOffsetsCountUnicodeCodePoints() {
        String content = """
                sir 0.1
                software UnicodeSample {
                  metadata { displayName "😀"; namespace "example.unicode"; }
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
        ParseResult result = parser.parse(new SirSource(SourceId.of("tests/unicode.sir"), content));
        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));

        int utf16Index = content.indexOf("\"example.unicode\"");
        int expectedCodePointOffset = content.codePointCount(0, utf16Index);
        assertEquals(
                expectedCodePointOffset,
                result.document().orElseThrow().software().metadata().namespaceSpan().start().codePointOffset());
    }

    @Test
    void syntaxErrorsDoNotLeakAntlrMessagesToConsole() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            ParseResult result = parseResource("invalid/arbitrary-call.sir");
            assertFalse(result.isSuccess());
        } finally {
            System.setErr(originalErr);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    private ParseResult parseResource(String path) {
        return parser.parse(new SirSource(SourceId.of("tests/" + path), resource(path)));
    }

    private static String resource(String path) {
        try (var stream = SirParserTest.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing test resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertFailedWith(ParseResult result, String code) {
        assertFalse(result.isSuccess());
        assertTrue(result.document().isEmpty());
        assertTrue(result.diagnostics().stream().map(Diagnostic::code)
                .anyMatch(value -> value.value().equals(code)), () -> messages(result.diagnostics()));
    }

    private void assertGrammarFailed(String content) {
        ParseResult result = parser.parse(new SirSource(SourceId.of("tests/invalid/non-canonical.sir"), content));
        assertFalse(result.isSuccess());
        assertTrue(result.document().isEmpty());
        assertTrue(result.diagnostics().stream()
                .map(Diagnostic::code)
                .anyMatch(value -> value.value().startsWith("SIR-PARSE-")), () -> messages(result.diagnostics()));
    }

    private static String messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(value -> value.code().value() + ": " + value.message())
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
    }
}
