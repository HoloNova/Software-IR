package io.kcg.sir.lowering.api;

import java.util.Objects;

public record LoweringDiagnosticCode(String value) {

    public LoweringDiagnosticCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
