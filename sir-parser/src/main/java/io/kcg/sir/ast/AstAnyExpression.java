package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/**
 * The {@code any(<Entity>, <conditions>)} existence predicate: true when the tested
 * entity has at least one row that satisfies the conditions.
 *
 * <p>The conditions stay one expression on purpose. Splitting them into separate
 * existence tests would change the meaning: "some enrollment is for this course and
 * is active" is not the same as "some enrollment is for this course and some
 * enrollment is active". The connection to the root entity is written inside those
 * conditions as a {@code Ref} comparison, so a later phase reads the join column
 * from the document instead of guessing it from the model.
 */
public record AstAnyExpression(
        AstNodeId id,
        SourceSpan span,
        AstNameRef entity,
        AstExpression conditions) implements AstExpression {

    public AstAnyExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(conditions, "conditions");
    }
}
