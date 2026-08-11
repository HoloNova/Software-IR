package io.kcg.sir.internal;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

final class LexerDiagnosticListener extends BaseErrorListener {
    private final SourceText source;
    private final DiagnosticCollector diagnostics;

    LexerDiagnosticListener(SourceText source, DiagnosticCollector diagnostics) {
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
        var position = source.position(line, charPositionInLine);
        int endOffset = Math.min(position.codePointOffset() + 1, source.length());
        diagnostics.error(
                "SIR-LEX-001",
                "存在 SIR v0.1 不允许的字符",
                new io.kcg.sir.source.SourceSpan(source.sourceId(), position, source.position(endOffset)));
    }
}
