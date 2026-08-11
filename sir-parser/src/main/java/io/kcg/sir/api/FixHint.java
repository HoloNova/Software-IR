package io.kcg.sir.api;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record FixHint(String message, SourceSpan replacementSpan, String replacementText) {
    public FixHint {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(replacementSpan, "replacementSpan");
        Objects.requireNonNull(replacementText, "replacementText");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
