package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Fact;
import java.util.Objects;
import java.util.Optional;

/** One parameter of a {@code feature} definition: its name, its kind of value ({@code name} or {@code duration}) and its default if it has one. */
public record NormalizedFeatureParameter(@Fact String name, @Fact String kind, @Fact Optional<String> defaultValue) {
   public NormalizedFeatureParameter {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(defaultValue, "defaultValue");
   }
}
