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
        AstViewField,
        AstCredentialClause,
        AstUniqueKey,
        AstCapabilityClause,
        AstWorkflow,
        AstStep,
        AstMonthBoundary,
        AstFindOrder,
        AstFindOrderKey,
        AstFindPage,
        AstTypeRef,
        AstConstraint,
        AstBinding,
        AstNameRef,
        AstExpression {

    AstNodeId id();

    SourceSpan span();
}
