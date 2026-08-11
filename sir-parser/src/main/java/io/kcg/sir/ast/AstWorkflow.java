package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record AstWorkflow(
        AstNodeId id,
        SourceSpan span,
        List<AstStep> steps) implements AstNode {

    public AstWorkflow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }
}
