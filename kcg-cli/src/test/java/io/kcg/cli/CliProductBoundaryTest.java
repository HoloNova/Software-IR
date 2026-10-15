package io.kcg.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.cli.CliTestFixtures.ProcResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CLI product boundary, as evidence rather than as documentation.
 *
 * <p>Three claims are tested here, and each is the kind of claim that quietly rots if it is only
 * written down:
 *
 * <ol>
 *   <li><b>Only the published commands exist.</b> {@code generate}, {@code inspect}, {@code edit}, {@code check}, {@code version-plan} and
 *       {@code version-apply} are the whole surface. The commands ADR-019 proposed and the
 *       retired Change planning commands ({@code context}, {@code plan}) must be refused as usage
 *       errors, must leave any directory they are pointed at byte-for-byte unchanged, and must not
 *       appear in the help text.</li>
 *   <li><b>Every command surface has a defined exit code.</b> Help and version succeed; missing,
 *       relative or unknown arguments are usage errors.</li>
 *   <li><b>Output is canonical JSON.</b> Errors are single-line JSON, and the same input produces
 *       byte-identical stdout.</li>
 * </ol>
 *
 * <p>Exit codes are asserted on a real child process, because that is what a caller observes.
 */
class CliProductBoundaryTest {

    /** Commands that are not published: ADR-019's proposals, and the retired Change planning commands. */
    private static final List<String> UNPUBLISHED_COMMANDS =
            List.of("register", "apply", "recover", "context", "plan");

    @TempDir
    Path tempDir;

    /** Each unpublished command is refused, changes nothing on disk, and is absent from the help. */
    @Test
    void unpublishedCommandsAreRefusedAndChangeNothing() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(project.resolve("marker.txt"), "untouched");
        TreeMap<String, String> before = snapshot(project);
        String help = CliTestFixtures.runCli("--help").stdout();

        for (String command : UNPUBLISHED_COMMANDS) {
            ProcResult result = CliTestFixtures.runCli(command,
                    "--state-root", project.toString(),
                    "--output-root", project.toString(),
                    "--candidate-sir", project.toString());

            assertEquals(2, result.exit(),
                    () -> "an unpublished command must be a usage error: " + command
                            + "\n" + result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("\"KCG-CLI-USAGE-"),
                    () -> "the refusal must carry a usage diagnostic code: " + command
                            + "\n" + result.stdout());
            assertTrue(result.stdout().contains("unknown command"),
                    () -> "the refusal must say the command is unknown: " + command + "\n"
                            + result.stdout());
            assertTrue(help.lines().noneMatch(line -> line.startsWith("  " + command + " ")),
                    () -> "the help text must not advertise " + command + "\n" + help);
            assertEquals(before, snapshot(project),
                    () -> "a refused command must not touch the directory it is pointed at: " + command);
        }
    }

    /** The published surface answers help and version successfully. */
    @Test
    void helpAndVersionSucceedAndListOnlyPublishedCommands() throws Exception {
        ProcResult topHelp = CliTestFixtures.runCli("--help");
        assertEquals(0, topHelp.exit(), () -> "help must succeed: " + topHelp.stderr());
        for (String published : List.of("generate", "inspect", "edit", "check", "version-plan", "version-apply")) {
            assertTrue(topHelp.stdout().lines().anyMatch(line -> line.startsWith("  " + published + " ")),
                    "top help must list " + published);
        }

        ProcResult shortHelp = CliTestFixtures.runCli("-h");
        assertEquals(0, shortHelp.exit(), "the -h alias must succeed");
        assertEquals(topHelp.stdout(), shortHelp.stdout(),
                "the -h alias must print the same help as --help");

        ProcResult version = CliTestFixtures.runCli("--version");
        assertEquals(0, version.exit(), "version must succeed");
        assertTrue(version.stdout().contains(KcgCli.VERSION),
                () -> "version output must carry the published version string: " + version.stdout());
        assertEquals(version.stdout(), CliTestFixtures.runCli("-V").stdout(),
                "the -V alias must print the same version as --version");
    }

    /** Argument mistakes are usage errors rather than crashes or silent successes. */
    @Test
    void argumentMistakesAreUsageErrors() throws Exception {
        List<List<String>> mistaken = new ArrayList<>();
        mistaken.add(List.of());
        mistaken.add(List.of("not-a-command"));
        mistaken.add(List.of("version-plan"));
        mistaken.add(List.of("version-plan", "--repository-root", tempDir.toString()));
        mistaken.add(List.of("version-apply", "--output-root", tempDir.toString()));
        mistaken.add(List.of("check", "--input", "file"));

        for (List<String> args : mistaken) {
            ProcResult result = CliTestFixtures.runCli(args.toArray(new String[0]));
            assertEquals(2, result.exit(),
                    () -> "expected a usage error for " + args + "\n" + result.stdout()
                            + result.stderr());
            assertTrue(result.stdout().startsWith("{"),
                    () -> "a usage error must still print the canonical document: " + args);
            assertTrue(result.stdout().contains("\"outcome\":\"USAGE_ERROR\""),
                    () -> "a usage error must be reported as USAGE_ERROR: " + args + "\n"
                            + result.stdout());
        }
    }

    /**
     * Usage errors are deterministic too.
     *
     * <p>This is a regression test for a defect found earlier: the parsers collected their known
     * options in a {@code Map.of}, whose iteration order is not specified, so two runs of the same
     * incomplete command line reported <em>different</em> missing options. A diagnostic that
     * changes between runs is not canonical output.
     */
    @Test
    void usageErrorsAreDeterministicAcrossProcesses() throws Exception {
        String[] incomplete = {"version-plan",
                "--repository-root", tempDir.toString(),
                "--source-root", tempDir.toString(),
                "--entry", "project.sir"};

        String first = CliTestFixtures.runCli(incomplete).stdout();
        String second = CliTestFixtures.runCli(incomplete).stdout();
        String third = CliTestFixtures.runCli(incomplete).stdout();

        assertEquals(first, second, "the same incomplete command must report the same option");
        assertEquals(second, third, "the same incomplete command must report the same option");
        assertTrue(first.contains("missing required option:"),
                () -> "an incomplete command must report a missing option: " + first);
    }

    /** Usage errors are canonical single-line JSON too, not free-form text. */
    @Test
    void usageErrorsAreCanonicalJson() throws Exception {
        ProcResult result = CliTestFixtures.runCli("nope");
        assertEquals(2, result.exit(), "an unknown command must be a usage error");
        assertEquals(result.stdout().indexOf('\n'), result.stdout().length() - 1,
                "a usage error must be a single line ending in one newline");
        assertTrue(result.stdout().contains("\"protocolVersion\":\"KCG-CLI-VERSION-CHANGE-V1\""),
                "a usage error must carry the protocol version");
        assertFalse(result.stdout().isEmpty());
        assertTrue(result.stdout().contains("\"diagnostics\":["),
                "a usage error must carry diagnostics");
    }

    /**
     * @param root a directory
     * @return every regular file under it as relative path to hex-encoded content
     */
    private static TreeMap<String, String> snapshot(Path root) throws IOException {
        TreeMap<String, String> files = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path)) {
                    files.put(root.relativize(path).toString().replace('\\', '/'),
                            java.util.HexFormat.of().formatHex(Files.readAllBytes(path)));
                }
            }
        }
        return files;
    }
}
