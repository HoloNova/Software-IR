package io.kcg.sir.application.internal.state;

import java.util.Objects;

public enum OutputMutationEvidence {
   NO_OUTPUT_MUTATION,
   DIRECTORY_MUTATION_OBSERVED,
   TARGET_MUTATION_POSSIBLE,
   TARGET_MUTATION_PROVED;

   public OutputMutationEvidence advanceTo(OutputMutationEvidence next) {
      Objects.requireNonNull(next, "next");
      return next.ordinal() >= this.ordinal() ? next : this;
   }

   public boolean isMutationObserved() {
      return this != NO_OUTPUT_MUTATION;
   }

   public boolean isTargetMutationPossible() {
      return this == TARGET_MUTATION_POSSIBLE || this == TARGET_MUTATION_PROVED;
   }
}
