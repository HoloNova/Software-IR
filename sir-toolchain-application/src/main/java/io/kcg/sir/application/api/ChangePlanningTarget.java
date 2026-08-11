package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ChangeTarget;
import java.util.Objects;
import java.util.Optional;

public record ChangePlanningTarget(
   String targetKey,
   ChangePlanningSide side,
   ChangePlanningTargetKind kind,
   ChangeTarget target,
   String declarationDisplayName,
   Optional<String> targetDisplayName
) {
   public ChangePlanningTarget {
      Objects.requireNonNull(targetKey, "targetKey");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(declarationDisplayName, "declarationDisplayName");
      targetDisplayName = Objects.requireNonNull(targetDisplayName, "targetDisplayName");
      if (!targetKey.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("targetKey must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }
}
