package io.kcg.sir.ast;

public sealed interface AstStep extends AstNode permits
        AstValidateStep,
        AstLoadStep,
        AstFindStep,
        AstScalarQueryStep,
        AstSumStep,
        AstGroupedSumStep,
        AstCreateStep,
        AstUpdateStep,
        AstPersistStep,
        AstDeleteStep,
        AstReturnStep {
}
