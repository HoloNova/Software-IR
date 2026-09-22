package io.kcg.sir.ast;

public sealed interface AstTypeRef extends AstNode permits
        AstNamedTypeRef,
        AstOptionalTypeRef,
        AstListTypeRef,
        AstPageTypeRef,
        AstRefTypeRef {
}
