package io.kcg.sir.application.execution;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Replaces every occurrence of a declared secret in text that is about to become evidence. A secret shorter than
 * four characters is refused rather than accepted: masking {@code "a"} would destroy the log and still protect nothing.
 */
final class LogRedactor {
    static final String MASK = "[REDACTED]";
    static final int MIN_SECRET_LENGTH = 4;
    private final List<String> secrets;

    LogRedactor(Collection<String> secrets) {
        Objects.requireNonNull(secrets, "secrets");
        for (String secret : secrets) {
            if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
                throw new IllegalArgumentException("a secret must be at least " + MIN_SECRET_LENGTH + " characters to be redacted reliably");
            }
        }

        // Longest first, so a secret that contains another is masked whole.
        this.secrets = secrets.stream().distinct().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder())).toList();
    }

    String redact(String text) {
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, MASK);
        }

        return result;
    }
}
