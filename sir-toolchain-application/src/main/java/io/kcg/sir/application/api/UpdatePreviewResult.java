package io.kcg.sir.application.api;

import io.kcg.sir.change.edit.SemanticDelta;
import java.util.List;
import java.util.Objects;

/** A read-only preview of moving from one revision to another. Nothing was written, saved or applied. */
public sealed interface UpdatePreviewResult permits UpdatePreviewResult.Success, UpdatePreviewResult.Failure {
    List<ExecutionDiagnostic> diagnostics();

    /** Which input a failure came from. */
    enum Side { BASE, CANDIDATE, OUTPUT_ROOT }

    /**
     * The three views are kept apart on purpose: {@code source} is which source files differ,
     * {@code software} is what the software does differently, {@code files} is which implementation
     * files must change.
     */
    record Success(SourceDelta source, SemanticDelta software, FileDelta files, List<ExecutionDiagnostic> diagnostics) implements UpdatePreviewResult {
        public Success {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(software, "software");
            Objects.requireNonNull(files, "files");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
                throw new IllegalArgumentException("a successful preview has no ERROR diagnostic");
            }
        }

        /** The source changed but the software and every generated file are the same (a comment, a moved declaration). */
        public boolean sourceOnly() {
            return this.source.changed() && this.software.isEmpty() && this.files.changes().isEmpty();
        }

        public boolean hasConflicts() {
            return !this.files.conflicts().isEmpty();
        }
    }

    record Failure(Side side, ExecutionStage failedStage, List<ExecutionDiagnostic> diagnostics) implements UpdatePreviewResult {
        public Failure {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(failedStage, "failedStage");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) {
                throw new IllegalArgumentException("a failed preview needs an ERROR diagnostic");
            }
        }
    }
}
