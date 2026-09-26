package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.symbol.DeclarationIdentity;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Identity contract for declarations that carry an explicit {@code @id} (Q16).
 *
 * <p>The unit's whole point: a capability or entity member with a declared id must keep the same
 * identity — symbol, source node, scope symbols and reference bindings — when its name changes, and
 * must not take over an identity just because it reuses a name. Every identity a name-derived
 * symbol could produce stays untouched, so sources without {@code @id} behave exactly as before.
 */
class DeclaredIdentitySemanticsTest {

    private static final String DECLARED = TestSources.sir("""
            entity Course persistent {
              identity id: Int64 generated auto;
              field code: String where notBlank @id("course-code");
              field title: String where notBlank @id("course-title");
            }
            input GetCourseInput {
              field id: Int64;
            }
            error CourseNotFound;
            capability GetCourse @id("get-course") {
              input GetCourseInput;
              output Ref<Course>;
              fails CourseNotFound;
              requires readonly;
              expose query;
              workflow {
                load Course by input.id as course else CourseNotFound;
                return course;
              }
            }
            """);

    /** The same program with the capability and one member renamed; both keep their declared ids. */
    private static final String DECLARED_RENAMED = DECLARED
            .replace("GetCourse", "FindCourse")
            .replace("field title: String", "field name: String");

    private static final String DECLARED_RECREATED = DECLARED.replace("@id(\"get-course\")", "@id(\"get-course-2026\")");

    private static final String WITHOUT_IDS = DECLARED
            .replace(" @id(\"course-code\")", "")
            .replace(" @id(\"course-title\")", "")
            .replace(" @id(\"get-course\")", "");

    private static final SymbolId DECLARED_CAPABILITY = new SymbolId("sir://Test/declared/capability/get-course");
    private static final SymbolId DECLARED_FIELD = new SymbolId("sir://Test/declared/entity-field/course-code");
    private static final SymbolId LEGACY_CAPABILITY = new SymbolId("sir://Test/capability/GetCourse");
    private static final SymbolId LEGACY_FIELD = new SymbolId("sir://Test/entity/Course/field/code");

    @Test
    void declaredIdentityNeverLooksLikeANameDerivedOne() {
        NormalizedSemanticModel model = model(DECLARED);

        assertEquals(DECLARED_CAPABILITY, capability(model).id());
        assertEquals(DECLARED_FIELD, entity(model).fields().get(0).id());
        assertTrue(DeclarationIdentity.isDeclared(DECLARED_CAPABILITY));
        assertTrue(DeclarationIdentity.isDeclared(DECLARED_FIELD));
        assertFalse(DeclarationIdentity.isDeclared(LEGACY_CAPABILITY));
        assertFalse(DeclarationIdentity.isDeclared(LEGACY_FIELD));
        assertNotEquals(DECLARED_CAPABILITY, LEGACY_CAPABILITY);
        assertNotEquals(DECLARED_FIELD, LEGACY_FIELD);
    }

    @Test
    void renamingKeepsEveryIdentityAndBindingOfTheDeclaredCapability() {
        NormalizedSemanticModel base = model(DECLARED);
        NormalizedSemanticModel candidate = model(DECLARED_RENAMED);
        NormalizedCapability baseCapability = capability(base);
        NormalizedCapability candidateCapability = capability(candidate);

        assertEquals("FindCourse", candidateCapability.name());
        assertEquals(baseCapability.id(), candidateCapability.id());
        assertEquals(baseCapability.sourceNodeId(), candidateCapability.sourceNodeId());
        assertEquals(baseCapability.workflow().id(), candidateCapability.workflow().id());
        assertEquals(baseCapability.workflow().sourceNodeId(), candidateCapability.workflow().sourceNodeId());

        NormalizedStep baseStep = baseCapability.workflow().steps().getFirst();
        NormalizedStep candidateStep = candidateCapability.workflow().steps().getFirst();
        assertEquals(baseStep.sourceNodeId(), candidateStep.sourceNodeId());

        assertEquals(baseCapability.inputSymbol(), candidateCapability.inputSymbol());
        assertEquals(
                DECLARED_CAPABILITY.value() + "/var/input",
                baseCapability.inputSymbol().orElseThrow().value());

        AstNodeId stepNodeId = baseStep.sourceNodeId();
        assertEquals(base.referenceBindings().get(stepNodeId), candidate.referenceBindings().get(stepNodeId));
        assertTrue(
                base.referenceBindings().get(stepNodeId).value().startsWith(DECLARED_CAPABILITY.value() + "/step/"),
                base.referenceBindings().get(stepNodeId).value());
        assertTrue(base.referenceBindings().get(stepNodeId).value().endsWith("/var/course"));
    }

