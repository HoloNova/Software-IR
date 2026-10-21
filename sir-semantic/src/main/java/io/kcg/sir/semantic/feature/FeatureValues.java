package io.kcg.sir.semantic.feature;

import io.kcg.sir.ast.*;
import java.util.*;

/** Typed bound values: a list never becomes a comma-separated scalar. */
public record FeatureValues(Map<String, String> scalars, Map<String, List<String>> groups,
        Map<String, List<AstNameRef>> references) {
    public FeatureValues {
        scalars = Collections.unmodifiableMap(new LinkedHashMap<>(scalars));
        groups = immutableLists(groups);
        references = immutableLists(references);
    }

    private static <T> Map<String, List<T>> immutableLists(Map<String, List<T>> original) {
        var result = new LinkedHashMap<String, List<T>>();
        original.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    public static FeatureValues of(List<AstFeatureArgument> arguments, List<Features.Parameter> parameters) {
        var scalars = new LinkedHashMap<String, String>();
        var groups = new LinkedHashMap<String, List<String>>();
        var references = new LinkedHashMap<String, List<AstNameRef>>();
        for (var argument : arguments) {
            String name = argument.parameter().text();
            switch (argument.value()) {
                case AstFeatureArgument.Word word -> {
                    scalars.put(name, word.text());
                    if (word.reference() != null && parameters.stream().anyMatch(p -> p.name().equals(name) && p.kind() == Features.Kind.NAME)) references.put(name, List.of(word.reference()));
                }
                case AstFeatureArgument.Text text -> scalars.put(name, text.value());
                case AstFeatureArgument.Duration duration -> scalars.put(name, duration.text());
                case AstFeatureArgument.Names names -> {
                    groups.put(name, names.items().stream().map(AstNameRef::text).toList());
                    references.put(name, names.items());
                }
            }
        }
        return new FeatureValues(scalars, groups, references);
    }

    public String scalar(String parameter) {
        if (!scalars.containsKey(parameter)) throw new FeatureTemplate.Problem(parameter + " is not a scalar parameter", parameter);
        return scalars.get(parameter);
    }

    public List<String> names(String parameter) {
        if (groups.containsKey(parameter)) return groups.get(parameter);
        String scalar = scalar(parameter);
        return scalar.isEmpty() ? List.of() : List.of(scalar);
    }

    public boolean given(String parameter) {
        return groups.containsKey(parameter) ? !groups.get(parameter).isEmpty() : !scalar(parameter).isEmpty();
    }
}
