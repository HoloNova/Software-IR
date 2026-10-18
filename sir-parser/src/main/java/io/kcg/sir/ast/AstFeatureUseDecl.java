package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * A {@code use} declaration: the author asks for a language feature by name, with its arguments
 * ({@code use DualTokenLogin { account: User; access: 15 minutes; }}).
 *
 * <p>The parser records only what is written. Which features exist, which parameters they take and what
 * they expand into is decided by the semantic analysis.
 */
public record AstFeatureUseDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        List<AstFeatureArgument> arguments) implements AstDeclaration {

    public AstFeatureUseDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }
}
