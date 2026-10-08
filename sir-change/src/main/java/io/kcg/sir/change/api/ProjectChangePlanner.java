package io.kcg.sir.change.api;

import io.kcg.sir.change.internal.ProjectChangePlannerCore;
import java.util.*;

/** Explicit project-only planning/verification. Does not broaden ChangePlanner or RenamePlanner admission. */
public final class ProjectChangePlanner {
    public ProjectChangeAnalysis plan(ProjectChangePlanningInput input) {
        Objects.requireNonNull(input);
        try { return ProjectChangePlannerCore.plan(input); }
        catch(IllegalArgumentException e) { return new ProjectChangeAnalysis.Failure(List.of(ChangeDiagnostic.error("SIR-PROJECT-CHANGE-SOURCE-001",ChangeDiagnosticStage.COMPAT,0,"invalid or oversized project evidence: "+e.getMessage()))); }
    }
    public List<ChangeDiagnostic> verify(ProjectChangePlan plan,ProjectChangePlanningInput input) {
        Objects.requireNonNull(plan);Objects.requireNonNull(input);
        var result=plan(input);
        if(result instanceof ProjectChangeAnalysis.Failure f)return f.diagnostics();
        if(plan.formatVersion()!=1 || !(result instanceof ProjectChangeAnalysis.Planned p) || !p.plan().equals(plan))
            return List.of(ChangeDiagnostic.error("SIR-PROJECT-CHANGE-VERIFY-001",ChangeDiagnosticStage.IMPACT,0,"project plan differs from complete replanning"));
        return List.of();
    }
}
