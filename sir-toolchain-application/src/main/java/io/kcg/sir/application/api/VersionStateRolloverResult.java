package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** How a rollover ended, in terms of what the caller must do next and what was left where. */
public sealed interface VersionStateRolloverResult permits VersionStateRolloverResult.RolledOver, VersionStateRolloverResult.Refused {
    /** What a refused rollover left in the new state directory. */
    enum NewStateTouched {
        /** Nothing was created or written there. */
        NO,
        /** The directory may hold a partial state from this call. It is safe to discard, and nothing else may be there. */
        PARTIAL_STATE
    }

    /** What the old state's history held when it was retired; its limits are what made the rollover necessary. */
    record ArchiveFacts(int versions, int relations, int receipts, long evidenceBytes) {
        public ArchiveFacts {
            if (versions < 1 || relations < 0 || receipts < 0 || evidenceBytes < 0) {
                throw new IllegalArgumentException("invalid archive facts");
            }
        }
    }

    /**
     * The project continues in {@code newStateRoot}, at the same version (the same baseline ID), with an empty history
     * budget. {@code oldStateRoot} is retired: it holds a marker, so the toolchain refuses it from now on, and its content
     * is the evidence of the old history for manual inspection only.
     *
     * @param resumed true when an earlier call had already created the new state and only the retirement was missing
     */
    record RolledOver(Path oldStateRoot, Path newStateRoot, Path outputRoot, ProjectBaselineReceipt receipt, ArchiveFacts archive,
            boolean resumed, List<String> notes) implements VersionStateRolloverResult {
        public RolledOver {
            Objects.requireNonNull(oldStateRoot, "oldStateRoot");
            Objects.requireNonNull(newStateRoot, "newStateRoot");
            Objects.requireNonNull(outputRoot, "outputRoot");
            Objects.requireNonNull(receipt, "receipt");
            Objects.requireNonNull(archive, "archive");
            notes = List.copyOf(notes);
        }
    }

    /** Nothing was retired. {@code code} is stable; the old state is exactly as it was unless the message says otherwise. */
    record Refused(String code, String message, NewStateTouched newState) implements VersionStateRolloverResult {
        public Refused {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(newState, "newState");
        }
    }
}
