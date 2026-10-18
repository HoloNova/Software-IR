package io.kcg.sir.semantic.type;

import io.kcg.sir.semantic.schema.Derived;
import io.kcg.sir.semantic.schema.Fact;
import java.util.Objects;

/**
 * A type the author names for what a value means: {@code Money}, {@code Count}, {@code Flag}, or a meaning of their
 * own. It is the type of a declared field; the analysis itself reasons about the kind of value it stands for
 * ({@link #kind}), and a target decides the physical representation from the meaning (a Java type, a column).
 */
public record SemanticType(@Fact String name, @Derived PrimitiveType kind) implements SirType {
   public SemanticType {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(kind, "kind");
   }
}
