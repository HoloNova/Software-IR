package io.kcg.sir.ast;

public sealed interface AstCapabilityClause extends AstNode permits
        AstActorClause,
        AstInputClause,
        AstOutputClause,
        AstFailsClause,
        AstRequiresClause,
        AstExposeClause {
}
