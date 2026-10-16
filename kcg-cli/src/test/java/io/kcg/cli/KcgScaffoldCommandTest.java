package io.kcg.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * The public {@code scaffold} command as a caller sees it: real child processes, stdout piped on into
 * {@code edit}, and the original sources never touched. What the pattern contains is the scaffold
 * application's test; this is the command line, the batch it prints, and how it refuses.
 */
class KcgScaffoldCommandTest {
    @TempDir Path temp;

    private Path base() throws IOException {
        Path source = Files.createDirectory(temp.resolve("sir"));
        try (var input = KcgScaffoldCommandTest.class.getResourceAsStream("/io/kcg/cli/scaffold-base.sir")) {
            Files.write(source.resolve("project.sir"), input.readAllBytes());
        }
        return source;
    }

    private static String[] scaffoldArgs(Path source, String... extras) {
        var args = new ArrayList<>(List.of("scaffold", "dual-token", "--source-root", source.toString(), "--entry", "project.sir",
            "--account", "User", "--name-field", "name", "--password-field", "passwordHash"));
        args.addAll(List.of(extras));
        return args.toArray(new String[0]);
    }

    private CliTestFixtures.ProcResult run(String input, String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m", "-XX:MaxMetaspaceSize=128m", "-cp", System.getProperty("java.class.path"), "io.kcg.cli.KcgCli"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("CLI timeout"); }
        return new CliTestFixtures.ProcResult(process.exitValue(), stdout, stderr);
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        var files = new TreeMap<String, String>();
        if (Files.exists(root)) try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                files.put(root.relativize(path).toString(), java.util.HexFormat.of().formatHex(Files.readAllBytes(path)));
        }
        return files;
    }

    @Test void stdoutIsOneCommandBatchThatEditAcceptsAndTheSourcesAreUntouched() throws Exception {
        Path source = base();
        var before = snapshot(source);
        var scaffold = run("", scaffoldArgs(source));
        assertEquals(0, scaffold.exit(), scaffold.stdout());
        assertEquals("", scaffold.stderr());
        assertTrue(scaffold.stdout().endsWith("}\n") && scaffold.stdout().indexOf('\n') == scaffold.stdout().length() - 1, "one JSON object on one line");
        var batch = CommandJson.object(StrictJson.parse(scaffold.stdout().strip()));
        assertEquals("KCG-CLI-COMMANDS-V1", batch.get("protocolVersion"));
        assertEquals("DualTokenDesk", batch.get("softwareName"));
        var commands = (List<?>) batch.get("commands");
        assertEquals(13, commands.size());
        assertTrue(commands.stream().allMatch(command -> "add".equals(CommandJson.object(command).get("op"))));
        assertEquals(before, snapshot(source), "scaffold writes nothing");

        Path candidate = temp.resolve("candidate");
        var edit = run(scaffold.stdout(), "edit", "--source-root", source.toString(), "--entry", "project.sir", "--candidate-root", candidate.toString());
        assertEquals(0, edit.exit(), edit.stdout());
        assertTrue(edit.stdout().contains("\"outcome\":\"CANDIDATE_SAVED\""), edit.stdout());
        String saved = Files.readString(candidate.resolve("project.sir"));
        assertTrue(saved.contains("credential subject owner expires expiresAt;"), saved);
        assertTrue(saved.contains("capability Login {") && saved.contains("capability Refresh {") && saved.contains("capability Logout {"), saved);
        assertTrue(saved.contains("now() + 15 minutes") && saved.contains("now() + 7 days"), saved);
        assertEquals(before, snapshot(source), "edit saves a candidate and leaves the source alone");
    }

    @Test void lifetimesAndPrefixAreCarriedIntoTheDeclarations() throws Exception {
        Path source = base();
        var scaffold = run("", scaffoldArgs(source, "--access-minutes", "30", "--refresh-days", "14", "--prefix", "Auth"));
        assertEquals(0, scaffold.exit(), scaffold.stdout());
        Path candidate = temp.resolve("candidate");
        assertEquals(0, run(scaffold.stdout(), "edit", "--source-root", source.toString(), "--entry", "project.sir", "--candidate-root", candidate.toString()).exit());
        String saved = Files.readString(candidate.resolve("project.sir"));
        assertTrue(saved.contains("now() + 30 minutes") && saved.contains("now() + 14 days"), saved);
        assertTrue(saved.contains("entity AuthAccessToken persistent") && saved.contains("capability AuthLogin {") && saved.contains("error AuthBadLogin http 401;"), saved);
    }

    @Test void whatDoesNotFitIsRefusedWithTheAnalysisOwnFindingsAndNotABatch() throws Exception {
        Path source = base();
        var wrongField = run("", "scaffold", "dual-token", "--source-root", source.toString(), "--entry", "project.sir",
            "--account", "User", "--name-field", "name", "--password-field", "nope");
        assertEquals(3, wrongField.exit());
        assertTrue(wrongField.stdout().contains("\"outcome\":\"REFUSED\"") && wrongField.stdout().contains("SIR-"), wrongField.stdout());
        assertFalse(wrongField.stdout().contains("\"commands\""), "a refusal prints no batch");

        Path candidate = temp.resolve("candidate");
        var first = run("", scaffoldArgs(source));
        assertEquals(0, run(first.stdout(), "edit", "--source-root", source.toString(), "--entry", "project.sir", "--candidate-root", candidate.toString()).exit());
        var again = run("", scaffoldArgs(candidate));
        assertEquals(3, again.exit(), "the names are taken now");
        assertTrue(again.stdout().contains("\"outcome\":\"REFUSED\""), again.stdout());

        var ttl = run("", scaffoldArgs(source, "--access-minutes", "0"));
        assertEquals(3, ttl.exit());
        assertTrue(ttl.stdout().contains("--access-minutes"), ttl.stdout());
    }

    @Test void theCommandLineIsStrictAndTheHelpIsThere() throws Exception {
        Path source = base();
        assertEquals(2, run("", "scaffold", "dual-token", "--source-root", source.toString(), "--entry", "project.sir").exit());
        assertEquals(2, run("", "scaffold", "other").exit());
        assertEquals(2, run("", scaffoldArgs(source, "--access-minutes", "soon")).exit());
        assertEquals(2, run("", scaffoldArgs(source, "--prefix", "A", "--prefix", "B")).exit());
        assertEquals(2, run("", scaffoldArgs(source, "--unknown", "x")).exit());
        var help = run("", "scaffold", "--help");
        assertEquals(0, help.exit());
        assertTrue(help.stdout().contains("kcg scaffold dual-token") && help.stdout().contains("--password-field"), help.stdout());
        assertTrue(run("", "--help").stdout().contains("scaffold"));
    }
}
