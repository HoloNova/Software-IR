package io.kcg.sir.change.edit;

import io.kcg.sir.semantic.schema.DraftValidator;
import io.kcg.sir.semantic.schema.NodeType;
import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.SlotSpec;
import io.kcg.sir.semantic.schema.SlotSpec.Cardinality;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Applies add, delete and update commands to a software draft.
 *
 * <p>The same mechanical steps serve every construct the schema describes: find the slot named by
 * the selector, check the new value has the shape the schema allows there, replace the subtree.
 * There is no code per kind of software element. The commands run in order on a private, immutable
 * draft; the first command that cannot be applied rejects the whole batch and nothing is returned
 * to publish.
 */
public final class DraftEdit {
   private static final Set<SlotKind> IDENTITY = Set.of(SlotKind.IDENTITY);

   private final SoftwareSchema schema;
   private final DraftValidator validator;

   public DraftEdit(SoftwareSchema schema) {
      this.schema = schema;
      this.validator = new DraftValidator(schema);
   }

   /** The edited draft, or the position and reason of the first command that could not be applied. */
   public sealed interface Result permits Applied, Rejected {
   }

   public record Applied(SoftwareDraft draft) implements Result {
   }

   public record Rejected(int commandIndex, String reason) implements Result {
   }

   public Result apply(SoftwareDraft draft, List<Command> commands) {
      StructNode root = draft.root();
      for (int i = 0; i < commands.size(); i++) {
         try {
            root = this.applyOne(root, commands.get(i));
         } catch (CommandRejected e) {
            return new Rejected(i, e.getMessage());
         }
      }

      return new Applied(draft.withRoot(root));
   }

   private StructNode applyOne(StructNode root, Command command) {
      return switch (command) {
         case Command.Add add -> this.descend(root, add.container().steps(), 0, add.container(), (node, spec, pick) -> this.add(node, spec, pick, add));
         case Command.Delete delete -> this.descend(root, delete.target().steps(), 0, delete.target(), (node, spec, pick) -> this.delete(node, spec, pick, delete));
         case Command.Update update -> this.descend(root, update.target().steps(), 0, update.target(), (node, spec, pick) -> this.update(node, spec, pick, update));
      };
   }

   /** What to do once the last step of a selector has been reached. */
   private interface Action {
      StructNode apply(StructNode owner, SlotSpec spec, Selector.Step pick);
   }

   private StructNode descend(StructNode node, List<Selector.Step> steps, int index, Selector whole, Action action) {
      if (steps.isEmpty()) {
         throw new CommandRejected("a command must name a slot; the software itself cannot be added, deleted or replaced");
      }

      Selector.Step step = steps.get(index);
      NodeType type = this.schema.byKind(node.kind()).orElseThrow(() -> new CommandRejected("unknown node kind " + node.kind()));
      SlotSpec spec = type.slots().stream().filter(s -> s.key().equals(step.slot())).findFirst()
         .orElseThrow(() -> new CommandRejected(whole + ": " + node.kind() + " has no slot '" + step.slot() + "'"));
      if (spec.kind() == SlotKind.SOURCE || spec.kind() == SlotKind.DERIVED) {
         throw new CommandRejected(whole + ": '" + step.slot() + "' is " + spec.kind() + " and is never edited");
      }

      if (index == steps.size() - 1) {
         return action.apply(node, spec, step);
      }

      // not the last step: walk into the chosen child and rebuild the path back up
      StructValue current = node.slot(step.slot()).value();
      StructValue rebuilt = switch (spec.cardinality()) {
         case MANY -> {
            List<StructValue> elements = new ArrayList<>(((StructValue.Seq)current).elements());
            int at = this.elementIndex(elements, step, whole);
            elements.set(at, new StructValue.Child(this.descend(childOf(elements.get(at), whole), steps, index + 1, whole, action)));
            yield new StructValue.Seq(elements);
         }
         default -> {
            if (!(current instanceof StructValue.Child child)) {
               throw new CommandRejected(whole + ": '" + step.slot() + "' holds no node to walk into");
            }

            yield new StructValue.Child(this.descend(child.node(), steps, index + 1, whole, action));
         }
      };
      return node.withSlot(step.slot(), rebuilt);
   }

   // ----- the three actions --------------------------------------------------------------------

