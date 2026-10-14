package io.kcg.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.cli.CliTestFixtures.ProcResult;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.ProjectToolchainResult;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code version-plan} and {@code version-apply}, run as real child processes so that the exit code and the bytes
 * on stdout are what a caller sees. The project is a Git working tree holding the SIR sources and the generated
 * project; the expected result of a change is the production toolchain generating the candidate from scratch.
 */
class KcgVersionCommandsTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private static final List<String> FILES = List.of("project.sir", "modules/course.sir", "modules/student.sir", "modules/enrollment.sir");
    private static final String CODE_32 = "field code: String where notBlank, length(1, 32);";
    private static final String CODE_40 = "field code: String where notBlank, length(1, 40);";

    @TempDir Path temp;
    private Path repository;
    private Path sources;
    private Path output;
    private Path candidate;

    @BeforeEach
    void setUp() throws Exception {
        this.repository = Files.createDirectories(this.temp.resolve("repo"));
        this.sources = this.repository.resolve("sources");
        this.output = this.repository.resolve("generated");
        this.candidate = this.temp.resolve("candidate");
        for (String file : FILES) {
            String text = resource(file);
            write(this.sources.resolve(file), text);
            write(this.candidate.resolve(file), file.equals("project.sir") ? text.replace(CODE_32, CODE_40) : text);
        }

        assertInstanceOf(ProjectToolchainResult.Success.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(this.sources, ENTRY, this.output)));
    }

    private static String resource(String file) throws IOException {
        try (var stream = KcgVersionCommandsTest.class.getResourceAsStream("/io/kcg/cli/multi-course/" + file)) {
            return new String(java.util.Objects.requireNonNull(stream, file).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private String[] roots(String command, String... more) {
        List<String> args = new ArrayList<>(List.of(command,
            "--repository-root", this.repository.toString(), "--source-root", this.sources.toString(), "--entry", "project.sir",
            "--output-root", this.output.toString(), "--candidate-root", this.candidate.toString()));
        args.addAll(List.of(more));
        return args.toArray(new String[0]);
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        assertTrue(start >= 0, name + " not in " + json);
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static TreeMap<String, String> snapshot(Path root) throws IOException {
        TreeMap<String, String> map = new TreeMap<>();
        if (!Files.exists(root)) {
            return map;
        }

        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String key = root.relativize(path).toString();
                map.put(key, Files.isRegularFile(path) ? java.util.HexFormat.of().formatHex(Files.readAllBytes(path)) : "DIR");
            }
        }

        return map;
    }

    private Path freshGeneration() throws IOException {
        Path expected = this.temp.resolve("expected-output");
        assertInstanceOf(ProjectToolchainResult.Success.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(this.candidate, ENTRY, expected)));
        return expected;
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(this.repository.toFile()).redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + ": " + text);
        return text;
    }

    // ------------------------------------------------------------------------------------ version-plan

    @Test
    void versionPlanPrintsThePlanAndChangesNothing() throws Exception {
        var sourcesBefore = snapshot(this.sources);
        var outputBefore = snapshot(this.output);

        ProcResult plan = CliTestFixtures.runCli(roots("version-plan"));

        assertEquals(0, plan.exit(), plan.stdout() + plan.stderr());
        assertTrue(plan.stdout().startsWith("{\"protocolVersion\":\"KCG-CLI-VERSION-CHANGE-V1\",\"command\":\"version-plan\",\"outcome\":\"PLANNED\""), plan.stdout());
        assertTrue(field(plan.stdout(), "planId").matches("[0-9a-f]{64}"));
        assertTrue(plan.stdout().contains("\"sources\":[{\"source\":\"project.sir\",\"action\":\"CHANGED\"}]"), plan.stdout());
        assertTrue(plan.stdout().contains("\"action\":\"UPDATE\""), "the longer code field changes generated files: " + plan.stdout());
        assertEquals(sourcesBefore, snapshot(this.sources));
        assertEquals(outputBefore, snapshot(this.output));
        assertFalse(Files.exists(this.repository.resolve(".git")), "planning must not even create a repository");

        // Canonical: another process prints the same bytes.
        assertEquals(plan.stdout(), CliTestFixtures.runCli(roots("version-plan")).stdout());
    }

    @Test
    void versionPlanSaysNoChangesWhenTheCandidateIsTheCurrentVersion() throws Exception {
        this.candidate = this.sources;

        ProcResult plan = CliTestFixtures.runCli(roots("version-plan"));

        assertEquals(0, plan.exit(), plan.stdout());
        assertTrue(plan.stdout().contains("\"command\":\"version-plan\",\"outcome\":\"NO_CHANGES\""), plan.stdout());
        assertFalse(plan.stdout().contains("\"planId\""));
    }

    @Test
    void versionPlanRefusesAGeneratedProjectThatIsNotTheCurrentSirAndTellsWhatToDo() throws Exception {
        Path pom = this.output.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom) + "\n<!-- edited by hand -->");

        ProcResult plan = CliTestFixtures.runCli(roots("version-plan"));

        assertEquals(3, plan.exit(), plan.stdout());
        assertTrue(plan.stdout().contains("\"outcome\":\"FAILURE\""), plan.stdout());
        assertTrue(plan.stdout().contains("SIR-APP-WORKSPACE-DRIFT-001"), plan.stdout());
        assertTrue(plan.stdout().contains("regenerate"), plan.stdout());
    }

    // ----------------------------------------------------------------------------------- version-apply

    @Test
    void versionApplyCarriesOutThePlanThatWasSeenAndSavesBothVersionsInGit() throws Exception {
        String planId = field(CliTestFixtures.runCli(roots("version-plan")).stdout(), "planId");

        ProcResult apply = CliTestFixtures.runCli(roots("version-apply", "--expected-plan-id", planId));

        assertEquals(0, apply.exit(), apply.stdout() + apply.stderr());
        assertTrue(apply.stdout().contains("\"command\":\"version-apply\",\"outcome\":\"APPLIED\""), apply.stdout());
        assertTrue(field(apply.stdout(), "beforeCommit").matches("[0-9a-f]{40,64}"));
        assertTrue(field(apply.stdout(), "afterCommit").matches("[0-9a-f]{40,64}"));
        // The project is now exactly what a fresh generation of the candidate gives, and the SIR on disk is the candidate.
        assertEquals(snapshot(freshGeneration()), snapshot(this.output));
        for (String file : FILES) {
            assertEquals(Files.readString(this.candidate.resolve(file)), Files.readString(this.sources.resolve(file)), file);
        }

        assertEquals("2", git("rev-list", "--count", "HEAD").strip());
        assertEquals(resource("project.sir"), git("show", "HEAD~1:sources/project.sir"), "the previous version is in Git");
        assertEquals("", git("status", "--porcelain", "--", "sources", "generated").strip());

        // Nothing is left to do now, and saying so saves nothing.
        ProcResult again = CliTestFixtures.runCli(roots("version-apply", "--expected-plan-id", planId));
        assertEquals(0, again.exit(), again.stdout());
        assertTrue(again.stdout().contains("\"outcome\":\"NO_CHANGES\""), again.stdout());
        assertEquals("2", git("rev-list", "--count", "HEAD").strip());
    }

    @Test
    void versionApplyWithAPlanIdThatIsNotTheCurrentPlanChangesNothing() throws Exception {
        var sourcesBefore = snapshot(this.sources);
        var outputBefore = snapshot(this.output);

        ProcResult apply = CliTestFixtures.runCli(roots("version-apply", "--expected-plan-id", "0".repeat(64)));

        assertEquals(3, apply.exit(), apply.stdout());
        assertTrue(apply.stdout().contains("\"outcome\":\"FAILURE\""), apply.stdout());
        assertTrue(apply.stdout().contains("SIR-APP-WORKSPACE-VERIFY-001"), apply.stdout());
        assertEquals(sourcesBefore, snapshot(this.sources));
        assertEquals(outputBefore, snapshot(this.output));
        assertFalse(Files.exists(this.repository.resolve(".git")), "a refused apply must not create a repository");
    }

    // ------------------------------------------------------------------------------- usage and help

    @Test
    void argumentMistakesAreUsageErrors() throws Exception {
        List<List<String>> mistaken = new ArrayList<>();
        mistaken.add(List.of("version-plan"));
        mistaken.add(List.of("version-plan", "--repository-root", this.repository.toString()));
        mistaken.add(List.of(roots("version-plan")).stream().map(a -> a.equals(this.sources.toString()) ? "relative/sources" : a).toList());
        mistaken.add(List.of(roots("version-plan")).stream().map(a -> a.equals("project.sir") ? "../project.sir" : a).toList());
        mistaken.add(List.of(roots("version-apply")));
        mistaken.add(List.of(roots("version-apply", "--expected-plan-id", "not-hex")));
        mistaken.add(List.of(roots("version-plan", "--expected-plan-id", "0".repeat(64))));
        mistaken.add(List.of(roots("version-plan", "--unknown", "x")));

        for (List<String> args : mistaken) {
            ProcResult result = CliTestFixtures.runCli(args.toArray(new String[0]));
            assertEquals(2, result.exit(), () -> args + "\n" + result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("\"outcome\":\"USAGE_ERROR\""), result.stdout());
        }

        assertFalse(Files.exists(this.repository.resolve(".git")));
    }

    @Test
    void topHelpListsTheNewCommandsAndMarksTheOldOnesDeprecated() throws Exception {
        String top = CliTestFixtures.runCli("--help").stdout();
        assertTrue(top.contains("version-plan") && top.contains("version-apply"), top);
        assertTrue(top.contains("DEPRECATED. Inspect"), top);
        assertTrue(top.contains("DEPRECATED. Re-derive"), top);

        for (String command : List.of("version-plan", "version-apply")) {
            ProcResult help = CliTestFixtures.runCli(command, "--help");
            assertEquals(0, help.exit());
            for (String option : List.of("--repository-root", "--source-root", "--entry", "--output-root", "--candidate-root")) {
                assertTrue(help.stdout().contains(option), command + " help must document " + option);
            }
        }

        assertTrue(CliTestFixtures.runCli("version-apply", "--help").stdout().contains("--expected-plan-id"));
    }
}
