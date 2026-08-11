package io.kcg.sir.ast;

public sealed interface AstStep extends AstNode permits
        AstValidateStep,
        AstLoadStep,
        AstFindStep,
        AstCreateStep,
        AstUpdateStep,
        AstPersistStep,
        AstReturnStep {
}
