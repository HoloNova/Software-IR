package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.feature.SemanticTypes;
import io.kcg.sir.semantic.model.*;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.RefType;
import java.util.ArrayList;
import java.util.List;

/** Rechecks the row-local boundary when the public lowering API receives a constructed core model. */
final class NormalizedViewExpressions {
    private NormalizedViewExpressions() {}

    static List<String> validate(NormalizedView view, NormalizedViewField field,
            NormalizedViewField.Computed computed, NormalizedSemanticModel model) {
        List<String> errors = new ArrayList<>();
        var row = model.symbols().byId(computed.rowSymbol()).orElse(null);
        boolean owned = model.symbols().symbolsInScope(view.id()).stream().anyMatch(s -> s.id().equals(computed.rowSymbol()));
        if (!owned || !(row instanceof Symbol.VariableSymbol variable)
                || !(variable.type() instanceof RefType reference) || !reference.entityId().equals(view.sourceEntity()))
            errors.add("computed projection row must belong to its source view and entity");
        if (!SemanticTypes.kind(field.type()).equals(SemanticTypes.kind(computed.expression().type())))
            errors.add("computed projection result must match its declared type");
        walk(computed.expression(), computed.rowSymbol(), view.sourceEntity(), model, errors);
        return List.copyOf(errors);
    }

    private static void walk(NormalizedExpression expression, SymbolId row, SymbolId entity,
            NormalizedSemanticModel model, List<String> errors) {
        switch (expression) {
            case NormalizedExpression.NameExpression e -> {
                if (!e.resolvedSymbol().equals(row) || !(e.type() instanceof RefType ref) || !ref.entityId().equals(entity))
                    errors.add("computed projection reads a foreign row or context");
            }
            case NormalizedExpression.MemberExpression e -> {
                var member = model.symbols().byId(e.resolvedMember()).orElse(null);
                if (member instanceof Symbol.EnumMemberSymbol) return;
                if (!(member instanceof Symbol.FieldSymbol field)) {
                    errors.add("computed projection member must be a bound field"); return;
                }
                if (e.receiver() instanceof NormalizedExpression.NameExpression) {
                    if (!field.ownerId().equals(entity)) errors.add("computed member does not belong to the source entity");
                    var declaration = model.declarations().stream().filter(NormalizedEntity.class::isInstance)
                            .map(NormalizedEntity.class::cast).filter(d -> d.id().equals(entity)).findFirst().orElse(null);
                    if (declaration != null && declaration.fields().stream().anyMatch(f -> f.id().equals(field.id()) && f.secret()))
                        errors.add("computed projection cannot read a secret field");
                } else if (!(e.receiver().type() instanceof RefType reference)
                        || model.declarations().stream().filter(NormalizedEntity.class::isInstance).map(NormalizedEntity.class::cast)
                        .noneMatch(d -> d.id().equals(reference.entityId()) && d.identity().id().equals(field.id()))) {
                    errors.add("computed projection cannot follow a reference to read another row");
                }
                if (!SemanticTypes.kind(e.type()).equals(SemanticTypes.kind(field.type())))
                    errors.add("computed member type differs from its bound field");
                walk(e.receiver(), row, entity, model, errors);
            }
            case NormalizedExpression.UnaryExpression e -> walk(e.operand(), row, entity, model, errors);
            case NormalizedExpression.BinaryExpression e -> {
                if (e.operator() == io.kcg.sir.ast.AstBinaryOperator.MATCHES
                        || e.operator() == io.kcg.sir.ast.AstBinaryOperator.CONTAINS_LITERAL)
                    errors.add("computed projection cannot match text");
                walk(e.left(), row, entity, model, errors);
                walk(e.right(), row, entity, model, errors);
            }
            case NormalizedExpression.TimeShiftExpression e -> walk(e.base(), row, entity, model, errors);
            case NormalizedExpression.BooleanLiteral ignored -> {}
            case NormalizedExpression.IntegerLiteral ignored -> {}
            case NormalizedExpression.DecimalLiteral ignored -> {}
            case NormalizedExpression.StringLiteral ignored -> {}
            default -> errors.add("computed projection contains a non-local value");
        }
    }
}
