package io.kcg.sir.ast;

public sealed interface AstStep extends AstNode permits
        AstValidateStep,
        AstLoadStep,
        AstFindStep,
        AstScalarQueryStep,
        AstSumStep,
        AstCreateStep,
        AstUpdateStep,
        AstPersistStep,
        AstDeleteStep,
        AstReturnStep {
}
