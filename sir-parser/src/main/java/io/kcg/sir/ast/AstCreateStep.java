package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record AstCreateStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef entity,
        AstName result,
        List<AstBinding> bindings) implements AstStep {

    public AstCreateStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(result, "result");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    }
}
