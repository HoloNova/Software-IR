package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Changes a loaded row in the workflow.
 *
 * <p>{@code guard} is the condition the stored row must still meet when the change is written, and
 * {@code guardError} is what is raised when it does not. They are present together or not at all.
 */
public record AstUpdateStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef target,
        Optional<AstExpression> guard,
        Optional<AstNameRef> guardError,
        List<AstBinding> bindings) implements AstStep {

    public AstUpdateStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(guardError, "guardError");
        if (guard.isPresent() != guardError.isPresent()) {
            throw new IllegalArgumentException("a guard and its error come together");
        }

        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    }

    public AstUpdateStep(AstNodeId id, SourceSpan span, AstNameRef target, List<AstBinding> bindings) {
        this(id, span, target, Optional.empty(), Optional.empty(), bindings);
    }
}
