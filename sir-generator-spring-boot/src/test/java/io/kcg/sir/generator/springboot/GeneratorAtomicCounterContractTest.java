package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated-code contract of a column changed by an amount: the database adds to the stored value in the
 * statement itself, the seat check compares two columns inside it, and the row handed back is read again.
 */
class GeneratorAtomicCounterContractTest {
    private static final String SOURCE = "valid/atomic-counter.sir";
    private static final String BASE = "src/main/java/example/seats/application/";

    private final List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(SOURCE);

    @Test
    void theColumnIsIncreasedByTheDatabaseUnderAGuardThatComparesTwoColumns() {
        String service = service("TakeService");

        assertTrue(service.contains("int courseUpdated = courseMapper.update(null, new LambdaUpdateWrapper<Course>()\n"
                + "                .eq(Course::getId, course.getId())\n"
                + "                .and(q0 -> q0.apply(\"enrolled < capacity\"))\n"
                + "                .setIncrBy(Course::getEnrolled, 1));"), service);
        assertTrue(service.contains("if (courseUpdated != 1) {\n            throw new CourseFullException();\n        }\n"), service);
    }

    @Test
    void theColumnIsDecreasedByAnAmountFromTheRequestWithoutAGuard() {
        String service = service("LeaveService");

        assertTrue(service.contains("int courseUpdated = courseMapper.update(null, new LambdaUpdateWrapper<Course>()\n"
                + "                .eq(Course::getId, course.getId())\n"
                + "                .setDecrBy(Course::getEnrolled, input.getSeats()));"), service);
        assertTrue(service.contains("throw new IllegalStateException("), service);
        assertFalse(service.contains("updateById(course)"), service);
    }

    @Test
    void theStoredValueIsReadBackBeforeTheRowIsReturned() {
        String service = service("TakeService");

        assertTrue(service.contains("Course courseStored = courseMapper.selectById(course.getId());\n"
                + "        course.setEnrolled(courseStored.getEnrolled());"), service);
        assertTrue(service.indexOf("courseStored") < service.indexOf("return "), service);
    }

    private String service(String name) {
        String path = BASE + name + ".java";
        return files.stream()
                .filter(file -> file.relativePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing generated file " + path))
                .content();
    }
}
