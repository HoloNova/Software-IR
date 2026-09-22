package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.type.PrimitiveType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The write-side slice's semantic rules: the version marker, error statuses, the conditional
 * persist failure, patch payloads and presence.
 *
 * <p>Both sides are asserted. The legal source analyzes clean and produces the normalized shapes
 * the target needs; every illegal combination is rejected by its own phase with its own code,
 * and the expected count is asserted so a source that trips several rules cannot pass as one.
 *
 * <p>Counterexamples are built from whole named fragments rather than by editing one long source
 * literal, so an edit that fails to apply fails the test instead of silently testing the legal
 * source again.
 */
class WriteSliceSemanticsTest {
    private static final String FIXTURE = "valid/course-admin.sir";

    private static final String ENTITY = """
            entity Course persistent {
              identity id: Int64 generated auto;
              field code: String where notBlank, length(1, 32);
              field name: String where notBlank, length(1, 100);
              field description: Optional<String> where length(0, 500);
              field capacity: Int32 where min(1);
              field version: Int64 versioned;
            }
            """;

    private static final String VIEW = """
            view CourseDetail from Course {
              field id: Int64;
              field code: String;
              field name: String;
              field description: Optional<String>;
              field capacity: Int32;
              field version: Int64;
            }
            """;

    private static final String GET_INPUT = """
            input GetCourseInput {
              field id: Int64;
            }
            """;

    private static final String CREATE_INPUT = """
            input CreateCourseInput {
              field code: String where notBlank, length(1, 32);
              field name: String where notBlank, length(1, 100);
              field description: Optional<String> where length(0, 500);
              field capacity: Int32 where min(1);
            }
            """;

    private static final String PATCH_INPUT = """
            input UpdateCourseInput patch of Course {
              field id: Int64;
              field name: String;
              field description: Optional<String>;
              field capacity: Int32;
            }
            """;

    private static final String ERRORS = """
            error CourseNotFound http 404;
            error EmptyChange;
            error StaleVersion http 409;
            """;

    private static final String GET_CAPABILITY = """
            capability GetCourse {
              input GetCourseInput;
              output CourseDetail;
              fails CourseNotFound;
              requires readonly;
              expose query;
              workflow {
                load Course by input.id as course else CourseNotFound;
                return course;
              }
            }
            """;

    private static final String CREATE_CAPABILITY = """
            capability CreateCourse {
              input CreateCourseInput;
              output CourseDetail;
              requires atomic;
              expose command;
              workflow {
                create Course as created {
                  code: input.code;
                  name: input.name;
                  description: input.description;
                  capacity: input.capacity;
                }
                persist created;
                return created;
              }
            }
            """;

    private static final String UPDATE_CAPABILITY = """
            capability UpdateCourse {
              input UpdateCourseInput;
              output CourseDetail;
              fails CourseNotFound;
              fails EmptyChange;
              fails StaleVersion;
              requires atomic;
              expose command;
              workflow {
                validate input.name.present or input.description.present or input.capacity.present else EmptyChange;
                load Course by input.id as course else CourseNotFound;
                update course {
                  name: input.name;
                  description: input.description;
                  capacity: input.capacity;
                }
                persist course else StaleVersion;
                return course;
              }
            }
            """;

    /** The version marker line, as it appears inside the entity block. */
    private static final String VERSION_MARKER = "field version: Int64 versioned;";

    private static String declarations() {
        return String.join("\n",
                ENTITY, VIEW, GET_INPUT, CREATE_INPUT, PATCH_INPUT, ERRORS,
                GET_CAPABILITY, CREATE_CAPABILITY, UPDATE_CAPABILITY);
    }

    private static SemanticAnalysis analyze(String declarations) {
        return TestSources.analyze(TestSources.sir(declarations));
    }

    private static SemanticAnalysis analyzeBase() {
        return analyze(declarations());
    }

