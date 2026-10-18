package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Fact;
import java.util.Objects;

/** One written argument of a {@code use} declaration: the parameter and its value, e.g. {@code access} and {@code 15 minutes}. */
public record NormalizedFeatureArgument(@Fact String name, @Fact String value) {
   public NormalizedFeatureArgument {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
   }
}
