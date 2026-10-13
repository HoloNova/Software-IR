package io.kcg.sir.semantic.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Puts the collections whose order carries no meaning into one fixed order.
 *
 * <p>Two drafts that describe the same software but list their declarations (or the errors a
 * capability may fail with) differently are the same software. Sorting every {@link Keyed}
 * collection by the element's name, or by the value itself for plain values, makes them compare
 * equal. Collections without the marker keep their order, because there it is part of the software.
 */
public final class DraftOrder {
   private DraftOrder() {
   }

   public static StructNode canonical(StructNode node, SoftwareSchema schema) {
      Optional<NodeType> type = schema.byKind(node.kind());
      List<StructNode.Slot> slots = new ArrayList<>();
      for (StructNode.Slot slot : node.slots()) {
         boolean keyed = type.flatMap(t -> t.slots().stream().filter(s -> s.key().equals(slot.key())).findFirst()).map(SlotSpec::keyed).orElse(false);
         slots.add(new StructNode.Slot(slot.key(), slot.kind(), value(slot.value(), keyed, schema)));
      }

      return new StructNode(node.kind(), slots);
   }

   private static StructValue value(StructValue value, boolean keyed, SoftwareSchema schema) {
      return switch (value) {
         case StructValue.Child child -> new StructValue.Child(canonical(child.node(), schema));
         case StructValue.Seq seq -> {
            List<StructValue> elements = new ArrayList<>(seq.elements().stream().map(e -> value(e, false, schema)).toList());
            if (keyed) {
               elements.sort(Comparator.comparing(DraftOrder::sortKey));
            }

            yield new StructValue.Seq(elements);
         }
         default -> value;
      };
   }

   private static String sortKey(StructValue element) {
      return switch (element) {
         case StructValue.Text text -> text.value();
         case StructValue.Child child -> child.node().kind() + ":" + SoftwareDraft.nameOf(child.node());
         default -> "";
      };
   }
}