    @Test
    void renamingAMemberKeepsItsIdentityAndTheOtherMembersToo() {
        NormalizedSemanticModel base = model(DECLARED);
        NormalizedSemanticModel candidate = model(DECLARED_RENAMED);

        assertEquals("name", entity(candidate).fields().get(1).name());
        assertEquals(entity(base).fields().get(1).id(), entity(candidate).fields().get(1).id());
        assertEquals(entity(base).fields().get(0).id(), entity(candidate).fields().get(0).id());
        assertEquals(entity(base).fields().get(1).sourceNodeId(), entity(candidate).fields().get(1).sourceNodeId());
    }

    @Test
    void recreatingASameNamedDeclarationWithANewIdDoesNotReuseTheOldIdentity() {
        NormalizedSemanticModel base = model(DECLARED);
        NormalizedSemanticModel recreated = model(DECLARED_RECREATED);

        assertEquals(capability(base).name(), capability(recreated).name());
        assertNotEquals(capability(base).id(), capability(recreated).id());
        assertNotEquals(capability(base).sourceNodeId(), capability(recreated).sourceNodeId());
        assertEquals(new SymbolId("sir://Test/declared/capability/get-course-2026"), capability(recreated).id());
    }

    @Test
    void sourcesWithoutDeclaredIdsKeepTheirNameDerivedIdentities() {
        NormalizedSemanticModel model = model(WITHOUT_IDS);

        assertEquals(LEGACY_CAPABILITY, capability(model).id());
        assertEquals(LEGACY_FIELD, entity(model).fields().get(0).id());
        assertEquals(LEGACY_CAPABILITY.value() + "/var/input", capability(model).inputSymbol().orElseThrow().value());
        assertEquals(LEGACY_CAPABILITY, capability(model).workflow().id());
    }

    @Test
    void duplicateDeclaredIdsAreRejectedWithSpanAndCode() {
        SemanticAnalysis duplicates = TestSources.analyze(TestSources.sir("""
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String @id("shared");
                }
                error CourseNotFound;
                capability GetCourse @id("shared") {
                  output Unit;
                  fails CourseNotFound;
                  requires readonly;
                  expose query;
                  workflow {
                    return unit;
                  }
                }
                """));

        assertEquals(List.of("SIR-IDENTITY-002"), TestSources.errorCodes(duplicates));
        List<Diagnostic> errors = duplicates.diagnostics().stream().filter(Diagnostic::isError).toList();
        assertEquals(1, errors.size());
        assertEquals("test", errors.getFirst().primarySpan().source().value());
    }

    @Test
    void malformedDeclaredIdsAreRejectedWithSpanAndCode() {
        SemanticAnalysis malformed = TestSources.analyze(TestSources.sir("""
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String @id("has space");
                }
                """));

        assertEquals(List.of("SIR-IDENTITY-001"), TestSources.errorCodes(malformed));
        assertEquals("test", malformed.diagnostics().getFirst().primarySpan().source().value());
    }

    @Test
    void declaredIdentityIsDeterministicForIdenticalBytes() {
        NormalizedSemanticModel first = model(DECLARED);
        NormalizedSemanticModel second = model(DECLARED);

        assertEquals(capability(first).id(), capability(second).id());
        assertEquals(capability(first).inputSymbol(), capability(second).inputSymbol());
        assertEquals(entity(first).fields().get(0).id(), entity(second).fields().get(0).id());
        assertEquals(
                first.referenceBindings().keySet().stream().toList(),
                second.referenceBindings().keySet().stream().toList());
        assertEquals(first.referenceBindings(), second.referenceBindings());
    }

    private static NormalizedSemanticModel model(String source) {
        SemanticAnalysis analysis = TestSources.analyze(source);
        assertTrue(analysis.isSuccess(), () -> analysis.diagnostics().toString());
        return analysis.model().orElseThrow();
    }

    private static NormalizedCapability capability(NormalizedSemanticModel model) {
        return (NormalizedCapability) model.declarations().stream()
                .filter(NormalizedCapability.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private static NormalizedEntity entity(NormalizedSemanticModel model) {
        return (NormalizedEntity) model.declarations().stream()
                .filter(NormalizedEntity.class::isInstance)
                .findFirst()
                .orElseThrow();
    }
}
