package io.kcg.sir.semantic.schema;

import java.util.Objects;

/**
 * One classified component of a {@link NodeType}.
 *
 * @param key        the record component name; the stable slot key
 * @param kind       the role the component plays
 * @param keyed      whether a collection is matched by element identity instead of by position
 * @param cardinality how many values the slot holds
 * @param valueType  the element type with {@code Optional} and {@code List} removed
 * @param viaVariableType for a reference: the written text is the type of the parameter variable it names
 */
public record SlotSpec(String key, SlotKind kind, boolean keyed, Cardinality cardinality, Class<?> valueType, boolean viaVariableType) {
   public SlotSpec {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(cardinality, "cardinality");
      Objects.requireNonNull(valueType, "valueType");
   }

   public SlotSpec(String key, SlotKind kind, boolean keyed, Cardinality cardinality, Class<?> valueType) {
      this(key, kind, keyed, cardinality, valueType, false);
   }

   public enum Cardinality {
      ONE,
      OPTIONAL,
      MANY
   }
}
