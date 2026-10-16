package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/**
 * The author's statement that the rows of an entity are bearer credentials: the primary key is the token,
 * {@code subject} names the field holding who the token belongs to, and {@code expires} names the field
 * holding the moment after which the token stops being valid.
 */
public record AstCredentialClause(
        AstNodeId id,
        SourceSpan span,
        AstNameRef subject,
        AstNameRef expires) implements AstNode {

    public AstCredentialClause {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(expires, "expires");
    }
}
