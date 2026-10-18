package io.kcg.cli;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real CLI processes, real candidate directories and real Git publication; no Maven/application runtime. */
class KcgWorkflowCommandsTest {
    @TempDir Path temp;
    private static final List<String> SOURCES = List.of("project.sir", "modules/course.sir", "modules/student.sir", "modules/enrollment.sir");

    private CliTestFixtures.ProcResult run(String input, String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m", "-XX:MaxMetaspaceSize=128m", "-cp", System.getProperty("java.class.path"), "io.kcg.cli.KcgCli"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("CLI timeout"); }
        return new CliTestFixtures.ProcResult(process.exitValue(), stdout, stderr);
    }
    private String[] sourceArgs(String command, Path source, String... extras) {
        var args = new ArrayList<>(List.of(command, "--source-root", source.toString(), "--entry", "project.sir"));
        args.addAll(List.of(extras)); return args.toArray(new String[0]);
    }
    private static String resource(String path) throws IOException {
        try (var input = KcgWorkflowCommandsTest.class.getResourceAsStream("/io/kcg/cli/" + path)) {
            return new String(java.util.Objects.requireNonNull(input, path).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static Map<String, String> snapshot(Path root) throws IOException {
        var files = new TreeMap<String, String>();
        if (Files.exists(root)) try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                files.put(root.relativize(path).toString(), java.util.HexFormat.of().formatHex(Files.readAllBytes(path)));
        }
        return files;
    }
    private static Map<String, Object> obj(Object value) { return CommandJson.object(value); }
    private static Map<String, Object> text(String value) { return Map.of("type", "text", "value", value); }
    private static Map<String, Object> selector(Object... steps) { return Map.of("steps", List.of(steps)); }
    private static Map<String, Object> named(String slot, String name) { return Map.of("slot", slot, "name", name); }
    private static Map<String, Object> slot(String name) { return Map.of("slot", name); }
    // Select inspector templates without reconstructing semantic nodes.
    private static Map<String, Object> template(Map<String, Object> info, String name) {
        var seq = obj(obj(obj(info.get("draft")).get("slots")).get("declarations"));
        for (Object element : (List<?>) seq.get("elements")) {
            var child = obj(element);
            if (name.equals(obj(obj(child.get("slots")).get("name")).get("value")))
                return obj(StrictJson.parse(json(child))); // independent copy
        }
        throw new AssertionError("missing declaration " + name);
    }
    private static String json(Object value) {
        if (value instanceof String s) return JsonStringEncoder.encode(s);
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream()
            .map(e -> JsonStringEncoder.encode((String)e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(KcgWorkflowCommandsTest::json).toList()) + "]";
        return value.toString();
    }
    private static String batch(Map<String, Object> info, List<Object> commands) {
        return json(Map.of("protocolVersion", info.get("commandProtocolVersion"), "protocol", info.get("protocol"),
            "softwareName", info.get("softwareName"), "baselineSha256", info.get("baselineSha256"), "commands", commands));
    }

    @Test void publicCommandsComposeMultiSourceEditingPlanningAndGitApply() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo")), source = repo.resolve("sir"), output = repo.resolve("generated");
        for (String file : SOURCES) {
            Path path = source.resolve(file); Files.createDirectories(path.getParent());
            String content = resource("multi-course/" + file);
            if (file.equals("project.sir")) content = content.replace("  declarations {", "  declarations {\n    error UnusedError;");
            Files.writeString(path, content);
        }
        var before = snapshot(source);
        var generated = run("", sourceArgs("generate", source, "--output-root", output.toString()));
        assertEquals(0, generated.exit(), generated.stdout() + generated.stderr());
        assertTrue(generated.stdout().contains("\"outcome\":\"GENERATED\""));
        assertFalse(Files.exists(repo.resolve(".git")));
        var outputBefore = snapshot(output);
        String inspection = run("", sourceArgs("inspect", source)).stdout();
        assertEquals(inspection, run("", sourceArgs("inspect", source)).stdout());
        var info = obj(StrictJson.parse(inspection));
        var input = template(info, "SearchCourseEnrollmentsInput");
        obj(input.get("slots")).put("name", text("ExtraSearchInput"));
        var capability = template(info, "SearchCourseEnrollments");
        obj(capability.get("slots")).put("name", text("ExtraSearch"));
        obj(capability.get("slots")).put("inputSymbol", text("ExtraSearchInput"));
        String commands = batch(info, List.of(
            Map.of("op", "add", "container", selector(slot("declarations")), "value", input),
            Map.of("op", "add", "container", selector(slot("declarations")), "value", capability),
            Map.of("op", "delete", "target", selector(named("declarations", "UnusedError"))),
            Map.of("op", "update", "target", selector(named("declarations", "CourseNotFound"), slot("httpStatus")), "value", text("409"), "expectedOld", text("404")),
            Map.of("op", "update", "target", selector(named("declarations", "Course"), named("fields", "code"), named("constraints", "length"), Map.of("slot", "arguments", "index", 1)), "value", Map.of("type", "child", "kind", "IntegerLiteral", "slots", Map.of("value", text("40"))))));
        Path invalidCandidate = temp.resolve("invalid-reference");
        String invalidReference = batch(info, List.of(Map.of("op", "update", "target", selector(named("declarations", "GetCourse"), slot("inputSymbol")), "value", text("MissingInput"))));
        var invalid = run(invalidReference, sourceArgs("edit", source, "--candidate-root", invalidCandidate.toString()));
        assertEquals(3, invalid.exit(), invalid.stdout());
        assertTrue(invalid.stdout().contains("INVALID_RESULT"), invalid.stdout());
        assertFalse(Files.exists(invalidCandidate)); assertEquals(before, snapshot(source));
        Path candidate = temp.resolve("candidate");
        var edited = run(commands, sourceArgs("edit", source, "--candidate-root", candidate.toString()));
        assertEquals(0, edited.exit(), edited.stdout() + edited.stderr());
        assertTrue(edited.stdout().contains("CANDIDATE_SAVED"), edited.stdout());
        assertEquals(before, snapshot(source)); assertEquals(outputBefore, snapshot(output));
        for (String file : List.of("modules/student.sir", "modules/enrollment.sir"))
            assertEquals(before.get(file), snapshot(candidate).get(file));
        assertTrue(Files.readString(candidate.resolve("project.sir")).contains("capability ExtraSearch"));
        assertFalse(Files.readString(candidate.resolve("project.sir")).contains("UnusedError"));
        assertFalse(Files.exists(repo.resolve(".git")));
        String[] planArgs = {"version-plan", "--repository-root", repo.toString(), "--source-root", source.toString(),
            "--entry", "project.sir", "--output-root", output.toString(), "--candidate-root", candidate.toString()};
        var plan = run("", planArgs); assertEquals(0, plan.exit(), plan.stdout() + plan.stderr());
        String planId = (String) obj(StrictJson.parse(plan.stdout())).get("planId");
        var applyArgs = new ArrayList<>(List.of(planArgs)); applyArgs.set(0, "version-apply");
        applyArgs.addAll(List.of("--expected-plan-id", planId));
        var applied = run("", applyArgs.toArray(new String[0]));
        assertEquals(0, applied.exit(), applied.stdout() + applied.stderr());
        Path fresh = temp.resolve("fresh");
        assertInstanceOf(ToolchainResult.Success.class, new ToolchainApplication().execute(new ToolchainRequest(candidate, SourceId.of("project.sir"), fresh)));
        assertEquals(snapshot(fresh), snapshot(output)); assertEquals(snapshot(candidate), snapshot(source));
        assertTrue(Files.isDirectory(repo.resolve(".git")));
    }

    /** The draft holds what uses expand into; inspect says which declarations those are and which use they come from. */
    @Test void inspectNamesTheDeclarationsAUseExpandedInto() throws Exception {
        Path source = Files.createDirectory(temp.resolve("sir"));
        Files.writeString(source.resolve("project.sir"), resource("crud-feature.sir"));
        var inspected = run("", sourceArgs("inspect", source)); assertEquals(0, inspected.exit(), inspected.stdout());
        var expanded = (List<?>) obj(StrictJson.parse(inspected.stdout())).get("expandedDeclarations");
        assertTrue(expanded.contains(Map.of("kind", "NormalizedCapability", "name", "CreateNote", "feature", "Crud",
            "arguments", Map.of("entity", "Note"), "role", "CreateNote")), expanded.toString());
        assertTrue(expanded.contains(Map.of("kind", "NormalizedEntityExtension", "name", "User", "feature", "DualTokenLogin",
            "arguments", Map.of("account", "User"), "role", "User")), expanded.toString());
        // Written declarations are not listed: the entities, and the uses themselves.
        assertTrue(expanded.stream().map(e -> obj(e).get("name")).noneMatch(n -> n.equals("Note") || n.equals("Crud")), expanded.toString());
        // Each of the four Crud uses: 5 capabilities, a view, 4 inputs and 2 errors, one more (the stale version) for the
        // versioned Note. The login: 3 entities, 2 views, 2 inputs, 3 errors, 3 capabilities and the extension of User.
        assertEquals(4 * 12 + 1 + 14, expanded.size(), expanded.toString());
    }

    @Test void commodityUsesTheSameProtocolAndErrorsNeverTouchSources() throws Exception {
        Path source = Files.createDirectory(temp.resolve("sir"));
        Files.writeString(source.resolve("project.sir"), resource("campus-market.sir"));
        var before = snapshot(source);
        var inspected = run("", sourceArgs("inspect", source)); assertEquals(0, inspected.exit(), inspected.stdout());
        var info = obj(StrictJson.parse(inspected.stdout()));
        Map<String, Object> newError = Map.of("type", "child", "kind", "NormalizedError", "slots", Map.of("name", text("UnusedNewError"), "httpStatus", text("409")));
        String commands = batch(info, List.of(Map.of("op", "add", "container", selector(slot("declarations")), "value", newError)));
        Path candidate = temp.resolve("candidate");
        assertEquals(0, run(commands, sourceArgs("edit", source, "--candidate-root", candidate.toString())).exit());
        assertEquals(before, snapshot(source));
        var conflict = run(commands, sourceArgs("edit", source, "--candidate-root", candidate.toString()));
        assertEquals(3, conflict.exit()); assertEquals(before, snapshot(source));
        Path bad = temp.resolve("bad");
        var invalid = run(commands.replace("\"op\":\"add\"", "\"op\":\"add\",\"bogus\":true"), sourceArgs("edit", source, "--candidate-root", bad.toString()));
        assertEquals(2, invalid.exit(), invalid.stdout()); assertFalse(Files.exists(bad));
        var stale = run(commands.replace((String) info.get("baselineSha256"), "0".repeat(64)), sourceArgs("edit", source, "--candidate-root", bad.toString()));
        assertEquals(3, stale.exit(), stale.stdout()); assertTrue(stale.stdout().contains("STALE_BASELINE"));
        assertFalse(Files.exists(bad)); assertEquals(before, snapshot(source));
    }

    @Test void generationRefusesExistingRootsAndMalformedArgumentsHaveDefinedExits() throws Exception {
        Path source = Files.createDirectory(temp.resolve("sir"));
        Files.writeString(source.resolve("project.sir"), resource("campus-market.sir"));
        Path existing = Files.createDirectory(temp.resolve("existing")); Files.writeString(existing.resolve("marker"), "keep");
        assertEquals(3, run("", sourceArgs("generate", source, "--output-root", existing.toString())).exit());
        assertEquals("keep", Files.readString(existing.resolve("marker")));
        for (String command : List.of("generate", "inspect", "edit")) {
            assertEquals(0, run("", command, "--help").exit());
            assertEquals(2, run("", command, "--source-root", "relative", "--entry", "project.sir").exit());
        }
    }
}
