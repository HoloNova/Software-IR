package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstImport(AstNodeId id, SourceSpan span, AstNameRef declaration, AstSourcePath from) {
    public AstImport { Objects.requireNonNull(id); Objects.requireNonNull(span); Objects.requireNonNull(declaration); Objects.requireNonNull(from); }
}