    /**
     * Replaces one declaration block by an edited copy of itself.
     *
     * <p>Every counterexample is expressed as a whole-block substitution, so an edit that does not
     * apply fails loudly instead of silently re-testing the legal source.
     */
    private static String replaceBlock(String fragment, String replacement) {
        String source = declarations();
        assertTrue(source.contains(fragment), () -> "block must exist in the source: " + fragment);
        String edited = source.replace(fragment, replacement);
        assertFalse(edited.equals(source), () -> "counterexample must change the source: " + fragment);
        return edited;
    }

    /** Rewrites one line of a block, keeping that block's indentation. */
    private static String replaceLine(String block, String line, String replacement) {
        return lineAt(block, line) < 0
                ? fail("line must exist in block: " + line)
                : String.join("\n", lines(block)).replaceFirst(java.util.regex.Pattern.quote(indentedLine(block, line)),
                        java.util.regex.Matcher.quoteReplacement(indentOf(block, line) + replacement));
    }

    /** Inserts a line directly after another, with the same indentation. */
    private static String insertAfter(String block, String line, String extra) {
        int index = lineAt(block, line);
        if (index < 0) {
            return fail("line must exist in block: " + line);
        }

        List<String> lines = lines(block);
        lines.add(index + 1, indentOf(block, line) + extra);
        return String.join("\n", lines);
    }

    /** Removes one line of a block. */
    private static String removeLine(String block, String line) {
        int index = lineAt(block, line);
        if (index < 0) {
            return fail("line must exist in block: " + line);
        }

        List<String> lines = lines(block);
        lines.remove(index);
        return String.join("\n", lines);
    }

    /** Removes the lines from one line through another, inclusive. */
    private static String removeBlock(String block, String firstLine, String lastLine) {
        int from = lineAt(block, firstLine);
        int to = lineAt(block, lastLine);
        if (from < 0 || to < from) {
            return fail("block must exist: " + firstLine + " .. " + lastLine);
        }

        List<String> lines = lines(block);
        lines.subList(from, to + 1).clear();
        return String.join("\n", lines);
    }

    private static List<String> lines(String block) {
        return new ArrayList<>(List.of(block.split("\n", -1)));
    }

