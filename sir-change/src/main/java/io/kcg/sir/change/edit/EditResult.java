package io.kcg.sir.change.edit;

import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The outcome of editing a set of SIR sources with a command batch: new sources, or a refusal with the reason. */
public sealed interface EditResult permits EditResult.Edited, EditResult.Refused {
   /**
    * The batch was applied. {@code sources} has the same entry and the same files, with only the files that contain a
    * changed declaration different, and has already been read back through the formal analysis and found to say what the
    * commands asked for.
    *
    * @param delta   what changed in the software
    * @param changes what changed in the text, per source file, for review before anything is saved
    */
   record Edited(SourceSnapshot sources, SemanticDelta delta, List<LocatedChange> changes) implements EditResult {
      public Edited {
         Objects.requireNonNull(sources, "sources");
         Objects.requireNonNull(delta, "delta");
         changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
      }
   }

   /**
    * Nothing was produced. The baseline sources are untouched.
    *
    * @param candidate for an invalid result, the sources that were tried, so the author can see them
    */
   record Refused(Reason reason, String message, List<String> details, Optional<SourceSnapshot> candidate) implements EditResult {
      public Refused {
         Objects.requireNonNull(reason, "reason");
         Objects.requireNonNull(message, "message");
         details = List.copyOf(Objects.requireNonNull(details, "details"));
         Objects.requireNonNull(candidate, "candidate");
      }
   }

   /** Why a batch was refused. */
   enum Reason {
      /** The batch names a protocol this version does not know. */
      UNSUPPORTED_BATCH,
      /** The source is in a form the editor does not yet handle (for example a leading byte order mark). */
      UNSUPPORTED_SOURCE,
      /** The batch was written against a different source or software than the one given. */
      STALE_BASELINE,
      /** The source to edit is not itself a valid software. */
      BASELINE_INVALID,
      /** A command could not be applied; the batch carries all-or-nothing meaning. */
      COMMAND_REJECTED,
      /** The commands reach something the editor cannot write back yet. */
      NOT_SUPPORTED_YET,
      /** A rewrite or deletion would remove a comment, which is protected. */
      PROTECTED_COMMENT,
      /** The language cannot spell what the draft says. */
      NOT_REPRESENTABLE,
      /** The edited software is not valid; the formal analysis explains why. */
      INVALID_RESULT,
      /** The written source does not read back as the draft; never published. */
      ROUND_TRIP_MISMATCH
   }

   /** One change in the text of a source file. */
   record SourceChange(Type type, String declaration, String before, String after) {
      public enum Type {
         REWRITE,
         INSERT,
         REMOVE
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
