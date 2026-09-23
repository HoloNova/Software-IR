package io.kcg.sir.ast;

public sealed interface AstExpression extends AstNode permits
        AstBooleanLiteral,
        AstIntegerLiteral,
        AstDecimalLiteral,
        AstStringLiteral,
        AstUnitLiteral,
        AstNameExpression,
        AstMemberExpression,
        AstPresentExpression,
        AstNowExpression,
        AstGroupedExpression,
        AstUnaryExpression,
        AstBinaryExpression,
        AstAnyExpression {
}