    private static int lineAt(String block, String line) {
        List<String> lines = lines(block);
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).strip().equals(line)) {
                return index;
            }
        }

        return -1;
    }

    private static String indentOf(String block, String line) {
        String actual = lines(block).stream().filter(value -> value.strip().equals(line)).findFirst().orElseThrow();
        return actual.substring(0, actual.indexOf(line));
    }

    private static String indentedLine(String block, String line) {
        return indentOf(block, line) + line;
    }

    private static String fail(String message) {
        throw new AssertionError(message);
    }

    @Test
    void theWriteSliceSourceAnalyzesWithoutDiagnostics() {
        SemanticAnalysis result = analyzeBase();

        assertTrue(result.isSuccess(), () -> "expected a clean analysis: " + result.diagnostics());
        assertEquals(List.of(), TestSources.errorCodes(result));
    }

    @Test
    void theShippedFixtureAnalyzesWithoutDiagnostics() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);

        assertTrue(result.isSuccess(), () -> "fixture must analyze: " + result.diagnostics());
    }

    @Test
    void theEntityCarriesItsResolvedVersionField() {
        NormalizedEntity course = entity(analyzeBase().model().orElseThrow(), "Course");

        assertTrue(course.versionField().isPresent(), "the version field is resolved");
        NormalizedField version = course.fields().stream()
                .filter(NormalizedField::versioned)
                .findFirst()
                .orElseThrow();
        assertEquals(version.id(), course.versionField().orElseThrow());
        assertEquals(1, course.fields().stream().filter(NormalizedField::versioned).count());
    }

    @Test
    void thePatchPayloadCarriesItsSourceEntityAndBoundMembers() {
        NormalizedSemanticModel model = analyzeBase().model().orElseThrow();
        NormalizedInput patch = input(model, "UpdateCourseInput");

        assertEquals(NormalizedInput.Kind.PATCH, patch.kind());
        assertEquals(entity(model, "Course").id(), patch.patchSourceEntity().orElseThrow());
        assertTrue(patch.fields().stream().allMatch(field -> field.patchSourceField().isPresent()),
                "every patch field keeps the entity member it applies to");
    }

    @Test
    void aPlainInputStaysPlain() {
        NormalizedSemanticModel model = analyzeBase().model().orElseThrow();
        NormalizedInput plain = input(model, "CreateCourseInput");

        assertEquals(NormalizedInput.Kind.PLAIN, plain.kind());
        assertTrue(plain.patchSourceEntity().isEmpty());
        assertTrue(plain.fields().stream().allMatch(field -> field.patchSourceField().isEmpty()));
    }

    @Test
    void errorStatusesAreResolvedOnceWithTheDefaultKept() {
        NormalizedSemanticModel model = analyzeBase().model().orElseThrow();

        assertEquals(404, error(model, "CourseNotFound").httpStatus());
        assertEquals(409, error(model, "StaleVersion").httpStatus());
        assertEquals(400, error(model, "EmptyChange").httpStatus());
    }

    @Test
    void thePersistFailureAndThePresenceGuardAreNormalized() {
        NormalizedSemanticModel model = analyzeBase().model().orElseThrow();
        NormalizedCapability update = capability(model, "UpdateCourse");
        NormalizedStep.PersistStep persist = update.workflow().steps().stream()
                .filter(NormalizedStep.PersistStep.class::isInstance)
                .map(NormalizedStep.PersistStep.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(error(model, "StaleVersion").id(), persist.failure().orElseThrow());

        NormalizedStep.ValidateStep guard = update.workflow().steps().stream()
                .filter(NormalizedStep.ValidateStep.class::isInstance)
                .map(NormalizedStep.ValidateStep.class::cast)
                .findFirst()
                .orElseThrow();
        List<NormalizedExpression.PresentExpression> presences = collectPresence(guard.condition());
        assertEquals(3, presences.size(), () -> "the guard reads all three change fields: " + presences);
        for (NormalizedExpression.PresentExpression presence : presences) {
            assertEquals(PrimitiveType.BOOLEAN, presence.type(), "presence is a boolean test");
        }
    }

    @Test
    void aSecondVersionFieldIsRejected() {
        assertRejectedWith(
                replaceBlock(ENTITY, insertAfter(ENTITY, VERSION_MARKER, "field other: Int64 versioned;")),
                "SIR-VALID-002", 1);
    }

    @Test
    void aNonIntegerVersionFieldIsRejected() {
        // Two errors follow from one edit: the marker's own type rule, and the projection that
        // then declares a type the entity no longer has.
        assertRejectedWith(
                replaceBlock(ENTITY, replaceLine(ENTITY, VERSION_MARKER, "field version: String versioned;")),
                "SIR-TYPE-001", 2);
    }

    @Test
    void aConstrainedVersionFieldIsRejected() {
        assertRejectedWith(
                replaceBlock(ENTITY, replaceLine(ENTITY, VERSION_MARKER, "field version: Int64 where min(0) versioned;")),
                "SIR-VALID-002", 1);
    }

    @Test
    void theVersionMarkerIsRejectedOnProjectionsAndPayloadFields() {
        assertRejectedWith(
                replaceBlock(VIEW, replaceLine(VIEW, "field version: Int64;", "field version: Int64 versioned;")),
                "SIR-VALID-002", 1);
        assertRejectedWith(
                replaceBlock(GET_INPUT, replaceLine(GET_INPUT, "field id: Int64;", "field id: Int64 versioned;")),
                "SIR-VALID-002", 1);
    }

    @Test
    void bindingTheVersionFieldInCreateOrUpdateIsRejected() {
        assertRejectedWith(
                replaceBlock(CREATE_CAPABILITY,
                        insertAfter(CREATE_CAPABILITY, "capacity: input.capacity;", "version: 1;")),
                "SIR-VALID-004", 1);
        assertRejectedWith(
                replaceBlock(UPDATE_CAPABILITY,
                        insertAfter(UPDATE_CAPABILITY, "capacity: input.capacity;", "version: 1;")),
                "SIR-VALID-004", 1);
    }

    @Test
    void aVersionedUpdateNeedsTheEntityPatchPayload() {
        assertRejectedWith(
                replaceBlock(UPDATE_CAPABILITY,
                        replaceLine(UPDATE_CAPABILITY, "input UpdateCourseInput;", "input CreateCourseInput;")),
                "SIR-VALID-004", 1);
    }

    @Test
    void aConditionalUpdateNeedsItsElseAndAnInsertMustNotHaveOne() {
        assertRejectedWith(
                replaceBlock(UPDATE_CAPABILITY,
                        replaceLine(UPDATE_CAPABILITY, "persist course else StaleVersion;", "persist course;")),
                "SIR-VALID-004", 1);
        assertRejectedWith(
                replaceBlock(CREATE_CAPABILITY,
                        replaceLine(CREATE_CAPABILITY, "persist created;", "persist created else StaleVersion;")),
                "SIR-VALID-004", 1);
    }

    @Test
    void aPersistFailureMustBeDeclaredInFails() {
        assertRejectedWith(
                replaceBlock(UPDATE_CAPABILITY, removeLine(UPDATE_CAPABILITY, "fails StaleVersion;")),
                "SIR-FLOW-003", 1);
    }

    @Test
    void anErrorStatusOutsideTheSupportedSetIsRejected() {
        assertRejectedWith(
                replaceBlock(ERRORS, replaceLine(ERRORS, "error StaleVersion http 409;", "error StaleVersion http 500;")),
                "SIR-TYPE-001", 1);
        assertRejectedWith(
                replaceBlock(ERRORS,
                        replaceLine(ERRORS, "error StaleVersion http 409;", "error StaleVersion http 999999999999999999999;")),
                "SIR-TYPE-001", 1);
    }

    @Test
    void aPatchFieldWithConstraintsIsRejected() {
        assertRejectedWith(
                replaceBlock(PATCH_INPUT,
                        replaceLine(PATCH_INPUT, "field capacity: Int32;", "field capacity: Int32 where min(1);")),
                "SIR-VALID-003", 1);
    }

    @Test
    void aPatchPayloadWithoutItsIdentityIsRejected() {
        assertRejectedWith(
                replaceBlock(PATCH_INPUT, removeLine(PATCH_INPUT, "field id: Int64;")),
                "SIR-VALID-003", 1);
    }

    @Test
    void aPatchPayloadDeclaringItsIdentityTwiceIsRejected() {
        assertRejectedWith(
                replaceBlock(PATCH_INPUT, insertAfter(PATCH_INPUT, "field capacity: Int32;", "field id: Int64;")),
                "SIR-VALID-003", 1);
    }

    @Test
    void aPatchPayloadCannotDeclareTheVersionField() {
        assertRejectedWith(
                replaceBlock(PATCH_INPUT, insertAfter(PATCH_INPUT, "field capacity: Int32;", "field version: Int64;")),
                "SIR-VALID-003", 1);
    }

    @Test
    void aPatchPayloadRequiresAVersionedEntity() {
        String source = declarations()
                .replace(VERSION_MARKER, "field version: Int64;")
                .replace("persist course else StaleVersion;", "persist course;");
        assertRejectedWith(source, "SIR-VALID-003", 1);
    }

    @Test
    void aPatchFieldTypeMustMatchTheEntityField() {
        // One edit, two reports: the payload's own contract, and the update binding that would
        // then hand the entity a value it cannot hold.
        assertRejectedWith(
                replaceBlock(PATCH_INPUT, replaceLine(PATCH_INPUT, "field capacity: Int32;", "field capacity: String;")),
                "SIR-TYPE-001", 2);
    }

    @Test
    void aPatchFieldMustExistOnTheEntity() {
        assertRejectedWith(
                replaceBlock(PATCH_INPUT, insertAfter(PATCH_INPUT, "field capacity: Int32;", "field missing: String;")),
                "SIR-SYMBOL-002", 1);
    }

    @Test
    void aPatchPayloadOnAReadOnlyCapabilityIsRejected() {
        // Read-only and command are contradictory for a payload that changes an entity.
        assertRejectedWith(
                replaceBlock(UPDATE_CAPABILITY,
                        replaceLine(UPDATE_CAPABILITY, "requires atomic;", "requires readonly;")),
                "SIR-VALID-003", 1);
    }

    @Test
    void aCapabilityWithoutAWriteStepCannotUseAPatchPayload() {
        assertRejectedWith(
                replaceBlock(UPDATE_CAPABILITY,
                        removeBlock(UPDATE_CAPABILITY, "update course {", "persist course else StaleVersion;")),
                "SIR-VALID-003", 1);
    }

    @Test
    void presenceRequiresAPatchPayloadField() {
        // A plain input's field has no presence to ask about: absent and empty are the same value.
        String source = declarations()
                .replace(GET_INPUT, insertAfter(GET_INPUT, "field id: Int64;", "field only: Boolean;"))
                .replace("capability CreateCourse", "capability CreateCourse")
                .replace(GET_CAPABILITY, insertAfter(GET_CAPABILITY, "workflow {", "validate input.only.present else CourseNotFound;"));
        assertRejectedWith(source, "SIR-FLOW-001", 1);
    }

    @Test
    void everyPayloadChangeFieldMustBeBoundByTheUpdate() {
        // Dropping one binding would leave that change accepted and silently ignored.
        assertRejectedWith(
                replaceBlock(UPDATE_CAPABILITY, removeLine(UPDATE_CAPABILITY, "capacity: input.capacity;")),
                "SIR-VALID-004", 1);
    }

    @Test
    void presenceOnThePatchIdentityIsRejected() {
        assertRejectedWith(
                replaceBlock(UPDATE_CAPABILITY,
                        replaceLine(UPDATE_CAPABILITY,
                                "validate input.name.present or input.description.present or input.capacity.present else EmptyChange;",
                                "validate input.id.present or input.name.present else EmptyChange;")),
                "SIR-VALID-004", 1);
    }

    private static NormalizedEntity entity(NormalizedSemanticModel model, String name) {
        return model.declarations().stream()
                .filter(NormalizedEntity.class::isInstance)
                .map(NormalizedEntity.class::cast)
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static NormalizedError error(NormalizedSemanticModel model, String name) {
        return model.declarations().stream()
                .filter(NormalizedError.class::isInstance)
                .map(NormalizedError.class::cast)
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static NormalizedInput input(NormalizedSemanticModel model, String name) {
        return model.declarations().stream()
                .filter(NormalizedInput.class::isInstance)
                .map(NormalizedInput.class::cast)
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static NormalizedCapability capability(NormalizedSemanticModel model, String name) {
        return model.declarations().stream()
                .filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast)
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<NormalizedExpression.PresentExpression> collectPresence(NormalizedExpression expression) {
        List<NormalizedExpression.PresentExpression> found = new ArrayList<>();
        collectPresence(expression, found);
        return found;
    }

    private static void collectPresence(NormalizedExpression expression, List<NormalizedExpression.PresentExpression> sink) {
        switch (expression) {
            case NormalizedExpression.PresentExpression present -> sink.add(present);
            case NormalizedExpression.BinaryExpression binary -> {
                collectPresence(binary.left(), sink);
                collectPresence(binary.right(), sink);
            }
            case NormalizedExpression.UnaryExpression unary -> collectPresence(unary.operand(), sink);
            default -> {
            }
        }
    }

    private static void assertRejectedWith(String source, String code, int count) {
        SemanticAnalysis result = analyze(source);
        assertFalse(result.isSuccess(), "source must be rejected: " + code);
        assertTrue(result.model().isEmpty(), "rejected source must not produce a model");

        List<String> matching = result.diagnostics().stream()
                .filter(Diagnostic::isError)
                .filter(diagnostic -> diagnostic.code().value().equals(code))
                .map(Diagnostic::message)
                .toList();
        assertEquals(count, matching.size(),
                () -> "expected exactly " + count + " " + code + " but got " + matching + " (all: " + result.diagnostics() + ")");
    }
}
