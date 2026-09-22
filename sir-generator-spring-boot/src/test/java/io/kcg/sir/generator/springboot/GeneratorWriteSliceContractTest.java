package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Q10 generated-code contract for the write slice.
 *
 * <p>These assertions are the reason the real-database scenario can be trusted: the decision that makes
 * a version-guarded write correct has to be visible in the generated source, not inferred from a
 * passing test. The slice's own generated project is compiled offline by
 * {@link GeneratedProjectOfflineCompilationTest}, so everything asserted here is known to be valid
 * Java.
 */
class GeneratorWriteSliceContractTest {

    private static final String SOURCE = "valid/course-admin.sir";
    private static final String API = "src/main/java/com/example/courseadmin/api/";
    private static final String APPLICATION = "src/main/java/com/example/courseadmin/application/";
    private static final String PERSISTENCE = "src/main/java/com/example/courseadmin/persistence/";

    @Test
    void mapperBoundsTheWriteOnTheIdentityAndTheExpectedVersion() {
        String mapper = content(PERSISTENCE + "CourseMapper.java");

        assertTrue(mapper.contains("@Select(\"SELECT * FROM course WHERE id = #{id} FOR UPDATE\")"), mapper);
        assertTrue(mapper.contains(
                "@Update(\"UPDATE course SET name = #{candidate.name}, description = #{candidate.description}, "
                        + "capacity = #{candidate.capacity}, version = version + 1 "
                        + "WHERE id = #{candidate.id} AND version = #{expectedVersion}\")"), mapper);
        assertTrue(mapper.contains("int updateIfVersionMatches(@Param(\"candidate\") Course candidate, "
                + "@Param(\"expectedVersion\") long expectedVersion);"), mapper);
    }

    @Test
    void patchPayloadIsAnEnvelopeThatRemembersWhichChangesArrived() {
        String payload = content(API + "UpdateCourseInput.java");

        assertTrue(payload.contains("@NotNull\n    private Long id;"), payload);
        assertTrue(payload.contains("@NotNull\n    private Long expectedVersion;"), payload);
        assertTrue(payload.contains("private Changes changes;"), payload);
        assertTrue(payload.contains("public static final class Changes {"), payload);
        assertTrue(payload.contains("public boolean has(String property) {"), payload);
        // An unknown change property is refused by the decoder rather than collected: the payload
        // records only which declared changes arrived.
        assertTrue(payload.contains("private final java.util.Set<String> present = new java.util.LinkedHashSet<>();"),
                payload);
        assertFalse(payload.contains("@JsonAnySetter"), payload);
        for (String change : List.of("name", "description", "capacity")) {
            assertTrue(payload.contains("public void " + change + "("), payload);
            assertTrue(payload.contains("this.present.add(\"" + change + "\""), payload);
        }
    }

    @Test
    void theUpdateLocksTheRowAndRefusesAStaleExpectedVersion() {
        String service = content(APPLICATION + "UpdateCourseService.java");

        assertTrue(service.contains("courseMapper.selectByIdForUpdate(input.getId());"), service);
        assertTrue(service.contains("if (!java.util.Objects.equals(course.getVersion(), input.getExpectedVersion())) {"), service);
        assertTrue(service.contains("throw new StaleVersionException();"), service);
    }

    @Test
    void theUpdateMergesOnlyTheChangesTheRequestCarried() {
        String service = content(APPLICATION + "UpdateCourseService.java");

        assertTrue(service.contains("course.setName(input.getChanges().has(\"name\") "
                + "? input.getChanges().getName() : course.getName());"), service);
        assertTrue(service.contains("course.setDescription(input.getChanges().has(\"description\") "
                + "? input.getChanges().getDescription().orElse(null) : course.getDescription());"), service);
        assertTrue(service.contains("course.setCapacity(input.getChanges().has(\"capacity\") "
                + "? input.getChanges().getCapacity() : course.getCapacity());"), service);
        assertFalse(service.contains("course.setCode("), "an unchangeable field must not be merged: " + service);
        assertEquals(1, occurrences(service, "course.setVersion("),
                "the only version write is the committed increment: " + service);
        assertTrue(service.contains("course.setVersion(input.getExpectedVersion() + 1L);"), service);
    }

