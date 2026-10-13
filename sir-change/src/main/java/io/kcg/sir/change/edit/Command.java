package io.kcg.sir.change.edit;

import io.kcg.sir.semantic.schema.StructValue;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The three things a change can do to a software draft: add, delete, update.
 *
 * <p>A command names where it acts and carries typed values; it never carries a rule about what the
 * software may contain. Whether the result is a valid software is decided only by the formal
 * analysis of the source written from the edited draft, so a new language construct needs no new
 * command.
 */
public sealed interface Command permits Command.Add, Command.Delete, Command.Update {
   /**
    * Puts a new element into a collection (or a value into an empty optional slot).
    *
    * @param container the collection slot, or the empty optional slot
    * @param position  where in an ordered collection; a collection matched by identity (the
    *                  declarations) has no meaningful order and ignores it
    */
   record Add(Selector container, StructValue value, OptionalInt position) implements Command {
      public Add {
         Objects.requireNonNull(container, "container");
         Objects.requireNonNull(value, "value");
         Objects.requireNonNull(position, "position");
      }

      public Add(Selector container, StructValue value) {
         this(container, value, OptionalInt.empty());
      }
   }

   /**
    * Removes one element of a collection, or the value of an optional slot. Nothing that referred to
    * it is cleaned up: a dangling reference is the formal analysis' error to report.
    *
    * @param expectedOld if present, the current value must match it (identity ignored) or the command fails
    */
   record Delete(Selector target, Optional<StructValue> expectedOld) implements Command {
      public Delete {
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(expectedOld, "expectedOld");
      }

      public Delete(Selector target) {
         this(target, Optional.empty());
      }
   }

   /**
    * Replaces the value at {@code target}: an attribute, a child subtree, one element, or a whole
    * collection. A replaced node keeps its identity; an identity is never edited.
    *
    * @param expectedOld if present, the current value must match it (identity ignored) or the command fails
    */
   record Update(Selector target, StructValue value, Optional<StructValue> expectedOld) implements Command {
      public Update {
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(value, "value");
         Objects.requireNonNull(expectedOld, "expectedOld");
      }

      public Update(Selector target, StructValue value) {
         this(target, value, Optional.empty());
      }
   }
}
