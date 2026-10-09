package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;

/** Static qualification only: ok does not certify a generated project builds or meets business requirements. */
public record SirValidationResult(String id, boolean ok, ValidationStopAfter stage,
    List<ValidationDiagnostic> diagnostics, int fileCount, String digest, String sourceSha256) {
    public SirValidationResult { Objects.requireNonNull(stage); diagnostics = List.copyOf(diagnostics); }
    public boolean hasInternalFailure() { return diagnostics.stream().anyMatch(d -> d.code().startsWith("KCG-CHECK-INTERNAL-")); }
    public static SirValidationResult failure(String id, ValidationStopAfter stage, String code, String message, String sha) {
        return new SirValidationResult(id, false, stage, List.of(ValidationDiagnostic.error(stage, code, message)), 0, null, sha);
    }
}
