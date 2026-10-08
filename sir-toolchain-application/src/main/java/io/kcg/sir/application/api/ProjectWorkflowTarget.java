package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/** A baseline workflow, not a name lookup. Physical locations belong to the current source snapshot. */
public record ProjectWorkflowTarget(ChangeTarget target,String displayName,SourceSpan declarationSpan,SourceSpan workflowSpan) {
    public ProjectWorkflowTarget { Objects.requireNonNull(target);Objects.requireNonNull(displayName);Objects.requireNonNull(declarationSpan);Objects.requireNonNull(workflowSpan); }
}
