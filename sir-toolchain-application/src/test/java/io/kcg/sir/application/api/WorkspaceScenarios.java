package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.MultiSourceTestSupport;
import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A project whose SIR sources and generated files sit side by side in one directory (the Git working tree),
 * and a candidate version of it, with the expected result produced independently: the production toolchain
 * generates the candidate from scratch in another directory.
 *
 * <p>Building one is slow (it generates twice), so a {@link Template} is built once and copied for each case.
 */
final class WorkspaceScenarios {
    private WorkspaceScenarios() {
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
        /** Only a comment changes: new sources and no generated file change. */
        SOURCE_ONLY,
        ADD_ONLY,
        DELETE_ONLY,
        /** Two source files change together. */
        TWO_SOURCES,
        /** A new source file that the entry lists. */
        NEW_SOURCE_FILE
    }

    /** The base project and the candidate, built once. */
    record Template(Path directory, SourceSnapshot base, SourceSnapshot candidate, Map<String, String> baseOutput, Map<String, String> candidateOutput) {
        /** A private copy of the base project, with its own roots. */
        Workspace copy(Path where) throws IOException {
            copyTree(this.directory.resolve("repo"), where.resolve("repo"));
            return new Workspace(where.resolve("repo"), where.resolve("repo/sources"), where.resolve("repo/generated"), this);
        }
    }

    record Workspace(Path repository, Path sources, Path output, Template template) {
        WorkspaceVersionRequest request() {
            return this.request(this.template.candidate());
        }

        WorkspaceVersionRequest request(SourceSnapshot candidate) {
            return new WorkspaceVersionRequest(this.repository, this.sources, this.output, candidate);
        }

        Map<String, String> outputTree() throws Exception {
            return MultiSourceTestSupport.tree(this.output);
        }

        Map<String, String> sourceFiles() throws Exception {
            return MultiSourceTestSupport.files(this.sources);
        }
    }

    static Template template(Path directory, Kind kind) throws Exception {
        Path repo = Files.createDirectories(directory.resolve("repo"));
        Path sources = MultiSourceTestSupport.fixture(repo);
        Path output = repo.resolve("generated");
        MultiSourceTestSupport.generate(sources, output);
        SourceSnapshot base = read(sources, output);
        SourceSnapshot candidate = candidate(base, kind);

        Path expectedSources = directory.resolve("expected-sources");
        for (var file : candidate.manifest().files()) {
            Path path = expectedSources.resolve(file.sourceId().value());
            Files.createDirectories(path.getParent());
            Files.write(path, candidate.bytes(file.sourceId()));
        }

        Path expectedOutput = directory.resolve("expected-output");
        MultiSourceTestSupport.generate(expectedSources, expectedOutput);
        return new Template(directory, base, candidate, MultiSourceTestSupport.tree(output), MultiSourceTestSupport.tree(expectedOutput));
    }

    static SourceSnapshot read(Path sources, Path output) {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var loaded = ProjectSourceReader.read(new ToolchainRequest(sources, MultiSourceTestSupport.ENTRY, output), diagnostics);
        assertTrue(loaded.isPresent(), diagnostics.toString());
        return loaded.get().snapshot();
    }

    /** The files of a snapshot as hex by path, comparable with {@link MultiSourceTestSupport#files}. */
    static Map<String, String> files(SourceSnapshot snapshot) {
        Map<String, String> map = new LinkedHashMap<>();
        for (var file : snapshot.manifest().files()) {
            map.put(file.sourceId().value(), HexFormat.of().formatHex(snapshot.bytes(file.sourceId())));
        }

        return map;
    }

    private static SourceSnapshot candidate(SourceSnapshot base, Kind kind) {
        String project = text(base, "project.sir");
        int from = project.indexOf("    capability GetCourse {");
        int to = project.indexOf("    capability CreateCourse {");
        assertTrue(from > 0 && to > from, "fixture changed");
        String getCourse = project.substring(from, to);
        String longerCode = "field code: String where notBlank, length(1, 32);";
        String longerCodeTo = "field code: String where notBlank, length(1, 40);";
        return switch (kind) {
            case MIXED -> edit(base, new String[] {"project.sir", getCourse, NEW_CAPABILITY}, new String[] {"project.sir", longerCode, longerCodeTo});
            case RENAME -> edit(base, new String[] {"project.sir", "namespace \"com.example.courseadmin\"", "namespace \"com.example.coursemanager\""});
            case SOURCE_ONLY -> edit(base, new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// reviewed"});
            case ADD_ONLY -> edit(base, new String[] {"project.sir", "    capability CreateCourse {", NEW_CAPABILITY + "    capability CreateCourse {"});
            case DELETE_ONLY -> edit(base, new String[] {"project.sir", getCourse, ""});
            case TWO_SOURCES -> edit(base, new String[] {"project.sir", longerCode, longerCodeTo}, new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// reviewed"});
            case NEW_SOURCE_FILE -> {
                SourceSnapshot listed = edit(base, new String[] {"project.sir", "    source \"modules/enrollment.sir\";", "    source \"modules/enrollment.sir\";\n    source \"modules/notes.sir\";"});
                yield with(listed, "modules/notes.sir", "sir 0.2\nimports {}\ndeclarations {\n}\n");
            }
        };
    }

    static String text(SourceSnapshot snapshot, String file) {
        return new String(snapshot.bytes(SourceId.of(file)), StandardCharsets.UTF_8);
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

    static SourceSnapshot with(SourceSnapshot base, String file, String text) {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (var existing : base.manifest().files()) {
            inputs.put(existing.sourceId(), base.bytes(existing.sourceId()));
        }

        inputs.put(SourceId.of(file), text.getBytes(StandardCharsets.UTF_8));
        return new SourceSnapshot(base.entry(), inputs);
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }
}
