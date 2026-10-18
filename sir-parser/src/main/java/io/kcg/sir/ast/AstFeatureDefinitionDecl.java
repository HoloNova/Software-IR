package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@code feature} declaration: a feature of the author's own. It names its parameters and holds the text its
 * {@code use} expands into, with {@code ${parameter}} where a parameter's value goes. The parser records only what is
 * written; the semantic analysis checks the parameters and the text.
 */
public record AstFeatureDefinitionDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        List<Parameter> parameters,
        SourceSpan templateSpan,
        String template) implements AstDeclaration {

    /**
     * One parameter: its name, the kind of value ({@code name} or {@code duration}, as written) and its default if it
     * has one. A default written as {@code ""} is the empty name.
     */
    public record Parameter(SourceSpan span, AstName name, AstName kind, Optional<AstFeatureArgument.Value> defaultValue, boolean emptyDefault) {
        public Parameter {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(defaultValue, "defaultValue");
        }
    }

    public AstFeatureDefinitionDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        Objects.requireNonNull(templateSpan, "templateSpan");
        Objects.requireNonNull(template, "template");
    }
}
