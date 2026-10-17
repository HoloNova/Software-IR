package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceSetManifest;
import java.util.List;

public sealed interface DeploymentPackageResult {
    List<ExecutionDiagnostic> diagnostics();
    record Exported(String packageId, SourceSetManifest sources, ExecutionManifest manifest,
            List<ExecutionDiagnostic> diagnostics) implements DeploymentPackageResult {
        public Exported { diagnostics = List.copyOf(diagnostics); }
    }
    record Failure(List<ExecutionDiagnostic> diagnostics) implements DeploymentPackageResult {
        public Failure { diagnostics = List.copyOf(diagnostics); }
    }
}
