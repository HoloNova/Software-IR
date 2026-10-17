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

   /**
    * One difference. {@code before} is empty for an addition, {@code after} for a deletion.
    *
    * <p>The path is for people to read. A consumer that must know which declaration or member changed
    * uses the identities instead: {@code trail} lists, outermost first, the identified elements the
    * change lies in, and {@code subject} is the identity of the element an addition or deletion adds or
    * deletes, when that element has one. So adding a field reports the field as subject inside its
    * entity's trail, while adding a constraint to the field reports no subject inside a trail that
    * ends with the field.
    */
   public record Change(Kind kind, String path, String before, String after, List<String> trail, Optional<String> subject) {
      public Change {
         Objects.requireNonNull(kind, "kind");
         Objects.requireNonNull(path, "path");
         Objects.requireNonNull(before, "before");
         Objects.requireNonNull(after, "after");
         trail = List.copyOf(Objects.requireNonNull(trail, "trail"));
         Objects.requireNonNull(subject, "subject");
         if (subject.isPresent() && kind == Kind.UPDATE) {
            throw new IllegalArgumentException("only an addition or deletion has a subject");
         }
      }

      public Change(Kind kind, String path, String before, String after) {
         this(kind, path, before, after, List.of(), Optional.empty());
      }

      private Change(Kind kind, String path, String before, String after, List<String> trail) {
         this(kind, path, before, after, trail, Optional.empty());
      }

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
      new Differ(schema, out).node(before.root().kind(), List.of(), before.root(), after.root());
      return new SemanticDelta(out);
   }

   private record Differ(SoftwareSchema schema, List<Change> out) {
      void node(String path, List<String> outer, StructNode before, StructNode after) {
         List<String> trail = within(outer, before);
         if (!before.kind().equals(after.kind())) {
            this.out.add(new Change(Kind.UPDATE, path, label(before), label(after), trail));
            return;
         }

         Optional<NodeType> type = this.schema.byKind(before.kind());
         for (StructNode.Slot slot : before.slots()) {
            if (slot.kind() == SlotKind.IDENTITY) {
               continue;
            }

            boolean keyed = type.flatMap(t -> t.slots().stream().filter(s -> s.key().equals(slot.key())).findFirst()).map(SlotSpec::keyed).orElse(false);
            StructValue other = after.slot(slot.key()).value();
            this.value(path + "." + slot.key(), trail, keyed, slot.value(), other);
         }
      }

      void value(String path, List<String> trail, boolean keyed, StructValue before, StructValue after) {
         switch (before) {
            case StructValue.Text b -> {
               if (!(after instanceof StructValue.Text a) || !a.value().equals(b.value())) {
                  this.out.add(new Change(Kind.UPDATE, path, describe(before), describe(after), trail));
               }
            }
            case StructValue.None b -> {
               if (!(after instanceof StructValue.None)) {
                  this.out.add(new Change(Kind.ADD, path, "", describe(after), trail, identityOf(after)));
               }
            }
            case StructValue.Child b -> {
               if (after instanceof StructValue.None) {
                  this.out.add(new Change(Kind.DELETE, path, describe(before), "", trail, identityOf(before)));
               } else if (after instanceof StructValue.Child a) {
                  this.node(path + "/" + label(a.node()), trail, b.node(), a.node());
               } else {
                  this.out.add(new Change(Kind.UPDATE, path, describe(before), describe(after), trail));
               }
            }
            case StructValue.Seq b -> this.sequence(path, trail, keyed, b.elements(), ((StructValue.Seq)after).elements());
         }
      }

      void sequence(String path, List<String> trail, boolean keyed, List<StructValue> before, List<StructValue> after) {
         Map<String, StructValue> beforeByKey = keysOf(before);
         Map<String, StructValue> afterByKey = keysOf(after);
         if (beforeByKey != null && afterByKey != null) {
            for (Map.Entry<String, StructValue> entry : beforeByKey.entrySet()) {
               StructValue match = afterByKey.get(entry.getKey());
               if (match == null) {
                  this.out.add(new Change(Kind.DELETE, path, describe(entry.getValue()), "", trail, identityOf(entry.getValue())));
               } else {
                  this.value(path, trail, false, entry.getValue(), match);
               }
            }

            for (Map.Entry<String, StructValue> entry : afterByKey.entrySet()) {
               if (!beforeByKey.containsKey(entry.getKey())) {
                  this.out.add(new Change(Kind.ADD, path, "", describe(entry.getValue()), trail, identityOf(entry.getValue())));
               }
            }

            List<String> common = beforeByKey.keySet().stream().filter(afterByKey::containsKey).toList();
            List<String> commonAfter = afterByKey.keySet().stream().filter(beforeByKey::containsKey).toList();
            if (!keyed && !common.equals(commonAfter)) {
               this.out.add(new Change(Kind.UPDATE, path + " (order)", String.join(", ", common), String.join(", ", commonAfter), trail));
            }

            return;
         }

         int shared = Math.min(before.size(), after.size());
         for (int i = 0; i < shared; i++) {
            this.value(path + "[" + i + "]", trail, false, before.get(i), after.get(i));
         }

         for (int i = shared; i < before.size(); i++) {
            this.out.add(new Change(Kind.DELETE, path + "[" + i + "]", describe(before.get(i)), "", trail, identityOf(before.get(i))));
         }

         for (int i = shared; i < after.size(); i++) {
            this.out.add(new Change(Kind.ADD, path + "[" + i + "]", "", describe(after.get(i)), trail, identityOf(after.get(i))));
         }
      }
   }

   /** The identity of {@code value}, when it is a node that has one. */
   private static Optional<String> identityOf(StructValue value) {
      return value instanceof StructValue.Child child ? SoftwareDraft.identityOf(child.node()) : Optional.empty();
   }

   /** The trail extended by the identity of {@code node}, when it has one. */
   private static List<String> within(List<String> trail, StructNode node) {
      Optional<String> identity = SoftwareDraft.identityOf(node);
      if (identity.isEmpty()) {
         return trail;
      }

      List<String> extended = new ArrayList<>(trail);
      extended.add(identity.get());
      return List.copyOf(extended);
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
