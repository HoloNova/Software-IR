package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@code persist <variable> [else <Error>];} step.
 *
 * <p>The declared failure is the step's own failure path: a versioned update that
 * does not affect exactly one row reports it instead of silently succeeding.
 */
public record AstPersistStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef target,
        Optional<AstNameRef> failure) implements AstStep {

    public AstPersistStep(AstNodeId id, SourceSpan span, AstNameRef target) {
        this(id, span, target, Optional.empty());
    }

    public AstPersistStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(failure, "failure");
    }
}
