package io.kcg.sir.lowering.api;

import java.util.Objects;

public record LoweredNodeId(String value) {

    public LoweredNodeId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
