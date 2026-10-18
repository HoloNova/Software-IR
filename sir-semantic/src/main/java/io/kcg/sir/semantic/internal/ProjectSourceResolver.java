package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.*;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.semantic.symbol.*;
import io.kcg.sir.source.*;
import java.util.*;

/** File visibility is verified against typed reference bindings, including inferred field owners. */
public final class ProjectSourceResolver {
    private ProjectSourceResolver() {}

    public static SemanticAnalysis analyze(ProjectSemanticInput input) {
        var root = input.root();
        var diagnostics = new ArrayList<Diagnostic>();
        Map<SourceId, AstSourceUnit> units = new TreeMap<>(Comparator.comparing(SourceId::value));
        SourceId entry = root.span().source();
        units.put(entry, root);
        for (var fragment : input.fragments()) {
            if (units.putIfAbsent(fragment.span().source(), fragment) != null)
                diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-SET-001", "source unit provided twice", fragment.span()));
        }
        Set<SourceId> declaredSources = new HashSet<>(); declaredSources.add(entry);
        for (var path : root.sources()) {
            try {
                var id = SourceSetManifest.strictId(path.path());
                if (!declaredSources.add(id)) diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-SET-001", "duplicate or entry source in sources: " + id.value(), path.span()));
            } catch (IllegalArgumentException e) {
                diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-SET-001", e.getMessage(), path.span()));
            }
        }
        if (!declaredSources.equals(units.keySet()))
            diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-SET-001", "parsed units do not exactly cover the sources list", root.span()));
        // Each use declaration becomes, in its own unit, the declarations its feature expands into; from here on every
        // check sees the expanded declarations as if the author had written them in that file.
        // The features the software defines are known to every file, like the language's own.
        List<AstFeatureDefinitionDecl> definitions = new ArrayList<>();
        for (var unit : units.values())
            unit.declarations().stream().filter(AstFeatureDefinitionDecl.class::isInstance).map(AstFeatureDefinitionDecl.class::cast).forEach(definitions::add);
        var featureDefinitions = FeatureDefinitions.build(definitions, diagnostics);
        // First the uses whose feature reads no entity; they may add fields to entities and declare types.
        Map<SourceId, List<AstDeclaration>> featured = new HashMap<>();
        List<AstFeatureUseDecl> uses = new ArrayList<>();
        Map<AstNodeId, String> useKeys = new HashMap<>();
        Map<AstNodeId, FeatureExpander.Expansion> expanded = new HashMap<>();
        for (var unit : units.values()) {
            var result = FeatureExpander.expand(unit.declarations(), featureDefinitions, Optional.empty(), diagnostics);
            featured.put(unit.span().source(), result.declarations());
            uses.addAll(result.uses());
            useKeys.putAll(result.keys());
            expanded.putAll(result.expanded());
        }
        // The semantic types the software declares are known to every file, like the language's own; the fields of every
        // file (an expanded feature's too) then get the rules their type brings.
        List<AstSemanticTypeDecl> typeDeclarations = new ArrayList<>();
        for (var unit : units.values())
            featured.get(unit.span().source()).stream().filter(AstSemanticTypeDecl.class::isInstance).map(AstSemanticTypeDecl.class::cast).forEach(typeDeclarations::add);
        var semanticTypes = SemanticTypeDeclarations.build(typeDeclarations, diagnostics);
        // Then the uses whose feature goes through an entity's fields, with the entities as the first run left them.
        var entities = FeatureEntities.of(units.values().stream().map(u -> featured.get(u.span().source())).toList(), semanticTypes.types());
        for (var unit : units.values()) {
            var result = FeatureExpander.expand(featured.get(unit.span().source()), featureDefinitions, Optional.of(entities), diagnostics);
            featured.put(unit.span().source(), result.declarations());
            uses.addAll(result.uses());
            useKeys.putAll(result.keys());
            expanded.putAll(result.expanded());
        }
        Map<SourceId, List<AstDeclaration>> effective = new HashMap<>();
        for (var unit : units.values())
            effective.put(unit.span().source(), SemanticTypeExpander.expand(featured.get(unit.span().source()).stream()
                .filter(d -> !(d instanceof AstSemanticTypeDecl)).toList(), semanticTypes.types()));
        // Every entity gets the fields extend declarations add to it; from here on they are its own fields.
        var extensions = EntityExtensions.merge(units, effective, diagnostics);
        effective.putAll(extensions.declarations());
        // A feature can be used again for other values, never twice for the same ones.
        Map<String, AstFeatureUseDecl> usedFeatures = new HashMap<>();
        for (AstFeatureUseDecl use : uses) {
            String key = useKeys.get(use.id());
            AstFeatureUseDecl first = usedFeatures.putIfAbsent(key, use);
            if (first != null) diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-FEATURE-005", "feature used twice for the same values: " + key,
                    use.span(), new io.kcg.sir.api.RelatedLocation("首次使用在此处", first.span())));
        }
        Map<SourceId, Map<String, AstDeclaration>> declarations = new HashMap<>();
        for (var unit : units.values()) {
            Map<String, AstDeclaration> local = new HashMap<>();
            for (AstDeclaration declaration : effective.get(unit.span().source())) {
                local.putIfAbsent(name(declaration), declaration); // the existing Symbol pass diagnoses duplicate declarations
                if (!declaration.span().source().equals(unit.span().source()))
                    diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-SET-001", "declaration location belongs to a different source unit", declaration.span()));
            }
            declarations.put(unit.span().source(), local);
        }
        Map<SourceId, Set<AstNodeId>> imports = new HashMap<>();
        for (var unit : units.values()) {
            Set<String> visibleNames = new HashSet<>(declarations.get(unit.span().source()).keySet());
            Set<AstNodeId> visible = new HashSet<>();
            imports.put(unit.span().source(), visible);
            for (var imported : unit.imports()) {
                SourceId from;
                try { from = SourceSetManifest.strictId(imported.from().path()); }
                catch (IllegalArgumentException e) {
                    diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-IMPORT-001", e.getMessage(), imported.from().span())); continue;
                }
                if (from.equals(unit.span().source())) {
                    diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-IMPORT-004", "self import is unnecessary and forbidden", imported.from().span())); continue;
                }
                Map<String, AstDeclaration> sourceDeclarations = declarations.get(from);
                if (sourceDeclarations == null) {
                    diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-IMPORT-001", "import source is not in the project sources: " + from.value(), imported.from().span())); continue;
                }
                AstDeclaration found = sourceDeclarations.get(imported.declaration().text());
                if (found == null) {
                    diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-IMPORT-002", "declaration not found in specified source: " + imported.declaration().text(), imported.declaration().span())); continue;
                }
                if (!visibleNames.add(imported.declaration().text())) {
                    diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-IMPORT-003", "duplicate or local-conflicting imported name: " + imported.declaration().text(), imported.declaration().span())); continue;
                }
                visible.add(found.id());
            }
        }
        if (units.size() > SourceSetManifest.MAX_FILES) diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-SET-001", "too many source units", root.span()));
        if (!diagnostics.isEmpty()) return failure(diagnostics);
        Set<SourceId> visiting = new HashSet<>(), done = new HashSet<>();
        for (SourceId source : units.keySet()) cycles(source, units, visiting, done, diagnostics);
        if (!diagnostics.isEmpty()) return failure(diagnostics);
        // Canonical category order gives entity members to the existing relation resolver before views.
        List<AstDeclaration> ordered = units.values().stream().flatMap(u -> effective.get(u.span().source()).stream())
                .sorted(Comparator.comparingInt(ProjectSourceResolver::category).thenComparing(ProjectSourceResolver::name)).toList();
        var original = root.document().software();
        var software = new AstSoftware(original.id(), original.span(), original.name(), original.metadata(), original.target(), ordered);
        var resolved = new ResolvePass(software, semanticTypes.types(), semanticTypes.spans()).run();
        // Resolution reports what it can and the later passes still run, so one check shows the type and validation errors
        // as well. Only a broken identity stops here: nothing after it can trust the symbols.
        if (resolved.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-IDENTITY-002"))) return failure(resolved.diagnostics());
        boolean resolutionFailed = resolved.diagnostics().stream().anyMatch(Diagnostic::isError);
        Map<SymbolId, AstDeclaration> top = new HashMap<>();
        ordered.forEach(d -> top.put(resolved.declarationBindings().get(d.id()), d));
        // Visibility is judged on complete bindings; after a resolution error they are not, and the check would only echo it.
        for (var binding : resolutionFailed ? List.<ReferenceSiteBinding>of() : resolved.referenceSiteBindings().all()) {
            Symbol target = resolved.symbols().byId(binding.targetSymbol()).orElseThrow();
            if (target.kind() == SymbolKind.PRIMITIVE || target.kind() == SymbolKind.VARIABLE) continue;
            SymbolId topId = switch (target) {
                case Symbol.FieldSymbol f -> f.ownerId();
                case Symbol.EnumMemberSymbol e -> e.enumId();
                default -> target.id();
            };
            var owner = top.get(topId);
            if (owner == null) {
                diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-VISIBILITY-002", "reference has no top-level declaration owner: " + target.name(), binding.site().span()));
                continue;
            }
            SourceId using = binding.site().span().source();
            if (!using.equals(owner.span().source()) && !imports.getOrDefault(using, Set.of()).contains(owner.id())) {
                diagnostics.add(DiagnosticBuilder.errorWithRelated("SIR-SOURCE-VISIBILITY-001",
                        "reference to " + name(owner) + " requires an explicit import from " + owner.span().source().value(), binding.site().span(),
                        new io.kcg.sir.api.RelatedLocation("目标声明位于此处", owner.span())));
            }
        }
        if (!diagnostics.isEmpty()) {
            diagnostics.addAll(0, resolved.diagnostics().stream().filter(Diagnostic::isError).toList());
            return failure(diagnostics);
        }
        // No second Resolve, no fallback lookup in Type/Validate/Normalize.
        var typed = new TypePass(software, resolved).run();
        var validated = new ValidatePass(software, typed).run();
        return new NormalizePass(software, validated, uses, useKeys, expanded, semanticTypes.types(), semanticTypes.declarations(), featureDefinitions.definitions(),
                extensions.extensions(), extensions.addedFields()).run();
    }

    private static void cycles(SourceId source, Map<SourceId, AstSourceUnit> units,
            Set<SourceId> visiting, Set<SourceId> done, List<Diagnostic> diagnostics) {
        if (done.contains(source)) return;
        visiting.add(source);
        for (var imported : units.get(source).imports()) {
            SourceId target = SourceSetManifest.strictId(imported.from().path());
            if (visiting.contains(target)) diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-IMPORT-005",
                    "file import cycle closes at " + target.value(), imported.from().span()));
            else cycles(target, units, visiting, done, diagnostics);
        }
        visiting.remove(source); done.add(source);
    }

    private static SemanticAnalysis.Failure failure(List<Diagnostic> diagnostics) {
        return new SemanticAnalysis.Failure(diagnostics.stream().sorted(Comparator
                .comparing((Diagnostic d) -> d.primarySpan().source().value())
                .thenComparingInt(d -> d.primarySpan().start().codePointOffset())
                .thenComparing(d -> d.code().value())).toList());
    }
    private static int category(AstDeclaration declaration) {
        return switch (declaration) {
            case AstEnumDecl d -> 0; case AstEntityDecl d -> 1; case AstInputDecl d -> 2;
            case AstViewDecl d -> 3; case AstErrorDecl d -> 4; case AstCapabilityDecl d -> 5;
            case AstFeatureUseDecl d -> 6;
            case AstSemanticTypeDecl d -> 7;
            case AstFeatureDefinitionDecl d -> 8;
            case AstEntityExtensionDecl d -> 9;
        };
    }
    static String name(AstDeclaration declaration) {
        return switch (declaration) {
            case AstEnumDecl d -> d.name().text(); case AstEntityDecl d -> d.name().text(); case AstInputDecl d -> d.name().text();
            case AstViewDecl d -> d.name().text(); case AstErrorDecl d -> d.name().text(); case AstCapabilityDecl d -> d.name().text();
            case AstFeatureUseDecl d -> d.name().text();
            case AstSemanticTypeDecl d -> d.name().text();
            case AstFeatureDefinitionDecl d -> d.name().text();
            case AstEntityExtensionDecl d -> d.entity().text();
        };
    }
}
