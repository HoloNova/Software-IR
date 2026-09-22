package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstCreateStep;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstMemberExpression;
import io.kcg.sir.ast.AstNameExpression;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstRefTypeRef;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.ReferenceSite;
import io.kcg.sir.semantic.api.ReferenceSiteBinding;
import io.kcg.sir.semantic.api.ReferenceSiteBindings;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the typed reference-site contract (ADR-005).
 *
 * <p>These tests assert the invariants of {@link ReferenceSiteBindings}, {@link ReferenceSite} and
 * {@link ReferenceSiteBinding}, and verify that a single legal SIR source covering every
 * {@link ReferenceRole} values produces a deterministic, immutable, fully-bound reference-site
 * collection. They also verify that undefined references and role/Kind mismatches produce only
 * Resolve-stage diagnostics (no duplicate diagnostics from later passes).
 */
class TypedReferenceSiteContractTest {

    private static final String SOFTWARE_DECLARATIONS = """
            enum Status { ACTIVE, INACTIVE }
            entity User persistent {
              identity id: Int64 generated auto;
              field state: Status;
            }
            entity Account persistent {
              identity id: Int64 generated auto;
              field state: Status;
              field version: Int64 versioned;
            }
            input LookupInput {
              field id: Int64;
              field active: Boolean;
            }
            view UserSummary from User {
              field id: Int64;
              field state: Status;
            }
            view AccountSummary from Account {
              field id: Int64;
              field state: Status;
            }
            input SearchInput {
              field keyword: String;
              field page: Int32;
              field size: Int32;
            }
            input AccountPatch patch of Account {
              field id: Int64;
              field state: Status;
            }
            error NotFound;
            error InvalidPage;
            error Stale;
            error InvalidChange;
            capability ExerciseAll {
              input LookupInput;
              output List<Ref<User>>;
              fails NotFound;
              requires atomic;
              expose command;
              workflow {
                validate input.active else NotFound;
                load User by input.id as user else NotFound;
                create User as created { state: Status.INACTIVE; }
                update user { state: Status.ACTIVE; }
                persist user;
                find User where item.id == input.id as users;
                return users;
              }
            }
            capability PatchAccount {
              input AccountPatch;
              output AccountSummary;
              fails InvalidChange;
              fails NotFound;
              fails Stale;
              requires atomic;
              expose command;
              workflow {
                validate input.state.present else InvalidChange;
                load Account by input.id as account else NotFound;
                update account { state: input.state; }
                persist account else Stale;
                return account;
              }
            }
            capability SearchUsers {
              input SearchInput;
              output Page<UserSummary>;
              fails InvalidPage;
              requires readonly;
              expose query;
              workflow {
                find User
                  where item.state == Status.ACTIVE
                  order by id descending
                  Page input.page, input.size else InvalidPage
                  as users;
                return users;
              }
            }
            """;

    private static String contractSource() {
        return TestSources.sir(SOFTWARE_DECLARATIONS);
    }

    private static SemanticAnalysis analyzeContract() {
        SemanticAnalysis result = TestSources.analyze(contractSource());
        assertTrue(result.isSuccess(), () -> "Contract source must analyze successfully: "
                + TestSources.errorCodes(result));
        return result;
    }

    @Test
    void everyReferenceRoleIsCoveredByAtLeastOneBinding() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        ReferenceSiteBindings bindings = model.referenceSiteBindings();
        assertFalse(bindings.all().isEmpty(), "ReferenceSiteBindings must not be empty");

