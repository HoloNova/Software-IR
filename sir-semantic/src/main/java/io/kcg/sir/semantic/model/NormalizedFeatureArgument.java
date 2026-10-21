package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Fact;
import java.util.Objects;

/** One written argument of a {@code use} declaration: the parameter and its value, e.g. {@code access} and {@code 15 minutes}. */
public record NormalizedFeatureArgument(@Fact String name, @Fact String value,
      @io.kcg.sir.semantic.schema.Derived java.util.List<FeatureArgumentReference> references) {
   public NormalizedFeatureArgument(String name, String value) { this(name, value, java.util.List.of()); }
   public NormalizedFeatureArgument {
      references = java.util.List.copyOf(references);
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
   }
}
