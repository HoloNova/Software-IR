package io.kcg.sir.api;

import java.util.Objects;

public record DiagnosticCode(String value) {
    public DiagnosticCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
