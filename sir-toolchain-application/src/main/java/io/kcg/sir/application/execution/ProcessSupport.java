package io.kcg.sir.application.execution;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Starting a child process from an empty environment, bounding it in time, and ending its whole tree. */
final class ProcessSupport {
    /** The only variables copied from the host: where to find tools, never credentials or proxy settings. */
    static final Set<String> HOST_ALLOWLIST = Set.of("PATH");
    static final Duration GRACE = Duration.ofSeconds(3);
    static final Duration PUMP_JOIN = Duration.ofSeconds(5);

    private ProcessSupport() {}

    /** A fresh environment: a short host allowlist, the current JDK, deterministic locale and time zone, then the caller's own. */
    static Map<String, String> baseEnvironment(Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : HOST_ALLOWLIST) {
            String value = System.getenv(name);
            if (value != null && !value.isEmpty()) {
                env.put(name, value);
            }
        }

        env.put("JAVA_HOME", System.getProperty("java.home"));
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("TZ", "UTC");
        env.putAll(extra);
        return env;
    }

    record Finished(boolean timedOut, int exitCode, boolean treeGone) {}

    /** Starts the process with merged output going to the log, and waits at most {@code timeout}. */
    static Finished run(List<String> command, java.nio.file.Path directory, Map<String, String> environment, Duration timeout, ProcessLog log)
            throws IOException, InterruptedException {
        Process process = start(command, directory, environment);
        Thread pump = pumpInBackground(process, log);
        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        boolean gone = true;
        if (!exited) {
            gone = terminateTree(process);
        }

        endPump(process, pump);
        return new Finished(!exited, exited ? process.exitValue() : -1, gone);
    }

    static Process start(List<String> command, java.nio.file.Path directory, Map<String, String> environment) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        return builder.start();
    }

    static Thread pumpInBackground(Process process, ProcessLog log) {
        Thread pump = new Thread(() -> log.pump(process.getInputStream()), "kcg-verification-log");
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    /** A descendant that keeps the pipe open must not hold the pump forever. */
    static void endPump(Process process, Thread pump) throws InterruptedException {
        pump.join(PUMP_JOIN.toMillis());
        if (pump.isAlive()) {
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
                // Closing is only to release the reader.
            }

            pump.join(PUMP_JOIN.toMillis());
        }
    }

    /**
     * Ends the process and everything it started: polite first, then forced, and then checks that nothing is left.
     * A process that has already exited has no tree to list, so this is for live processes only.
     *
     * @return true if the process and all of its descendants are proved gone
     */
    static boolean terminateTree(Process process) throws InterruptedException {
        List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
        tree.add(process.toHandle());
        tree.forEach(ProcessHandle::destroy);
        waitForExit(tree, GRACE);
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        waitForExit(tree, GRACE);
        return tree.stream().noneMatch(ProcessHandle::isAlive);
    }

    private static void waitForExit(List<ProcessHandle> handles, Duration total) throws InterruptedException {
        long deadline = System.nanoTime() + total.toNanos();
        for (ProcessHandle handle : handles) {
            while (handle.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }
    }

    /** The first lines marked as errors in a finished log, to explain a failed build without exposing the whole log. */
    static List<String> errorLines(java.nio.file.Path logFile, int limit) {
        List<String> lines = new ArrayList<>();
        try (var stream = java.nio.file.Files.lines(logFile, java.nio.charset.StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) stream::iterator) {
                if (line.startsWith("[ERROR]") && lines.size() < limit) {
                    lines.add(line.length() > 300 ? line.substring(0, 300) : line);
                }
            }
        } catch (IOException | java.io.UncheckedIOException ignored) {
            // The log is only an explanation; the exit code is the verdict.
        }

        return lines;
    }
}
