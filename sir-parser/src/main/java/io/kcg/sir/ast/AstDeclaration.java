package io.kcg.sir.ast;

public sealed interface AstDeclaration extends AstNode permits
        AstEnumDecl,
        AstEntityDecl,
        AstInputDecl,
        AstViewDecl,
        AstErrorDecl,
        AstCapabilityDecl {
}
