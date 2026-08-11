package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstMetadata(
        AstNodeId id,
        SourceSpan span,
        String displayName,
        SourceSpan displayNameSpan,
        String namespace,
        SourceSpan namespaceSpan) implements AstNode {

    public AstMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(displayNameSpan, "displayNameSpan");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(namespaceSpan, "namespaceSpan");
    }
}
