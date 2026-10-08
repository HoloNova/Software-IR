package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ChangeDiagnosticStage;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.source.SourceSpan;
import java.util.*;

/** Preserves compiler locations and the planner's typed sub-stage without changing legacy diagnostics. */
public record ProjectChangeDiagnostic(String code,ProjectChangeStage stage,ExecutionSeverity severity,String message,
        Optional<SourceSpan> sourceSpan,Optional<LoweredNodeId> artifactId,Optional<String> relativePath,
        Optional<ChangeDiagnosticStage> planningStage) {
    public ProjectChangeDiagnostic {
        Objects.requireNonNull(code);Objects.requireNonNull(stage);Objects.requireNonNull(severity);Objects.requireNonNull(message);Objects.requireNonNull(sourceSpan);Objects.requireNonNull(artifactId);Objects.requireNonNull(relativePath);Objects.requireNonNull(planningStage);
        if(code.isBlank() || message.isBlank())throw new IllegalArgumentException("blank project diagnostic");
        if(stage!=ProjectChangeStage.PLAN && planningStage.isPresent())throw new IllegalArgumentException("planning sub-stage outside PLAN");
    }
    public boolean isError() { return severity.isError(); }
    public static ProjectChangeDiagnostic from(ExecutionDiagnostic d) { return new ProjectChangeDiagnostic(d.code(),ProjectChangeStage.valueOf(d.stage().name()),d.severity(),d.message(),d.sourceSpan(),d.loweredNodeId(),d.relativePath(),Optional.empty()); }
    public static ProjectChangeDiagnostic error(String code,ProjectChangeStage stage,String message) { return new ProjectChangeDiagnostic(code,stage,ExecutionSeverity.ERROR,message,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty()); }
}
