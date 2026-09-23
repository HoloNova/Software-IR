package io.kcg.sir.change.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.model.NormalizedView;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parity between the change layer's semantic projection and the language surface the
 * semantic layer actually produces (Q14).
 *
 * <p>{@link SemanticProjection} decides what counts as "this declaration changed", so every
 * fact the language can express needs a place in it. Two failure modes are pinned here:
 *
 * <ul>
 *   <li><b>Crashes</b>: the projection must not throw for any construct the language accepts.
 *       Q10's {@code .present}, Q11's {@code any(...)} and Q9's {@code Page<...>} all used to
 *       fall through to an {@code IllegalStateException}.</li>
 *   <li><b>Silent omissions</b>: a candidate that changes only this fact must project
 *       differently, otherwise the planner reports {@code NoChanges} for a real change.</li>
 * </ul>
 *
 * <p>The final test is a surface gate: every permitted variant of {@link NormalizedExpression},
 * {@link SirType} and {@link NormalizedStep} must be named by the projection source, so adding
 * a construct to the language fails here first instead of silently becoming "unchanged".
 */
class SemanticProjectionParityTest {

    // ------------------------------------------------------------------
    // 崩溃面：语言的构造必须能被投影
    // ------------------------------------------------------------------

    @Test
    void presentExistsAndPageConstructsAreProjected() throws Exception {
        NormalizedSemanticModel model = compile(template());

        NormalizedCapability update = capability(model, "UpdateCourse");
        // .present (Q10) inside a validate step
        assertNotNull(SemanticProjection.ofWorkflowOnly(update.workflow()),
                "a workflow containing .present must project");

        NormalizedCapability search = capability(model, "SearchCourses");
        SemanticProjection.WorkflowP workflow = SemanticProjection.ofWorkflowOnly(search.workflow());
        // any(...) (Q11) inside a find predicate
        assertNotNull(workflow, "a workflow containing any(...) must project");

        NormalizedStep.FindStep find = findStep(search);
        assertTrue(find.predicate() instanceof NormalizedExpression.ExistsExpression,
                "the search predicate is the exists expression this test is about");

        // Page<...> (Q9) as a capability output type
        SemanticProjection.ofFullDeclaration(search);
        SemanticProjection.ofCapabilityContract(search);
        assertTrue(search.outputType() instanceof io.kcg.sir.semantic.type.PageType,
                "the search output is the page type this test is about");
    }

    // ------------------------------------------------------------------
    // 灵敏度面：只改一个事实，投影必须不同
    // ------------------------------------------------------------------

    @Test
    void changingThePresentTargetChangesTheProjection() throws Exception {
        assertProjectsDifferently(
                replace("validate input.name.present else EmptyChange;",
                        "validate input.capacity.present else EmptyChange;"),
                "the .present target is part of the projection");
    }

    @Test
    void changingTheExistsConditionChangesTheProjection() throws Exception {
        assertProjectsDifferently(
                replace("status == EnrollmentStatus.ACTIVE", "status == EnrollmentStatus.CANCELLED"),
                "the exists condition is part of the projection");
    }

    @Test
    void changingTheOrderKeyChangesTheProjection() throws Exception {
        assertProjectsDifferently(
                replace("          order by code ascending", "          order by name descending"),
                "the find order keys are part of the projection");
    }

    @Test
    void changingThePageClauseChangesTheProjection() throws Exception {
        assertProjectsDifferently(
                replace("Page input.page, input.size else InvalidPage", "Page input.page, input.sizeAlt else InvalidPage"),
                "the find page clause is part of the projection");
    }

    @Test
    void changingTheVersionedFlagChangesTheProjection() throws Exception {
        String source = template();
        String marked = "field version: Int64 versioned;";
        assertTrue(source.contains(marked), "the template must declare the versioned field");
        assertEquals(2, source.split(java.util.regex.Pattern.quote(marked), -1).length - 1,
                "the template declares the versioned field twice (Course and Student); the variant must "
                        + "target the one without a patch payload");
        int courseVersion = source.indexOf(marked);
        String changed = source.substring(0, courseVersion + marked.length())
                + source.substring(courseVersion + marked.length()).replaceFirst(
                        java.util.regex.Pattern.quote(marked), "field version: Int64;");
        assertNotEquals(source, changed);
        assertProjectsDifferently(changed, "the versioned flag is part of the entity field projection");
    }

