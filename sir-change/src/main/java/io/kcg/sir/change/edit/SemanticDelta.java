package io.kcg.sir.change.edit;

import io.kcg.sir.semantic.schema.NodeType;
import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.SlotSpec;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What changed in the software between two revisions, in the software's own terms.
 *
 * <p>Computed by walking two drafts through the schema, so every construct the schema describes is
 * compared the same way and none is compared by hand. It answers "what is different about the
 * software", not "which files differ": one added field changes several generated files, and a
 * source that merely moved changes none.
 *
 * <p>Elements that carry an identity (or are plain names) are matched by it, so inserting a field
 * is one addition and not a shift of every later field. Elements with neither, such as workflow
 * steps, are matched by position.
 */
public record SemanticDelta(List<Change> changes) {
   public SemanticDelta {
      changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
   }

   public enum Kind {
      ADD,
      DELETE,
      UPDATE
   }

   /** One difference. {@code before} is empty for an addition, {@code after} for a deletion. */
   public record Change(Kind kind, String path, String before, String after) {
      @Override
      public String toString() {
         return this.kind + " " + this.path + (this.before.isEmpty() ? "" : "  was: " + this.before) + (this.after.isEmpty() ? "" : "  now: " + this.after);
      }
   }

   public boolean isEmpty() {
      return this.changes.isEmpty();
   }

   public static SemanticDelta between(SoftwareDraft before, SoftwareDraft after, SoftwareSchema schema) {
      List<Change> out = new ArrayList<>();
      new Differ(schema, out).node(before.root().kind(), before.root(), after.root());
      return new SemanticDelta(out);
   }

   private record Differ(SoftwareSchema schema, List<Change> out) {
      void node(String path, StructNode before, StructNode after) {
         if (!before.kind().equals(after.kind())) {
            this.out.add(new Change(Kind.UPDATE, path, label(before), label(after)));
            return;
         }

         Optional<NodeType> type = this.schema.byKind(before.kind());
         for (StructNode.Slot slot : before.slots()) {
            if (slot.kind() == SlotKind.IDENTITY) {
               continue;
            }

            boolean keyed = type.flatMap(t -> t.slots().stream().filter(s -> s.key().equals(slot.key())).findFirst()).map(SlotSpec::keyed).orElse(false);
            StructValue other = after.slot(slot.key()).value();
            this.value(path + "." + slot.key(), keyed, slot.value(), other);
         }
      }

      void value(String path, boolean keyed, StructValue before, StructValue after) {
         switch (before) {
            case StructValue.Text b -> {
               if (!(after instanceof StructValue.Text a) || !a.value().equals(b.value())) {
                  this.out.add(new Change(Kind.UPDATE, path, describe(before), describe(after)));
               }
            }
            case StructValue.None b -> {
               if (!(after instanceof StructValue.None)) {
                  this.out.add(new Change(Kind.ADD, path, "", describe(after)));
               }
            }
            case StructValue.Child b -> {
               if (after instanceof StructValue.None) {
                  this.out.add(new Change(Kind.DELETE, path, describe(before), ""));
               } else if (after instanceof StructValue.Child a) {
                  this.node(path + "/" + label(a.node()), b.node(), a.node());
               } else {
                  this.out.add(new Change(Kind.UPDATE, path, describe(before), describe(after)));
               }
            }
            case StructValue.Seq b -> this.sequence(path, keyed, b.elements(), ((StructValue.Seq)after).elements());
         }
      }

      void sequence(String path, boolean keyed, List<StructValue> before, List<StructValue> after) {
         Map<String, StructValue> beforeByKey = keysOf(before);
         Map<String, StructValue> afterByKey = keysOf(after);
         if (beforeByKey != null && afterByKey != null) {
            for (Map.Entry<String, StructValue> entry : beforeByKey.entrySet()) {
               StructValue match = afterByKey.get(entry.getKey());
               if (match == null) {
                  this.out.add(new Change(Kind.DELETE, path, describe(entry.getValue()), ""));
               } else {
                  this.value(path, false, entry.getValue(), match);
               }
            }

            for (Map.Entry<String, StructValue> entry : afterByKey.entrySet()) {
               if (!beforeByKey.containsKey(entry.getKey())) {
                  this.out.add(new Change(Kind.ADD, path, "", describe(entry.getValue())));
               }
            }

            List<String> common = beforeByKey.keySet().stream().filter(afterByKey::containsKey).toList();
            List<String> commonAfter = afterByKey.keySet().stream().filter(beforeByKey::containsKey).toList();
            if (!keyed && !common.equals(commonAfter)) {
               this.out.add(new Change(Kind.UPDATE, path + " (order)", String.join(", ", common), String.join(", ", commonAfter)));
            }

            return;
         }

         int shared = Math.min(before.size(), after.size());
         for (int i = 0; i < shared; i++) {
            this.value(path + "[" + i + "]", false, before.get(i), after.get(i));
         }

         for (int i = shared; i < before.size(); i++) {
            this.out.add(new Change(Kind.DELETE, path + "[" + i + "]", describe(before.get(i)), ""));
         }

         for (int i = shared; i < after.size(); i++) {
            this.out.add(new Change(Kind.ADD, path + "[" + i + "]", "", describe(after.get(i))));
         }
      }
   }

   /** The match key of every element, or null when the elements cannot all be told apart by one. */
   private static Map<String, StructValue> keysOf(List<StructValue> elements) {
      Map<String, StructValue> keyed = new LinkedHashMap<>();
      for (StructValue element : elements) {
         String key = switch (element) {
            case StructValue.Text text -> text.value();
            case StructValue.Child child -> identityOrName(child.node());
            default -> null;
         };
         if (key == null || keyed.put(key, element) != null) {
            return null;
         }
      }

      return keyed;
   }

   private static String identityOrName(StructNode node) {
      Optional<String> identity = SoftwareDraft.identityOf(node);
      if (identity.isPresent()) {
         return node.kind() + ":" + identity.get();
      }

      return node.find("name").filter(s -> s.value() instanceof StructValue.Text).map(s -> node.kind() + ":" + ((StructValue.Text)s.value()).value()).orElse(null);
   }

   private static String label(StructNode node) {
      String name = SoftwareDraft.nameOf(node);
      return name.isEmpty() ? node.kind() : node.kind() + "[" + name + "]";
   }

   private static String describe(StructValue value) {
      return switch (value) {
         case StructValue.Text text -> text.value();
         case StructValue.None none -> "";
         case StructValue.Child child -> label(child.node());
         case StructValue.Seq seq -> "[" + seq.elements().size() + " elements]";
      };
   }
}
