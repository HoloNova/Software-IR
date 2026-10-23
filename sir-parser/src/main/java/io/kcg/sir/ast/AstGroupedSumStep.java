package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sum A.f by k where <pred> Page p,s else E as r}: one exact Decimal total per distinct reference key.
 *
 * <p>A separate node from {@link AstSumStep} on purpose: the single-value step has no key and no page, so making
 * both optional on one record would let a half-grouped plan reach later phases.
 */
public record AstGroupedSumStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef entity,
        AstNameRef field,
        AstNameRef key,
        AstExpression predicate,
        AstFindPage page,
        AstName result) implements AstStep {

    public AstGroupedSumStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(result, "result");
    }
}
