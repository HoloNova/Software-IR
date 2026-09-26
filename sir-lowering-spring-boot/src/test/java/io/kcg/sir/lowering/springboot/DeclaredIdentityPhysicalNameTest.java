package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.symbol.SymbolId;
import org.junit.jupiter.api.Test;

/**
 * The physical-name contract of a declared identity (Q16): keeping a declaration's identity does not
 * make the target inherit its old physical names, and it does not make the database inherit an old
 * column name either.
 *
 * <p>These facts are what the unit's boundary rests on. A renamed capability keeps its lowered
 * artifact id (it is derived from the stable {@code SymbolId}) while its class names and route follow
 * the new name — that is why a rename changes paths and must be planned as a path change. An entity
 * member's column still follows the field name, which is why a field rename is refused until column
 * inheritance and migration (G3) exist instead of being quietly assumed.
 */
class DeclaredIdentityPhysicalNameTest {

    @Test
    void aRenamedCapabilityKeepsItsArtifactIdentityAndChangesItsPhysicalNames() {
        SpringBootLoweredModel base = LoweringTestSupport.lowerSourceSuccess(source("SearchCourseEnrollments"));
        SpringBootLoweredModel candidate = LoweringTestSupport.lowerSourceSuccess(source("SearchEnrollments"));

        SpringArtifact baseService = artifact(base, SpringArtifact.Role.SERVICE);
        SpringArtifact candidateService = artifact(candidate, SpringArtifact.Role.SERVICE);
        assertEquals(baseService.id(), candidateService.id(), "the artifact id follows the stable declared identity");
        assertEquals(baseService.ownerSymbol(), candidateService.ownerSymbol());
        assertEquals("SearchCourseEnrollmentsService", baseService.simpleName());
        assertEquals("SearchEnrollmentsService", candidateService.simpleName());

        SpringArtifact baseController = artifact(base, SpringArtifact.Role.CONTROLLER);
        SpringArtifact candidateController = artifact(candidate, SpringArtifact.Role.CONTROLLER);
        assertEquals(baseController.id(), candidateController.id());
        assertEquals("SearchCourseEnrollmentsController", baseController.simpleName());
        assertEquals("SearchEnrollmentsController", candidateController.simpleName());
    }

    @Test
    void theRouteFollowsTheNewNameEvenThoughTheIdentityDidNotChange() {
        SpringBootLoweredModel base = LoweringTestSupport.lowerSourceSuccess(source("SearchCourseEnrollments"));
        SpringBootLoweredModel candidate = LoweringTestSupport.lowerSourceSuccess(source("SearchEnrollments"));

        SpringBootDeclaration.CapabilityDeclaration baseCapability = capability(base);
        SpringBootDeclaration.CapabilityDeclaration candidateCapability = capability(candidate);

        assertEquals("/api/search-course-enrollments", baseCapability.route());
        assertEquals("/api/search-enrollments", candidateCapability.route());
        assertNotEquals(baseCapability.route(), candidateCapability.route());
    }

    @Test
    void anEntityMemberColumnStillFollowsItsFieldNameSoARenameCannotInheritTheOldColumn() {
        SpringBootLoweredModel base = LoweringTestSupport.lowerSourceSuccess(source("SearchCourseEnrollments"));
        SpringBootLoweredModel renamedField = LoweringTestSupport.lowerSourceSuccess(
                source("SearchCourseEnrollments").replace("field courseCode", "field code"));

        SpringBootDeclaration.EntityDeclaration baseEntity = entity(base);
        SpringBootDeclaration.EntityDeclaration renamedEntity = entity(renamedField);

        assertEquals("course_code", columnOf(baseEntity, "courseCode"));
        assertEquals("code", columnOf(renamedEntity, "code"));
        assertEquals(
                sourceSymbolOf(baseEntity, "courseCode"),
                sourceSymbolOf(renamedEntity, "code"),
                "the declared member identity is stable while its column name is not"
        );
    }

    private static String source(String capabilityName) {
        return """
                sir 0.1

                software PhysicalNames {
                  metadata {
                    displayName "Physical Names";
                    namespace "com.example.physicalnames";
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
                    entity Course persistent {
                      identity id: Int64 generated auto;
                      field courseCode: String where notBlank @id("course-code");
                    }
                    input GetCourseInput {
                      field id: Int64;
                    }
                    error CourseNotFound;
                    capability %s @id("search-enrollments") {
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
                  }
                }
                """.formatted(capabilityName);
    }

    private static SpringArtifact artifact(SpringBootLoweredModel model, SpringArtifact.Role role) {
        return model.artifacts().stream()
                .filter(artifact -> artifact.role() == role)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + role + " artifact in the lowered model"));
    }

    private static SpringBootDeclaration.CapabilityDeclaration capability(SpringBootLoweredModel model) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no capability declaration in the lowered model"));
    }

    private static SpringBootDeclaration.EntityDeclaration entity(SpringBootLoweredModel model) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entity declaration in the lowered model"));
    }

    private static String columnOf(SpringBootDeclaration.EntityDeclaration entity, String javaName) {
        return entity.fields().stream()
                .filter(field -> field.javaName().equals(javaName))
                .findFirst()
                .flatMap(SpringBootDeclaration.Property::columnName)
                .orElseThrow(() -> new AssertionError("no column for member " + javaName));
    }

    private static SymbolId sourceSymbolOf(
            SpringBootDeclaration.EntityDeclaration entity, String javaName
    ) {
        return entity.fields().stream()
                .filter(field -> field.javaName().equals(javaName))
                .map(SpringBootDeclaration.Property::sourceSymbol)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no declared member " + javaName));
    }

    @Test
    void theFixtureItselfLowersWithUsableNames() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSourceSuccess(source("SearchEnrollments"));

        assertTrue(model.artifacts().stream().anyMatch(artifact -> artifact.role() == SpringArtifact.Role.SERVICE));
        assertEquals(2, model.artifacts().stream()
                .filter(artifact -> artifact.role() == SpringArtifact.Role.SERVICE
                        || artifact.role() == SpringArtifact.Role.CONTROLLER)
                .count());
    }
}
