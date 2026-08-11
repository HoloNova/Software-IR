package io.kcg.sir.source;

import java.util.Objects;

/**
 * A left-closed, right-open source interval.
 */
public record SourceSpan(SourceId source, SourcePosition start, SourcePosition end) {
    public SourceSpan {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");

        if (end.codePointOffset() < start.codePointOffset()) {
            throw new IllegalArgumentException("end offset must not precede start offset");
        }
        if (end.line() < start.line()
                || (end.line() == start.line() && end.column() < start.column())) {
            throw new IllegalArgumentException("end position must not precede start position");
        }
    }
}
