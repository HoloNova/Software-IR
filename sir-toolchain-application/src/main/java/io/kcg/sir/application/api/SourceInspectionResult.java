package io.kcg.sir.application.api;

import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.source.SourceSnapshot;
import java.util.List;
import java.util.Objects;

public sealed interface SourceInspectionResult permits SourceInspectionResult.Success, SourceInspectionResult.Failure {
    List<ExecutionDiagnostic> diagnostics();
    /**
     * @param expanded the declarations of the draft that a {@code use} expanded into: they are not in the source, and an
     *     edit changes the use, never them
     */
    record Success(SourceSnapshot sources, SoftwareSchema schema, SoftwareDraft draft, List<Expanded> expanded, List<ExecutionDiagnostic> diagnostics)
            implements SourceInspectionResult {
        public Success {
            Objects.requireNonNull(sources); Objects.requireNonNull(schema); Objects.requireNonNull(draft);
            expanded = List.copyOf(expanded);
            diagnostics = List.copyOf(diagnostics);
        }
    }
    /**
     * A declaration of the draft that a use expanded into, by its kind and name, with the use it came from (the feature's
     * name and the arguments as written) and its role in the feature.
     */
    record Expanded(String kind, String name, String feature, java.util.Map<String, String> arguments, String role) {
        public Expanded {
            Objects.requireNonNull(kind); Objects.requireNonNull(name); Objects.requireNonNull(feature); Objects.requireNonNull(role);
            arguments = java.util.Map.copyOf(arguments);
        }
    }
    record Failure(List<ExecutionDiagnostic> diagnostics) implements SourceInspectionResult {
        public Failure { diagnostics = List.copyOf(diagnostics); }
    }
}
