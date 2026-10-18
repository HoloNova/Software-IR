package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstFeatureArgument;
import io.kcg.sir.ast.AstFeatureUseDecl;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.feature.FeatureTemplate;
import io.kcg.sir.semantic.feature.Features;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Replaces each {@code use} declaration of one source unit by the declarations its feature expands into.
 *
 * <p>The expansion stays where the {@code use} was written: its declarations belong to the same source unit, carry the
 * {@code use} declaration's span and get ids under the {@code use} declaration's id, so imports, visibility and every
 * later pass treat them exactly like declarations the author wrote in that file. Only the parameters' shape is checked
 * here; whether the named entity and fields exist and fit is the ordinary analysis of the expanded declarations.
 *
 * <p>A feature whose template goes through an entity's fields needs the entities first, and an entity's fields
 * include the ones other features add. So the expansion runs twice: first every use whose template reads no entity,
 * then, with the entities as they now are, the others. A feature of the second kind cannot declare types (the types
 * are known by then) and its own added fields are not seen by other features of its kind.
 *
 * <p>A software can use a feature more than once, for different values: a use is told apart by the feature's name and
 * the values of the parameters that have no default ({@code Crud} for {@code Note} and {@code Crud} for {@code Tag}).
 */
final class FeatureExpander {
    private FeatureExpander() {}

    /**
     * Where an expanded top-level declaration came from: the {@code use} declaration, what tells that use apart, and the
     * declaration's own name in the feature.
     */
    record Expansion(AstFeatureUseDecl use, String key, String role) {}

    /** @param keys what tells each use apart, by the use declaration's id */
    record Result(List<AstDeclaration> declarations, List<AstFeatureUseDecl> uses, Map<AstNodeId, String> keys, Map<AstNodeId, Expansion> expanded) {}

    /**
     * @param defined the features the software defines itself; the language's own are always available
     * @param entities empty for the first run, which leaves the uses whose template reads an entity in place; the
     *     entities for the second run, which expands those
     */
    static Result expand(List<AstDeclaration> written, FeatureDefinitions.Result defined,
            Optional<Function<String, Optional<FeatureTemplate.Entity>>> entities, List<Diagnostic> diagnostics) {
        List<AstDeclaration> declarations = new ArrayList<>();
        List<AstFeatureUseDecl> uses = new ArrayList<>();
        Map<AstNodeId, String> keys = new LinkedHashMap<>();
        Map<AstNodeId, Expansion> expanded = new LinkedHashMap<>();
        for (AstDeclaration declaration : written) {
            if (declaration instanceof io.kcg.sir.ast.AstFeatureDefinitionDecl) {
                continue; // a definition is not something to implement; it is what a use expands by
            }

            if (!(declaration instanceof AstFeatureUseDecl use)) {
                declarations.add(declaration);
                continue;
            }
            Optional<Features.Feature> feature = Optional.ofNullable(defined.features().get(use.name().text())).or(() -> Features.named(use.name().text()));
            if (feature.isPresent() && entities.isEmpty() && feature.get().parsed().readsEntities()) {
                declarations.add(use); // expanded by the second run, once the entities are known
                continue;
            }
            uses.add(use);
            keys.put(use.id(), use.name().text());
            if (feature.isEmpty()) {
                // A definition that was refused has said what is wrong with it; its uses add nothing to that.
                if (!defined.refused().contains(use.name().text())) {
                    diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-001", "unknown feature: " + use.name().text(), use.name().span()));
                }
                continue;
            }
            Optional<Map<String, String>> values = values(use, feature.get(), diagnostics);
            if (values.isEmpty()) continue;
            String key = key(feature.get(), values.get());
            keys.put(use.id(), key);
            String text;
            try {
                text = feature.get().expand(values.get(), entities.orElse(name -> Optional.empty()));
            } catch (FeatureTemplate.Problem problem) {
                var at = problem.parameter().flatMap(p -> use.arguments().stream().filter(a -> a.parameter().text().equals(p)).findFirst())
                    .map(a -> a.value().span()).orElse(use.span());
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-003", problem.getMessage(), at));
                continue;
            }
            var parsed = SirParser.create().parseExpansion(text, use.id(), use.span());
            if (parsed.declarations().isEmpty()) {
                String why = parsed.diagnostics().stream().filter(Diagnostic::isError).findFirst().map(d -> ": " + d.message()).orElse("");
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-004", "feature " + use.name().text() + " could not be expanded" + why, use.span()));
                continue;
            }
            if (parsed.declarations().get().stream().anyMatch(d -> d instanceof AstFeatureUseDecl || d instanceof io.kcg.sir.ast.AstFeatureDefinitionDecl)) {
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-004",
                    "feature " + use.name().text() + " expands into a use or a feature declaration; a feature's text declares only types, entities, inputs, views, errors and capabilities", use.span()));
                continue;
            }
            if (entities.isPresent() && parsed.declarations().get().stream().anyMatch(io.kcg.sir.ast.AstSemanticTypeDecl.class::isInstance)) {
                diagnostics.add(DiagnosticBuilder.error("SIR-FEATURE-004",
                    "feature " + use.name().text() + " goes through an entity's fields, so it cannot declare types: the types are known before it expands", use.span()));
                continue;
            }
            String prefix = values.get().getOrDefault("prefix", "");
            for (AstDeclaration produced : parsed.declarations().get()) {
                declarations.add(produced);
                String name = ProjectSourceResolver.name(produced);
                expanded.put(produced.id(), new Expansion(use, key, name.startsWith(prefix) ? name.substring(prefix.length()) : name));
            }
        }
        return new Result(List.copyOf(declarations), List.copyOf(uses), keys, expanded);
    }

    /** The feature's name and, in order, the values of its parameters that have no default. */
    private static String key(Features.Feature feature, Map<String, String> values) {
        StringBuilder key = new StringBuilder(feature.name());
        for (Features.Parameter parameter : feature.parameters()) {
            if (parameter.defaultValue().isEmpty()) key.append('/').append(values.get(parameter.name()));
        }
        return key.toString();
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
