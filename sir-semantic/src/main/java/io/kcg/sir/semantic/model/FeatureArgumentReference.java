package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Derived;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

/** A formally bound token range in an argument's canonical source value. */
public record FeatureArgumentReference(@Derived int start, @Derived int end, @Reference SymbolId target) {
    public FeatureArgumentReference {
        if (start < 0 || end <= start) throw new IllegalArgumentException("invalid argument token range");
        Objects.requireNonNull(target);
    }
}
