package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The formal INPUT members that numeric operations actually depend on, in first-use order. */
final class ArithmeticInputs {
    private ArithmeticInputs() {}

    static List<SpringExpression.MemberExpression> members(SpringBootWorkflow workflow, SymbolId input) {
        Map<SymbolId, SpringExpression.MemberExpression> fields = new LinkedHashMap<>();
        for (var step : workflow.steps()) {
            switch (step) {
                case SpringBootWorkflow.ValidateStep value -> collect(value.condition(), false, input, fields);
                case SpringBootWorkflow.LoadStep value -> collect(value.idExpression(), false, input, fields);
                case SpringBootWorkflow.LoadActorStep value -> collect(value.idExpression(), false, input, fields);
                case SpringBootWorkflow.FindStep value -> collect(value.predicate(), false, input, fields);
                case SpringBootWorkflow.MonthStep value -> { collect(value.month(),false,input,fields);collect(value.zone(),false,input,fields); }
                case SpringBootWorkflow.SumStep value -> collect(value.predicate(), false, input, fields);
                case SpringBootWorkflow.GroupedSumStep value -> collect(value.predicate(), false, input, fields);
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

    private static boolean numeric(LoweredJavaType type) {
        return integral(type) || type instanceof LoweredJavaType.Scalar scalar && scalar.kind() == LoweredJavaType.ScalarKind.BIG_DECIMAL;
    }

    private static void collect(SpringExpression expression, boolean arithmetic, SymbolId input,
            Map<SymbolId, SpringExpression.MemberExpression> fields) {
        switch (expression) {
            case SpringExpression.BinaryExpression value -> {
                boolean required = arithmetic || switch (value.operator()) { case ADD, SUB, MUL -> true; default -> false; };
                collect(value.left(), required, input, fields);
                collect(value.right(), required, input, fields);
            }
            case SpringExpression.UnaryExpression value -> collect(value.operand(),
                    arithmetic || value.operator() == SpringExpression.UnaryOperator.NEGATE, input, fields);
            case SpringExpression.IntegralToDecimal value -> collect(value.operand(), arithmetic, input, fields);
            case SpringExpression.MemberExpression value -> {
                if (arithmetic && numeric(value.type()) && value.receiver() instanceof SpringExpression.NameExpression receiver
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
