package io.kcg.sir.api;

import io.kcg.sir.ast.AstDeclaration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The declarations a feature's text expands into, or the diagnostics that text produced. */
public record ExpansionParseResult(Optional<List<AstDeclaration>> declarations, List<Diagnostic> diagnostics) {
    public ExpansionParseResult {
        declarations = Objects.requireNonNull(declarations).map(List::copyOf);
        diagnostics = List.copyOf(diagnostics);
        if (declarations.isPresent() == diagnostics.stream().anyMatch(Diagnostic::isError))
            throw new IllegalArgumentException("declarations presence must match parse success");
    }
}
