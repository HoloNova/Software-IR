package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceSetManifest;
import java.util.List;

public sealed interface DeploymentPackageResult {
    List<ExecutionDiagnostic> diagnostics();
    record Exported(String packageId, SourceSetManifest sources, ExecutionManifest manifest,
            List<ExecutionDiagnostic> diagnostics, java.util.Optional<Continuation> continuation) implements DeploymentPackageResult {
        public Exported { diagnostics = List.copyOf(diagnostics); java.util.Objects.requireNonNull(continuation); }
        public Exported(String packageId, SourceSetManifest sources, ExecutionManifest manifest, List<ExecutionDiagnostic> diagnostics) {
            this(packageId, sources, manifest, diagnostics, java.util.Optional.empty());
        }
    }
    /**
     * How the export continues a base delivery's chain: what changed in the software since the base (as the change layer
     * reports it), the schema version the chain ends at, and the versions it appends.
     */
    record Continuation(String basePackageId, int schemaVersion, List<String> changes, List<String> steps) {
        public Continuation { changes = List.copyOf(changes); steps = List.copyOf(steps); }
    }
    record Failure(List<ExecutionDiagnostic> diagnostics) implements DeploymentPackageResult {
        public Failure { diagnostics = List.copyOf(diagnostics); }
    }
}
