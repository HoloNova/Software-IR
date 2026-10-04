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
        Map<SourceId, Map<String, AstDeclaration>> declarations = new HashMap<>();
        for (var unit : units.values()) {
            Map<String, AstDeclaration> local = new HashMap<>();
            for (AstDeclaration declaration : unit.declarations()) {
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
        List<AstDeclaration> ordered = units.values().stream().flatMap(u -> u.declarations().stream())
                .sorted(Comparator.comparingInt(ProjectSourceResolver::category).thenComparing(ProjectSourceResolver::name)).toList();
        var original = root.document().software();
        var software = new AstSoftware(original.id(), original.span(), original.name(), original.metadata(), original.target(), ordered);
        var resolved = new ResolvePass(software).run();
        if (resolved.diagnostics().stream().anyMatch(Diagnostic::isError)) return failure(resolved.diagnostics());
        Map<SymbolId, AstDeclaration> top = new HashMap<>();
        ordered.forEach(d -> top.put(resolved.declarationBindings().get(d.id()), d));
        for (var binding : resolved.referenceSiteBindings().all()) {
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
        if (!diagnostics.isEmpty()) return failure(diagnostics);
        // No second Resolve, no fallback lookup in Type/Validate/Normalize.
        var typed = new TypePass(software, resolved).run();
        var validated = new ValidatePass(software, typed).run();
        return new NormalizePass(software, validated).run();
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
        };
    }
    private static String name(AstDeclaration declaration) {
        return switch (declaration) {
            case AstEnumDecl d -> d.name().text(); case AstEntityDecl d -> d.name().text(); case AstInputDecl d -> d.name().text();
            case AstViewDecl d -> d.name().text(); case AstErrorDecl d -> d.name().text(); case AstCapabilityDecl d -> d.name().text();
        };
    }
}
