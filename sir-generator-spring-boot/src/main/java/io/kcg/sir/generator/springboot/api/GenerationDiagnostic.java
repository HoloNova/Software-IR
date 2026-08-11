package io.kcg.sir.generator.springboot.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import java.util.Objects;
import java.util.Optional;

public record GenerationDiagnostic(String code, String message, Optional<LoweredNodeId> nodeId) {
   public GenerationDiagnostic {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(message, "message");
      Objects.requireNonNull(nodeId, "nodeId");
      if (code.isBlank()) {
         throw new IllegalArgumentException("code must not be blank");
      }

      if (message.isBlank()) {
         throw new IllegalArgumentException("message must not be blank");
      }

      nodeId = Objects.requireNonNull(nodeId, "nodeId");
   }

   public static GenerationDiagnostic of(String code, String message) {
      return new GenerationDiagnostic(code, message, Optional.empty());
   }

   public static GenerationDiagnostic of(String code, String message, LoweredNodeId nodeId) {
      return new GenerationDiagnostic(code, message, Optional.of(nodeId));
   }
}