   private StructNode add(StructNode owner, SlotSpec spec, Selector.Step pick, Command.Add command) {
      if (pick.name().isPresent() || pick.index().isPresent()) {
         throw new CommandRejected(command.container() + ": add targets a slot, not one of its elements");
      }

      StructValue value = this.canonical(command.value());
      this.requireShape(spec, value, command.container().toString());
      StructValue current = owner.slot(spec.key()).value();
      switch (spec.cardinality()) {
         case MANY -> {
            List<StructValue> elements = new ArrayList<>(((StructValue.Seq)current).elements());
            this.requireNewName(elements, value, command.container());
            if (spec.keyed() && value instanceof StructValue.Text && elements.contains(value)) {
               throw new CommandRejected(command.container() + " already contains " + ((StructValue.Text)value).value());
            }

            int at = spec.keyed() || command.position().isEmpty() ? elements.size() : command.position().getAsInt();
            if (at < 0 || at > elements.size()) {
               throw new CommandRejected(command.container() + ": position " + at + " is outside 0.." + elements.size());
            }

            elements.add(at, value);
            return owner.withSlot(spec.key(), new StructValue.Seq(elements));
         }
         case OPTIONAL -> {
            if (!(current instanceof StructValue.None)) {
               throw new CommandRejected(command.container() + " already has a value; use update");
            }

            return owner.withSlot(spec.key(), value);
         }
         default -> throw new CommandRejected(command.container() + " holds exactly one value; use update");
      }
   }

   private StructNode delete(StructNode owner, SlotSpec spec, Selector.Step pick, Command.Delete command) {
      StructValue current = owner.slot(spec.key()).value();
      switch (spec.cardinality()) {
         case MANY -> {
            List<StructValue> elements = new ArrayList<>(((StructValue.Seq)current).elements());
            int at = this.elementIndex(elements, pick, command.target());
            this.requireExpected(elements.get(at), command.expectedOld(), command.target());
            elements.remove(at);
            return owner.withSlot(spec.key(), new StructValue.Seq(elements));
         }
         case OPTIONAL -> {
            if (current instanceof StructValue.None) {
               throw new CommandRejected(command.target() + " has no value to delete");
            }

            this.requireExpected(current, command.expectedOld(), command.target());
            return owner.withSlot(spec.key(), new StructValue.None());
         }
         default -> throw new CommandRejected(command.target() + " holds exactly one value and cannot be deleted; update it instead");
      }
   }

   private StructNode update(StructNode owner, SlotSpec spec, Selector.Step pick, Command.Update command) {
      if (spec.kind() == SlotKind.IDENTITY) {
         throw new CommandRejected(command.target() + ": an identity is never edited");
      }

      StructValue current = owner.slot(spec.key()).value();
      boolean element = pick.name().isPresent() || pick.index().isPresent();
      if (spec.cardinality() == Cardinality.MANY && element) {
         List<StructValue> elements = new ArrayList<>(((StructValue.Seq)current).elements());
         int at = this.elementIndex(elements, pick, command.target());
         this.requireExpected(elements.get(at), command.expectedOld(), command.target());
         StructValue replacement = this.canonical(keepIdentity(elements.get(at), this.canonical(command.value())));
         this.requireShape(spec, replacement, command.target().toString());
         this.requireNameFree(elements, replacement, at, command.target());

         elements.set(at, replacement);
         return owner.withSlot(spec.key(), new StructValue.Seq(elements));
      }

      if (spec.cardinality() == Cardinality.MANY) {
         if (!(this.canonical(command.value()) instanceof StructValue.Seq seq)) {
            throw new CommandRejected(command.target() + ": a whole collection is replaced by a sequence");
         }

         this.requireExpected(current, command.expectedOld(), command.target());
         for (StructValue each : seq.elements()) {
            this.requireShape(spec, each, command.target().toString());
         }

         return owner.withSlot(spec.key(), seq);
      }

      if (spec.cardinality() == Cardinality.OPTIONAL && current instanceof StructValue.None) {
         throw new CommandRejected(command.target() + " has no value to update; use add");
      }

      this.requireExpected(current, command.expectedOld(), command.target());
      StructValue replacement = this.canonical(keepIdentity(current, this.canonical(command.value())));
      this.requireShape(spec, replacement, command.target().toString());
      return owner.withSlot(spec.key(), replacement);
   }

   // ----- checks -------------------------------------------------------------------------------

   private void requireShape(SlotSpec spec, StructValue value, String where) {
      List<String> problems = this.validator.validateElement(spec, value);
      if (!problems.isEmpty()) {
         throw new CommandRejected(where + ": " + String.join("; ", problems));
      }
   }

   private void requireExpected(StructValue current, Optional<StructValue> expected, Selector where) {
      if (expected.isPresent() && !withoutIdentity(current).equals(withoutIdentity(this.canonical(expected.get())))) {
         throw new CommandRejected(where + " is not what the command expected (the draft changed since it was read)");
      }
   }

   private void requireNewName(List<StructValue> elements, StructValue added, Selector where) {
      this.requireNameFree(elements, added, -1, where);
   }

