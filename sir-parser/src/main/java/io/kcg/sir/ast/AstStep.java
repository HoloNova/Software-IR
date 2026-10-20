package io.kcg.sir.ast;

public sealed interface AstStep extends AstNode permits
        AstValidateStep,
        AstLoadStep,
        AstFindStep,
        AstScalarQueryStep,
        AstCreateStep,
        AstUpdateStep,
        AstPersistStep,
        AstDeleteStep,
        AstReturnStep {
}
