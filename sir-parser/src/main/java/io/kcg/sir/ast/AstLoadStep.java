package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads one row. {@code idExpression} is the value to look the row up by: the identity when
 * {@code keyField} is empty, otherwise the value of the unique field {@code keyField} names.
 */
public record AstLoadStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef entity,
        AstExpression idExpression,
        Optional<AstNameRef> keyField,
        AstName result,
        AstNameRef error,
        boolean forUpdate) implements AstStep {

    public AstLoadStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(idExpression, "idExpression");
        Objects.requireNonNull(keyField, "keyField");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(error, "error");
    }
}
