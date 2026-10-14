package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceSnapshot;
import java.util.Objects;

/**
 * One candidate, given as the exact source bytes of one software (a snapshot whose entry is the root document), and how far to verify it. The caller chooses {@code through} explicitly; it is never inferred,
 * so asking only for static generation can never start a process.
 */
public record VerificationRequest(String id, SourceSnapshot sources, VerificationStage through) {
    public static final int MAX_ID_CHARS = 256;

    public VerificationRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(through, "through");
        if (id.isBlank() || id.length() > MAX_ID_CHARS) {
            throw new IllegalArgumentException("id must be 1 to " + MAX_ID_CHARS + " characters");
        }
    }
}
