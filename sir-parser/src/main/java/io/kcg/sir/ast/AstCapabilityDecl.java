package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
        AstWorkflow workflow) implements AstDeclaration {

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
    }
}
