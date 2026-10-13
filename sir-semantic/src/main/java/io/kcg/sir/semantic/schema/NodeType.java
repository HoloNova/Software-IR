package io.kcg.sir.semantic.schema;

import java.util.List;
import java.util.Objects;

/**
 * One kind of node in the software model: a record and the classified slots it is made of.
 *
 * <p>{@link #kind()} is the record's simple name. {@link SoftwareSchema} rejects two records with
 * the same simple name, so a kind names exactly one record.
 */
public record NodeType(Class<?> type, String kind, List<SlotSpec> slots) {
   public NodeType {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(kind, "kind");
      slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
   }

   public SlotSpec slot(String key) {
      return this.slots.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow(() -> new IllegalArgumentException(this.kind + " has no slot " + key));
   }
}
