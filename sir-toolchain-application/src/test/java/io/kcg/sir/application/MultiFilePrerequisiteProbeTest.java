package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.*;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.generator.springboot.api.*;
import io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Q18 prerequisite experiments over today's APIs, NOT a multi-file product entry.
 * Each simulated part is parsed as a valid legacy document to obtain real per-file AST nodes.
 * We combine AST declarations (never source strings), then exercise the existing target.
 * The wrapper is test-only: Q18's actual fragments must not repeat software/target headers.
 */
class MultiFilePrerequisiteProbeTest {
    private static final SourceId ROOT = SourceId.of("project.sir");
    private static final String SOURCE = resource().replace("capability SearchCourseEnrollments {",
            "capability SearchCourseEnrollments @id(\"search-course-enrollments\") {");

    @Test
    void proposedProjectWordsAreCurrentlyLegalLegacyIdentifiers() {
        for (String name : List.of("sources", "source", "imports", "import")) {
            String header = SOURCE.substring(0, SOURCE.indexOf("  declarations {"));
            var doc = parse(header + " declarations { entity " + name
                    + " persistent { identity id: Int64 generated auto; } } }", ROOT);
            assertEquals(name, ((AstEntityDecl) doc.software().declarations().getFirst()).name().text());
        }
    }

    @Test
    void realPerFileDeclarationsAndCanonicalKindThenNameOrderKeepGeneratedCourseBytes() {
        var single = compile(parse(SOURCE, ROOT));
        var split = compile(split(false));
        assertEquals(inventory(single.files()), inventory(split.files()));
        assertEquals(4, split.model().declarations().stream()
                .map(d -> d.span().source()).distinct().count());
    }

