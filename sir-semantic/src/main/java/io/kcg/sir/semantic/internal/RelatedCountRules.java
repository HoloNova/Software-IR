package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.*;
import io.kcg.sir.semantic.context.TypedContext;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A related count is an explicit projection read, not a general-purpose query expression. */
final class RelatedCountRules {
    private RelatedCountRules() {}

    static void validate(AstCountExpression count, TypedContext typed, SymbolId row,
            AstSoftware software, List<Diagnostic> diagnostics) {
        SymbolId entity = typed.referenceSiteBindings().targetFor(count.entity().id()).orElse(null);
        SymbolId field = ExistenceConnection.fieldOf(count.conditions(), typed.referenceSiteBindings(), Map.of(count.id(), row));
        Symbol source = field == null ? null : typed.symbols().byId(field).orElse(null);
        Symbol root = typed.symbols().byId(row).orElse(null);
        if (!(source instanceof Symbol.FieldSymbol f && f.ownerId().equals(entity) && f.type() instanceof RefType ref
                && root instanceof Symbol.VariableSymbol variable && variable.type() instanceof RefType parent
                && parent.entityId().equals(ref.entityId())))
            reject(count, "count requires a direct non-optional child reference == the current source row", diagnostics);
        boolean persistent = software.declarations().stream().filter(AstEntityDecl.class::isInstance).map(AstEntityDecl.class::cast)
                .anyMatch(e -> java.util.Objects.equals(entity, typed.declarationBindings().get(e.id())));
        if (!persistent) reject(count, "count requires a persistent related entity", diagnostics);
        if (!TypeRules.isBoolean(typed.expressionTypes().get(count.conditions().id())))
            reject(count, "count conditions must be Boolean", diagnostics);
        predicate(count.conditions(), typed, row, entity, field, diagnostics);
    }

    private static void predicate(AstExpression value, TypedContext typed, SymbolId row, SymbolId entity,
            SymbolId connection, List<Diagnostic> diagnostics) {
        if (value instanceof AstBinaryExpression binary && binary.operator() == AstBinaryOperator.EQ
                && (pair(binary.left(), binary.right(), typed, connection, row)
                    || pair(binary.right(), binary.left(), typed, connection, row))) return;
        switch (value) {
            case AstGroupedExpression e -> predicate(e.inner(), typed, row, entity, connection, diagnostics);
            case AstBinaryExpression e -> {
                if (e.operator() == AstBinaryOperator.CONTAINS_LITERAL && !(e.right() instanceof AstStringLiteral))
                    reject(e, "count literal matches need a constant string", diagnostics);
                if (!Set.of(AstBinaryOperator.AND, AstBinaryOperator.OR, AstBinaryOperator.EQ, AstBinaryOperator.NE,
                        AstBinaryOperator.GT, AstBinaryOperator.GE, AstBinaryOperator.LT, AstBinaryOperator.LE,
                        AstBinaryOperator.CONTAINS_LITERAL).contains(e.operator()))
                    reject(e, "count only accepts scalar predicate comparisons", diagnostics);
                predicate(e.left(), typed, row, entity, connection, diagnostics);
                predicate(e.right(), typed, row, entity, connection, diagnostics);
            }
            case AstUnaryExpression e -> {
                if (e.operator() != AstUnaryOperator.NOT) reject(e, "count does not compute arithmetic", diagnostics);
                predicate(e.operand(), typed, row, entity, connection, diagnostics);
            }
            case AstNameExpression e -> {
                var symbol = typed.referenceSiteBindings().targetFor(e.name().id()).flatMap(typed.symbols()::byId).orElse(null);
                if (!(symbol instanceof Symbol.FieldSymbol f && f.ownerId().equals(entity) && !(f.type() instanceof RefType)))
                    reject(e, "count cannot capture root values or external context", diagnostics);
            }
            case AstMemberExpression e -> {
                if (!(typed.referenceSiteBindings().targetFor(e.member().id()).flatMap(typed.symbols()::byId)
                        .orElse(null) instanceof Symbol.EnumMemberSymbol))
                    reject(e, "count only permits related fields and declared enum literals", diagnostics);
            }
            case AstBooleanLiteral ignored -> {}
            case AstIntegerLiteral ignored -> {}
            case AstDecimalLiteral ignored -> {}
            case AstStringLiteral ignored -> {}
            default -> reject(value, "count forbids nested queries, clock and runtime context", diagnostics);
        }
    }

    private static boolean pair(AstExpression field, AstExpression item, TypedContext typed, SymbolId connection, SymbolId row) {
        return field instanceof AstNameExpression f && item instanceof AstNameExpression i && connection != null
                && typed.referenceSiteBindings().targetFor(f.name().id()).filter(connection::equals).isPresent()
                && typed.referenceSiteBindings().targetFor(i.name().id()).filter(row::equals).isPresent();
    }

    static void outputs(AstSoftware software, TypedContext typed, List<Diagnostic> diagnostics) {
        var views = software.declarations().stream().filter(AstViewDecl.class::isInstance).map(AstViewDecl.class::cast)
                .collect(java.util.stream.Collectors.toMap(v -> typed.declarationBindings().get(v.id()), v -> v));
        for (var declaration : software.declarations()) {
            if (!(declaration instanceof AstCapabilityDecl cap)) continue;
            SirType output = typed.typeRefTypes().get(cap.output().type().id());
            if (!contains(output, views, typed, new java.util.HashSet<>())) continue;
            if (cap.exposure().exposure() != AstExposureKind.QUERY
                    || cap.requirements().stream().noneMatch(r -> r.requirement() == AstRequirementKind.READONLY))
                reject(cap, "count projections require a readonly query", diagnostics);
            bounded(output, views, typed, new java.util.HashSet<>(), diagnostics, cap);
        }
    }

    private static boolean contains(SirType type, Map<SymbolId, AstViewDecl> views, TypedContext typed, Set<SymbolId> visited) {
        if (type instanceof PageType p) return contains(p.element(), views, typed, visited);
        if (type instanceof ListType l) return contains(l.element(), views, typed, visited);
        if (type instanceof OptionalType o) return contains(o.element(), views, typed, visited);
        if (!(type instanceof DeclaredType d) || !visited.add(d.symbolId()) || !views.containsKey(d.symbolId())) return false;
        return views.get(d.symbolId()).fields().stream().anyMatch(f -> f.expression().orElse(null) instanceof AstCountExpression
                || contains(typed.typeRefTypes().get(f.type().id()), views, typed, visited));
    }

    private static void bounded(SirType type, Map<SymbolId, AstViewDecl> views, TypedContext typed, Set<SymbolId> visited,
            List<Diagnostic> diagnostics, AstNode at) {
        if (type instanceof ListType && contains(type, views, typed, new java.util.HashSet<>())) {
            reject(at, "count projections cannot be reached through an unbounded List or TO_MANY", diagnostics);
            return;
        }
        if (type instanceof PageType p) bounded(p.element(), views, typed, visited, diagnostics, at);
        if (type instanceof OptionalType o) bounded(o.element(), views, typed, visited, diagnostics, at);
        if (type instanceof DeclaredType d && visited.add(d.symbolId()) && views.containsKey(d.symbolId()))
            views.get(d.symbolId()).fields().forEach(f -> bounded(typed.typeRefTypes().get(f.type().id()), views, typed, visited, diagnostics, f));
    }

    private static void reject(AstNode node, String message, List<Diagnostic> diagnostics) {
        diagnostics.add(DiagnosticBuilder.error("SIR-VALID-001", message, node.span()));
    }
}
