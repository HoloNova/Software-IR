package io.kcg.sir.change.api;

import java.util.List;
import java.util.Objects;

public record ChangeSet(ChangeIrVersion version, ChangeBaseRevision basedOn, List<ChangeOperation> operations) {
   public ChangeSet {
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(basedOn, "basedOn");
      operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
      if (operations.size() != 1) {
         throw new IllegalArgumentException("operations must contain exactly one element; got " + operations.size());
      }
   }
}
