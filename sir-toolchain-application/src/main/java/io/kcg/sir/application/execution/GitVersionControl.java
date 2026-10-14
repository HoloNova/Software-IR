package io.kcg.sir.application.execution;

import io.kcg.sir.application.api.VersionControl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Runs the {@code git} program to give the product the three things it needs from version history.
 *
 * <p>Why it exists: the product keeps no history of its own, so "the previous version" is whatever Git
 * holds. This adapter is deliberately narrow. It saves only the files it is told to save, in one commit,
 * and leaves everything else in the user's working tree and index alone, so that a user's unrelated or
 * half-staged work is never swept into a snapshot. Paths are always taken literally, so a file name that
 * looks like a pattern or an option cannot change what is saved.
 *
 * <p>The child starts from a short environment: where to find tools, and the user's home so that their own
 * Git identity applies. It never prompts. If no identity is configured at all it uses a fixed fallback for
 * that one commit instead of failing, and it does not touch any Git configuration.
 */
public final class GitVersionControl implements VersionControl {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_OUTPUT_BYTES = 1 << 20;
    private static final int MAX_PATHS = 2000;
    private static final int MAX_REASON = 600;
    private static final String FALLBACK_NAME = "KCG";
    private static final String FALLBACK_EMAIL = "kcg@localhost";

    private final String executable;
    private final Map<String, String> environment;
    private final Duration timeout;

    /** Uses {@code git} from the host's {@code PATH} and the host user's home for their Git identity. */
    public GitVersionControl() {
        this("git", hostEnvironment(), DEFAULT_TIMEOUT);
    }

    /** @param extraEnvironment variables added to the short base environment, for example {@code HOME} */
    public GitVersionControl(String executable, Map<String, String> extraEnvironment, Duration timeout) {
        this.executable = Objects.requireNonNull(executable, "executable");
        Map<String, String> extra = new LinkedHashMap<>(Objects.requireNonNull(extraEnvironment, "extraEnvironment"));
        extra.put("GIT_TERMINAL_PROMPT", "0");
        this.environment = ProcessSupport.baseEnvironment(extra);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    private static Map<String, String> hostEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : List.of("HOME", "XDG_CONFIG_HOME")) {
            String value = System.getenv(name);
            if (value != null && !value.isEmpty()) {
                env.put(name, value);
            }
        }

