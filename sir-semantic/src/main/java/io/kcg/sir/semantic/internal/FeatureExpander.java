package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstFeatureArgument;
import io.kcg.sir.ast.AstFeatureUseDecl;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.feature.Features;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replaces each {@code use} declaration of one source unit by the declarations its feature expands into.
 *
 * <p>The expansion stays where the {@code use} was written: its declarations belong to the same source unit, carry the
 * {@code use} declaration's span and get ids under the {@code use} declaration's id, so imports, visibility and every
 * later pass treat them exactly like declarations the author wrote in that file. Only the parameters' shape is checked
 * here; whether the named entity and fields exist and fit is the ordinary analysis of the expanded declarations.
 */
final class FeatureExpander {
    private FeatureExpander() {}

    /** Where an expanded top-level declaration came from: the {@code use} declaration, and its own name in the feature. */
    record Expansion(AstFeatureUseDecl use, String role) {}

    record Result(List<AstDeclaration> declarations, List<AstFeatureUseDecl> uses, Map<AstNodeId, Expansion> expanded) {}

    static Result expand(List<AstDeclaration> written, List<Diagnostic> diagnostics) {
        List<AstDeclaration> declarations = new ArrayList<>();
        List<AstFeatureUseDecl> uses = new ArrayList<>();
        Map<AstNodeId, Expansion> expanded = new LinkedHashMap<>();
        for (AstDeclaration declaration : written) {
            if (!(declaration instanceof AstFeatureUseDecl use)) {
                declarations.add(declaration);
                continue;
            }
            uses.add(use);
            Optional<Features.Feature> feature = Features.named(use.name().text());
            if (feature.isEmpty()) {
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-001", "unknown feature: " + use.name().text(), use.name().span()));
                continue;
            }
            Optional<Map<String, String>> values = values(use, feature.get(), diagnostics);
            if (values.isEmpty()) continue;
            var parsed = SirParser.create().parseExpansion(feature.get().expand(values.get()), use.id(), use.span());
            if (parsed.declarations().isEmpty()) {
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-004", "feature " + use.name().text() + " could not be expanded", use.span()));
                continue;
            }
            String prefix = values.get().getOrDefault("prefix", "");
            for (AstDeclaration produced : parsed.declarations().get()) {
                declarations.add(produced);
                String name = ProjectSourceResolver.name(produced);
                expanded.put(produced.id(), new Expansion(use, name.startsWith(prefix) ? name.substring(prefix.length()) : name));
            }
        }
        return new Result(List.copyOf(declarations), List.copyOf(uses), expanded);
    }

    /** Every parameter's text with defaults applied, or empty after reporting why not. */
    private static Optional<Map<String, String>> values(AstFeatureUseDecl use, Features.Feature feature, List<Diagnostic> diagnostics) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean refused = false;
        for (AstFeatureArgument argument : use.arguments()) {
            String name = argument.parameter().text();
            Optional<Features.Parameter> parameter = feature.parameter(name);
            if (parameter.isEmpty()) {
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-002", feature.name() + " has no parameter " + name, argument.parameter().span()));
                refused = true;
                continue;
            }
            if (values.containsKey(name)) {
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-002", "parameter " + name + " is written twice", argument.parameter().span()));
                refused = true;
                continue;
            }
            boolean duration = argument.value() instanceof AstFeatureArgument.Duration;
            if (duration != (parameter.get().kind() == Features.Kind.DURATION)) {
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-003",
                    name + " must be " + (duration ? "a name" : "a duration such as 15 minutes"), argument.value().span()));
                refused = true;
                continue;
            }
            Optional<String> problem = parameter.get().problem(argument.value().text(), seconds(argument.value()));
            if (problem.isPresent()) {
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-003", problem.get(), argument.value().span()));
                refused = true;
                continue;
            }
            values.put(name, argument.value().text());
        }
        for (Features.Parameter parameter : feature.parameters()) {
            if (values.containsKey(parameter.name()) || refused) continue;
            if (parameter.defaultValue().isPresent()) values.put(parameter.name(), parameter.defaultValue().get());
            else {
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-002", feature.name() + " needs parameter " + parameter.name(), use.span()));
                refused = true;
            }
        }
        return refused ? Optional.empty() : Optional.of(values);
    }

    private static BigInteger seconds(AstFeatureArgument.Value value) {
        if (!(value instanceof AstFeatureArgument.Duration duration)) return BigInteger.ZERO;
        long unit = switch (duration.unit()) {
            case SECONDS -> 1;
            case MINUTES -> 60;
            case HOURS -> 3_600;
            case DAYS -> 86_400;
        };
        return duration.amount().multiply(BigInteger.valueOf(unit));
    }
}
