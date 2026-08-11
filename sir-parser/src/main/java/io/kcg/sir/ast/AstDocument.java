package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstDocument(
        AstNodeId id,
        SourceSpan span,
        SirVersion sirVersion,
        AstSoftware software) implements AstNode {

    public AstDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(sirVersion, "sirVersion");
        Objects.requireNonNull(software, "software");
    }
}
