package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record AstUpdateStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef target,
        List<AstBinding> bindings) implements AstStep {

    public AstUpdateStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(target, "target");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    }
}
