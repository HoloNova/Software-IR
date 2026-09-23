package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Q15: an entity-level artifact must be a function of the entity declaration alone.
 *
 * <p>The mapper used to be rendered from whichever capability happened to perform a conditional
 * update, so removing that capability rewrote a surviving file and the change layer refused the
 * plan ({@code SIR-CHANGE-IMPACT-202}). These tests pin the invariant directly: with or without
 * such a capability, the entity's mapper is byte-identical, and it appears exactly when the entity
 * declares a version column.
 */
class GeneratorEntityArtifactInvarianceTest {

    private static final String WRITE_SLICE = "valid/course-admin.sir";
    private static final String MAPPER = "src/main/java/com/example/courseadmin/persistence/CourseMapper.java";
    private static final String NO_VERSION_MAPPER = "src/main/java/com/example/campusmarket/persistence/GoodsMapper.java";

    @Test
    void theMapperIsIdenticalWithAndWithoutAConditionalUpdateCapability() {
        String withUpdate = GeneratorTestSupport.resourceText(WRITE_SLICE);
        String withoutUpdate = removeCapability(withUpdate, "UpdateCourse");
        assertNotEquals(withUpdate, withoutUpdate, "the probe must really remove the capability");

        String mapperWith = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccessFromText(withUpdate), MAPPER);
        String mapperWithout = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccessFromText(withoutUpdate), MAPPER);

        assertEquals(mapperWith, mapperWithout,
                "the entity's mapper must not depend on which capabilities exist; otherwise removing a "
                        + "capability rewrites a surviving file and the change layer refuses the plan");
    }

    @Test
    void anEntityWhoseVersionIsDeclaredAlwaysCarriesItsCompareAndSetHelpers() {
        String withoutUpdate = removeCapability(GeneratorTestSupport.resourceText(WRITE_SLICE), "UpdateCourse");

        String mapper = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccessFromText(withoutUpdate), MAPPER);

        assertTrue(mapper.contains("@Select(\"SELECT * FROM course WHERE id = #{id} FOR UPDATE\")"), mapper);
        assertTrue(mapper.contains("Course selectByIdForUpdate(@Param(\"id\") Long id);"), mapper);
        assertTrue(mapper.contains("int updateIfVersionMatches(@Param(\"candidate\") Course candidate, "
                + "@Param(\"expectedVersion\") long expectedVersion);"), mapper);
        assertTrue(mapper.contains("version = version + 1"), mapper);
    }

    @Test
    void anEntityWithoutAVersionColumnCarriesNoCompareAndSetHelpers() {
        // Sensitivity probe for the rule above: no version declaration, no conditional-update helpers.
        List<io.kcg.sir.generator.springboot.api.GeneratedFile> files =
                GeneratorTestSupport.generateSuccess("valid/campus-market.sir");

        String mapper = GeneratorTestSupport.contentEndingWith(files, NO_VERSION_MAPPER);
        assertFalse(mapper.contains("updateIfVersionMatches"), mapper);
        assertFalse(mapper.contains("FOR UPDATE"), mapper);
    }

    /** Removes one capability block, keeping its input/error declarations behind (Q3 convention). */
    private static String removeCapability(String source, String capabilityName) {
        String marker = "capability " + capabilityName + " {";
        int start = source.indexOf(marker);
        assertTrue(start > 0, "the fixture must declare " + capabilityName);
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, "the capability block must be closed");
        return source.substring(0, start) + source.substring(end + "\n    }\n".length());
    }
}
