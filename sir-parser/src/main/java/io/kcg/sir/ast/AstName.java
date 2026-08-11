package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstName(String text, SourceSpan span) {
    public AstName {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(span, "span");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
