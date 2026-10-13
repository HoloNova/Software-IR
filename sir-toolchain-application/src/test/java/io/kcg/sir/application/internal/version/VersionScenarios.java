package io.kcg.sir.application.internal.version;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.MultiSourceTestSupport;
import io.kcg.sir.application.api.ProjectBaselineApplication;
import io.kcg.sir.application.api.ProjectBaselineRegistrationRequest;
import io.kcg.sir.application.api.ProjectBaselineResult;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.ProjectToolchainResult;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stored project and a candidate version of it, ready for a transaction, with the expected result
 * produced independently: the production toolchain generates the candidate from scratch.
 */
final class VersionScenarios {
    private VersionScenarios() {
    }

    private static final String NEW_CAPABILITY = """
            capability FindCourses {
              input SearchCourseEnrollmentsInput;
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
        """;

    enum Kind {
        /** Create, update and delete files in directories that already exist. */
        MIXED,
        /** A new Java package: every generated file moves, so directories are created and removed. */
        RENAME,
        /** Only a comment changes: a new version with no file operation. */
        SOURCE_ONLY,
        ADD_ONLY,
        DELETE_ONLY
    }

    record Scenario(Path root, Path output, Path state, SourceSnapshot baseSources, SourceSnapshot candidateSources,
            ProjectBaselineVerification.Verified base, ProjectBaselineVerification.Verified candidate, VersionPlan plan,
            Map<String, String> baseTree, Map<String, String> candidateTree) {
        String baseId() {
            return this.base.bundle().baselineId();
        }

        String candidateId() {
            return this.candidate.bundle().baselineId();
        }
    }

    static Scenario create(Path root, Kind kind) throws Exception {
        Files.createDirectories(root);
        Path source = MultiSourceTestSupport.fixture(root);
        Path output = root.resolve("output");
        Path state = root.resolve("state");
        var generated = MultiSourceTestSupport.generate(source, output);
        var saved = assertInstanceOf(ProjectBaselineResult.Success.class,
            new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(), output, state)));
        SourceSnapshot baseSources = saved.sources();
        SourceSnapshot candidateSources = candidate(baseSources, kind);

        var diagnostics = new ArrayList<io.kcg.sir.application.api.ExecutionDiagnostic>();
        var base = ProjectBaselineVerification.compile(baseSources, output, diagnostics);
        var candidate = ProjectBaselineVerification.compile(candidateSources, output, diagnostics);
        assertNotNull(base, diagnostics.toString());
        assertNotNull(candidate, diagnostics.toString());
        VersionPlan plan = VersionPlans.compare(state, base, candidate);

        Path candidateSourceDir = root.resolve("candidate-sources");
        for (var file : candidateSources.manifest().files()) {
            Path path = candidateSourceDir.resolve(file.sourceId().value());
            Files.createDirectories(path.getParent());
            Files.write(path, candidateSources.bytes(file.sourceId()));
        }

        Path candidateOutput = root.resolve("candidate-output");
        assertInstanceOf(ProjectToolchainResult.Success.class,
            new ToolchainApplication().executeProject(new ProjectToolchainRequest(candidateSourceDir, candidateSources.entry(), candidateOutput)));
        Map<String, String> baseTree = MultiSourceTestSupport.tree(output);
        Map<String, String> candidateTree = MultiSourceTestSupport.tree(candidateOutput);
        // From here on nothing may need the original source directory: the stored version carries the sources.
        Files.move(source, root.resolve("unavailable"));
        return new Scenario(root, output, state, baseSources, candidateSources, base, candidate, plan, baseTree, candidateTree);
    }

    private static SourceSnapshot candidate(SourceSnapshot base, Kind kind) {
        String project = new String(base.bytes(MultiSourceTestSupport.ENTRY), StandardCharsets.UTF_8);
        int from = project.indexOf("    capability GetCourse {");
        int to = project.indexOf("    capability CreateCourse {");
        assertTrue(from > 0 && to > from, "fixture changed");
        String getCourse = project.substring(from, to);
        return switch (kind) {
            case MIXED -> edit(base, new String[] {"project.sir", getCourse, NEW_CAPABILITY},
                new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);"});
            case RENAME -> edit(base, new String[] {"project.sir", "namespace \"com.example.courseadmin\"", "namespace \"com.example.coursemanager\""});
            case SOURCE_ONLY -> edit(base, new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// reviewed"});
            case ADD_ONLY -> edit(base, new String[] {"project.sir", "    capability CreateCourse {", NEW_CAPABILITY + "    capability CreateCourse {"});
            case DELETE_ONLY -> edit(base, new String[] {"project.sir", getCourse, ""});
        };
    }

    /** The snapshot with edits applied; each edit is {file, from, to}. */
    static SourceSnapshot edit(SourceSnapshot base, String[]... edits) {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (var file : base.manifest().files()) {
            inputs.put(file.sourceId(), base.bytes(file.sourceId()));
        }

        for (String[] edit : edits) {
            SourceId id = SourceId.of(edit[0]);
            String text = new String(inputs.get(id), StandardCharsets.UTF_8);
            assertTrue(text.contains(edit[1]), "fixture changed: " + edit[1]);
            inputs.put(id, text.replace(edit[1], edit[2]).getBytes(StandardCharsets.UTF_8));
        }

        return new SourceSnapshot(base.entry(), inputs);
    }
}
