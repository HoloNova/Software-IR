package io.kcg.sir.ast;

public sealed interface AstStep extends AstNode permits
        AstValidateStep,
        AstLoadStep,
        AstFindStep,
        AstEachStep,
        AstScalarQueryStep,
        AstSumStep,
        AstMonthStep,
        AstGroupedSumStep,
        AstCreateStep,
        AstUpdateStep,
        AstPersistStep,
        AstDeleteStep,
        AstReturnStep {
}