    @Test
    void theMergedCandidateIsCheckedBeforeItIsWritten() {
        String service = content(APPLICATION + "UpdateCourseService.java");

        assertTrue(service.contains("ValidationSupport.notBlank(courseViolations, \"changes.name\", course.getName());"), service);
        assertTrue(service.contains("ValidationSupport.length(courseViolations, \"changes.name\", course.getName(), 1, 100);"), service);
        assertTrue(service.contains("ValidationSupport.length(courseViolations, \"changes.description\", "
                + "course.getDescription(), 0, 500);"), service);
        assertTrue(service.contains("ValidationSupport.min(courseViolations, \"changes.capacity\", "
                + "course.getCapacity() == null ? null : java.math.BigDecimal.valueOf(course.getCapacity().longValue()), "
                + "new java.math.BigDecimal(\"1\"));"), service);
        assertTrue(service.contains("throw ApiException.invalidRequest(courseViolations);"), service);

        // The checks run before the statement, never after it.
        assertTrue(service.indexOf("updateIfVersionMatches") > service.indexOf("courseViolations.isEmpty()"), service);
    }

    @Test
    void aConstraintOnANullablePayloadFieldAppliesToTheOptionalElement() {
        // Found by the real-database run: an annotation on an Optional-typed field makes Hibernate
        // Validator throw instead of rejecting, so the constraint belongs on the element.
        String payload = content(API + "CreateCourseInput.java");

        assertTrue(payload.contains("private java.util.Optional<@Size(min = 0, max = 500) String> description;"), payload);
    }

    @Test
    void aNullableFieldIsPlainInTheEntityAndOptionalInThePayloadAndView() {
        // Found by the real-database run: the persistence layer has no type handler for an Optional
        // parameter, so handing it one fails at runtime. A nullable column is therefore a plain
        // nullable property, and the Optional belongs to the request and the response.
        String entity = content("src/main/java/com/example/courseadmin/domain/Course.java");

        assertTrue(entity.contains("private String description;"), entity);
        assertFalse(entity.contains("Optional"), entity);
        assertTrue(content(API + "CreateCourseInput.java").contains("private java.util.Optional<"), "payload keeps the optional");
        assertTrue(content(API + "CourseDetail.java").contains("java.util.Optional<String> description;"),
                "view keeps the optional");
    }

    @Test
    void theConditionalPersistReportsAnUnmatchedRow() {
        String service = content(APPLICATION + "UpdateCourseService.java");

        assertTrue(service.contains("int courseAffected = courseMapper.updateIfVersionMatches(course, input.getExpectedVersion());"), service);
        assertTrue(service.contains("if (courseAffected != 1) {"), service);
        // The statement advanced the stored version, so the answer reports the committed one.
        assertTrue(service.contains("course.setVersion(input.getExpectedVersion() + 1L);"), service);
        assertTrue(service.indexOf("course.setVersion(input.getExpectedVersion() + 1L)")
                < service.indexOf("return new CourseDetail("), service);
        assertFalse(service.contains("courseMapper.updateById("), "a versioned change must not take the unchecked path: " + service);
    }

    @Test
    void anEmptyChangeSetIsRefusedBeforeAnythingIsRead() {
        String service = content(APPLICATION + "UpdateCourseService.java");

        assertTrue(service.contains("input.getChanges().has(\"name\")"), service);
        assertTrue(service.contains("throw new EmptyChangeException();"), service);
        assertTrue(service.indexOf("EmptyChangeException") < service.indexOf("selectByIdForUpdate"), service);
    }

    @Test
    void creationStartsTheVersionAndChecksTheCandidate() {
        String service = content(APPLICATION + "CreateCourseService.java");

        assertTrue(service.contains("created.setVersion(0L);"), service);
        assertTrue(service.contains("created.setDescription(input.getDescription().orElse(null));"), service);
        assertTrue(service.contains("ValidationSupport.notBlank(createdViolations, \"code\", created.getCode());"), service);
        assertTrue(service.contains("throw ApiException.invalidRequest(createdViolations);"), service);
        assertTrue(service.indexOf("created.setVersion(0L)") < service.indexOf("courseMapper.insert(created)"), service);
    }

    @Test
    void writeResponsesAreTheDeclaredProjection() {
        String create = content(APPLICATION + "CreateCourseService.java");
        String update = content(APPLICATION + "UpdateCourseService.java");

        // Every field of the declared view is projected from the entity member the view names.
        assertTrue(create.contains("return new CourseDetail(created.getId(), created.getCode(), created.getName(), "
                + "java.util.Optional.ofNullable(created.getDescription()), created.getCapacity(), "
                + "created.getVersion());"), create);
        assertTrue(update.contains("return new CourseDetail(course.getId(), course.getCode(), course.getName(), "
                + "java.util.Optional.ofNullable(course.getDescription()), course.getCapacity(), "
                + "course.getVersion());"), update);
    }

