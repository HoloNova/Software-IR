package io.kcg.sir.internal;

import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

final class SourceText {
    private final SourceId sourceId;
    private final String content;
    private final int[] codePoints;
    private final int[] lines;
    private final int[] columns;

    SourceText(SourceId sourceId, String content) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.content = Objects.requireNonNull(content, "content");
        this.codePoints = content.codePoints().toArray();
        this.lines = new int[codePoints.length + 1];
        this.columns = new int[codePoints.length + 1];
        buildPositions();
    }

    SourceId sourceId() {
        return sourceId;
    }

    String content() {
        return content;
    }

    int length() {
        return codePoints.length;
    }

    SourcePosition position(int codePointOffset) {
        int offset = Math.max(0, Math.min(codePointOffset, codePoints.length));
        return new SourcePosition(offset, lines[offset], columns[offset]);
    }

    SourcePosition position(int oneBasedLine, int zeroBasedColumn) {
        int wantedColumn = zeroBasedColumn + 1;
        for (int offset = 0; offset < lines.length; offset++) {
            if (lines[offset] == oneBasedLine && columns[offset] == wantedColumn) {
                return position(offset);
            }
        }
        return position(codePoints.length);
    }

    SourceSpan span(Token token) {
        if (token == null) {
            SourcePosition eof = position(codePoints.length);
            return new SourceSpan(sourceId, eof, eof);
        }
        int start = token.getStartIndex() < 0 ? codePoints.length : token.getStartIndex();
        int end = token.getStopIndex() < start ? start : token.getStopIndex() + 1;
        return new SourceSpan(sourceId, position(start), position(end));
    }

    SourceSpan zeroSpan(Token token) {
        SourcePosition at = token == null
                ? position(codePoints.length)
                : position(Math.max(0, token.getStartIndex()));
        return new SourceSpan(sourceId, at, at);
    }

    SourceSpan insertionSpanAfter(Token token) {
        if (token == null) {
            return zeroSpan(null);
        }
        int end = token.getStopIndex() < token.getStartIndex()
                ? Math.max(0, token.getStartIndex())
                : token.getStopIndex() + 1;
        SourcePosition at = position(end);
        return new SourceSpan(sourceId, at, at);
    }

    SourceSpan span(ParserRuleContext context) {
        Objects.requireNonNull(context, "context");
        Token startToken = context.getStart();
        Token stopToken = context.getStop();
        int start = startToken == null || startToken.getStartIndex() < 0
                ? codePoints.length
                : startToken.getStartIndex();
        int end = stopToken == null || stopToken.getStopIndex() < start
                ? start
                : stopToken.getStopIndex() + 1;
        return new SourceSpan(sourceId, position(start), position(end));
    }

    private void buildPositions() {
        int line = 1;
        int column = 1;
        lines[0] = line;
        columns[0] = column;
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (codePoint == '\r') {
                line++;
                column = 1;
            } else if (codePoint == '\n') {
                if (index == 0 || codePoints[index - 1] != '\r') {
                    line++;
                }
                column = 1;
            } else {
                column++;
            }
            lines[index + 1] = line;
            columns[index + 1] = column;
        }
    }
}
