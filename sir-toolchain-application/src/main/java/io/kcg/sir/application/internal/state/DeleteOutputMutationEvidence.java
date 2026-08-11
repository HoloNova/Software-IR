package io.kcg.sir.application.internal.state;

import java.util.Objects;

public enum DeleteOutputMutationEvidence {
   NO_OUTPUT_MUTATION,
   BACKUP_LINK_POSSIBLE,
   BACKUP_LINK_PROVED,
   DELETE_INTENT_FORCED,
   TARGET_DELETED_PROVED;

   public DeleteOutputMutationEvidence advanceTo(DeleteOutputMutationEvidence next) {
      Objects.requireNonNull(next, "next");
      return next.ordinal() >= this.ordinal() ? next : this;
   }

   public boolean isMutationObserved() {
      return this != NO_OUTPUT_MUTATION;
   }

   public boolean isDeleteMutationPossible() {
      return this == DELETE_INTENT_FORCED || this == TARGET_DELETED_PROVED;
   }

   public boolean isTargetDeletedProved() {
      return this == TARGET_DELETED_PROVED;
   }
}
