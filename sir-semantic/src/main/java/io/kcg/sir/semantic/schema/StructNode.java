package io.kcg.sir.semantic.schema;

import java.util.List;
import java.util.Objects;

/**
 * One node of the generic software structure: a kind and its classified slots.
 *
 * <p>Two nodes are equal exactly when they have the same kind and the same slot values, so
 * comparing two views answers "did the software change" without any per-kind comparison code.
 */
public record StructNode(String kind, List<Slot> slots) {
   public StructNode {
      Objects.requireNonNull(kind, "kind");
      slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
   }

   /** One slot of a node with the role the schema gave it. */
   public record Slot(String key, SlotKind kind, StructValue value) {
      public Slot {
         Objects.requireNonNull(key, "key");
         Objects.requireNonNull(kind, "kind");
         Objects.requireNonNull(value, "value");
      }
   }

   public Slot slot(String key) {
      return this.slots.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow(() -> new IllegalArgumentException(this.kind + " has no slot " + key));
   }

   public java.util.Optional<Slot> find(String key) {
      return this.slots.stream().filter(s -> s.key().equals(key)).findFirst();
   }

   /** A copy of this node whose slot {@code key} holds {@code value}; the slot must exist. */
   public StructNode withSlot(String key, StructValue value) {
      List<Slot> changed = new java.util.ArrayList<>();
      boolean found = false;
      for (Slot slot : this.slots) {
         if (slot.key().equals(key)) {
            changed.add(new Slot(slot.key(), slot.kind(), value));
            found = true;
         } else {
            changed.add(slot);
         }
      }

      if (!found) {
         throw new IllegalArgumentException(this.kind + " has no slot " + key);
      }

      return new StructNode(this.kind, changed);
   }

   /** A deep copy without the slots of the given roles; used to compare shape without identity or position. */
   public StructNode without(java.util.Set<SlotKind> roles) {
      List<Slot> kept = new java.util.ArrayList<>();
      for (Slot slot : this.slots) {
         if (!roles.contains(slot.kind())) {
            kept.add(new Slot(slot.key(), slot.kind(), withoutRoles(slot.value(), roles)));
         }
      }

      return new StructNode(this.kind, kept);
   }

   private static StructValue withoutRoles(StructValue value, java.util.Set<SlotKind> roles) {
      return switch (value) {
         case StructValue.Child child -> new StructValue.Child(child.node().without(roles));
         case StructValue.Seq seq -> new StructValue.Seq(seq.elements().stream().map(e -> withoutRoles(e, roles)).toList());
         default -> value;
      };
   }

   /** An indented, deterministic text form for reports and test failure messages. */
   public String render() {
      StringBuilder out = new StringBuilder();
      this.render(out, 0);
      return out.toString();
   }

   private void render(StringBuilder out, int depth) {
      out.append("  ".repeat(depth)).append(this.kind).append('\n');
      for (Slot slot : this.slots) {
         renderValue(out, depth + 1, slot.key() + " [" + slot.kind() + "]", slot.value());
      }
   }

   private static void renderValue(StringBuilder out, int depth, String label, StructValue value) {
      String indent = "  ".repeat(depth);
      switch (value) {
         case StructValue.Text text -> out.append(indent).append(label).append(" = ").append(text.value()).append('\n');
         case StructValue.None none -> out.append(indent).append(label).append(" = (none)\n");
         case StructValue.Child child -> {
            out.append(indent).append(label).append(" =\n");
            child.node().render(out, depth + 1);
         }
         case StructValue.Seq seq -> {
            out.append(indent).append(label).append(" = [").append(seq.elements().size()).append("]\n");
            for (StructValue element : seq.elements()) {
               renderValue(out, depth + 1, "-", element);
            }
         }
      }
   }
}
