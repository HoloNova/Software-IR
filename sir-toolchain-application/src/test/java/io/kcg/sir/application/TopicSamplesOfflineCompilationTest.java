package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Java generated for each of the topic samples must compile, offline, with the frozen dependencies. Passing the
 * SIR checks is not enough: a view that nests another view, or a load through a reference, once passed them and
 * produced Java that did not compile. It needs {@code -Dmaven.repo.local=<frozen repository>} like the generator's own
 * compilation test, and is skipped (not passed) without it.
 */
class TopicSamplesOfflineCompilationTest {
    private static final List<String> TOPICS = List.of("market", "library", "course", "blog", "tasks", "shop", "booking", "ledger");

    @Test void theJavaOfEveryTopicCompilesOffline(@TempDir Path temp) throws Exception {
        String configured = System.getProperty("maven.repo.local");
        assumeTrue(configured != null && !configured.isBlank(), "NOT_RUN: no -Dmaven.repo.local");
        Path repository = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(repository), "NOT_RUN: no such Maven repository: " + repository);
        for (String topic : TOPICS) {
            Path project = temp.resolve(topic);
            for (var file : generate(topic)) {
                Path target = project.resolve(file.relativePath()).normalize();
                assertTrue(target.startsWith(project), file.relativePath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content(), StandardCharsets.UTF_8);
            }
            compile(topic, project, repository, temp);
        }
    }

    private static List<io.kcg.sir.generator.springboot.api.GeneratedFile> generate(String topic) throws Exception {
        SourceId entry = SourceId.of("project.sir");
        byte[] text;
        try (InputStream in = TopicSamplesOfflineCompilationTest.class.getResourceAsStream("/topics/" + topic + "/project.sir")) {
            text = Objects.requireNonNull(in, topic).readAllBytes();
        }
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(entry, Map.of(entry, text)),
            model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(compiled.isPresent(), () -> topic + ": " + diagnostics);
        return compiled.get().generatedFiles();
    }

    private static void compile(String topic, Path project, Path repository, Path temp) throws Exception {
        Path log = temp.resolve(topic + "-maven.log");
        List<String> command = List.of(maven().toString(), "--batch-mode", "--no-transfer-progress", "--offline",
            "-Dmaven.repo.local=" + repository, "-DskipTests", "compile");
        Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        String output = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "<no log>";
        assertTrue(finished, () -> topic + ": offline compile timed out\n" + output);
        assertEquals(0, process.exitValue(), () -> topic + ": the generated Java does not compile\n" + output);
    }

    private static Path maven() {
        List<Path> candidates = new ArrayList<>();
        for (String home : new String[] {System.getProperty("maven.home"), System.getenv("MAVEN_HOME"), System.getenv("M2_HOME")})
            if (home != null && !home.isBlank()) candidates.add(Path.of(home, "bin", "mvn"));
        String path = System.getenv("PATH");
        if (path != null)
            for (String entry : path.split(Pattern.quote(File.pathSeparator))) if (!entry.isBlank()) candidates.add(Path.of(entry, "mvn"));
        return candidates.stream().map(c -> c.toAbsolutePath().normalize()).filter(Files::isRegularFile).findFirst()
            .orElseThrow(() -> new AssertionError("cannot locate mvn; candidates=" + candidates));
    }
}
