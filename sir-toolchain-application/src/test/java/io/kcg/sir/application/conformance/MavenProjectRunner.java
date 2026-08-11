package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs Maven builds on generated project trees. Each distinct generated tree
 * runs {@code <maven> -B -ntp -Dmaven.repo.local=<repo> clean verify}. At
 * least one repeated build uses offline mode.
 *
 * <p>Maven success does not imply Context, HTTP, or database success.
 * stdout/stderr are captured for sanitized evidence.
 */
public final class MavenProjectRunner {

    private final Path mavenExecutable;
    private final Path isolatedRepo;
    private final Map<String, String> environment;
    private final StreamingSecretRedactor redactor;

    public MavenProjectRunner(Path mavenExecutable, Path isolatedRepo,
                              Map<String, String> environment,
                              StreamingSecretRedactor redactor) {
        this.mavenExecutable = Objects.requireNonNull(mavenExecutable, "mavenExecutable");
        this.isolatedRepo = Objects.requireNonNull(isolatedRepo, "isolatedRepo");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    /**
     * Run {@code clean verify} on the given project directory.
     *
     * @param projectDir the generated project root
     * @param offline    whether to use Maven offline mode
     * @return the build result (exit code + sanitized logs)
     */
    public BuildResult cleanVerify(Path projectDir, boolean offline) throws IOException, InterruptedException {
        Objects.requireNonNull(projectDir, "projectDir");
        List<String> command = new ArrayList<>();
        command.add(mavenExecutable.toString());
        command.add("-B");
        command.add("-ntp");
        command.add("-Dmaven.repo.local=" + isolatedRepo);
        if (offline) {
            command.add("-o");
        }
        command.add("clean");
        command.add("verify");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir.toFile());
        pb.environment().clear();
        pb.environment().putAll(environment);
        pb.redirectErrorStream(false);

        Process process = pb.start();
        byte[] stdoutBytes = readAll(process.getInputStream());
        byte[] stderrBytes = readAll(process.getErrorStream());
        int exitCode = process.waitFor();

        byte[] sanitizedStdout = redactor.redactBytes(stdoutBytes);
        byte[] sanitizedStderr = redactor.redactBytes(stderrBytes);

        return new BuildResult(exitCode, sanitizedStdout, sanitizedStderr, command);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    /**
     * @return the Maven executable version string (e.g. "Apache Maven 3.9.x").
     */
    public String mavenVersion() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(mavenExecutable.toString(), "-v");
        pb.environment().clear();
        pb.environment().putAll(environment);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] output = readAll(process.getInputStream());
        process.waitFor();
        return redactor.redactString(new String(output, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * One Maven build result.
     *
     * @param exitCode         process exit code (0 = success)
     * @param sanitizedStdout  sanitized stdout bytes
     * @param sanitizedStderr  sanitized stderr bytes
     * @param command          the command (sanitized; no credentials)
     */
    public record BuildResult(int exitCode, byte[] sanitizedStdout,
                              byte[] sanitizedStderr, List<String> command) {
        public BuildResult {
            sanitizedStdout = sanitizedStdout.clone();
            sanitizedStderr = sanitizedStderr.clone();
            command = List.copyOf(command);
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}