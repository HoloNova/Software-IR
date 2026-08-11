package io.kcg.sir.ast;

public sealed interface AstExpression extends AstNode permits
        AstBooleanLiteral,
        AstIntegerLiteral,
        AstDecimalLiteral,
        AstStringLiteral,
        AstUnitLiteral,
        AstNameExpression,
        AstMemberExpression,
        AstNowExpression,
        AstGroupedExpression,
        AstUnaryExpression,
        AstBinaryExpression {
}
