package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A capability declaration.
 *
 * <p>{@code declaredId} is the persistent declaration id written as {@code @id("...")}. When it is
 * present the declaration's identity (and every identity derived from it) stops depending on the
 * capability name: renaming the capability keeps the same id, and deleting the declaration and
 * re-creating a same-named one with a different declared id yields a different declaration.
 */
public record AstCapabilityDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        Optional<AstActorClause> actor,
        Optional<AstInputClause> input,
        AstOutputClause output,
        List<AstFailsClause> failures,
        List<AstRequiresClause> requirements,
        AstExposeClause exposure,
        AstWorkflow workflow,
        Optional<String> declaredId) implements AstDeclaration {

    public AstCapabilityDecl(
            AstNodeId id,
            SourceSpan span,
            AstName name,
            Optional<AstActorClause> actor,
            Optional<AstInputClause> input,
            AstOutputClause output,
            List<AstFailsClause> failures,
            List<AstRequiresClause> requirements,
            AstExposeClause exposure,
            AstWorkflow workflow) {
        this(id, span, name, actor, input, output, failures, requirements, exposure, workflow, Optional.empty());
    }

    public AstCapabilityDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        Objects.requireNonNull(exposure, "exposure");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(declaredId, "declaredId");
    }
}
