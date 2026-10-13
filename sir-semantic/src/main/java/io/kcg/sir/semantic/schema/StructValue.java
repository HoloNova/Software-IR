package io.kcg.sir.semantic.schema;

import java.util.List;
import java.util.Objects;

/** A value inside a {@link StructNode}: one piece of text, nothing, a child node, or a sequence. */
public sealed interface StructValue permits StructValue.Text, StructValue.None, StructValue.Child, StructValue.Seq {
   /** A scalar, a reference or a source position rendered as canonical text. */
   record Text(String value) implements StructValue {
      public Text {
         Objects.requireNonNull(value, "value");
      }
   }

   /** An absent optional value. */
   record None() implements StructValue {
   }

   record Child(StructNode node) implements StructValue {
      public Child {
         Objects.requireNonNull(node, "node");
      }
   }

   /** Elements in the order that carries meaning, or in identity order for a keyed collection. */
   record Seq(List<StructValue> elements) implements StructValue {
      public Seq {
         elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
      }
   }
}
