package io.kcg.sir.internal;

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
    public io.kcg.sir.api.SourceUnitParseResult parseProject(SirSource source) {
        return parseSourceUnit(source, true);
    }

    @Override
    public io.kcg.sir.api.SourceUnitParseResult parseFragment(SirSource source) {
        return parseSourceUnit(source, false);
    }

    @Override
    public io.kcg.sir.api.ExpansionParseResult parseExpansion(String declarations, io.kcg.sir.ast.AstNodeId under,
            io.kcg.sir.source.SourceSpan at) {
        return parseExpansion(declarations, under, at, java.util.List.of());
    }

    @Override
    public io.kcg.sir.api.ExpansionParseResult parseExpansion(String declarations, io.kcg.sir.ast.AstNodeId under,
            io.kcg.sir.source.SourceSpan at, java.util.List<io.kcg.sir.api.ExpansionOrigin> origins) {
        Objects.requireNonNull(declarations, "declarations");
        Objects.requireNonNull(under, "under");
        Objects.requireNonNull(at, "at");
        String text = "sir 0.3\nimports { }\ndeclarations {\n" + declarations + "\n}\n";
        SourceText source = new SourceText(io.kcg.sir.source.SourceId.of("feature-expansion.sir"), text, at, origins, "sir 0.3\nimports { }\ndeclarations {\n".length());
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        try {
            SirLexer lexer = new SirLexer(CharStreams.fromString(text, "feature-expansion.sir"));
            lexer.removeErrorListeners();
            lexer.addErrorListener(new LexerDiagnosticListener(source, diagnostics));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();
            collectLexicalTokenDiagnostics(tokens, source, diagnostics);
            if (diagnostics.hasErrors()) return new io.kcg.sir.api.ExpansionParseResult(java.util.Optional.empty(), diagnostics.diagnostics());
            tokens.seek(0);
            io.kcg.sir.internal.SirParser parser = new io.kcg.sir.internal.SirParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new ParserDiagnosticListener(source, diagnostics));
            var tree = parser.sourceFragment();
            if (diagnostics.hasErrors()) return new io.kcg.sir.api.ExpansionParseResult(java.util.Optional.empty(), diagnostics.diagnostics());
            return new io.kcg.sir.api.ExpansionParseResult(
                    java.util.Optional.of(new SirAstBuilder(source, under).buildDeclarations(tree)), diagnostics.diagnostics());
        } catch (VirtualMachineError fatal) { throw fatal; }
        catch (RuntimeException e) {
            diagnostics.error("SIR-INTERNAL-001", "功能展开文本解析失败", at);
            return new io.kcg.sir.api.ExpansionParseResult(java.util.Optional.empty(), diagnostics.diagnostics());
        }
    }

    private io.kcg.sir.api.SourceUnitParseResult parseSourceUnit(SirSource input, boolean root) {
        Objects.requireNonNull(input, "source");
        SourceText source = new SourceText(input.id(), stripBom(input.content()));
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        try {
            SirLexer lexer = new SirLexer(CharStreams.fromString(stripBom(input.content()), input.id().value()));
            lexer.removeErrorListeners();
            lexer.addErrorListener(new LexerDiagnosticListener(source, diagnostics));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();
            collectLexicalTokenDiagnostics(tokens, source, diagnostics);
            if (diagnostics.hasErrors()) return unitFailure(diagnostics);
            tokens.seek(0);
            io.kcg.sir.internal.SirParser parser = new io.kcg.sir.internal.SirParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new ParserDiagnosticListener(source, diagnostics));
            if (root) {
                var tree = parser.projectDocument();
                if (diagnostics.hasErrors()) return unitFailure(diagnostics);
                if (!tree.versionLiteral().getText().equals("0.3")) {
                    diagnostics.error("SIR-VERSION-001", "项目输入只支持 SIR 0.3（0.2 及更早的写法不再接受）", source.span(tree.versionLiteral()));
                    return unitFailure(diagnostics);
                }
                if (!new HeaderCompatibilityCheck().validateTarget(tree.targetBlock(), source, diagnostics)) return unitFailure(diagnostics);
                return new io.kcg.sir.api.SourceUnitParseResult(java.util.Optional.of(new SirAstBuilder(source).buildProject(tree)), diagnostics.diagnostics());
            }
            var tree = parser.sourceFragment();
            if (diagnostics.hasErrors()) return unitFailure(diagnostics);
            if (!tree.versionLiteral().getText().equals("0.3")) {
                diagnostics.error("SIR-VERSION-001", "源片段只支持 SIR 0.3（0.2 及更早的写法不再接受）", source.span(tree.versionLiteral()));
                return unitFailure(diagnostics);
            }
            return new io.kcg.sir.api.SourceUnitParseResult(java.util.Optional.of(new SirAstBuilder(source).buildFragment(tree)), diagnostics.diagnostics());
        } catch (VirtualMachineError fatal) { throw fatal; }
        catch (RuntimeException e) {
            diagnostics.error("SIR-INTERNAL-001", "解析器内部错误，未返回不完整源片段", source.zeroSpan(null));
            return unitFailure(diagnostics);
        }
    }

    private static io.kcg.sir.api.SourceUnitParseResult unitFailure(DiagnosticCollector diagnostics) {
        return new io.kcg.sir.api.SourceUnitParseResult(java.util.Optional.empty(), diagnostics.diagnostics());
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
