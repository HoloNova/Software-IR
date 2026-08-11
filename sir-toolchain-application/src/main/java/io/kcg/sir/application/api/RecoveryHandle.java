package io.kcg.sir.application.api;

import java.util.Objects;
import java.util.Optional;

public record RecoveryHandle(Optional<String> transactionId) {
   public RecoveryHandle {
      Objects.requireNonNull(transactionId, "transactionId");
   }

   public static RecoveryHandle any() {
      return new RecoveryHandle(Optional.empty());
   }

   public static RecoveryHandle of(String transactionId) {
      Objects.requireNonNull(transactionId, "transactionId");
      if (transactionId.isBlank()) {
         throw new IllegalArgumentException("transactionId must not be blank");
      } else {
         return new RecoveryHandle(Optional.of(transactionId));
      }
   }
}
