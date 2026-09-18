package io.kcg.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 *   <li><b>Only two commands are published.</b> {@code generate}, {@code register}, {@code apply}
 *       and {@code recover} must be refused as usage errors, must leave both the state root and the
 *       output root byte-for-byte unchanged, and must not appear in the help text.</li>
 *   <li><b>Every command surface has a defined exit code.</b> Help and version succeed; missing,
 *       relative or unknown arguments are usage errors.</li>
 *   <li><b>Output is canonical JSON.</b> The same input produces byte-identical stdout, and errors
 *       are single-line JSON too.</li>
 * </ol>
 *
 * <p>Exit codes are asserted on a real child process, because that is what a caller observes.
 */
class CliProductBoundaryTest {

    /** The commands ADR-019 proposes but which are not published today. */
    private static final List<String> UNPUBLISHED_COMMANDS =
            List.of("generate", "register", "apply", "recover");

    @TempDir
    Path tempDir;

    /**
     * Each unpublished command is refused, changes nothing on disk, and is absent from the help.
     *
     * <p>The tree comparison is the part that matters: a command could be wired up but still fail,
     * and a failing command that already wrote something would be worse than an unknown one. Both
     * the state root and the output root are compared, because a partially implemented command could
     * touch either.
     */
    @Test
    void unpublishedCommandsAreRefusedAndChangeNothing() throws Exception {
        CliTestFixtures.RegisteredBase base = CliTestFixtures.registerBase(tempDir,
                "campus-market.sir", "build");
        Path candidate = CliTestFixtures.writeSir(tempDir, "candidate.sir",
                "campus-market-candidate.sir");
        TreeMap<String, String> stateBefore = snapshot(base.stateRoot());
        TreeMap<String, String> outputBefore = snapshot(base.outputRoot());
        String help = CliTestFixtures.runCli("--help").stdout();

        for (String command : UNPUBLISHED_COMMANDS) {
            ProcResult result = CliTestFixtures.runCli(command,
                    "--state-root", base.stateRoot().toString(),
                    "--output-root", base.outputRoot().toString(),
                    "--candidate-sir", candidate.toString());

            assertEquals(2, result.exit(),
                    () -> "an unpublished command must be a usage error: " + command
                            + "\n" + result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("\"KCG-CLI-USAGE-"),
                    () -> "the refusal must carry a usage diagnostic code: " + command
                            + "\n" + result.stdout());
            assertTrue(result.stdout().contains("unknown command"),
                    () -> "the refusal must say the command is unknown: " + command + "\n"
                            + result.stdout());
            assertFalse(help.contains(" " + command),
                    () -> "the help text must not advertise " + command + "\n" + help);
            assertEquals(stateBefore, snapshot(base.stateRoot()),
                    () -> "a refused command must not touch the state root: " + command);
            assertEquals(outputBefore, snapshot(base.outputRoot()),
                    () -> "a refused command must not touch the output root: " + command);
        }
    }

