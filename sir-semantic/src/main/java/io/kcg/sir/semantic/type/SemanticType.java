package io.kcg.sir.semantic.type;

import io.kcg.sir.semantic.feature.SemanticTypes;
import io.kcg.sir.semantic.schema.Fact;
import java.util.Objects;

/**
 * A type the author names for what a value means: {@code Money}, {@code Count}, {@code Flag}. It is the type of a
 * declared field; the analysis itself reasons about the kind of value it stands for ({@link SemanticTypes#kindOf}),
 * and a target decides the physical representation from the meaning (a Java type, a column).
 */
public record SemanticType(@Fact String name) implements SirType {
   public SemanticType {
      Objects.requireNonNull(name, "name");
      if (SemanticTypes.named(name).isEmpty()) {
         throw new IllegalArgumentException("not a semantic type: " + name);
      }
   }

   /** The kind of value, which is what typing and a target's representation follow. */
   public PrimitiveType kind() {
      return SemanticTypes.named(this.name).orElseThrow().kind();
   }
}
