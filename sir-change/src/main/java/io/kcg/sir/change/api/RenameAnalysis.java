package io.kcg.sir.change.api;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of planning a rename.
 *
 * <p>{@link Planned} carries the read-only plan; {@link NoRename} means the request names the same
 * declaration under one name, so there is nothing to do; {@link Rejected} means the rename could not
 * be proven. A rejected rename never carries a partial plan: a plan exists only when the identity,
 * the artifact mapping and the three file sets were all verified.
 *
 * <p>There is deliberately no equivalent of {@code ChangeAnalysis.Failure} → apply path here: a
 * rename plan cannot be applied by {@code ChangePlanner}, and the existing
 * {@link ConflictPolicy#REPLACE_EXISTING} generation must not be used as a substitute. Applying a
 * rename atomically is a separate unit of work.
 */
public sealed interface RenameAnalysis permits RenameAnalysis.Planned, RenameAnalysis.NoRename, RenameAnalysis.Rejected {
   List<RenameDiagnostic> diagnostics();

   record Planned(RenamePlan plan, List<RenameDiagnostic> diagnostics) implements RenameAnalysis {
      public Planned {
         Objects.requireNonNull(plan, "plan");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().anyMatch(RenameDiagnostic::isError)) {
            throw new IllegalArgumentException("Planned must not contain ERROR diagnostics");
         }
      }
   }

   record NoRename(RenameNoChangeReason reason, List<RenameDiagnostic> diagnostics) implements RenameAnalysis {
      public NoRename {
         Objects.requireNonNull(reason, "reason");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().anyMatch(RenameDiagnostic::isError)) {
            throw new IllegalArgumentException("NoRename must not contain ERROR diagnostics");
         }
      }
   }

   record Rejected(List<RenameDiagnostic> diagnostics) implements RenameAnalysis {
      public Rejected {
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().noneMatch(RenameDiagnostic::isError)) {
            throw new IllegalArgumentException("Rejected must contain at least one ERROR diagnostic");
         }
      }
   }
}
