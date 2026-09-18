package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Launches and controls a Spring Boot application process from the generated
 * executable jar. The harness does not edit generated sources, add
 * {@code application.yml}, inject host Filter/Interceptor, or modify the
 * generated POM.
 *
 * <p>Shutdown sends the defined graceful termination, waits for exit, then
 * uses a bounded forced termination if needed. Failure to prove the process
 * exited is {@code FAILED(CLEANUP_PROCESS_REMAINS)}.
 */
public final class SpringApplicationProcess {

    private final Path javaExecutable;
    private final Map<String, String> environment;
    private final StreamingSecretRedactor redactor;
    private Process process;

    public SpringApplicationProcess(Path javaExecutable, Map<String, String> environment,
                                    StreamingSecretRedactor redactor) {
        this.javaExecutable = Objects.requireNonNull(javaExecutable, "javaExecutable");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    /**
     * Start the Spring application from the generated jar.
     *
     * @param jarPath    the executable jar path
     * @param serverPort the loopback port
     * @return the process (started)
     */
    public synchronized Process start(Path jarPath, int serverPort) throws IOException {
        return start(jarPath, serverPort, null, null);
    }

    /**
     * Start the Spring application, streaming both output streams through the
     * redactor into caller-supplied evidence streams.
     *
     * <p>The conformance harness uses this overload so the application's stdout and
     * stderr become redacted evidence while it runs, rather than only after it exits.
     * Both streams are drained on daemon threads, so an application that logs heavily
     * cannot block on a full pipe buffer.
     *
     * @param jarPath    the executable jar path
     * @param serverPort the loopback port
     * @param stdout     the destination for redacted stdout, or {@code null} to leave
     *                   the stream undrained (the two-argument behavior)
     * @param stderr     the destination for redacted stderr, or {@code null}
     * @return the started process
     */
    public synchronized Process start(Path jarPath, int serverPort,
                                      OutputStream stdout, OutputStream stderr) throws IOException {
        Objects.requireNonNull(jarPath, "jarPath");
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("process already running");
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dserver.port=" + serverPort);
        command.add("-Dserver.address=127.0.0.1");
        command.add("-jar");
        command.add(jarPath.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().clear();
        pb.environment().putAll(environment);
        pb.redirectErrorStream(false);
        process = pb.start();
        if (stdout != null) {
            pump(process.getInputStream(), redactor.wrap(stdout));
        }
        if (stderr != null) {
            pump(process.getErrorStream(), redactor.wrap(stderr));
        }
        return process;
    }

    private static void pump(InputStream source, OutputStream destination) {
        Thread thread = new Thread(() -> {
            try (InputStream in = source; OutputStream out = destination) {
                in.transferTo(out);
                out.flush();
            } catch (IOException ignored) {
                // A closed evidence stream must not fail the run; process exit proof and
                // the evidence scan remain the authorities.
            }
        }, "kcg-conformance-spring-pump");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Gracefully stop the process: destroy, wait up to
     * {@code graceMillis}, then destroyForcibly if needed.
     *
     * @return true if the process exited within the grace period
     */
    public synchronized boolean stop(long graceMillis) throws InterruptedException {
        if (process == null || !process.isAlive()) {
            return true;
        }
        process.destroy();
        if (process.waitFor(graceMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return true;
        }
        process.destroyForcibly();
        return process.waitFor(graceMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * @return true if the process is null or has exited.
     */
    public synchronized boolean isStopped() {
        return process == null || !process.isAlive();
    }

    /**
     * @return the process exit code, or -1 if still running.
     */
    public synchronized int exitCode() {
        if (process == null || process.isAlive()) {
            return -1;
        }
        return process.exitValue();
    }

    public synchronized Process process() {
        return process;
    }

    public StreamingSecretRedactor redactor() {
        return redactor;
    }
}