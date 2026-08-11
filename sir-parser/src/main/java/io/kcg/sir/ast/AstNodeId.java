package io.kcg.sir.ast;

import java.util.Objects;

public record AstNodeId(String value) {
    public AstNodeId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
