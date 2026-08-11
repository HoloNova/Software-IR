package io.kcg.sir.lowering.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface LoweringAnalysis<M extends LoweredModel>
        permits LoweringAnalysis.Success, LoweringAnalysis.Failure {

    List<LoweringDiagnostic> diagnostics();

    Optional<M> model();

    default boolean hasErrors() {
        return diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity().isError());
    }

    default boolean isSuccess() {
        return !hasErrors();
    }

    record Success<M extends LoweredModel>(
            M loweredModel,
            List<LoweringDiagnostic> diagnostics
    ) implements LoweringAnalysis<M> {
        public Success {
            Objects.requireNonNull(loweredModel, "loweredModel");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().isError())) {
                throw new IllegalArgumentException("Success must not contain error diagnostics");
            }
        }

        @Override
        public Optional<M> model() {
            return Optional.of(loweredModel);
        }
    }

    record Failure<M extends LoweredModel>(
            List<LoweringDiagnostic> diagnostics
    ) implements LoweringAnalysis<M> {
        public Failure {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity().isError())) {
                throw new IllegalArgumentException("Failure must contain at least one error diagnostic");
            }
        }

        @Override
        public Optional<M> model() {
            return Optional.empty();
        }
    }
}
