package io.kcg.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The CLI must never let an internal failure escape as an unhandled exception.
 *
 * <p>Run in-process rather than in a child JVM, because the crash is injected through a static hook
 * that cannot cross a process boundary. The interesting part is not that the exit code is 70, but
 * that the caller still receives the canonical document: a stack trace on stderr with no JSON on
 * stdout would leave a script with nothing to parse.
 */
class CliInternalFailureTest {

    @AfterEach
    void clearCrashInjection() {
        KcgCli.crashInjection = null;
    }

    @Test
    void injectedCrashStillProducesTheCanonicalDocumentAndExitCode70() {
        KcgCli.crashInjection = new IllegalStateException("injected crash for the boundary test");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            exit = KcgCli.run(new String[] {"--help"});
        } finally {
            System.setOut(originalOut);
        }

        String stdout = captured.toString(StandardCharsets.UTF_8);
        assertNotNull(stdout, "the crash path must still write something to stdout");
        assertEquals(70, exit, () -> "an internal failure must exit with 70, got " + exit
                + " stdout=" + stdout);
        assertTrue(stdout.contains("KCG-CLI-INTERNAL-001"),
                () -> "the crash must be reported as a structured diagnostic: " + stdout);
        assertTrue(stdout.startsWith("{\"protocolVersion\""),
                () -> "the crash document must still be canonical JSON: " + stdout);
        assertEquals(stdout.indexOf('\n'), stdout.length() - 1,
                "the crash document must be a single line ending in one newline");
        assertFalse(stdout.contains("injected crash for the boundary test"),
                () -> "the internal message must not leak into the canonical document: " + stdout);
    }

    @Test
    void successPathIsUnaffectedByTheClearedHook() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            exit = KcgCli.run(new String[] {"--version"});
        } finally {
            System.setOut(originalOut);
        }
        assertEquals(CliExit.OK, exit, "the version command must still succeed");
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains(KcgCli.VERSION),
                "the version command must print the version string");
    }
}