    /** The published surface answers help and version successfully. */
    @Test
    void helpAndVersionSucceedAndListOnlyPublishedCommands() throws Exception {
        ProcResult topHelp = CliTestFixtures.runCli("--help");
        assertEquals(0, topHelp.exit(), () -> "help must succeed: " + topHelp.stderr());
        assertTrue(topHelp.stdout().contains("context"), "top help must list the context command");
        assertTrue(topHelp.stdout().contains("plan"), "top help must list the plan command");
        for (String command : UNPUBLISHED_COMMANDS) {
            assertFalse(topHelp.stdout().contains(" " + command),
                    "top help must not list the unpublished command " + command);
        }

        ProcResult shortHelp = CliTestFixtures.runCli("-h");
        assertEquals(0, shortHelp.exit(), "the -h alias must succeed");
        assertEquals(topHelp.stdout(), shortHelp.stdout(),
                "the -h alias must print the same help as --help");

        for (String command : List.of("context", "plan")) {
            ProcResult commandHelp = CliTestFixtures.runCli(command, "--help");
            assertEquals(0, commandHelp.exit(),
                    () -> command + " --help must succeed: " + commandHelp.stderr());
            assertTrue(commandHelp.stdout().contains("--state-root"),
                    () -> command + " help must document --state-root");
            assertTrue(commandHelp.stdout().contains("--output-root"),
                    () -> command + " help must document --output-root");
        }

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
        CliTestFixtures.RegisteredBase base = CliTestFixtures.registerBase(tempDir,
                "campus-market.sir", "build");
        Path candidate = CliTestFixtures.writeSir(tempDir, "candidate.sir",
                "campus-market-candidate.sir");

        List<List<String>> mistaken = new ArrayList<>();
        mistaken.add(List.of());
        mistaken.add(List.of("not-a-command"));
        mistaken.add(List.of("context"));
        mistaken.add(List.of("context", "--state-root", base.stateRoot().toString()));
        mistaken.add(List.of("plan", "--output-root", base.outputRoot().toString()));
        mistaken.add(List.of("context",
                "--state-root", "relative/state",
                "--output-root", base.outputRoot().toString(),
                "--candidate-sir", candidate.toString()));
        mistaken.add(List.of("plan",
                "--state-root", base.stateRoot().toString(),
                "--output-root", base.outputRoot().toString(),
                "--candidate-sir", "relative/candidate.sir"));

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
     * The same input produces byte-identical stdout, which is what "canonical" has to mean.
     *
     * <p>Run in two separate child JVMs on purpose: that is the only way to catch output that
     * depends on per-process state such as collection iteration order or hash seeding.
     */
    @Test
    void repeatedRunsProduceByteIdenticalOutput() throws Exception {
        CliTestFixtures.RegisteredBase base = CliTestFixtures.registerBase(tempDir,
                "campus-market.sir", "build");
        Path candidate = CliTestFixtures.writeSir(tempDir, "candidate.sir",
                "campus-market-candidate.sir");

        // The context document supplies the context id and target key that plan requires.
        ProcResult context = CliTestFixtures.runCli("context",
                "--state-root", base.stateRoot().toString(),
                "--output-root", base.outputRoot().toString(),
                "--candidate-sir", candidate.toString());
        assertEquals(0, context.exit(), () -> "context must succeed: " + context.stdout()
                + context.stderr());
        // Reuse the workflow test's document helpers: the operation needs the BASE-side capability
        // workflow target, not merely the first target key in the document.
        String contextId = KcgCliWorkflowTest.js(context.stdout(), "contextId");
        String targetKey = KcgCliWorkflowTest.ftk(context.stdout(), "BASE",
                "CAPABILITY_WORKFLOW", "PublishGoods", null);
        assertNotNull(contextId, "the context document must carry a contextId");

        String[] args = {"plan",
                "--state-root", base.stateRoot().toString(),
                "--output-root", base.outputRoot().toString(),
                "--candidate-sir", candidate.toString(),
                "--expected-context-id", contextId,
                "--target-key", targetKey,
                "--change-ir-version", "V0_1",
                "--operation", "modify-capability-workflow"};

        ProcResult firstRun = CliTestFixtures.runCli(args);
        ProcResult secondRun = CliTestFixtures.runCli(args);
        assertEquals(0, firstRun.exit(), () -> "plan must succeed: " + firstRun.stdout()
                + firstRun.stderr());
        assertEquals(firstRun.stdout(), secondRun.stdout(),
                "two runs of the same command must print identical bytes");
        assertFalse(firstRun.stdout().isEmpty(), "the plan must produce a document");

        // A stable document starts with the protocol version and ends with exactly one newline.
        String first = firstRun.stdout();
        assertTrue(first.startsWith("{\"protocolVersion\":\"KCG-CLI-CHANGE-PLANNING-V1\""),
                () -> "the document must begin with the protocol version: "
                        + first.substring(0, Math.min(120, first.length())));
        assertEquals(first.indexOf('\n'), first.length() - 1,
                "the document must be a single line ending in one newline");
        assertEquals(1, first.lines().count(), "the document must be one line");
    }

    /**
     * Usage errors are deterministic too.
     *
     * <p>This is a regression test for a defect this work order found: the parsers collected their
     * known options in a {@code Map.of}, whose iteration order is not specified, so two runs of the
     * same incomplete command line reported <em>different</em> missing options. A diagnostic that
     * changes between runs is not canonical output.
     */
    @Test
    void usageErrorsAreDeterministicAcrossProcesses() throws Exception {
        String[] incomplete = {"plan",
                "--state-root", tempDir.toString(),
                "--output-root", tempDir.toString(),
                "--candidate-sir", tempDir.toString()};

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
        assertTrue(result.stdout().contains("\"protocolVersion\":\"KCG-CLI-CHANGE-PLANNING-V1\""),
                "a usage error must carry the protocol version");
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
                            hex(Files.readAllBytes(path)));
                }
            }
        }
        return files;
    }

    /**
     * @param bytes content
     * @return the content as lowercase hex, so comparisons are by value rather than by array
     *         identity (comparing {@code byte[]} with {@code equals} compares references)
     */
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

