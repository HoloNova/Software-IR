package io.kcg.sir.change.edit;

import io.kcg.sir.change.edit.EditResult.Reason;
import io.kcg.sir.change.edit.EditResult.SourceChange;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.util.List;
import java.util.Objects;

/** The outcome of editing a multi-file project with a command batch: a new set of sources, or a refusal. */
public sealed interface ProjectEditResult permits ProjectEditResult.Edited, ProjectEditResult.Refused {
   /**
    * The batch was applied. {@code sources} has the same entry and the same files, with only the
    * files that contain a changed declaration different, and has been read back through the formal
    * project analysis.
    *
    * @param delta   what changed in the software
    * @param changes what changed in the text, per source file, for review before anything is saved
    */
   record Edited(SourceSnapshot sources, SemanticDelta delta, List<LocatedChange> changes) implements ProjectEditResult {
      public Edited {
         Objects.requireNonNull(sources, "sources");
         Objects.requireNonNull(delta, "delta");
         changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
      }
   }

   /** Nothing was produced; the baseline sources are untouched. */
   record Refused(Reason reason, String message, List<String> details) implements ProjectEditResult {
      public Refused {
         Objects.requireNonNull(reason, "reason");
         Objects.requireNonNull(message, "message");
         details = List.copyOf(Objects.requireNonNull(details, "details"));
      }

      public static Refused of(EditResult.Refused refused) {
         return new Refused(refused.reason(), refused.message(), refused.details());
      }
   }

   /** One change in the text, and the source file it is in. */
   record LocatedChange(SourceId source, SourceChange change) {
      public LocatedChange {
         Objects.requireNonNull(source, "source");
         Objects.requireNonNull(change, "change");
      }
   }
}
