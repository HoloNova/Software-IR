package io.kcg.sir.api;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record RelatedLocation(String message, SourceSpan span) {
    public RelatedLocation {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(span, "span");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