        Set<ReferenceRole> presentRoles = EnumSet.noneOf(ReferenceRole.class);
        for (ReferenceSiteBinding b : bindings.all()) {
            presentRoles.add(b.site().role());
        }
        assertEquals(EnumSet.allOf(ReferenceRole.class), presentRoles,
                "Every ReferenceRole must be exercised by the contract source");
    }

    @Test
    void eachSiteHasIdSpanTextRoleAndUniqueAstNodeId() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        ReferenceSiteBindings bindings = model.referenceSiteBindings();

        Set<AstNodeId> seenSiteIds = new java.util.HashSet<>();
        for (ReferenceSiteBinding b : bindings.all()) {
            ReferenceSite site = b.site();
            assertNotEquals(null, site.id(), "site.id must not be null");
            assertNotEquals(null, site.span(), "site.span must not be null");
            assertNotEquals(null, site.text(), "site.text must not be null");
            assertNotEquals(null, site.role(), "site.role must not be null");
            assertFalse(site.text().isBlank(), "site.text must not be blank");

            SourceSpan span = site.span();
            assertTrue(span.start() != null && span.end() != null,
                    "site.span must have non-null start/end");
            assertTrue(span.end().codePointOffset() >= span.start().codePointOffset(),
                    "site.span end must not precede start");

            assertTrue(seenSiteIds.add(site.id()),
                    "Duplicate ReferenceSite AstNodeId: " + site.id().value());
        }
    }

    @Test
    void eachLegalSiteHasExactlyOneBinding() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        ReferenceSiteBindings bindings = model.referenceSiteBindings();

        Map<AstNodeId, Integer> siteCounts = new LinkedHashMap<>();
        for (ReferenceSiteBinding b : bindings.all()) {
            siteCounts.merge(b.site().id(), 1, Integer::sum);
        }
        for (Map.Entry<AstNodeId, Integer> entry : siteCounts.entrySet()) {
            assertEquals(1, entry.getValue(),
                    "Each legal ReferenceSite must have exactly one binding; site "
                            + entry.getKey().value() + " has " + entry.getValue());
        }
    }

    @Test
    void eachTargetSymbolKindMatchesRoleAllowedKinds() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        ReferenceSiteBindings bindings = model.referenceSiteBindings();

        for (ReferenceSiteBinding b : bindings.all()) {
            ReferenceRole role = b.site().role();
            Optional<Symbol> target = model.symbols().byId(b.targetSymbol());
            assertTrue(target.isPresent(),
                    "Target Symbol " + b.targetSymbol() + " must exist in SymbolTable for site "
                            + b.site().id().value());
            SymbolKind kind = target.get().kind();
            assertTrue(role.allowedTargetKinds().contains(kind),
                    "Role " + role + " does not allow target SymbolKind " + kind
                            + " (site " + b.site().id().value() + ", text '" + b.site().text() + "')");
        }
    }

    @Test
    void bindingsAreInCanonicalOrderByAstNodeIdValue() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        List<ReferenceSiteBinding> all = model.referenceSiteBindings().all();

        List<String> actualOrder = all.stream()
                .map(b -> b.site().id().value())
                .toList();
        List<String> expectedOrder = new ArrayList<>(actualOrder);
        expectedOrder.sort(String::compareTo);

        assertEquals(expectedOrder, actualOrder,
                "ReferenceSiteBindings must iterate in canonical order by AstNodeId.value() "
                        + "using String.compareTo (Locale.ROOT semantics)");
    }

    @Test
    void bindingForReturnsOptionalAndResolvesCorrectTarget() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        ReferenceSiteBindings bindings = model.referenceSiteBindings();

        AstNodeId unknownId = new AstNodeId("sir://nonexistent/site");
        Optional<ReferenceSiteBinding> absent = bindings.bindingFor(unknownId);
        assertTrue(absent.isEmpty(), "bindingFor must return empty Optional for unknown site ID");

        Optional<SymbolId> absentTarget = bindings.targetFor(unknownId);
        assertTrue(absentTarget.isEmpty(), "targetFor must return empty Optional for unknown site ID");

        for (ReferenceSiteBinding b : bindings.all()) {
            Optional<ReferenceSiteBinding> found = bindings.bindingFor(b.site().id());
            assertTrue(found.isPresent(), "bindingFor must return present Optional for known site ID");
            assertEquals(b, found.get(), "bindingFor must return the same binding instance/equals");
            assertEquals(b.targetSymbol(), bindings.targetFor(b.site().id()).orElseThrow(),
                    "targetFor must return the same SymbolId as the binding");
        }

        assertTrue(bindings.contains(bindings.all().getFirst().site().id()),
                "contains must return true for known site ID");
        assertFalse(bindings.contains(unknownId),
                "contains must return false for unknown site ID");
        assertEquals(bindings.all().size(), bindings.size(),
                "size() must equal all().size()");
    }

    @Test
    void allReturnsUnmodifiableList() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        ReferenceSiteBindings bindings = model.referenceSiteBindings();

        assertThrows(UnsupportedOperationException.class,
                () -> bindings.all().add(null),
                "all() must return an unmodifiable List");
        assertThrows(UnsupportedOperationException.class,
                () -> bindings.all().clear(),
                "all() must return an unmodifiable List");
    }

    @Test
    void referenceSiteBindingsRejectsDuplicateSiteIds() {
        ReferenceSite site = new ReferenceSite(
                new AstNodeId("dup-1"),
                new io.kcg.sir.source.SourceSpan(
                        io.kcg.sir.source.SourceId.of("test"),
                        new io.kcg.sir.source.SourcePosition(0, 1, 1),
                        new io.kcg.sir.source.SourcePosition(1, 1, 2)),
                "x",
                ReferenceRole.NAMED_TYPE);
        ReferenceSiteBinding b1 = new ReferenceSiteBinding(site,
                new SymbolId("sir://test/sym-1"));
        ReferenceSiteBinding b2 = new ReferenceSiteBinding(site,
                new SymbolId("sir://test/sym-2"));

        assertThrows(IllegalStateException.class,
                () -> ReferenceSiteBindings.of(List.of(b1, b2)),
                "ReferenceSiteBindings.of must reject duplicate site IDs immediately");
    }

    @Test
    void referenceSiteBindingsEmptyIsSingletonAndImmutable() {
        ReferenceSiteBindings empty = ReferenceSiteBindings.empty();
        assertEquals(0, empty.size(), "empty must have size 0");
        assertTrue(empty.all().isEmpty(), "empty must have empty all()");
        assertTrue(empty.bindingFor(new AstNodeId("any")).isEmpty(),
                "empty.bindingFor must return empty Optional");
        assertThrows(UnsupportedOperationException.class,
                () -> empty.all().add(null),
                "empty.all() must be unmodifiable");

        ReferenceSiteBindings alsoEmpty = ReferenceSiteBindings.of(List.of());
        assertEquals(empty, alsoEmpty, "of(empty) must equal empty()");
    }

    @Test
    void referenceSiteBindingsIgnoresInputOrder() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        ReferenceSiteBindings original = model.referenceSiteBindings();

        List<ReferenceSiteBinding> shuffled = new ArrayList<>(original.all());
        java.util.Collections.shuffle(shuffled, new java.util.Random(42L));
        ReferenceSiteBindings reshuffled = ReferenceSiteBindings.of(shuffled);

        assertEquals(original, reshuffled,
                "ReferenceSiteBindings constructed from shuffled input must equal the original "
                        + "(canonical order is determined internally, not by input order)");
        assertEquals(original.all(), reshuffled.all(),
                "Iteration order must be canonical regardless of input order");
    }

    @Test
    void repeatedAnalysisProducesEqualBindings() {
        SemanticAnalysis first = analyzeContract();
        SemanticAnalysis second = analyzeContract();

        ReferenceSiteBindings a = first.model().orElseThrow().referenceSiteBindings();
        ReferenceSiteBindings b = second.model().orElseThrow().referenceSiteBindings();

        assertEquals(a, b, "Repeated analysis must produce equal ReferenceSiteBindings");
        assertEquals(a.all(), b.all(),
                "Repeated analysis must produce identical binding iteration order");
        assertEquals(first.model().orElseThrow().referenceBindings(),
                second.model().orElseThrow().referenceBindings(),
                "Repeated analysis must produce equal legacy referenceBindings");
    }

    @Test
    void turkishLocaleProducesEqualBindings() {
        SemanticAnalysis before = analyzeContract();
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            SemanticAnalysis after = analyzeContract();
            assertEquals(before.model().orElseThrow().referenceSiteBindings(),
                    after.model().orElseThrow().referenceSiteBindings(),
                    "ReferenceSiteBindings must be identical under Turkish locale");
            assertEquals(before.diagnostics(), after.diagnostics(),
                    "Diagnostics must be identical under Turkish locale");
            assertEquals(before.model().orElseThrow().declarations(),
                    after.model().orElseThrow().declarations(),
                    "Normalized declarations must be identical under Turkish locale");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void legalSuccessNeverProducesSirUnknownInReferenceSiteBindings() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        for (ReferenceSiteBinding b : model.referenceSiteBindings().all()) {
            assertNotEquals("sir://unknown", b.targetSymbol().value(),
                    "Legal Success must not produce sir://unknown in ReferenceSiteBindings (site "
                            + b.site().id().value() + ", role " + b.site().role() + ")");
        }
    }

    @Test
    void legalSuccessNeverProducesSirUnknownInNormalizedDeclarations() {
        NormalizedSemanticModel model = analyzeContract().model().orElseThrow();
        StringBuilder violations = new StringBuilder();
        for (io.kcg.sir.semantic.model.NormalizedDeclaration d : model.declarations()) {
            collectSirUnknownViolations(d, violations);
        }
        assertTrue(violations.length() == 0,
                "Legal Success must not produce sir://unknown anywhere in normalized declarations: "
                        + violations);
    }

    private void collectSirUnknownViolations(
            io.kcg.sir.semantic.model.NormalizedDeclaration d, StringBuilder out) {
        if (d instanceof io.kcg.sir.semantic.model.NormalizedCapability c) {
            c.fails().forEach(id -> {
                if ("sir://unknown".equals(id.value())) {
                    out.append("fails; ");
                }
            });
            for (io.kcg.sir.semantic.model.NormalizedStep s : c.workflow().steps()) {
                collectStepSirUnknown(s, out);
            }
        } else if (d instanceof io.kcg.sir.semantic.model.NormalizedEntity e) {
            e.fields().forEach(f -> {
                if ("sir://unknown".equals(f.id().value())) out.append("entity-field; ");
            });
        }
    }

    private void collectStepSirUnknown(io.kcg.sir.semantic.model.NormalizedStep s, StringBuilder out) {
        switch (s) {
            case io.kcg.sir.semantic.model.NormalizedStep.ValidateStep v -> {
                if ("sir://unknown".equals(v.errorSymbol().value())) out.append("validate-error; ");
            }
            case io.kcg.sir.semantic.model.NormalizedStep.LoadStep l -> {
                if ("sir://unknown".equals(l.entitySymbol().value())) out.append("load-entity; ");
                if ("sir://unknown".equals(l.errorSymbol().value())) out.append("load-error; ");
                if ("sir://unknown".equals(l.resultVariable().value())) out.append("load-result; ");
            }
            case io.kcg.sir.semantic.model.NormalizedStep.FindStep f -> {
                if ("sir://unknown".equals(f.entitySymbol().value())) out.append("find-entity; ");
                if ("sir://unknown".equals(f.resultVariable().value())) out.append("find-result; ");
                if ("sir://unknown".equals(f.itemVariable().value())) out.append("find-item; ");
            }
            case io.kcg.sir.semantic.model.NormalizedStep.CreateStep c -> {
                if ("sir://unknown".equals(c.entitySymbol().value())) out.append("create-entity; ");
                if ("sir://unknown".equals(c.resultVariable().value())) out.append("create-result; ");
                c.bindings().forEach(b -> {
                    if ("sir://unknown".equals(b.fieldSymbol().value())) out.append("create-binding; ");
                });
            }
            case io.kcg.sir.semantic.model.NormalizedStep.UpdateStep u -> {
                if ("sir://unknown".equals(u.targetVariable().value())) out.append("update-target; ");
                u.bindings().forEach(b -> {
                    if ("sir://unknown".equals(b.fieldSymbol().value())) out.append("update-binding; ");
                });
            }
            case io.kcg.sir.semantic.model.NormalizedStep.PersistStep p -> {
                if ("sir://unknown".equals(p.targetVariable().value())) out.append("persist-target; ");
            }
            default -> { }
        }
    }

    @Test
    void undefinedReferenceProducesOnlyResolveStageError() {
        String source = TestSources.sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field name: String;
                }
                input LookupInput {
                  field id: Int64;
                }
                capability Lookup {
                  input LookupInput;
                  output Ref<User>;
                  requires readonly;
                  expose query;
                  workflow {
                    find Missing where true as results;
                    return results;
                  }
                }
                """);

        SemanticAnalysis result = assertDoesNotThrow(() -> TestSources.analyze(source));
        assertFalse(result.isSuccess(), "Source with undefined reference must fail");
        assertTrue(result.model().isEmpty(),
                "Failure must not produce a NormalizedSemanticModel");

        long symbolErrors = result.diagnostics().stream()
                .filter(d -> d.code().value().equals("SIR-SYMBOL-002") && d.isError())
                .count();
        assertEquals(1, symbolErrors,
                "Undefined reference must produce exactly one SIR-SYMBOL-002 from Resolve; "
                        + "later passes must not add a duplicate. Diagnostics: "
                        + result.diagnostics());
    }

    @Test
    void wrongRoleKindProducesOnlyResolveStageError() {
        String source = TestSources.sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field name: String;
                }
                input CreateInput {
                  field name: String;
                }
                capability Create {
                  input CreateInput;
                  output Ref<User>;
                  fails User;
                  requires atomic;
                  expose command;
                  workflow {
                    create User as u { name: input.name; }
                    persist u;
                    return u;
                  }
                }
                """);

        SemanticAnalysis result = assertDoesNotThrow(() -> TestSources.analyze(source));
        assertFalse(result.isSuccess(), "Source with role/Kind mismatch must fail");
        assertTrue(result.model().isEmpty(),
                "Failure must not produce a NormalizedSemanticModel");

        long typeErrors = result.diagnostics().stream()
                .filter(d -> d.code().value().equals("SIR-TYPE-001") && d.isError())
                .count();
        assertEquals(1, typeErrors,
                "Role/Kind mismatch (fails pointing to Entity) must produce exactly one "
                        + "SIR-TYPE-001 from Resolve; later passes must not add a duplicate. "
                        + "Diagnostics: " + result.diagnostics());
    }

    @Test
    void undefinedLoadErrorProducesOnlyResolveStageError() {
        String source = TestSources.sir("""
                entity User persistent {
                  identity id: Int64 generated auto;
                  field name: String;
                }
                input LookupInput {
                  field id: Int64;
                }
                capability Lookup {
                  input LookupInput;
                  output Ref<User>;
                  requires readonly;
                  expose query;
                  workflow {
                    load User by input.id as u else UndeclaredError;
                    return u;
                  }
                }
                """);

        SemanticAnalysis result = assertDoesNotThrow(() -> TestSources.analyze(source));
        assertFalse(result.isSuccess());
        assertTrue(result.model().isEmpty());

        long symbolErrors = result.diagnostics().stream()
                .filter(d -> d.code().value().equals("SIR-SYMBOL-002") && d.isError())
                .count();
        assertEquals(1, symbolErrors,
                "Undeclared error must produce exactly one SIR-SYMBOL-002 from Resolve; "
                        + "later passes must not add a duplicate. Diagnostics: "
                        + result.diagnostics());
    }

    @Test
    void allReferenceSiteAstNodeIdsMapBackToOriginalAst() {
        var document = TestSources.parse(contractSource());
        SemanticAnalysis analysis = new io.kcg.sir.semantic.api.SirSemanticAnalyzer().analyze(document);
        assertTrue(analysis.isSuccess(), () -> "Expected success: " + analysis.diagnostics());

        Map<AstNodeId, String> astRefTexts = new LinkedHashMap<>();
        collectAstNameRefs(document.software().declarations(), astRefTexts);

        ReferenceSiteBindings bindings = analysis.model().orElseThrow().referenceSiteBindings();
        for (ReferenceSiteBinding b : bindings.all()) {
            AstNodeId siteId = b.site().id();
            String astText = astRefTexts.get(siteId);
            assertTrue(astText != null,
                    "Every ReferenceSite AstNodeId must correspond to an AstNameRef in the original AST: "
                            + siteId.value());
            assertEquals(astText, b.site().text(),
                    "ReferenceSite.text must match the original AstNameRef.text for site "
                            + siteId.value());
        }
    }

    private void collectAstNameRefs(
            List<? extends io.kcg.sir.ast.AstDeclaration> declarations,
            Map<AstNodeId, String> sink) {
        for (io.kcg.sir.ast.AstDeclaration decl : declarations) {
            switch (decl) {
                case io.kcg.sir.ast.AstEnumDecl e -> { /* no AstNameRef references */ }
                case io.kcg.sir.ast.AstEntityDecl e -> {
                    collectTypeRef(e.identity().type(), sink);
                    for (io.kcg.sir.ast.AstField f : e.fields()) {
                        collectTypeRef(f.type(), sink);
                    }
                }
                case io.kcg.sir.ast.AstInputDecl e -> {
                    e.patchSourceEntity().ifPresent(source -> sink.put(source.id(), source.text()));
                    for (io.kcg.sir.ast.AstField f : e.fields()) {
                        collectTypeRef(f.type(), sink);
                    }
                }
                case io.kcg.sir.ast.AstViewDecl e -> {
                    sink.put(e.sourceEntity().id(), e.sourceEntity().text());
                    for (io.kcg.sir.ast.AstViewField f : e.fields()) {
                        sink.put(f.name().id(), f.name().text());
                        collectTypeRef(f.type(), sink);
                    }
                }
                case io.kcg.sir.ast.AstErrorDecl e -> { /* no AstNameRef references */ }
                case AstCapabilityDecl e -> {
                    e.actor().ifPresent(c -> collectTypeRef(c.type(), sink));
                    e.input().ifPresent(c -> collectTypeRef(c.type(), sink));
                    collectTypeRef(e.output().type(), sink);
                    for (var f : e.failures()) sink.put(f.error().id(), f.error().text());
                    for (var step : e.workflow().steps()) collectStepRefs(step, sink);
                }
            }
        }
    }

    private void collectTypeRef(io.kcg.sir.ast.AstTypeRef ref, Map<AstNodeId, String> sink) {
        switch (ref) {
            case io.kcg.sir.ast.AstNamedTypeRef n -> sink.put(n.name().id(), n.name().text());
            case AstRefTypeRef r -> sink.put(r.targetName().id(), r.targetName().text());
            case io.kcg.sir.ast.AstListTypeRef l -> collectTypeRef(l.elementType(), sink);
            case io.kcg.sir.ast.AstOptionalTypeRef o -> collectTypeRef(o.elementType(), sink);
            case io.kcg.sir.ast.AstPageTypeRef p -> collectTypeRef(p.elementType(), sink);
            default -> { }
        }
    }

    private void collectStepRefs(io.kcg.sir.ast.AstStep step, Map<AstNodeId, String> sink) {
        switch (step) {
            case AstValidateStep s -> {
                sink.put(s.error().id(), s.error().text());
                collectExpressionRefs(s.condition(), sink);
            }
            case AstLoadStep s -> {
                sink.put(s.entity().id(), s.entity().text());
                sink.put(s.error().id(), s.error().text());
                collectExpressionRefs(s.idExpression(), sink);
            }
            case AstFindStep s -> {
                sink.put(s.entity().id(), s.entity().text());
                collectExpressionRefs(s.predicate(), sink);
                s.order().ifPresent(order -> order.keys().forEach(key -> sink.put(key.field().id(), key.field().text())));
                s.page().ifPresent(page -> {
                    collectExpressionRefs(page.page(), sink);
                    collectExpressionRefs(page.size(), sink);
                    sink.put(page.error().id(), page.error().text());
                });
            }
            case AstCreateStep s -> {
                sink.put(s.entity().id(), s.entity().text());
                for (var b : s.bindings()) {
                    sink.put(b.fieldName().id(), b.fieldName().text());
                    collectExpressionRefs(b.value(), sink);
                }
            }
            case AstUpdateStep s -> {
                sink.put(s.target().id(), s.target().text());
                for (var b : s.bindings()) {
                    sink.put(b.fieldName().id(), b.fieldName().text());
                    collectExpressionRefs(b.value(), sink);
                }
            }
            case AstPersistStep s -> {
                sink.put(s.target().id(), s.target().text());
                s.failure().ifPresent(failure -> sink.put(failure.id(), failure.text()));
            }
            case io.kcg.sir.ast.AstReturnStep s -> collectExpressionRefs(s.value(), sink);
            default -> { }
        }
    }

    private void collectExpressionRefs(io.kcg.sir.ast.AstExpression expr, Map<AstNodeId, String> sink) {
        if (expr == null) return;
        switch (expr) {
            case AstNameExpression n -> sink.put(n.name().id(), n.name().text());
            case AstMemberExpression m -> {
                sink.put(m.member().id(), m.member().text());
                collectExpressionRefs(m.receiver(), sink);
            }
            case io.kcg.sir.ast.AstUnaryExpression u -> collectExpressionRefs(u.operand(), sink);
            case io.kcg.sir.ast.AstBinaryExpression b -> {
                collectExpressionRefs(b.left(), sink);
                collectExpressionRefs(b.right(), sink);
            }
            case io.kcg.sir.ast.AstGroupedExpression g -> collectExpressionRefs(g.inner(), sink);
            case io.kcg.sir.ast.AstPresentExpression p -> {
                sink.put(p.id(), "present");
                collectExpressionRefs(p.target(), sink);
            }
            default -> { }
        }
    }
}