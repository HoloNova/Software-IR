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
                message(source.codePointAt(position.codePointOffset())),
                new io.kcg.sir.source.SourceSpan(source.sourceId(), position, source.position(endOffset)));
    }

    /** Names the rejected character; only "?" gets a reason, because only its intent is known (a ? : choice). */
    static String message(int codePoint) {
        if (codePoint < 0) {
            return "存在 SIR 不允许的字符";
        }
        String shown = "“" + display(codePoint) + "”";
        if (codePoint == '?') {
            return "存在 SIR 不允许的字符" + shown + "：SIR 没有 ? : 条件表达式；局部更新的字段直接写 字段: input.字段;，没传会自动保留原值";
        }
        return "存在 SIR 不允许的字符" + shown;
    }

    /** Invisible or space-like characters are written as U+XXXX so the message never contains something unseen. */
    private static String display(int codePoint) {
        int type = Character.getType(codePoint);
        boolean invisible = Character.isISOControl(codePoint) || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || type == Character.FORMAT || type == Character.UNASSIGNED || type == Character.PRIVATE_USE
                || type == Character.SURROGATE;
        return invisible ? String.format("U+%04X", codePoint) : new String(Character.toChars(codePoint));
    }
}