    @Test
    void alphabeticalOnlyMergingIsInsufficientForTodaysRelationResolver() {
        var doc = split(false);
        var software = doc.software();
        var alphabetical = software.declarations().stream().sorted(Comparator.comparing(MultiFilePrerequisiteProbeTest::name)).toList();
        var reordered = new AstDocument(doc.id(), doc.span(), doc.sirVersion(), new AstSoftware(software.id(),
                software.span(), software.name(), software.metadata(), software.target(), alphabetical));
        var semantic = new SirSemanticAnalyzer().analyze(reordered);
        assertFalse(semantic.isSuccess());
        assertTrue(semantic.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-SYMBOL-002")
                && d.message().contains("Enrollment")), semantic.diagnostics().toString());
    }

    @Test
    void typedReferenceSitesCarryTheSourceAndTargetNeededForVisibilityChecking() {
        var model = compile(split(false)).model();
        Set<ReferenceRole> crossFileRoles = EnumSet.noneOf(ReferenceRole.class);
        for (var binding : model.referenceSiteBindings().all()) {
            var symbol = model.symbols().byId(binding.targetSymbol()).orElseThrow();
            if (symbol.kind() != SymbolKind.PRIMITIVE
                    && !binding.site().span().source().equals(symbol.declarationSpan().source())) {
                crossFileRoles.add(binding.site().role());
                assertTrue(model.declarations().stream().anyMatch(decl ->
                        decl.span().source().equals(symbol.declarationSpan().source())
                        && decl.span().start().codePointOffset() <= symbol.declarationSpan().start().codePointOffset()
                        && decl.span().end().codePointOffset() >= symbol.declarationSpan().end().codePointOffset()),
                        () -> "target has no top-level owner: " + symbol);
            }
        }
        assertTrue(crossFileRoles.containsAll(Set.of(ReferenceRole.REF_TYPE_TARGET,
                ReferenceRole.VIEW_SOURCE_ENTITY, ReferenceRole.EXISTS_SOURCE_ENTITY,
                ReferenceRole.EXISTS_CONDITION_FIELD, ReferenceRole.ORDER_FIELD,
                ReferenceRole.CREATE_ENTITY, ReferenceRole.LOAD_ENTITY,
                ReferenceRole.PATCH_SOURCE_ENTITY, ReferenceRole.VIEW_FIELD)), crossFileRoles.toString());
    }

    @Test
    void legacyGraphActuallyRejectsRealFragmentSpans() {
        var compiled = compile(split(false));
        var result = new ProjectGraphBuilder().build(new SpringBootProjectGraphInputFactory().build(
                compiled.model(), compiled.lowered(), compiled.files(), ROOT));
        var failure = assertInstanceOf(ProjectGraphAnalysis.Failure.class, result);
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-GRAPH-PROVENANCE-007")));
    }

    @Test
    void movingAnExplicitCapabilityKeepsItsSymbolButNotItsAstLocationIdentity() {
        var before = compile(split(false));
        var after = compile(split(true));
        var b = before.model().declarations().stream().filter(d -> d.name().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        var a = after.model().declarations().stream().filter(d -> d.name().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        assertEquals(b.id(), a.id());
        assertEquals(ROOT, b.span().source());
        assertEquals(SourceId.of("modules/search.sir"), a.span().source());
        assertNotEquals(b.sourceNodeId(), a.sourceNodeId());
        assertEquals(inventory(before.files()), inventory(after.files()));
    }

    private static AstDocument split(boolean moveSearch) {
        var original = parse(SOURCE, ROOT);
        String header = SOURCE.substring(0, SOURCE.indexOf("  declarations {"));
        Map<SourceId, List<String>> snippets = new TreeMap<>(Comparator.comparing(SourceId::value));
        for (var declaration : original.software().declarations()) {
            String name = name(declaration);
            String file = switch (name) {
                case "Course" -> "modules/course.sir";
                case "Student" -> "modules/student.sir";
                case "Enrollment", "EnrollmentStatus" -> "modules/enrollment.sir";
                case "SearchCourseEnrollments" -> moveSearch ? "modules/search.sir" : "project.sir";
                default -> "project.sir";
            };
            var span = declaration.span();
            String snippet = SOURCE.substring(SOURCE.offsetByCodePoints(0, span.start().codePointOffset()),
                    SOURCE.offsetByCodePoints(0, span.end().codePointOffset()));
            snippets.computeIfAbsent(SourceId.of(file), unused -> new ArrayList<>()).add(snippet);
        }
        List<AstDeclaration> declarations = new ArrayList<>();
        AstDocument root = null;
        for (var entry : snippets.entrySet()) {
            var document = parse(header + " declarations {\n" + String.join("\n", entry.getValue()) + "\n} }", entry.getKey());
            declarations.addAll(document.software().declarations());
            if (entry.getKey().equals(ROOT)) root = document;
        }
        declarations.sort(Comparator.comparingInt(MultiFilePrerequisiteProbeTest::rank)
                .thenComparing(MultiFilePrerequisiteProbeTest::name));
        var software = Objects.requireNonNull(root).software();
        return new AstDocument(root.id(), root.span(), root.sirVersion(), new AstSoftware(software.id(),
                software.span(), software.name(), software.metadata(), software.target(), declarations));
    }

    private static int rank(AstDeclaration declaration) {
        return switch (declaration) {
            case AstEnumDecl d -> 0;
            case AstEntityDecl d -> 1;
            case AstInputDecl d -> 2;
            case AstViewDecl d -> 3;
            case AstErrorDecl d -> 4;
            case AstCapabilityDecl d -> 5;
        };
    }

    private static String name(AstDeclaration declaration) {
        return switch (declaration) {
            case AstEntityDecl d -> d.name().text();
            case AstEnumDecl d -> d.name().text();
            case AstInputDecl d -> d.name().text();
            case AstViewDecl d -> d.name().text();
            case AstErrorDecl d -> d.name().text();
            case AstCapabilityDecl d -> d.name().text();
        };
    }

    private static AstDocument parse(String text, SourceId id) {
        var parsed = SirParser.create().parse(new SirSource(id, text));
        assertTrue(parsed.isSuccess(), parsed.diagnostics().toString());
        return parsed.document().orElseThrow();
    }

    private static Compilation compile(AstDocument document) {
        var semantic = new SirSemanticAnalyzer().analyze(document);
        assertTrue(semantic.isSuccess(), semantic.diagnostics().toString());
        var lowering = new SpringBootTargetLowering().lower(semantic.model().orElseThrow());
        assertTrue(lowering.isSuccess(), lowering.diagnostics().toString());
        var lowered = lowering.model().orElseThrow();
        var generated = assertInstanceOf(GenerationResult.Success.class, new SpringBootGenerator().generate(lowered));
        return new Compilation(semantic.model().orElseThrow(), lowered, generated.files());
    }

    private static Map<String, String> inventory(List<GeneratedFile> files) {
        Map<String, String> result = new TreeMap<>();
        for (var file : files) assertNull(result.put(file.relativePath(), file.content()));
        return result;
    }

    private static String resource() {
        try (var stream = MultiFilePrerequisiteProbeTest.class.getClassLoader().getResourceAsStream("valid/course-admin-enrollment.sir")) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    private record Compilation(NormalizedSemanticModel model, SpringBootLoweredModel lowered, List<GeneratedFile> files) {}
}
