package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates explicit row bindings instead of allowing the renderer to guess a projection source. */
final class ViewExpressionValidation {
    private ViewExpressionValidation() {}

    static List<String> validate(SpringBootDeclaration.ViewDeclaration view, SpringBootDeclaration.ViewField field,
            SpringExpression expression, Map<SymbolId, SpringBootDeclaration> declarations) {
        List<String> errors = new ArrayList<>();
        var row = view.sourceRow().orElse(null);
        if (row == null || !(row.type() instanceof LoweredJavaType.EntityReference reference)
                || !reference.entitySymbol().equals(view.sourceEntitySymbol())
                || !reference.entityJavaName().equals(view.sourceEntityJavaName())) {
            errors.add("computed projection requires its explicitly typed source row");
            return List.copyOf(errors);
        }
        if (!expression.type().equals(field.type())) errors.add("computed projection result type must equal its field type");
        LoweredJavaType scalar = field.type() instanceof LoweredJavaType.OptionalValue optional ? optional.elementType() : field.type();
        if (!(scalar instanceof LoweredJavaType.Scalar primitive && primitive.kind() != LoweredJavaType.ScalarKind.VOID)
                && !(scalar instanceof LoweredJavaType.Declared type && type.kind() == LoweredJavaType.DeclaredKind.ENUM))
            errors.add("computed projection result must be scalar");
        walk(expression, row.symbol(), row.type(), declarations, errors);
        return List.copyOf(errors);
    }

    private static boolean bool(LoweredJavaType type) {
        return type instanceof LoweredJavaType.Scalar s && s.kind() == LoweredJavaType.ScalarKind.BOOLEAN;
    }

    private static boolean numeric(LoweredJavaType type) {
        return type instanceof LoweredJavaType.Scalar s && switch (s.kind()) {
            case INTEGER, LONG, BIG_DECIMAL -> true;
            default -> false;
        };
    }

    private static void unary(SpringExpression.UnaryExpression expression, List<String> errors) {
        boolean valid = switch (expression.operator()) {
            case NOT -> bool(expression.type()) && bool(expression.operand().type());
            case NEGATE -> numeric(expression.type()) && expression.type().equals(expression.operand().type());
            case IS_NONE, IS_PRESENT -> bool(expression.type()) && expression.operand().type() instanceof LoweredJavaType.OptionalValue;
        };
        if (!valid) errors.add("computed unary expression has inconsistent types");
    }

    private static void binary(SpringExpression.BinaryExpression expression, List<String> errors) {
        LoweredJavaType left = expression.left().type(), right = expression.right().type();
        LoweredJavaType a = left instanceof LoweredJavaType.OptionalValue optional ? optional.elementType() : left;
        LoweredJavaType b = right instanceof LoweredJavaType.OptionalValue optional ? optional.elementType() : right;
        boolean valid = switch (expression.operator()) {
            case ADD, SUB, MUL -> numeric(left) && left.equals(right) && left.equals(expression.type());
            case AND, OR -> bool(left) && bool(right) && bool(expression.type());
            case EQ, NE -> bool(expression.type()) && a.equals(b);
            case GT, GE, LT, LE -> bool(expression.type()) && a.equals(b) && a instanceof LoweredJavaType.Scalar scalar
                    && switch (scalar.kind()) {
                        case INTEGER, LONG, BIG_DECIMAL, STRING, LOCAL_DATE, INSTANT -> true;
                        default -> false;
                    };
            case MATCHES, CONTAINS_LITERAL -> false;
        };
        if (!valid) errors.add("computed binary expression has inconsistent types");
    }

    private static void literal(SpringExpression expression, LoweredJavaType.ScalarKind expected, List<String> errors) {
        if (!(expression.type() instanceof LoweredJavaType.Scalar scalar) || scalar.kind() != expected)
            errors.add("computed literal type must match its value");
    }

    private static void walk(SpringExpression expression, SymbolId row, LoweredJavaType rowType,
            Map<SymbolId, SpringBootDeclaration> declarations, List<String> errors) {
        switch (expression) {
            case SpringExpression.NameExpression e -> {
                if (!e.resolvedSymbol().equals(row) || !e.type().equals(rowType))
                    errors.add("computed projection cannot read a foreign variable");
            }
            case SpringExpression.MemberExpression e -> {
                if (e.receiver() instanceof SpringExpression.NameExpression name
                        && name.type() instanceof LoweredJavaType.Declared type
                        && declarations.get(type.symbolId()) instanceof SpringBootDeclaration.EnumDeclaration enumeration) {
                    if (enumeration.members().stream().noneMatch(m -> m.sourceSymbol().equals(e.resolvedMember())
                            && m.javaName().equals(e.targetMember())) || !e.type().equals(type))
                        errors.add("computed enum member must belong to its declared enum");
                    return;
                }
                walk(e.receiver(), row, rowType, declarations, errors);
                if (!(e.receiver() instanceof SpringExpression.NameExpression)
                        || !(rowType instanceof LoweredJavaType.EntityReference reference)
                        || !(declarations.get(reference.entitySymbol()) instanceof SpringBootDeclaration.EntityDeclaration entity)) {
                    errors.add("computed projection cannot follow a reference"); return;
                }
                LoweredJavaType expected = null;
                String property = null;
                if (entity.identity().sourceSymbol().equals(e.resolvedMember())) {
                    expected = entity.identity().type(); property = entity.identity().javaName();
                }
                for (var field : entity.fields()) if (field.sourceSymbol().equals(e.resolvedMember())) {
                    expected = field.type(); property = field.javaName();
                }
                boolean identityValue = expected instanceof LoweredJavaType.EntityReference ref
                        && ref.identityStorageType().equals(e.type());
                if (expected == null || (!expected.equals(e.type()) && !identityValue) || !e.targetMember().equals(property))
                    errors.add("computed member must match its source entity field and property");
            }
            case SpringExpression.UnaryExpression e -> {
                unary(e, errors);
                walk(e.operand(), row, rowType, declarations, errors);
            }
            case SpringExpression.IntegralToDecimal e -> walk(e.operand(), row, rowType, declarations, errors);
            case SpringExpression.TimeShiftExpression e -> walk(e.base(), row, rowType, declarations, errors);
            case SpringExpression.BinaryExpression e -> {
                binary(e, errors);
                if (e.operator() == SpringExpression.BinaryOperator.MATCHES || e.operator() == SpringExpression.BinaryOperator.CONTAINS_LITERAL)
                    errors.add("computed projection cannot match text");
                walk(e.left(), row, rowType, declarations, errors);
                walk(e.right(), row, rowType, declarations, errors);
            }
            case SpringExpression.BooleanLiteral e -> literal(e, LoweredJavaType.ScalarKind.BOOLEAN, errors);
            case SpringExpression.IntegerLiteral e -> {
                if (!(e.type() instanceof LoweredJavaType.Scalar type)
                        || (type.kind() != LoweredJavaType.ScalarKind.INTEGER && type.kind() != LoweredJavaType.ScalarKind.LONG))
                    errors.add("computed integer literal must have an integer type");
                else if (e.value().bitLength() > (type.kind() == LoweredJavaType.ScalarKind.INTEGER ? 31 : 63))
                    errors.add("computed integer literal exceeds its declared width");
            }
            case SpringExpression.DecimalLiteral e -> literal(e, LoweredJavaType.ScalarKind.BIG_DECIMAL, errors);
            case SpringExpression.StringLiteral e -> literal(e, LoweredJavaType.ScalarKind.STRING, errors);
            default -> errors.add("computed projection contains a non-local expression");
        }
    }
}