    @Test
    void thePatchSpecificationIsProjected() throws Exception {
        // The language couples a patch payload with a version field and with the updated entity
        // (SIR-VALID-003/004: "patch 载荷的实体必须声明 version 字段", "versioned 实体的 update 必须使用
        // 该实体的 patch 载荷作为 input"), so an isolated plain-versus-patch variant is not
        // expressible. The projection is pinned positively instead: it must name both the kind and
        // the patched entity, otherwise a change of patch target would be invisible.
        NormalizedSemanticModel model = compile(template());
        String projection = SemanticProjection.ofFullDeclaration(input(model, "UpdateCourseInput")).toString();

        assertTrue(projection.contains("PATCH"), "the patch kind must be recorded: " + projection);
        assertTrue(projection.contains("sir://ProjectionParity/entity/Course"),
                "the patched entity must be recorded: " + projection);
    }

    @Test
    void changingTheNestedRelationTargetChangesTheProjection() throws Exception {
        assertProjectsDifferently(
                replace("field student: StudentSummary;", "field student: StudentSummaryAlt;"),
                "the nested relation target view is part of the view field projection");
    }

    @Test
    void changingThePersistFailureChangesTheProjection() throws Exception {
        assertProjectsDifferently(
                replace("persist course else StaleVersion;", "persist course else EmptyChange;"),
                "the conditional persist failure is part of the step projection");
    }

    @Test
    void theRelationProjectionCarriesCardinalityAndTarget() throws Exception {
        NormalizedSemanticModel model = compile(template());
        NormalizedView item = view(model, "CourseEnrollmentItem");
        String projection = SemanticProjection.ofFullDeclaration(item).toString();

        assertTrue(projection.contains("EnrollmentSummary"),
                "the to-many nested relation must record its target view: " + projection);
        assertTrue(projection.contains("MANY"),
                "the to-many nested relation must record its cardinality: " + projection);
    }

    // ------------------------------------------------------------------
    // 语言面闸门：sealed 变体与投影分支必须一一对应
    // ------------------------------------------------------------------

