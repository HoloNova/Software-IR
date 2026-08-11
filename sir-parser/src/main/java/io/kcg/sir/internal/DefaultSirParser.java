package io.kcg.sir.internal;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import java.util.Objects;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.Token;

public final class DefaultSirParser implements SirParser {
    private static final char BOM = '\uFEFF';

    @Override
    public ParseResult parse(SirSource source) {
        Objects.requireNonNull(source, "source");
        String normalized = stripBom(source.content());
        SourceText sourceText = new SourceText(source.id(), normalized);
        DiagnosticCollector diagnostics = new DiagnosticCollector();

        try {
            SirLexer lexer = new SirLexer(CharStreams.fromString(normalized, source.id().value()));
            lexer.removeErrorListeners();
            lexer.addErrorListener(new LexerDiagnosticListener(sourceText, diagnostics));

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();
            collectLexicalTokenDiagnostics(tokens, sourceText, diagnostics);
            if (diagnostics.hasErrors()) {
                return ParseResult.failure(diagnostics.diagnostics());
            }

            tokens.seek(0);
            io.kcg.sir.internal.SirParser antlrParser = new io.kcg.sir.internal.SirParser(tokens);
            antlrParser.removeErrorListeners();
            antlrParser.addErrorListener(new ParserDiagnosticListener(sourceText, diagnostics));
            antlrParser.setErrorHandler(new DefaultErrorStrategy());
            io.kcg.sir.internal.SirParser.DocumentContext parseTree = antlrParser.document();
            if (diagnostics.hasErrors()) {
                return ParseResult.failure(diagnostics.diagnostics());
            }

            if (!new HeaderCompatibilityCheck().validate(parseTree, sourceText, diagnostics)) {
                return ParseResult.failure(diagnostics.diagnostics());
            }

            return ParseResult.success(new SirAstBuilder(sourceText).build(parseTree), diagnostics.diagnostics());
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException exception) {
            diagnostics.error(
                    "SIR-INTERNAL-001",
                    "解析器内部错误，未返回不完整 AST",
                    sourceText.zeroSpan(null));
            return ParseResult.failure(diagnostics.diagnostics());
        }
    }

    private static String stripBom(String content) {
        return !content.isEmpty() && content.charAt(0) == BOM ? content.substring(1) : content;
    }

    private static void collectLexicalTokenDiagnostics(
            CommonTokenStream tokens,
            SourceText source,
            DiagnosticCollector diagnostics) {
        for (Token token : tokens.getTokens()) {
            switch (token.getType()) {
                case SirLexer.INVALID_STRING -> diagnostics.error(
                        "SIR-LEX-003", "字符串包含非法转义或未转义控制字符", source.span(token));
                case SirLexer.UNCLOSED_STRING -> diagnostics.error(
                        "SIR-LEX-002", "字符串未闭合", source.span(token));
                case SirLexer.UNCLOSED_BLOCK_COMMENT -> diagnostics.error(
                        "SIR-LEX-004", "块注释未闭合", source.span(token));
                case SirLexer.STRING -> validateDecodedString(token, source, diagnostics);
                default -> {
                    // Normal token.
                }
            }
        }
    }

    private static void validateDecodedString(
            Token token,
            SourceText source,
            DiagnosticCollector diagnostics) {
        try {
            SirStringDecoder.decode(token.getText());
        } catch (IllegalArgumentException exception) {
            diagnostics.error("SIR-LEX-003", "字符串包含非法 Unicode 序列", source.span(token));
        }
    }
}
