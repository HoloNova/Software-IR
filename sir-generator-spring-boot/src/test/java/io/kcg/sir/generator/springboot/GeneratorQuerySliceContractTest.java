package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Q9 generation contract for the paged query slice.
 *
 * <p>The paged path adds four things to the generated project: the page envelope, the response
 * projection, a paged query with declared ordering and literal matching, and the MyBatis-Plus
 * interceptor that makes pagination work at all.
 */
class GeneratorQuerySliceContractTest {

    private static final String COURSE_CATALOG = "valid/course-catalog.sir";

    @Test
    void pageEnvelopeIsRenderedAsAReusableResponseType() {
        String envelope = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccess(COURSE_CATALOG), "api/PageResponse.java");

        assertTrue(envelope.contains("public class PageResponse<T> {"), envelope);
        assertTrue(envelope.contains("private final long total;"), envelope);
        assertTrue(envelope.contains("private final int page;"), envelope);
        assertTrue(envelope.contains("private final int size;"), envelope);
        assertTrue(envelope.contains("private final List<T> records;"), envelope);
        assertTrue(envelope.contains("public long getTotal()"), envelope);
        assertTrue(envelope.contains("public List<T> getRecords()"), envelope);
    }

    @Test
    void viewIsRenderedAsAResponseProjectionWithoutValidationAnnotations() {
        String view = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccess(COURSE_CATALOG), "api/CourseSummary.java");

        assertTrue(view.contains("public class CourseSummary {"), view);
        assertTrue(view.contains("private final String code;"), view);
        assertTrue(view.contains("private final Integer capacity;"), view);
        assertTrue(view.contains("public CourseSummary(String code, String name, Integer capacity)"), view);
        assertTrue(view.contains("public String getCode()"), view);
        assertFalse(view.contains("jakarta.validation"), view);
        assertFalse(view.contains("@NotBlank"), view);
    }

    @Test
    void serviceRendersPagingOrderingLiteralEscapingAndProjection() {
        String service = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccess(COURSE_CATALOG), "application/SearchCoursesService.java");

        assertTrue(service.contains("public PageResponse<CourseSummary> searchCourses(SearchCoursesInput input)"), service);
        assertTrue(service.contains("int coursesPage = input.getPage() == null ? 1 : input.getPage();"), service);
        assertTrue(service.contains("int coursesSize = input.getSize() == null ? 20 : input.getSize();"), service);
        assertTrue(service.contains("if (coursesPage < 1 || coursesPage > 10000 || coursesSize < 1 || coursesSize > 100) {"), service);
        assertTrue(service.contains("throw new InvalidPageParamException();"), service);
        assertTrue(service.contains(".orderByAsc(Course::getCode)"), service);
        assertTrue(service.contains(".orderByAsc(Course::getId)"), service);
        assertTrue(service.contains(".selectCount(wrapper)"), service);
        assertTrue(service.contains(".selectList(wrapper.last(\"LIMIT \" + (coursesPage - 1) * coursesSize + \", \" + coursesSize))"), service);
        assertTrue(service.contains("long coursesTotal = courseMapper.selectCount(wrapper);"), service);
        assertTrue(service.contains("new PageResponse<>(coursesTotal, coursesPage, coursesSize,"), service);
        assertTrue(service.contains("new CourseSummary(row.getCode(), row.getName(), row.getCapacity())"), service);
    }

    @Test
    void literalMatchingEscapesWildcardsWithTheDeclaredEscapeCharacter() {
        String service = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccess(COURSE_CATALOG), "application/SearchCoursesService.java");
        // The generated source carries a Java literal, so the runtime SQL sees two backslashes and MySQL
        // reads exactly one escape character.
        String escapedBackslash = "\\".repeat(4);
        String backslashCharacterLiteral = "'" + "\\".repeat(2) + "'";

        assertTrue(service.contains("ESCAPE '" + escapedBackslash + "'"), service);
        assertTrue(service.contains("escapeLikeLiteral(input.getKeyword())"), service);
        assertTrue(service.contains("private static String escapeLikeLiteral(String value) {"), service);
        assertTrue(
                service.contains("if (current == " + backslashCharacterLiteral + " || current == '%' || current == '_') {"),
                service);
        assertTrue(service.contains("escaped.append(" + backslashCharacterLiteral + ");"), service);
    }

    @Test
    void applicationRegistersNoPaginationInterceptorBecauseTheFrozenSetHasNone() {
        String application = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccess(COURSE_CATALOG), "Application.java");

        assertFalse(application.contains("PaginationInnerInterceptor"), application);
        assertFalse(application.contains("MybatisPlusInterceptor"), application);
        assertTrue(application.contains("public static void main(String[] args) {"), application);
    }

    @Test
    void controllerReturnsThePageEnvelopeOfTheProjectedView() {
        String controller = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccess(COURSE_CATALOG), "api/SearchCoursesController.java");

        assertTrue(controller.contains("public PageResponse<CourseSummary> searchCourses(@Valid @ModelAttribute SearchCoursesInput input)"), controller);
        assertTrue(controller.contains("return service.searchCourses(input);"), controller);
    }

    @Test
    void repeatedGenerationOfThePagedSliceIsByteIdentical() {
        List<GeneratedFile> first = GeneratorTestSupport.generateSuccess(COURSE_CATALOG);
        List<GeneratedFile> second = GeneratorTestSupport.generateSuccess(COURSE_CATALOG);

        assertEquals(first.size(), second.size(), "generated file count must be stable");
        for (int index = 0; index < first.size(); index++) {
            GeneratedFile left = first.get(index);
            GeneratedFile right = second.get(index);
            assertEquals(left.relativePath(), right.relativePath(), "generated file order must be stable");
            assertEquals(left.content(), right.content(),
                    () -> "generated content must be byte-identical: " + left.relativePath());
            assertFalse(left.content().contains("\r"),
                    () -> "generated content must not contain CR: " + left.relativePath());
        }
    }

    @Test
    void pagedSliceGeneratesTheFilesItsIRDeclares() {
        List<String> paths = GeneratorTestSupport.generateSuccess(COURSE_CATALOG).stream()
                .map(GeneratedFile::relativePath)
                .toList();

        assertTrue(paths.contains("src/main/java/com/example/coursecatalog/api/PageResponse.java"), paths::toString);
        assertTrue(paths.contains("src/main/java/com/example/coursecatalog/api/CourseSummary.java"), paths::toString);
        assertTrue(paths.contains("src/main/java/com/example/coursecatalog/domain/Course.java"), paths::toString);
        assertTrue(paths.contains("src/main/java/com/example/coursecatalog/persistence/CourseMapper.java"), paths::toString);
    }
}
