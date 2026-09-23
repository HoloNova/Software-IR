package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeBaselinePlanningResult;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behaviour half of Q14: a candidate that differs from its baseline by exactly one projected fact
 * must produce an UPDATE plan rather than {@code NoChanges}.
 *
 * <p>This is the difference between "the projection does not throw" and "the projection tells the
 * truth". The facts pinned here are the ones the change vocabulary can express — the workflow facts
 * reachable through {@code ModifyCapabilityWorkflow}. Entity and view facts (a {@code versioned}
 * flag, a {@code patch of} payload, a nested relation target) have no change operation today, so
 * they are pinned at the projection level by {@code SemanticProjectionParityTest} in {@code
 * sir-change} instead of being forced through an operation that cannot declare them.
 */
class ChangeProjectionParityApplicationTest {

    private static final SourceId SOURCE_ID = SourceId.of("projection-behaviour.sir");

    @TempDir
    Path temporaryDirectory;

    private ChangeChainTestSupport support;

    @BeforeEach
    void setUpSupport() {
        support = new ChangeChainTestSupport(temporaryDirectory, SOURCE_ID);
    }

    @Test
    void changingTheOrderKeyOnlyIsAnUpdatePlan() throws Exception {
        assertOneFactChangeIsAnUpdate(
                "order by code ascending", "order by name descending", "the find order key");
    }

    @Test
    void changingThePageClauseOnlyIsAnUpdatePlan() throws Exception {
        assertOneFactChangeIsAnUpdate(
                "Page input.page, input.size else InvalidPage",
                "Page input.page, input.sizeAlt else InvalidPage",
                "the find page clause");
    }

    @Test
    void changingTheExistsConditionOnlyIsAnUpdatePlan() throws Exception {
        assertOneFactChangeIsAnUpdate(
                "status == EnrollmentStatus.ACTIVE", "status == EnrollmentStatus.CANCELLED",
                "the any(...) condition");
    }

    @Test
    void changingThePresentTargetOnlyIsAnUpdatePlan() throws Exception {
        assertOneFactChangeIsAnUpdate(
                "validate input.name.present else EmptyChange;", "validate input.code.present else EmptyChange;",
                "the .present target");
    }

    @Test
    void changingThePersistFailureOnlyIsAnUpdatePlan() throws Exception {
        assertOneFactChangeIsAnUpdate(
                "persist course else StaleVersion;", "persist course else CourseNotFound;",
                "the conditional persist failure");
    }

    @Test
    void anIdenticalCandidateIsStillNoChanges() throws Exception {
        ChangeChainTestSupport.Chain chain = support.startChainFromSource(source(), "baseline.sir");
        Path candidate = support.writeText("identical.sir", source());

        ChangeBaselinePlanningResult result = chain.plan(candidate,
                chain.modifyCapabilityWorkflow(chain.currentSir(), "SearchCourses"));

        // The control case: if an identical candidate were "planned", the parity assertions above
        // would pass for the wrong reason.
        assertTrue(support.assertNoChanges(result, "an identical candidate").length() > 0);
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private void assertOneFactChangeIsAnUpdate(String fact, String replacement, String what) throws Exception {
        ChangeChainTestSupport.Chain chain = support.startChainFromSource(source(), "baseline.sir");
        String base = source();
        assertTrue(base.contains(fact), "the template must contain the fact: " + fact);
        Path candidate = support.writeText("varying.sir", base.replace(fact, replacement));
        String owner = fact.contains("input.name.present") || fact.contains("persist course") ? "UpdateCourse" : "SearchCourses";
        ChangeSet changeSet = chain.modifyCapabilityWorkflow(chain.currentSir(), owner);

        ChangeBaselinePlanningResult result = chain.plan(candidate, changeSet);

        support.assertPlannedUpdate(result, what);
    }

    /** The base source, written into the chain workspace; every case varies exactly one line. */
    private static String source() {
        return """
                sir 0.1

                software ProjectionBehaviour {
                  metadata {
                    displayName "Projection Behaviour";
                    namespace "com.example.projectionbehaviour";
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
                      field version: Int64 versioned;
                    }

                    entity Enrollment persistent {
                      identity id: Int64 generated auto;
                      field course: Ref<Course>;
                      field status: EnrollmentStatus;
                    }

                    view CourseDetail from Course {
                      field id: Int64;
                      field code: String;
                      field name: String;
                      field version: Int64;
                    }

                    view EnrollmentSummary from Enrollment {
                      field id: Int64;
                      field status: EnrollmentStatus;
                    }

                    view CourseItem from Course {
                      field id: Int64;
                      field code: String;
                      field name: String;
                      field enrollments: List<EnrollmentSummary>;
                    }

                    input UpdateCourseInput patch of Course {
                      field id: Int64;
                      field name: String;
                      field code: String;
                    }

                    input SearchInput {
                      field page: Int32;
                      field size: Int32;
                      field sizeAlt: Int32;
                    }

                    error CourseNotFound;
                    error EmptyChange;
                    error StaleVersion;
                    error InvalidPage;

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
                          code: input.code;
                        }
                        persist course else StaleVersion;
                        return course;
                      }
                    }

                    capability SearchCourses {
                      input SearchInput;
                      output Page<CourseItem>;
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
}
