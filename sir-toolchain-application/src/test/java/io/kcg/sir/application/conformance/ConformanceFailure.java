package io.kcg.sir.application.conformance;

import java.util.Objects;
import java.util.Optional;

/**
 * A single conformance failure with a stable kind and a sanitized message key.
 *
 * <p>The {@code detail} field must never contain raw secrets, credentials,
 * raw {@code Throwable.toString()}, complete command lines, or complete
 * environment dumps. It is a sanitized, human-readable diagnostic.
 */
public final class ConformanceFailure {

    private final ConformanceFailureKind kind;
    private final String messageKey;
    private final String detail;
    private final Optional<String> scenarioName;

    public ConformanceFailure(ConformanceFailureKind kind, String messageKey, String detail) {
        this(kind, messageKey, detail, Optional.empty());
    }

    public ConformanceFailure(ConformanceFailureKind kind, String messageKey, String detail,
                              String scenarioName) {
        this(kind, messageKey, detail, Optional.of(scenarioName));
    }

    private ConformanceFailure(ConformanceFailureKind kind, String messageKey, String detail,
                               Optional<String> scenarioName) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.messageKey = Objects.requireNonNull(messageKey, "messageKey");
        if (messageKey.isBlank()) {
            throw new IllegalArgumentException("messageKey must not be blank");
        }
        this.detail = Objects.requireNonNull(detail, "detail");
        this.scenarioName = Objects.requireNonNull(scenarioName, "scenarioName");
    }

    public ConformanceFailureKind kind() {
        return kind;
    }

    public String messageKey() {
        return messageKey;
    }

    public String detail() {
        return detail;
    }

    public Optional<String> scenarioName() {
        return scenarioName;
    }

    @Override
    public String toString() {
        return scenarioName.isPresent()
                ? kind + "(" + messageKey + ")[" + scenarioName.get() + "]: " + detail
                : kind + "(" + messageKey + "): " + detail;
    }
}