    @Test
    void theTransportSaysPatchForChangesAndCreatedForCreation() {
        String update = content(API + "UpdateCourseController.java");
        String create = content(API + "CreateCourseController.java");

        assertTrue(update.contains("@PatchMapping"), update);
        assertTrue(update.contains("public CourseDetail updateCourse(@Valid @RequestBody UpdateCourseInput input) {"), update);
        assertTrue(create.contains("@PostMapping"), create);
        assertTrue(create.contains("@ResponseStatus(HttpStatus.CREATED)"), create);
    }

    @Test
    void everyFailureAnswersThroughOneEnvelope() {
        String advice = content(API + "ApiExceptionAdvice.java");
        String envelope = content(API + "ApiErrorResponse.java");
        String base = content(API + "ApiException.java");
        String config = content("src/main/resources/application.yml");

        assertTrue(envelope.contains("public record ApiErrorResponse(String code, String message, List<FieldError> fields) {"), envelope);
        assertTrue(envelope.contains("public record FieldError(String path, String code, String message) {"), envelope);
        assertTrue(base.contains("public class ApiException extends RuntimeException {"), base);
        assertTrue(base.contains("public static ApiException invalidRequest(List<ApiErrorResponse.FieldError> fields) {"), base);
        assertTrue(advice.contains("@ExceptionHandler(ApiException.class)"), advice);
        assertTrue(advice.contains("@ExceptionHandler(MethodArgumentNotValidException.class)"), advice);
        assertTrue(advice.contains("@ExceptionHandler(HttpMessageNotReadableException.class)"), advice);
        assertTrue(advice.contains("return ResponseEntity.status(failure.status())"), advice);
        assertTrue(config.contains("fail-on-unknown-properties: true"), config);
    }

    @Test
    void aFailureKeepsTheStatusAndCodeItDeclared() {
        assertTrue(content(API + "StaleVersionException.java")
                .contains("super(\"StaleVersion\", HttpStatus.CONFLICT);"));
        assertTrue(content(API + "CourseNotFoundException.java")
                .contains("super(\"CourseNotFound\", HttpStatus.NOT_FOUND);"));
        assertTrue(content(API + "EmptyChangeException.java")
                .contains("super(\"EmptyChange\", HttpStatus.BAD_REQUEST);"));
    }

    @Test
    void theConstraintHelpersCarryStableCodes() {
        String support = content(API + "ValidationSupport.java");

        for (String code : List.of("notBlank", "email", "length", "min", "max")) {
            assertTrue(support.contains("\"" + code + "\""), support);
        }
    }

    @Test
    void aQueryOnlyProjectGetsTheSameSupportFilesAsAWriteProject() {
        SpringBootLoweredModel queryOnly = GeneratorTestSupport.lowerSuccess("valid/course-catalog.sir");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(queryOnly);
        List<String> paths = files.stream().map(GeneratedFile::relativePath).toList();

        assertTrue(paths.contains("src/main/java/com/example/coursecatalog/api/ApiErrorResponse.java"), paths::toString);
        assertTrue(paths.contains("src/main/java/com/example/coursecatalog/api/ApiExceptionAdvice.java"), paths::toString);
        assertTrue(paths.contains("src/main/java/com/example/coursecatalog/api/ValidationSupport.java"), paths::toString);
    }

    @Test
    void theSetListFollowsTheChangeSetAndNeverTheRequestVersion() {
        // Guard against a hand-tuned statement: the SET list follows the payload's change set exactly.
        String mapper = content(PERSISTENCE + "CourseMapper.java");
        assertEquals(1, occurrences(mapper, "UPDATE course SET"), mapper);
        assertFalse(mapper.contains("code = #{candidate.code}"), "code is not a declared change: " + mapper);
        assertFalse(mapper.contains("version = #{candidate.version}"), "the request must not set the version: " + mapper);
    }

    private static String content(String relativePath) {
        return GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccess(SOURCE), relativePath);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            count++;
            index = source.indexOf(needle, index + needle.length());
        }

        return count;
    }
}
