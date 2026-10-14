package io.kcg.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** Runs the command line as a real child process, so that tests see what a caller sees. */
public final class CliTestFixtures {

    private CliTestFixtures() {
    }

    /**
     * One finished CLI invocation.
     *
     * @param exit   the process exit code, which is what a caller actually observes
     * @param stdout the process standard output
     * @param stderr the process standard error
     */
    public record ProcResult(int exit, String stdout, String stderr) {
    }

    /**
     * Run the CLI in a child JVM, so the exit code is the real process exit code.
     *
     * <p>The product contract is about what a caller observes — the exit code and the bytes on
     * stdout — and only a real process shows both. In-process calls ({@code KcgCli.run}) are used
     * separately for cases that need to inject a crash, which cannot cross a process boundary.
     *
     * @param args the CLI arguments
     * @return the exit code and captured streams
     * @throws Exception if the child JVM cannot be started or does not finish in time
     */
    static ProcResult runCli(String... args) throws Exception {
        String javaHome = System.getProperty("java.home");
        String javaExe = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        Path javaPath = Path.of(javaHome, "bin", javaExe);
        String classpath = System.getProperty("java.class.path");
        Objects.requireNonNull(classpath, "java.class.path");
        String[] command = new String[args.length + 4];
        command[0] = javaPath.toString();
        command[1] = "-cp";
        command[2] = classpath;
        command[3] = "io.kcg.cli.KcgCli";
        System.arraycopy(args, 0, command, 4, args.length);
        Process process = new ProcessBuilder(command).start();
        String stdout = readAll(process.getInputStream());
        String stderr = readAll(process.getErrorStream());
        if (!process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("CLI child process timed out");
        }
        return new ProcResult(process.exitValue(), stdout, stderr);
    }

    private static String readAll(java.io.InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
