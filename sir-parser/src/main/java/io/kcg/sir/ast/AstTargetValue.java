package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstTargetValue<T>(T value, SourceSpan span) {
    public AstTargetValue {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(span, "span");
    }
}