        return env;
    }

    @Override
    public Status inspect(Path root) {
        Path dir = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return new Status(State.NO_REPOSITORY, "not a directory: " + dir);
        }

        Run version = run(dir, List.of("--version"));
        if (!version.started() || version.exit() != 0) {
            return new Status(State.UNAVAILABLE, version.started() ? "git --version failed: " + reason(version) : "git cannot be started: " + version.output());
        }

        Run top = run(dir, List.of("rev-parse", "--show-toplevel"));
        if (!top.started() || top.timedOut()) {
            return new Status(State.UNAVAILABLE, "git did not answer: " + reason(top));
        }

        if (top.exit() != 0) {
            return new Status(State.NO_REPOSITORY, "no repository at " + dir);
        }

        try {
            Path reported = Path.of(top.output().strip()).toRealPath();
            return reported.equals(dir.toRealPath())
                ? new Status(State.REPOSITORY, "repository at " + dir)
                : new Status(State.NOT_TOP_LEVEL, dir + " is inside the repository at " + reported);
        } catch (IOException | RuntimeException e) {
            return new Status(State.UNAVAILABLE, "cannot compare the repository top level: " + e.getMessage());
        }
    }

    @Override
    public Optional<String> initialize(Path root) {
        Path dir = root.toAbsolutePath().normalize();
        Run init = run(dir, List.of("init", "-q"));
        return init.started() && !init.timedOut() && init.exit() == 0 ? Optional.empty() : Optional.of("git init failed: " + reason(init));
    }

    @Override
    public SnapshotOutcome snapshot(Path root, List<String> paths, String message) {
        Path dir = root.toAbsolutePath().normalize();
        Objects.requireNonNull(message, "message");
        Set<String> wanted = new TreeSet<>();
        for (String path : paths) {
            String refused = refusePath(path);
            if (refused != null) {
                return new Failed(refused);
            }

            wanted.add(path);
        }

        if (wanted.size() > MAX_PATHS) {
            return new Failed("a snapshot of more than " + MAX_PATHS + " files is refused");
        }

        Status status = this.inspect(dir);
        if (status.state() != State.REPOSITORY) {
            return new Failed("no usable repository at " + dir + ": " + status.detail());
        }

        if (wanted.isEmpty()) {
            return new Taken(head(dir), false);
        }

        // What exists is saved as it is; what is gone is saved as removed, but only if Git ever knew it.
        Set<String> commitPaths = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String path : wanted) {
            if (Files.exists(dir.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                commitPaths.add(path);
            } else {
                missing.add(path);
            }
        }

        if (!missing.isEmpty()) {
            Run listed = run(dir, withPaths(List.of("--literal-pathspecs", "ls-files", "-z"), missing));
            if (!listed.started() || listed.timedOut() || listed.exit() != 0) {
                return new Failed("git ls-files failed: " + reason(listed));
            }

            for (String known : listed.output().split("\0")) {
                if (!known.isEmpty() && missing.contains(known)) {
                    commitPaths.add(known);
                }
            }
        }

        if (commitPaths.isEmpty()) {
            return new Taken(head(dir), false);
        }

        Run added = run(dir, withPaths(List.of("--literal-pathspecs", "add", "-A"), commitPaths));
        if (!added.started() || added.timedOut() || added.exit() != 0) {
            return new Failed("git add failed: " + reason(added));
        }

        Run staged = run(dir, withPaths(List.of("--literal-pathspecs", "diff", "--cached", "--quiet"), commitPaths));
        if (!staged.started() || staged.timedOut() || staged.exit() > 1) {
            return new Failed("git diff failed: " + reason(staged));
        }

        if (staged.exit() == 0) {
            return new Taken(head(dir), false);
        }

        List<String> command = new ArrayList<>(this.identityOptions(dir));
        command.addAll(List.of("--literal-pathspecs", "commit", "-q", "--only", "-m", message));
        Run committed = run(dir, withPaths(command, commitPaths));
        if (!committed.started() || committed.timedOut() || committed.exit() != 0) {
            return new Failed("git commit failed: " + reason(committed));
        }

        Optional<String> head = head(dir);
        return head.isPresent() ? new Taken(head, true) : new Failed("the commit was made but the latest commit cannot be read");
    }

    /** {@code -c} options for one command when the user has no Git identity; empty when they have one. */
    private List<String> identityOptions(Path dir) {
        List<String> options = new ArrayList<>();
        if (this.configured(dir, "user.name").isEmpty()) {
            options.addAll(List.of("-c", "user.name=" + FALLBACK_NAME));
        }

        if (this.configured(dir, "user.email").isEmpty()) {
            options.addAll(List.of("-c", "user.email=" + FALLBACK_EMAIL));
        }

        return options;
    }

    private Optional<String> configured(Path dir, String key) {
        Run run = run(dir, List.of("config", "--get", key));
        return run.started() && run.exit() == 0 && !run.output().isBlank() ? Optional.of(run.output().strip()) : Optional.empty();
    }

    private Optional<String> head(Path dir) {
        Run head = run(dir, List.of("rev-parse", "--verify", "-q", "HEAD"));
        return head.started() && head.exit() == 0 && head.output().strip().matches("[0-9a-f]{40,64}") ? Optional.of(head.output().strip()) : Optional.empty();
    }

    private static List<String> withPaths(List<String> command, Set<String> paths) {
        List<String> all = new ArrayList<>(command);
        all.add("--");
        all.addAll(paths);
        return all;
    }

    /** Refuses a path that is not a plain relative file path inside the working tree. */
    private static String refusePath(String path) {
        if (path == null || path.isEmpty()) {
            return "an empty path cannot be saved";
        }

        if (path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0 || path.endsWith("/")) {
            return "not a plain relative file path: " + path;
        }

        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equals(".git")) {
                return "not a plain relative file path: " + path;
            }
        }

        return null;
    }

    private static String reason(Run run) {
        String text = run.timedOut() ? "timed out" : run.output().strip();
        if (text.isEmpty()) {
            text = "exit " + run.exit();
        }

        return text.length() > MAX_REASON ? text.substring(0, MAX_REASON) : text;
    }

    private record Run(boolean started, boolean timedOut, int exit, String output) {
    }

    private Run run(Path dir, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(this.executable);
        command.addAll(arguments);
        Process process;
        try {
            process = ProcessSupport.start(command, dir, this.environment);
        } catch (IOException | RuntimeException e) {
            return new Run(false, false, -1, String.valueOf(e.getMessage()));
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> drain(process.getInputStream(), captured), "kcg-git-output");
        pump.setDaemon(true);
        pump.start();
        try {
            boolean exited = process.waitFor(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                ProcessSupport.terminateTree(process);
            }

            ProcessSupport.endPump(process, pump);
            synchronized (captured) {
                return new Run(true, !exited, exited ? process.exitValue() : -1, captured.toString(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                ProcessSupport.terminateTree(process);
            } catch (InterruptedException ignored) {
                // Already interrupted; the process is ended on a best-effort basis.
            }

            return new Run(true, true, -1, "interrupted");
        }
    }

    /** Keeps reading to the end so the child never blocks on a full pipe, but keeps only a bounded part. */
    private static void drain(InputStream in, ByteArrayOutputStream captured) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                synchronized (captured) {
                    int room = MAX_OUTPUT_BYTES - captured.size();
                    if (room > 0) {
                        captured.write(buffer, 0, Math.min(read, room));
                    }
                }
            }
        } catch (IOException ignored) {
            // The stream closes when the process ends; what was read so far is all that is kept.
        }
    }
}
