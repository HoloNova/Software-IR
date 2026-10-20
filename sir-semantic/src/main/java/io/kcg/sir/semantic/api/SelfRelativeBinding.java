package io.kcg.sir.semantic.api;

import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.semantic.model.NormalizedBinding;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Optional;

/**
 * A binding that changes a column by an amount ({@code count: row.count + 1}) instead of setting it.
 *
 * <p>Such a binding is written as {@code SET count = count + ?}, so the database does the arithmetic on the stored
 * value. The validator has already settled that the shape is legal; this only recognizes it, so every stage that
 * needs to treat it differently asks the same question.
 *
 * @param subtract whether the amount is taken away from the column
 * @param amount the amount, which never reads the row
 */
public record SelfRelativeBinding(boolean subtract, NormalizedExpression amount) {

    /** The change this binding makes to its own column, if it is of the form {@code target.field (+|-) amount}. */
    public static Optional<SelfRelativeBinding> of(NormalizedBinding binding, SymbolId targetVariable) {
        if (!(binding.value() instanceof NormalizedExpression.BinaryExpression binary)
                || (binary.operator() != AstBinaryOperator.ADD && binary.operator() != AstBinaryOperator.SUB)) {
            return Optional.empty();
        }

        boolean ownColumn = binary.left() instanceof NormalizedExpression.MemberExpression member
                && member.receiver() instanceof NormalizedExpression.NameExpression name
                && name.resolvedSymbol().equals(targetVariable)
                && member.resolvedMember().equals(binding.fieldSymbol());
        return ownColumn
                ? Optional.of(new SelfRelativeBinding(binary.operator() == AstBinaryOperator.SUB, binary.right()))
                : Optional.empty();
    }
}