   /** A new or replaced element may not take a name another element of the collection already has. */
   private void requireNameFree(List<StructValue> elements, StructValue candidate, int ignoring, Selector where) {
      Optional<String> name = nameOf(candidate);
      if (name.isEmpty()) {
         return;
      }

      for (int i = 0; i < elements.size(); i++) {
         if (i != ignoring && nameOf(elements.get(i)).equals(name)) {
            throw new CommandRejected(where + " already has an element named '" + name.get() + "'; an existing identity is never overwritten");
         }
      }
   }

   private int elementIndex(List<StructValue> elements, Selector.Step step, Selector whole) {
      if (step.index().isPresent()) {
         int at = step.index().get();
         if (at < 0 || at >= elements.size()) {
            throw new CommandRejected(whole + ": index " + at + " is outside 0.." + (elements.size() - 1));
         }

         return at;
      }

      if (step.name().isPresent()) {
         int found = -1;
         for (int i = 0; i < elements.size(); i++) {
            if (nameOf(elements.get(i)).equals(step.name())) {
               if (found >= 0) {
                  throw new CommandRejected(whole + ": more than one element is named '" + step.name().get() + "'");
               }

               found = i;
            }
         }

         if (found < 0) {
            throw new CommandRejected(whole + ": no element named '" + step.name().get() + "'");
         }

         return found;
      }

      throw new CommandRejected(whole + ": name the element with a name or an index");
   }

   private static StructNode childOf(StructValue value, Selector whole) {
      if (value instanceof StructValue.Child child) {
         return child.node();
      }

      throw new CommandRejected(whole + ": the element is not a node");
   }

   // ----- helpers ------------------------------------------------------------------------------

   /** The same value with every node's slots in the order the schema lists them, so equal values compare equal. */
   private StructValue canonical(StructValue value) {
      return switch (value) {
         case StructValue.Child child -> new StructValue.Child(this.canonicalNode(child.node()));
         case StructValue.Seq seq -> new StructValue.Seq(seq.elements().stream().map(this::canonical).toList());
         default -> value;
      };
   }

   private StructNode canonicalNode(StructNode node) {
      Optional<NodeType> type = this.schema.byKind(node.kind());
      if (type.isEmpty()) {
         return node;
      }

      List<StructNode.Slot> ordered = new ArrayList<>();
      for (SlotSpec spec : type.get().slots()) {
         node.find(spec.key()).ifPresent(slot -> ordered.add(new StructNode.Slot(slot.key(), slot.kind(), this.canonical(slot.value()))));
      }

      // slots the schema does not list stay, so the shape check can name them
      for (StructNode.Slot slot : node.slots()) {
         if (type.get().slots().stream().noneMatch(s -> s.key().equals(slot.key()))) {
            ordered.add(slot);
         }
      }

      return new StructNode(node.kind(), ordered);
   }

   /**
    * The name an element is picked by. A feature definition shares its name with the {@code use} of the feature, so it
    * is picked as {@code feature Tagging}; every other element is picked by its own name.
    */
   private static Optional<String> nameOf(StructValue value) {
      if (value instanceof StructValue.Child child) {
         Optional<String> own = child.node().find("name").map(StructNode.Slot::value).filter(v -> v instanceof StructValue.Text).map(v -> ((StructValue.Text)v).value());
         return switch (child.node().kind()) {
            case "NormalizedFeatureDefinition" -> own.map(n -> "feature " + n);
            case "NormalizedEntityExtension" -> own.map(n -> "extend " + n);
            default -> own;
         };
      }

      return Optional.empty();
   }

   private static StructValue withoutIdentity(StructValue value) {
      return switch (value) {
         case StructValue.Child child -> new StructValue.Child(child.node().without(IDENTITY));
         case StructValue.Seq seq -> new StructValue.Seq(seq.elements().stream().map(DraftEdit::withoutIdentity).toList());
         default -> value;
      };
   }

   /** A replacement of the same kind inherits the identity of what it replaces; identity is not an edit. */
   private static StructValue keepIdentity(StructValue old, StructValue replacement) {
      if (!(old instanceof StructValue.Child oldChild) || !(replacement instanceof StructValue.Child newChild)) {
         return replacement;
      }

      if (!oldChild.node().kind().equals(newChild.node().kind())) {
         return replacement;
      }

      List<StructNode.Slot> slots = new ArrayList<>(newChild.node().slots());
      for (StructNode.Slot oldSlot : oldChild.node().slots()) {
         if (oldSlot.kind() == SlotKind.IDENTITY && newChild.node().find(oldSlot.key()).isEmpty()) {
            slots.add(0, oldSlot);
         }
      }

      return new StructValue.Child(new StructNode(newChild.node().kind(), slots));
   }

   /** A command that cannot be applied; carries the reason shown to whoever wrote the batch. */
   private static final class CommandRejected extends RuntimeException {
      CommandRejected(String message) {
         super(message);
      }
   }
}
