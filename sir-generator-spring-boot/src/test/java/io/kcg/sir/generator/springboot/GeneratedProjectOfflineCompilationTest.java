package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedProjectOfflineCompilationTest {

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(3);

    @Test
    void completeCampusMarketProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/campus-market.sir");
        materialize(files, projectRoot);

        Path maven = locateMavenExecutable();
        Path localRepository = frozenLocalRepository();
        Path buildLog = tempDir.resolve("generated-project-maven.log");
        List<String> command = List.of(
                maven.toString(),
                "--batch-mode",
                "--no-transfer-progress",
                "--offline",
                "-Dmaven.repo.local=" + localRepository,
                "-DskipTests",
                "compile");

        Process process = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(buildLog.toFile())
                .start();

        boolean finished = process.waitFor(BUILD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        String output = Files.exists(buildLog)
                ? Files.readString(buildLog, StandardCharsets.UTF_8)
                : "<build log was not created>";
        if (!finished) {
            fail(failureMessage(command, projectRoot, "timed out after " + BUILD_TIMEOUT, output));
        }
        assertEquals(0, process.exitValue(),
                () -> failureMessage(command, projectRoot, "exit code " + process.exitValue(), output));

        for (String classFile : List.of(
                "com/example/campusmarket/Application.class",
                "com/example/campusmarket/domain/User.class",
                "com/example/campusmarket/api/PublishGoodsInput.class",
                "com/example/campusmarket/persistence/UserMapper.class",
                "com/example/campusmarket/application/PublishGoodsService.class",
                "com/example/campusmarket/api/PublishGoodsController.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    private static void materialize(List<GeneratedFile> files, Path projectRoot) throws IOException {
        Files.createDirectories(projectRoot);
        for (GeneratedFile file : files) {
            Path target = projectRoot.resolve(file.relativePath()).normalize();
            assertTrue(target.startsWith(projectRoot),
                    () -> "generated path escaped the temporary project: " + file.relativePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
            assertEquals(file.content(), Files.readString(target, StandardCharsets.UTF_8),
                    () -> "materialized UTF-8 content changed: " + file.relativePath());
        }
    }

    private static Path frozenLocalRepository() {
        String configured = System.getProperty("maven.repo.local");
        assertTrue(configured != null && !configured.isBlank(),
                "Q1D requires the parent build to pass -Dmaven.repo.local=<frozen repository>");
        Path repository = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(repository),
                () -> "configured frozen Maven repository does not exist: " + repository);
        return repository;
    }

    private static Path locateMavenExecutable() {
        String executableName = isWindows() ? "mvn.cmd" : "mvn";
        List<Path> candidates = new ArrayList<>();
        addMavenHomeCandidate(candidates, System.getProperty("maven.home"), executableName);
        addMavenHomeCandidate(candidates, System.getenv("MAVEN_HOME"), executableName);
        addMavenHomeCandidate(candidates, System.getenv("M2_HOME"), executableName);

        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    candidates.add(Path.of(entry).resolve(executableName));
                }
            }
        }

        return candidates.stream()
                .map(candidate -> candidate.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "cannot locate " + executableName + " from maven.home, MAVEN_HOME, M2_HOME, or PATH; candidates="
                                + candidates));
    }

    private static void addMavenHomeCandidate(List<Path> candidates, String home, String executableName) {
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home).resolve("bin").resolve(executableName));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String failureMessage(
            List<String> command, Path projectRoot, String result, String output) {
        return "generated project offline compile failed\n"
                + "working directory: " + projectRoot + "\n"
                + "command: " + String.join(" ", command) + "\n"
                + "result: " + result + "\n"
                + "combined Maven output:\n" + output;
    }
}
