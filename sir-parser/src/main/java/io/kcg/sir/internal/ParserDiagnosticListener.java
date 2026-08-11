package io.kcg.sir.internal;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

final class ParserDiagnosticListener extends BaseErrorListener {
    private final SourceText source;
    private final DiagnosticCollector diagnostics;

    ParserDiagnosticListener(SourceText source, DiagnosticCollector diagnostics) {
        this.source = source;
        this.diagnostics = diagnostics;
    }

    @Override
    public void syntaxError(
            Recognizer<?, ?> recognizer,
            Object offendingSymbol,
            int line,
            int charPositionInLine,
            String message,
            RecognitionException exception) {
        Token token = offendingSymbol instanceof Token value ? value : null;
        String code;
        String localized;
        boolean missingToken = false;
        if (token != null && token.getType() == Token.EOF) {
            code = "SIR-PARSE-004";
            localized = "输入提前结束，SIR 结构尚未闭合";
        } else if (message != null && message.startsWith("missing ")) {
            code = "SIR-PARSE-002";
            localized = "缺少必要的语法元素";
            missingToken = true;
        } else if (message != null && message.startsWith("extraneous input")) {
            code = "SIR-PARSE-003";
            localized = "存在多余的语法元素";
        } else {
            code = "SIR-PARSE-001";
            localized = token == null
                    ? "输入不符合 SIR v0.1 语法"
                    : "遇到不符合 SIR v0.1 语法的内容：" + printable(token.getText());
        }
        Token previous = missingToken && recognizer instanceof Parser parser
                ? parser.getTokenStream().LT(-1)
                : null;
        diagnostics.error(code, localized, missingToken
                ? source.insertionSpanAfter(previous)
                : token == null ? source.zeroSpan(null) : source.span(token));
    }

    private static String printable(String text) {
        if (text == null || text.isBlank()) {
            return "<空白>";
        }
        return text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
