package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/**
 * The {@code Page <pageField>, <sizeField> else <Error>} clause of a {@code find} step.
 *
 * <p>The clause names the pagination sources and the error raised for an
 * illegal page or size. Defaults and legal bounds are Lowering decisions, so
 * they are deliberately absent here.
 */
public record AstFindPage(
        AstNodeId id,
        SourceSpan span,
        AstExpression page,
        AstExpression size,
        AstNameRef error) implements AstNode {

    public AstFindPage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(error, "error");
    }
}
