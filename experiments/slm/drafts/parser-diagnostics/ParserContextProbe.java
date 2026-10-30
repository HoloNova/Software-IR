package io.kcg.sir.internal;

import io.kcg.cli.ProbeJson;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.RuntimeMetaData;
import org.antlr.v4.runtime.Token;

/** Read-only observer of the existing CI parser. Does not replace its diagnostic listener or recovery. */
public final class ParserContextProbe {
    public static void main(String[] args) throws Exception {
        var cases = (List<?>) ProbeJson.read(Path.of(args[0]));
        var rows = new ArrayList<Map<String, Object>>();
        for (var raw : cases) {
            var input = (Map<?, ?>) raw;
            String id = (String) input.get("id");
            String text = (String) input.get("source");
            SourceId sourceId = SourceId.of("probe.sir");
            var control = new DefaultSirParser().parseProject(new SirSource(sourceId, text));
            var source = new SourceText(sourceId, text);
            var collector = new DiagnosticCollector();
            var lexer = new SirLexer(CharStreams.fromString(text, sourceId.value()));
            lexer.removeErrorListeners();
            var lexicalErrors = new ArrayList<String>();
            lexer.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> r, Object o, int line, int col, String msg, RecognitionException e) {
                    lexicalErrors.add(line + ":" + col + " " + msg);
                }
            });
            var tokens = new CommonTokenStream(lexer);
            tokens.fill();
            if (!lexicalErrors.isEmpty()) throw new IllegalStateException(id + " has lexical errors: " + lexicalErrors);
            var parser = new SirParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new ParserDiagnosticListener(source, collector));
            var events = new ArrayList<Map<String, Object>>();
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> r, Object offending, int line, int col, String msg, RecognitionException e) {
                    events.add(observe((Parser) r, (Token) offending, line, col, msg, e));
                }
            });
            parser.projectDocument();
            if (!control.diagnostics().equals(collector.diagnostics())) {
                throw new IllegalStateException(id + ": observation changed public diagnostics");
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("accepted", control.isSuccess());
            row.put("observer_matches_public_diagnostics", true);
            row.put("diagnostics", control.diagnostics().stream().map(d -> Map.of(
                    "code", d.code().value(), "message", d.message(),
                    "start_offset", d.primarySpan().start().codePointOffset(),
                    "end_offset", d.primarySpan().end().codePointOffset(),
                    "line", d.primarySpan().start().line(), "column", d.primarySpan().start().column())).toList());
            row.put("events", events);
            rows.add(row);
        }
        ProbeJson.write(Path.of(args[1]), Map.of(
                "antlr_runtime", RuntimeMetaData.getRuntimeVersion(),
                "observation_only", true, "cases", rows));
    }

    private static Map<String, Object> observe(Parser parser, Token token, int line, int column,
            String message, RecognitionException exception) {
        var row = new LinkedHashMap<String, Object>();
        row.put("line", line);
        row.put("column_zero_based", column);
        row.put("token", token.getText());
        row.put("token_type", parser.getVocabulary().getSymbolicName(token.getType()));
        row.put("expected", parser.getExpectedTokens().toString(parser.getVocabulary()));
        row.put("expected_ident", parser.getExpectedTokens().contains(SirParser.IDENT));
        row.put("expected_else", parser.getExpectedTokens().contains(SirParser.ELSE));
        row.put("raw_message", message);
        row.put("exception", exception == null ? null : exception.getClass().getSimpleName());
        row.put("rule_stack", parser.getRuleInvocationStack());
        row.put("previous", tokenView(parser.getTokenStream().LT(-1)));
        row.put("next", tokenView(parser.getTokenStream().LT(2)));
        var contexts = new ArrayList<Map<String, Object>>();
        for (ParserRuleContext c = parser.getContext(); c != null; c = c.getParent()) {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("rule", parser.getRuleNames()[c.getRuleIndex()]);
            detail.put("start", tokenView(c.getStart()));
            detail.put("stop", tokenView(c.getStop()));
            detail.put("subtree_has_error_node", subtreeProblem(c, false));
            detail.put("subtree_has_rule_exception", subtreeProblem(c, true));
            SirParser.ConstraintListContext constraints = c instanceof SirParser.EntityMemberDeclContext f ? f.constraintList()
                    : c instanceof SirParser.FieldDeclContext f ? f.constraintList()
                    : c instanceof SirParser.ViewFieldDeclContext f ? f.constraintList()
                    : c instanceof SirParser.SemanticTypeDeclContext f ? f.constraintList() : null;
            if (constraints != null) {
                detail.put("constraints", constraints.constraintCall().stream().map(call -> Map.of(
                        "text", call.getText(), "start_index", call.getStart().getTokenIndex(),
                        "stop_index", call.getStop() == null ? -1 : call.getStop().getTokenIndex(),
                        "has_error_node", subtreeProblem(call, false),
                        "has_rule_exception", subtreeProblem(call, true))).toList());
            }
            if (c instanceof SirParser.FindPageClauseContext page) {
                detail.put("page_expression", page.pageExpr == null ? null : page.pageExpr.getText());
                detail.put("size_expression", page.sizeExpr == null ? null : page.sizeExpr.getText());
                detail.put("size_stop", page.sizeExpr == null ? null : tokenView(page.sizeExpr.getStop()));
                detail.put("page_has_error_node", page.pageExpr == null ? null : subtreeProblem(page.pageExpr, false));
                detail.put("page_has_rule_exception", page.pageExpr == null ? null : subtreeProblem(page.pageExpr, true));
                detail.put("size_has_error_node", page.sizeExpr == null ? null : subtreeProblem(page.sizeExpr, false));
                detail.put("size_has_rule_exception", page.sizeExpr == null ? null : subtreeProblem(page.sizeExpr, true));
            }
            contexts.add(detail);
        }
        row.put("contexts", contexts);
        return row;
    }

    private static boolean subtreeProblem(org.antlr.v4.runtime.tree.ParseTree root, boolean exceptions) {
        var pending = new java.util.ArrayDeque<org.antlr.v4.runtime.tree.ParseTree>();
        pending.push(root);
        while (!pending.isEmpty()) {
            var node = pending.pop();
            if (!exceptions && node instanceof org.antlr.v4.runtime.tree.ErrorNode) return true;
            if (exceptions && node instanceof ParserRuleContext context && context.exception != null) return true;
            for (int i = 0; i < node.getChildCount(); i++) pending.push(node.getChild(i));
        }
        return false;
    }

    private static Map<String, Object> tokenView(Token token) {
        if (token == null) return Map.of();
        return Map.of("text", token.getText(), "type", token.getType(), "index", token.getTokenIndex());
    }
}
