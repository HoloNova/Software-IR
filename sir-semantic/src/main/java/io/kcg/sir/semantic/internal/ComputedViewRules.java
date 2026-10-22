package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.*;
import io.kcg.sir.semantic.context.TypedContext;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Set;

/** The expression subset a response may evaluate without acquiring another source of data. */
final class ComputedViewRules {
    private ComputedViewRules() {}

    static void validate(AstExpression expression, TypedContext typed, SymbolId row,
            Set<SymbolId> identities, List<Diagnostic> diagnostics) {
        switch (expression) {
            case AstGroupedExpression e -> validate(e.inner(), typed, row, identities, diagnostics);
            case AstUnaryExpression e -> validate(e.operand(), typed, row, identities, diagnostics);
            case AstTimeShiftExpression e -> validate(e.base(), typed, row, identities, diagnostics);
            case AstBinaryExpression e -> {
                if (e.operator() == AstBinaryOperator.MATCHES || e.operator() == AstBinaryOperator.CONTAINS_LITERAL)
                    reject(e, diagnostics);
                validate(e.left(), typed, row, identities, diagnostics);
                validate(e.right(), typed, row, identities, diagnostics);
            }
            case AstMemberExpression e -> {
                SymbolId member = typed.referenceSiteBindings().targetFor(e.member().id()).orElse(null);
                Symbol symbol = member == null ? null : typed.symbols().byId(member).orElse(null);
                if (symbol instanceof Symbol.EnumMemberSymbol) break;
                AstExpression receiver = e.receiver();
                while (receiver instanceof AstGroupedExpression group) receiver = group.inner();
                if (!(receiver instanceof AstNameExpression) && !identities.contains(member)) reject(e, diagnostics);
                validate(e.receiver(), typed, row, identities, diagnostics);
            }
            case AstNameExpression e -> {
                if (!typed.referenceSiteBindings().targetFor(e.name().id()).filter(row::equals).isPresent())
                    reject(e, diagnostics);
            }
            case AstBooleanLiteral ignored -> {}
            case AstIntegerLiteral ignored -> {}
            case AstDecimalLiteral ignored -> {}
            case AstStringLiteral ignored -> {}
            default -> reject(expression, diagnostics);
        }
    }

    private static void reject(AstExpression expression, List<Diagnostic> diagnostics) {
        diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001",
                "计算投影只允许来源行的纯标量表达式，不允许查询、外部上下文或跨行读取", expression.span()));
    }
}
