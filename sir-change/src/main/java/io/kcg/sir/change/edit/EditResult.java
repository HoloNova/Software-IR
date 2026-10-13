package io.kcg.sir.change.edit;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The outcome of editing a source with a command batch: a new source, or a refusal with the reason. */
public sealed interface EditResult permits EditResult.Edited, EditResult.Refused {
   /**
    * The batch was applied. {@code source} is a complete, valid SIR text that has already been read
    * back through the formal analysis and found to say what the commands asked for.
    *
    * @param delta         what changed in the software
    * @param sourceChanges what changed in the text, for review before anything is saved
    */
   record Edited(String source, SemanticDelta delta, List<SourceChange> sourceChanges) implements EditResult {
      public Edited {
         Objects.requireNonNull(source, "source");
         Objects.requireNonNull(delta, "delta");
         sourceChanges = List.copyOf(Objects.requireNonNull(sourceChanges, "sourceChanges"));
      }
   }

   /**
    * Nothing was produced. The baseline source is untouched.
    *
    * @param candidateSource for an invalid result, the text that was tried, so the author can see it
    */
   record Refused(Reason reason, String message, List<String> details, Optional<String> candidateSource) implements EditResult {
      public Refused {
         Objects.requireNonNull(reason, "reason");
         Objects.requireNonNull(message, "message");
         details = List.copyOf(Objects.requireNonNull(details, "details"));
         Objects.requireNonNull(candidateSource, "candidateSource");
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

   /** One change in the text of the source. */
   record SourceChange(Type type, String declaration, String before, String after) {
      public enum Type {
         REWRITE,
         INSERT,
         REMOVE
      }
   }
}
