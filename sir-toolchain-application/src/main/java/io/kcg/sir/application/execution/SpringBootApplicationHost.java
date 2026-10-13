package io.kcg.sir.application.execution;

import io.kcg.sir.application.api.CleanupProof;
import io.kcg.sir.application.api.LogRef;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.StageOutcome;
import io.kcg.sir.application.api.VerificationPorts;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Starts a built Spring Boot jar on a loopback port and waits until a route the caller names answers 200.
 *
 * <p>An open port or a log line is not readiness: only a real answer from the application's own route is.
 * The application runs from an empty environment plus exactly the variables the caller passes (the isolated
 * database's), with a heap and metaspace limit. A handle is returned for every process that was started,
 * including one that failed to become ready, so the caller can always stop it and collect its finished log.
 */
public final class SpringBootApplicationHost implements VerificationPorts.Host {
    private static final Duration POLL = Duration.ofMillis(250);

    private final Path java;
    private final int port;
    private final String readinessPath;
    private final LogRedactor redactor;

    public SpringBootApplicationHost(Path javaExecutable, int port, String readinessPath, Collection<String> secrets) {
        this.java = Objects.requireNonNull(javaExecutable, "javaExecutable").toAbsolutePath().normalize();
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("port must be an unprivileged port");
        }

        this.port = port;
        if (readinessPath == null || !readinessPath.startsWith("/") || readinessPath.contains(" ")) {
            throw new IllegalArgumentException("readinessPath must be an absolute path such as /api/items?page=1&size=1");
        }

        this.readinessPath = readinessPath;
        this.redactor = new LogRedactor(secrets);
    }

    @Override
    public Map<String, String> environment() {
        Map<String, String> facts = new TreeMap<>();
        facts.put("java.version", System.getProperty("java.version"));
        facts.put("port", Integer.toString(port));
        facts.put("readiness.path", readinessPath);
        return facts;
    }

    @Override
    public VerificationPorts.StartOutcome start(Path artifact, Map<String, String> environment, Path evidenceRoot, ResourceLimits limits) {
        if (!Files.isRegularFile(java) || !Files.isExecutable(java)) {
            return refused(StageOutcome.notRun("START_TOOL_UNAVAILABLE", "the Java executable is missing or not executable"));
        }

        if (accepting(port)) {
            return refused(StageOutcome.notRun("START_PORT_IN_USE", "something already accepts connections on port " + port));
        }

        Process process = null;
        ProcessLog log = null;
        Thread pump = null;
        try {
            List<String> command = List.of(java.toString(), "-Xmx" + limits.applicationHeapMegabytes() + "m",
                    "-XX:MaxMetaspaceSize=" + limits.metaspaceMegabytes() + "m", "-Dserver.port=" + port, "-Dserver.address=127.0.0.1",
                    "-jar", artifact.toString());
            log = new ProcessLog(evidenceRoot.resolve("logs/application.log"), "logs/application.log", redactor, limits.maxLogBytes());
            process = ProcessSupport.start(command, artifact.getParent(), ProcessSupport.baseEnvironment(environment));
            pump = ProcessSupport.pumpInBackground(process, log);
        } catch (IOException e) {
            return refused(StageOutcome.failed("START_IO_ERROR", "the application could not be started (" + e.getClass().getSimpleName() + ")"));
        }

        Handle handle = new Handle(process, pump, log, port);
        StageOutcome outcome = awaitReady(process, limits.startTimeout());
        return new VerificationPorts.StartOutcome(outcome, Optional.of(handle));
    }

    private StageOutcome awaitReady(Process process, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + readinessPath)).timeout(Duration.ofSeconds(2)).GET().build();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    return StageOutcome.failed("START_PROCESS_EXITED", "the application exited with status " + process.exitValue() + " before it was ready");
                }

                try {
                    if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                        return StageOutcome.passed("the application answered 200 on " + readinessPath);
                    }
                } catch (IOException notYet) {
                    // Not listening yet or not answering yet; keep waiting until the deadline.
                }

                Thread.sleep(POLL.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StageOutcome.failed("START_INTERRUPTED", "waiting for the application was interrupted");
        }

        return StageOutcome.failed("START_TIMEOUT", "the application did not answer 200 on " + readinessPath + " within " + timeout.toSeconds() + " seconds");
    }

    private static VerificationPorts.StartOutcome refused(StageOutcome outcome) {
        return new VerificationPorts.StartOutcome(outcome, Optional.empty());
    }

    private static boolean accepting(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static final class Handle implements VerificationPorts.Running {
        private final Process process;
        private final Thread pump;
        private final ProcessLog log;
        private final int port;
        private VerificationPorts.Stopped stopped;

        Handle(Process process, Thread pump, ProcessLog log, int port) {
            this.process = process;
            this.pump = pump;
            this.log = log;
            this.port = port;
        }

        @Override
        public URI baseUrl() {
            return URI.create("http://127.0.0.1:" + port);
        }

        @Override
        public synchronized VerificationPorts.Stopped stop() {
            if (stopped != null) {
                return stopped;
            }

            List<String> checks = new ArrayList<>();
            boolean proved = true;
            try {
                boolean wasAlive = process.isAlive();
                boolean gone = !wasAlive || ProcessSupport.terminateTree(process);
                proved &= gone;
                checks.add(gone ? "application process " + (wasAlive ? "ended" : "had already exited") + " (status " + (process.isAlive() ? "?" : process.exitValue()) + ")"
                        : "application process tree could not be proved ended");
                ProcessSupport.endPump(process, pump);
                boolean closed = !accepting(port);
                proved &= closed;
                checks.add(closed ? "port " + port + " no longer accepts connections" : "port " + port + " still accepts connections");
                LogRef ref = log.finish(LogRef.Kind.APPLICATION);
                stopped = new VerificationPorts.Stopped(new CleanupProof(proved, checks), List.of(ref));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = new VerificationPorts.Stopped(CleanupProof.notProved("application stop was interrupted"), List.of());
            } catch (IOException e) {
                stopped = new VerificationPorts.Stopped(new CleanupProof(false, checks), List.of());
            }

            return stopped;
        }
    }
}
