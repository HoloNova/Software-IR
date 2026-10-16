package io.kcg.sir.application.api;

import io.kcg.sir.semantic.schema.StructNode;
import java.util.List;
import java.util.Objects;

public sealed interface ScaffoldResult permits ScaffoldResult.Scaffolded, ScaffoldResult.Refused, ScaffoldResult.Failure {
    List<ExecutionDiagnostic> diagnostics();

    /** The declarations, to be sent to {@code edit} as {@code add} commands against {@code baselineSha256}. */
    record Scaffolded(String softwareName, String baselineSha256, List<StructNode> declarations, List<ExecutionDiagnostic> diagnostics)
            implements ScaffoldResult {
        public Scaffolded {
            Objects.requireNonNull(softwareName); Objects.requireNonNull(baselineSha256);
            declarations = List.copyOf(declarations); diagnostics = List.copyOf(diagnostics);
        }
    }

    /** The pattern does not fit this software (or the request is not acceptable); {@code details} say why. */
    record Refused(String message, List<String> details, List<ExecutionDiagnostic> diagnostics) implements ScaffoldResult {
        public Refused {
            Objects.requireNonNull(message);
            details = List.copyOf(details); diagnostics = List.copyOf(diagnostics);
        }
    }

    /** The sources could not be read or are not a valid software. */
    record Failure(List<ExecutionDiagnostic> diagnostics) implements ScaffoldResult {
        public Failure { diagnostics = List.copyOf(diagnostics); }
    }
}
