package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The INPUT fields a capability checks for a value before its workflow runs.
 *
 * <p>Every non-Optional field of a plain payload is required, whether the workflow compares it, stores it,
 * computes with it or never reads it: "not Optional" is the author's way of saying "the request must carry it".
 * The one exception is a field the capability uses only as the page or size of a paged step; the target fills
 * those with its paging defaults. Paging is told by the step's binding, not by the field's name, and a page or
 * size that also feeds arithmetic, a built-in function or a repeat count is required like any other operand.
 * A PATCH payload keeps the earlier rule: only its arithmetic operands are listed, because its changes keep
 * "absent means unchanged" (IR validation rejects a change used as an operand) and the envelope already requires
 * the identity and expected version.
 */
final class RequiredInputs {
    private RequiredInputs() {}

    /** One required field: the formal field, its transport property and where the requirement comes from. */
    record Member(SymbolId field, String property, LoweredOrigin origin) {}

    static List<Member> members(SpringBootDeclaration.InputDeclaration payload, SpringBootWorkflow workflow, SymbolId input) {
        List<SpringExpression.MemberExpression> arithmetic = operands(workflow, input);
        if (payload.patch().isPresent()) {
            return arithmetic.stream().map(member -> new Member(member.resolvedMember(), member.targetMember(), member.origin())).toList();
        }
        Set<SymbolId> operands = new HashSet<>();
        arithmetic.forEach(member -> operands.add(member.resolvedMember()));
        Set<SymbolId> pagingOnly = new HashSet<>();
        for (var step : SpringBootWorkflow.walk(workflow.steps()).toList()) {
            switch (step) {
                case SpringBootWorkflow.FindStep value -> value.page().ifPresent(page -> paging(page, pagingOnly));
                case SpringBootWorkflow.GroupedSumStep value -> paging(value.page(), pagingOnly);
                default -> { }
            }
        }
        pagingOnly.removeAll(operands);
        List<Member> required = new ArrayList<>();
        for (var field : payload.fields()) {
            if (field.type() instanceof LoweredJavaType.OptionalValue || pagingOnly.contains(field.sourceSymbol())) continue;
            required.add(new Member(field.sourceSymbol(), field.javaName(), field.origin()));
        }
        return List.copyOf(required);
    }

    private static void paging(SpringBootWorkflow.PageSpec page, Set<SymbolId> fields) {
        fields.add(page.pageFieldSymbol());
        fields.add(page.sizeFieldSymbol());
    }

    /** The INPUT members that numeric operations, built-in functions or a repeat count depend on, in first-use order. */
    private static List<SpringExpression.MemberExpression> operands(SpringBootWorkflow workflow, SymbolId input) {
        Map<SymbolId, SpringExpression.MemberExpression> fields = new LinkedHashMap<>();
        for (var step : io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.walk(workflow.steps()).toList()) {
            switch (step) {
                case SpringBootWorkflow.ValidateStep value -> collect(value.condition(), false, input, fields);
                case SpringBootWorkflow.LoadStep value -> collect(value.idExpression(), false, input, fields);
                case SpringBootWorkflow.LoadActorStep value -> collect(value.idExpression(), false, input, fields);
                case SpringBootWorkflow.FindStep value -> collect(value.predicate(), false, input, fields);
                case SpringBootWorkflow.MonthStep value -> { collect(value.month(),false,input,fields);collect(value.zone(),false,input,fields); }
                case SpringBootWorkflow.SumStep value -> collect(value.predicate(), false, input, fields);
                case SpringBootWorkflow.GroupedSumStep value -> collect(value.predicate(), false, input, fields);
                case SpringBootWorkflow.EachStep value -> collect(value.predicate(), false, input, fields);
                // A loop cannot run an unknown number of times, so a count from the request is required like an operand.
                case SpringBootWorkflow.RepeatStep value -> collect(value.count(), true, input, fields);
                case SpringBootWorkflow.ScalarQueryStep value -> collect(value.predicate(), false, input, fields);
                case SpringBootWorkflow.ReturnStep value -> collect(value.value(), false, input, fields);
                case SpringBootWorkflow.CreateStep value -> value.bindings().forEach(binding -> collect(binding.value(), false, input, fields));
                case SpringBootWorkflow.UpdateStep value -> {
                    value.guard().ifPresent(guard -> collect(guard.predicate(), false, input, fields));
                    value.bindings().forEach(binding -> collect(binding.value(), false, input, fields));
                }
                case SpringBootWorkflow.PersistStep ignored -> { }
            }
        }
        return List.copyOf(fields.values());
    }

    static boolean integral(LoweredJavaType type) {
        return type instanceof LoweredJavaType.Scalar scalar
                && (scalar.kind() == LoweredJavaType.ScalarKind.INTEGER || scalar.kind() == LoweredJavaType.ScalarKind.LONG);
    }

    /** A date or date-time; only a built-in function makes one of these a required value. */
    private static boolean temporal(LoweredJavaType type) {
        return type instanceof LoweredJavaType.Scalar scalar
                && (scalar.kind() == LoweredJavaType.ScalarKind.INSTANT || scalar.kind() == LoweredJavaType.ScalarKind.LOCAL_DATE);
    }

    private static boolean numeric(LoweredJavaType type) {
        return integral(type) || type instanceof LoweredJavaType.Scalar scalar && scalar.kind() == LoweredJavaType.ScalarKind.BIG_DECIMAL;
    }

    private static void collect(SpringExpression expression, boolean arithmetic, SymbolId input,
            Map<SymbolId, SpringExpression.MemberExpression> fields) {
        switch (expression) {
            case SpringExpression.BinaryExpression value -> {
                // A built-in function cannot answer without both arguments either, so its inputs are required the same way.
                boolean required = arithmetic || switch (value.operator()) {
                    case ADD, SUB, MUL, DAYS_BETWEEN, MAX, MIN, ADD_DAYS -> true;
                    default -> false;
                };
                collect(value.left(), required, input, fields);
                collect(value.right(), required, input, fields);
            }
            case SpringExpression.UnaryExpression value -> collect(value.operand(),
                    arithmetic || value.operator() == SpringExpression.UnaryOperator.NEGATE, input, fields);
            case SpringExpression.IntegralToDecimal value -> collect(value.operand(), arithmetic, input, fields);
            case SpringExpression.MemberExpression value -> {
                if (arithmetic && (numeric(value.type()) || temporal(value.type()))
                        && value.receiver() instanceof SpringExpression.NameExpression receiver
                        && receiver.resolvedSymbol().equals(input)) {
                    fields.putIfAbsent(value.resolvedMember(), value);
                }
                collect(value.receiver(), arithmetic, input, fields);
            }
            case SpringExpression.OptionalPayloadValue value -> collect(value.payloadMember(), arithmetic, input, fields);
            case SpringExpression.TimeShiftExpression value -> collect(value.base(), arithmetic, input, fields);
            case SpringExpression.DigestExpression value -> collect(value.operand(), arithmetic, input, fields);
            case SpringExpression.ExistsPredicate value -> value.arguments().forEach(argument -> {
                if (argument instanceof SpringExpression.ExistsArgument.Value bound) collect(bound.expression(), arithmetic, input, fields);
            });
            default -> { }
        }
    }
}
