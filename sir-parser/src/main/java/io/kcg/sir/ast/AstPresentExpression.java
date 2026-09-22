package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/**
 * The {@code <reference>.present} form: true when a patch payload field was part
 * of the request.
 *
 * <p>Presence is not the same question as "is the value null": an explicit null
 * is present, so the two must stay distinguishable in the AST. The receiver is
 * the member access whose presence is asked about.
 */
public record AstPresentExpression(
        AstNodeId id,
        SourceSpan span,
        AstExpression target) implements AstExpression {

    public AstPresentExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(target, "target");
    }
}
