package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.symbol.SymbolId;

/** Recognizes the explicitly bound inverse identity/foreign-key conjunction, never an arbitrary JOIN. */
final class ReverseExistence {
    private ReverseExistence() {}

    static boolean contains(NormalizedExpression expression, SymbolId foreignKey, SymbolId identity) {
        if (!(expression instanceof NormalizedExpression.BinaryExpression binary)) return false;
        if (binary.operator() == AstBinaryOperator.AND)
            return contains(binary.left(), foreignKey, identity) || contains(binary.right(), foreignKey, identity);
        return binary.operator() == AstBinaryOperator.EQ
            && (pair(binary.left(), binary.right(), foreignKey, identity)
                || pair(binary.right(), binary.left(), foreignKey, identity));
    }

    private static boolean pair(NormalizedExpression left, NormalizedExpression right, SymbolId field, SymbolId identity) {
        return left instanceof NormalizedExpression.NameExpression name && name.resolvedSymbol().equals(identity)
            && right instanceof NormalizedExpression.MemberExpression member && member.resolvedMember().equals(field);
    }
}
