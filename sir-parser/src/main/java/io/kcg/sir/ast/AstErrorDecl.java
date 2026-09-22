package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@code error} declaration, optionally carrying the HTTP status it maps to
 * ({@code error CourseNotFound http 404;}).
 *
 * <p>The status is kept as an arbitrary-precision integer rather than an
 * {@code int} so an out-of-range literal stays a reportable diagnostic instead of
 * a parsing crash; which statuses a target accepts is a later-phase rule.
 */
public record AstErrorDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        Optional<BigInteger> httpStatus) implements AstDeclaration {

    public AstErrorDecl(AstNodeId id, SourceSpan span, AstName name) {
        this(id, span, name, Optional.empty());
    }

    public AstErrorDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(httpStatus, "httpStatus");
    }
}
