package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;

public sealed interface AstNode permits
        AstDocument,
        AstSoftware,
        AstMetadata,
        AstTarget,
        AstDeclaration,
        AstEnumMember,
        AstIdentity,
        AstField,
        AstCapabilityClause,
        AstWorkflow,
        AstStep,
        AstTypeRef,
        AstConstraint,
        AstBinding,
        AstNameRef,
        AstExpression {

    AstNodeId id();

    SourceSpan span();
}
