package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Q11 generation contract for relations: the correlated {@code EXISTS} the predicate lowers to, the
 * batch read each nested projection costs, and the read-only transaction the whole query runs in.
 *
 * <p>The generated service is asserted statement by statement. The correlated subquery and the batch
 * reads are target decisions that were made before rendering, so their shape is a contract: a change
 * to any of them changes what the database is asked to do.
 */
class GeneratorRelationSliceContractTest {

    private static final String COURSE_ENROLLMENT = "valid/course-enrollment.sir";

    private final String service = GeneratorTestSupport.contentEndingWith(
            GeneratorTestSupport.generateSuccess(COURSE_ENROLLMENT),
            "application/SearchCourseEnrollmentsService.java");

    @Test
    void theExistencePredicateIsRenderedAsACorrelatedExistsWithBoundValues() {
        assertTrue(
                service.contains(".apply(\"EXISTS (SELECT 1 FROM enrollment enrollment_rel"
                        + " WHERE enrollment_rel.course_id = course.id AND enrollment_rel.status = {0})\", \"ACTIVE\")"),
                service);
    }

    @Test
    void everyAssociationIsReadOnceByTheKeysOfTheRowsAlreadyLoaded() {
        assertTrue(
                service.contains("var related0Keys = coursesRecords.stream().map(Course::getId).distinct().toList();"),
                service);
        assertTrue(
                service.contains("var related0 = related0Keys.isEmpty() ? java.util.List.<Enrollment>of() :"
                        + " enrollmentMapper.selectList(new LambdaQueryWrapper<Enrollment>()"
                        + ".in(Enrollment::getCourseId, related0Keys).orderByAsc(Enrollment::getId));"),
                service);
        assertTrue(
                service.contains("var related0Index = related0.stream().collect("
                        + "java.util.stream.Collectors.groupingBy(Enrollment::getCourseId));"),
                service);
    }

    @Test
    void aSingleRelatedRowIsReadByItsOwnIdentityAndIndexedByIdentity() {
        assertTrue(
                service.contains("var related1Keys = related0.stream().map(Enrollment::getStudentId)"
                        + ".filter(java.util.Objects::nonNull).distinct().toList();"),
                service);
        assertTrue(
                service.contains("var related1 = related1Keys.isEmpty() ? java.util.List.<Student>of() :"
                        + " studentMapper.selectList(new LambdaQueryWrapper<Student>().in(Student::getId, related1Keys));"),
                service);
        assertTrue(
                service.contains("var related1Index = related1.stream().collect("
                        + "java.util.stream.Collectors.toMap(Student::getId, related -> related));"),
                service);
    }

    @Test
    void theResponseProjectsTheRelatedRowsWithoutAQueryPerRow() {
        assertTrue(
                service.contains("related0Index.getOrDefault(row.getId(), java.util.List.of()).stream()"
                        + ".map(related0Row -> new EnrollmentSummary("),
                service);
        assertTrue(
                service.contains("java.util.Optional.ofNullable(related1Index.get(related0Row.getStudentId()))"
                        + ".map(related1Row -> new StudentSummary(related1Row.getId(), related1Row.getStudentNo(),"
                        + " related1Row.getName())).orElse(null)"),
                service);
    }

    @Test
    void theWholeQueryRunsInOneReadOnlyTransaction() {
        assertTrue(service.contains("@Transactional(readOnly = true)"), service);
    }

    @Test
    void theNestedViewsBecomeTheirOwnResponseTypes() {
        List<io.kcg.sir.generator.springboot.api.GeneratedFile> files =
                GeneratorTestSupport.generateSuccess(COURSE_ENROLLMENT);
        String item = GeneratorTestSupport.contentEndingWith(files, "api/CourseEnrollmentItem.java");
        String summary = GeneratorTestSupport.contentEndingWith(files, "api/EnrollmentSummary.java");

        assertTrue(item.contains("private final java.util.List<EnrollmentSummary> enrollments;"), item);
        assertTrue(summary.contains("private final StudentSummary student;"), summary);
    }
}
