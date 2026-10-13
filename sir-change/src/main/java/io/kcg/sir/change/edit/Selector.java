package io.kcg.sir.change.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Where in a draft a command acts: a path of slots from the root of the software.
 *
 * <p>Each step names a slot. A step into a collection also says which element: by <em>name</em> (the
 * element's own {@code name}, for declarations, fields, enum members) or by <em>index</em> (for
 * things with no name, such as workflow steps). Nothing is matched by similarity. An index is only
 * meaningful against the draft revision the commands run on: earlier commands in a batch can shift
 * it, which is why the batch applies commands in order and why names are preferred wherever the
 * element has one.
 */
public record Selector(List<Step> steps) {
   public Selector {
      steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
   }

   /** One hop: a slot, and for a collection slot the element chosen by name or index. */
   public record Step(String slot, Optional<String> name, Optional<Integer> index) {
      public Step {
         Objects.requireNonNull(slot, "slot");
         Objects.requireNonNull(name, "name");
         Objects.requireNonNull(index, "index");
         if (name.isPresent() && index.isPresent()) {
            throw new IllegalArgumentException("a step picks an element by name or by index, not both");
         }
      }

      @Override
      public String toString() {
         return this.slot + this.name.map(n -> "[" + n + "]").orElse("") + this.index.map(i -> "[" + i + "]").orElse("");
      }
   }

   public static Selector root() {
      return new Selector(List.of());
   }

   public Selector slot(String slot) {
      return this.with(new Step(slot, Optional.empty(), Optional.empty()));
   }

   public Selector named(String slot, String name) {
      return this.with(new Step(slot, Optional.of(name), Optional.empty()));
   }

   public Selector at(String slot, int index) {
      return this.with(new Step(slot, Optional.empty(), Optional.of(index)));
   }

   private Selector with(Step step) {
      List<Step> longer = new ArrayList<>(this.steps);
      longer.add(step);
      return new Selector(longer);
   }

   @Override
   public String toString() {
      return this.steps.isEmpty() ? "<software>" : String.join(".", this.steps.stream().map(Step::toString).toList());
   }
}
