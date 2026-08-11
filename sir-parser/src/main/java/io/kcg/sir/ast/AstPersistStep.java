package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstPersistStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef target) implements AstStep {

    public AstPersistStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(target, "target");
    }
}