    @Test
    void everySealedVariantOfTheLanguageSurfaceIsNamedByTheProjection() throws Exception {
        String source = withoutImports(Files.readString(mainSource().resolve("SemanticProjection.java")));

        for (Class<?> sealed : List.of(NormalizedExpression.class, SirType.class, NormalizedStep.class)) {
            Class<?>[] variants = sealed.getPermittedSubclasses();
            assertNotNull(variants, sealed + " must be sealed");
            assertTrue(variants.length > 0, sealed + " must permit variants");
            for (Class<?> variant : variants) {
                String simpleName = variant.getSimpleName();
                assertTrue(source.contains(simpleName),
                        "SemanticProjection must handle " + sealed.getSimpleName() + " variant " + simpleName
                                + "; a new language construct must be projected (or the projection is lying about changes)");
            }
        }
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    /** The default source; every test varies exactly one line of it. */
    private static String template() {
        return """
                sir 0.1

                software ProjectionParity {
                  metadata {
                    displayName "Projection Parity";
                    namespace "com.example.projectionparity";
                  }

                  target {
                    language java 21;
                    framework spring_boot;
                    persistence mybatis_plus;
                    database mysql;
                    build maven;
                    interface rest;
                  }

                  declarations {
                    enum EnrollmentStatus {
                      ACTIVE,
                      CANCELLED
                    }

                    entity Course persistent {
                      identity id: Int64 generated auto;
                      field code: String where notBlank, length(1, 32);
                      field name: String where notBlank, length(1, 100);
                      field capacity: Int32 where min(1);
                      field version: Int64 versioned;
                    }

                    entity Student persistent {
                      identity id: Int64 generated auto;
                      field studentNo: String where notBlank, length(1, 16);
                      field name: String where notBlank, length(1, 100);
                      /* Student has no patch input, so this versioned field isolates the versioned fact
                         from the language rule that couples a patch payload with a version field. */
                      field version: Int64 versioned;
                    }

                    entity Enrollment persistent {
                      identity id: Int64 generated auto;
                      field course: Ref<Course>;
                      field student: Ref<Student>;
                      field status: EnrollmentStatus;
                    }

                    view CourseDetail from Course {
                      field id: Int64;
                      field code: String;
                      field name: String;
                      field capacity: Int32;
                      field version: Int64;
                    }

                    view StudentSummary from Student {
                      field id: Int64;
                      field studentNo: String;
                      field name: String;
                    }

                    view StudentSummaryAlt from Student {
                      field id: Int64;
                      field studentNo: String;
                    }

                    view EnrollmentSummary from Enrollment {
                      field id: Int64;
                      field student: StudentSummary;
                      field status: EnrollmentStatus;
                    }

                    view CourseEnrollmentItem from Course {
                      field id: Int64;
                      field code: String;
                      field name: String;
                      field enrollments: List<EnrollmentSummary>;
                    }

                    input UpdateCourseInput patch of Course {
                      field id: Int64;
                      field name: String;
                      field capacity: Int32;
                    }

                    input SearchInput {
                      field page: Int32;
                      field size: Int32;
                      field sizeAlt: Int32;
                    }

                    error CourseNotFound;
                    error StaleVersion;
                    error InvalidPage;
                    error EmptyChange;

                    capability UpdateCourse {
                      input UpdateCourseInput;
                      output CourseDetail;
                      fails CourseNotFound;
                      fails EmptyChange;
                      fails StaleVersion;
                      requires atomic;
                      expose command;
                      workflow {
                        validate input.name.present else EmptyChange;
                        load Course by input.id as course else CourseNotFound;
                        update course {
                          name: input.name;
                          capacity: input.capacity;
                        }
                        persist course else StaleVersion;
                        return course;
                      }
                    }

                    capability SearchCourses {
                      input SearchInput;
                      output Page<CourseEnrollmentItem>;
                      fails InvalidPage;
                      requires readonly;
                      expose query;
                      workflow {
                        find Course
                          where any(Enrollment, course == item and status == EnrollmentStatus.ACTIVE)
                          order by code ascending
                          Page input.page, input.size else InvalidPage
                        as courses;
                        return courses;
                      }
                    }
                  }
                }
                """;
    }

    /**
     * Projects both variants of the same source and asserts the projection differs, so a change
     * that only touches the varying fact cannot be reported as {@code NoChanges}.
     */
    private void assertProjectsDifferently(String changed, String what) throws Exception {
        NormalizedSemanticModel before = compile(template());
        NormalizedSemanticModel after = compile(changed);
        assertNotEquals(projections(before), projections(after), what);
        assertEquals(projections(before), projections(compile(template())), "the projection must be deterministic");
    }

    /** Every declaration projection plus every capability workflow projection, in model order. */
    private static List<String> projections(NormalizedSemanticModel model) {
        return model.declarations().stream()
                .map(declaration -> SemanticProjection.ofFullDeclaration(declaration).toString())
                .toList();
    }

    private static String replace(String target, String replacement) {
        String source = template();
        assertTrue(source.contains(target), "the template must contain the varying fact: " + target);
        return source.replace(target, replacement);
    }

    private static NormalizedCapability capability(NormalizedSemanticModel model, String name) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof NormalizedCapability capability && capability.name().equals(name)) {
                return capability;
            }
        }

        throw new IllegalStateException("capability " + name + " not found");
    }

    private static NormalizedView view(NormalizedSemanticModel model, String name) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof NormalizedView view && view.name().equals(name)) {
                return view;
            }
        }

        throw new IllegalStateException("view " + name + " not found");
    }

    private static io.kcg.sir.semantic.model.NormalizedInput input(NormalizedSemanticModel model, String name) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof io.kcg.sir.semantic.model.NormalizedInput candidate
                    && candidate.name().equals(name)) {
                return candidate;
            }
        }

        throw new IllegalStateException("input " + name + " not found");
    }

    private static NormalizedStep.FindStep findStep(NormalizedCapability capability) {
        for (NormalizedStep step : capability.workflow().steps()) {
            if (step instanceof NormalizedStep.FindStep find) {
                return find;
            }
        }

        throw new IllegalStateException("no find step in " + capability.name());
    }

    /** Parses and analyzes one source through the public parser and semantic APIs. */
    private static NormalizedSemanticModel compile(String source) throws Exception {
        ParseResult parseResult = SirParser.create().parse(new SirSource(SourceId.of("projection-parity.sir"), source));
        assertTrue(parseResult.isSuccess(), () -> "the projection parity source must parse: " + parseResult.diagnostics());
        SemanticAnalysis analysis = new SirSemanticAnalyzer().analyze(parseResult.document().orElseThrow());
        assertFalse(analysis.hasErrors(), () -> "the projection parity source must analyze: " + analysis.diagnostics());
        return assertInstanceOf(SemanticAnalysis.Success.class, analysis).normalizedModel();
    }

    /** Import lines do not count as handling a variant, so the gate reads the body only. */
    private static String withoutImports(String source) {
        return source.lines()
                .filter(line -> !line.stripLeading().startsWith("import "))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Path mainSource() {
        Path mainSrc = Path.of("src/main/java");
        if (!Files.exists(mainSrc)) {
            mainSrc = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        }

        assertTrue(Files.exists(mainSrc), "sir-change main source root must exist: " + mainSrc.toAbsolutePath());
        return mainSrc.resolve("io/kcg/sir/change/internal");
    }
}
