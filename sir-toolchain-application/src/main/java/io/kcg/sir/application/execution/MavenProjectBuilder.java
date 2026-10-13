package io.kcg.sir.application.execution;

import io.kcg.sir.application.api.LogRef;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.StageOutcome;
import io.kcg.sir.application.api.VerificationPorts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Builds a generated project with Maven in its own process and returns the one executable jar it produced.
 *
 * <p>The repository policy is the caller's: which local repository, and online or offline. Nothing is inherited
 * from the user's own Maven setup: the child starts from a short allowlist of environment variables, and reads a
 * settings file that this class writes (empty) or that the caller names, never {@code ~/.m2/settings.xml}.
 * The build has a time limit; when it is exceeded the whole process tree is ended.
 */
public final class MavenProjectBuilder implements VerificationPorts.Builder {
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(30);
    private static final String EMPTY_SETTINGS = "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\"/>\n";

    private final Path maven;
    private final Path localRepository;
    private final boolean offline;
    private final Optional<Path> settingsFile;
    private final LogRedactor redactor;
    private String version;

    public MavenProjectBuilder(Path mavenExecutable, Path localRepository, boolean offline, Optional<Path> settingsFile, Collection<String> secrets) {
        this.maven = Objects.requireNonNull(mavenExecutable, "mavenExecutable").toAbsolutePath().normalize();
        this.localRepository = Objects.requireNonNull(localRepository, "localRepository").toAbsolutePath().normalize();
        this.offline = offline;
        this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile").map(p -> p.toAbsolutePath().normalize());
        this.redactor = new LogRedactor(secrets);
    }

    @Override
    public synchronized Map<String, String> environment() {
        if (version == null) {
            version = queryVersion();
        }

        Map<String, String> facts = new TreeMap<>();
        facts.put("maven.version", version);
        facts.put("java.version", System.getProperty("java.version"));
        facts.put("repository.offline", Boolean.toString(offline));
        facts.put("repository.settings", settingsFile.isPresent() ? "caller-supplied" : "empty");
        return facts;
    }

    @Override
    public VerificationPorts.BuildOutcome build(Path projectRoot, Path evidenceRoot, ResourceLimits limits) {
        Optional<StageOutcome> unavailable = unavailable();
        if (unavailable.isPresent()) {
            return new VerificationPorts.BuildOutcome(unavailable.get(), Optional.empty());
        }

        try {
            Path settings = settingsFile.orElse(null);
            if (settings == null) {
                settings = evidenceRoot.resolve("maven-settings.xml");
                Files.writeString(settings, EMPTY_SETTINGS, StandardCharsets.UTF_8);
            }

            List<String> command = new ArrayList<>(List.of(maven.toString(), "-B", "-ntp", "-s", settings.toString(),
                    "-Dmaven.repo.local=" + localRepository));
            if (offline) {
                command.add("-o");
            }

            command.add("clean");
            command.add("verify");
            Map<String, String> env = ProcessSupport.baseEnvironment(Map.of("MAVEN_OPTS",
                    "-Xmx" + limits.buildHeapMegabytes() + "m -XX:MaxMetaspaceSize=" + limits.metaspaceMegabytes() + "m"));
            ProcessLog log = new ProcessLog(evidenceRoot.resolve("logs/build.log"), "logs/build.log", redactor, limits.maxLogBytes());
            ProcessSupport.Finished finished = ProcessSupport.run(command, projectRoot, env, limits.buildTimeout(), log);
            LogRef ref = log.finish(LogRef.Kind.BUILD);
            if (finished.timedOut()) {
                String proof = finished.treeGone() ? "the build process tree was ended" : "the build process tree could not be proved ended";
                return failed("BUILD_TIMEOUT", "the build did not finish within " + limits.buildTimeout().toSeconds() + " seconds; " + proof, log, ref);
            }

            if (finished.exitCode() != 0) {
                return failed("BUILD_EXIT_NONZERO", "Maven exited with status " + finished.exitCode(), log, ref);
            }

            return findArtifact(projectRoot, ref);
        } catch (IOException e) {
            return new VerificationPorts.BuildOutcome(StageOutcome.failed("BUILD_IO_ERROR", "the build could not be run (" + e.getClass().getSimpleName() + ")"), Optional.empty());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new VerificationPorts.BuildOutcome(StageOutcome.failed("BUILD_INTERRUPTED", "the build was interrupted"), Optional.empty());
        }
    }

    private Optional<StageOutcome> unavailable() {
        if (!Files.isRegularFile(maven) || !Files.isExecutable(maven)) {
            return Optional.of(StageOutcome.notRun("BUILD_TOOL_UNAVAILABLE", "the Maven executable is missing or not executable"));
        }

        if (offline && !Files.isDirectory(localRepository)) {
            return Optional.of(StageOutcome.notRun("BUILD_REPOSITORY_UNAVAILABLE", "the offline local repository does not exist"));
        }

        if (settingsFile.isPresent() && !Files.isRegularFile(settingsFile.get())) {
            return Optional.of(StageOutcome.notRun("BUILD_REPOSITORY_UNAVAILABLE", "the named Maven settings file does not exist"));
        }

        return Optional.empty();
    }

    private static VerificationPorts.BuildOutcome failed(String code, String message, ProcessLog log, LogRef ref) {
        return new VerificationPorts.BuildOutcome(StageOutcome.failed(code, message, ProcessSupport.errorLines(log.file(), 20), List.of(ref)), Optional.empty());
    }

    /** Exactly one executable jar must exist: guessing among several would build evidence about the wrong file. */
    private VerificationPorts.BuildOutcome findArtifact(Path projectRoot, LogRef ref) throws IOException {
        Path target = projectRoot.resolve("target");
        List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(target)) {
            try (Stream<Path> stream = Files.list(target)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar") && !p.getFileName().toString().endsWith("-sources.jar"))
                        .filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).sorted().forEach(jars::add);
            }
        }

        List<Path> executable = new ArrayList<>();
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                if (file.getManifest() != null && file.getManifest().getMainAttributes().getValue("Main-Class") != null) {
                    executable.add(jar);
                }
            } catch (IOException ignored) {
                // A file that is not a readable jar is not the artifact.
            }
        }

        if (executable.size() != 1) {
            return new VerificationPorts.BuildOutcome(StageOutcome.failed("BUILD_ARTIFACT_NOT_UNIQUE",
                    "the build must produce exactly one executable jar, found " + executable.size(), List.of(), List.of(ref)), Optional.empty());
        }

        return new VerificationPorts.BuildOutcome(StageOutcome.passed("the project built and produced " + executable.get(0).getFileName(), List.of(ref)),
                Optional.of(executable.get(0)));
    }

    private String queryVersion() {
        if (!Files.isRegularFile(maven) || !Files.isExecutable(maven)) {
            return "UNAVAILABLE";
        }

        try {
            Process process = ProcessSupport.start(List.of(maven.toString(), "-v"), maven.getParent(), ProcessSupport.baseEnvironment(Map.of()));
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(VERSION_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                ProcessSupport.terminateTree(process);
                return "UNAVAILABLE";
            }

            String first = new String(output, StandardCharsets.UTF_8).lines().findFirst().orElse("UNAVAILABLE");
            return redactor.redact(first.length() > 200 ? first.substring(0, 200) : first);
        } catch (IOException e) {
            return "UNAVAILABLE";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "UNAVAILABLE";
        }
    }
}
