package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/** Raw decoded source-root-relative path, retaining its literal's diagnostic location. */
public record AstSourcePath(AstNodeId id, SourceSpan span, String path) {
    public AstSourcePath { Objects.requireNonNull(id); Objects.requireNonNull(span); Objects.requireNonNull(path); }
}
