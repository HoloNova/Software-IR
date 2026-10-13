package io.kcg.sir.application.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.kcg.sir.application.api.LogRef;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.StageOutcome;
import io.kcg.sir.application.api.StageStatus;
import io.kcg.sir.application.api.VerificationPorts;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process adapters run real child processes: shell scripts stand in for Maven, and a real jar holding a tiny
 * HTTP server stands in for the generated application. What is under test is the contract around them: empty
 * environment, time limits that end the whole process tree, bounded redacted logs, and proof that nothing is left.
 */
class ExecutionAdaptersTest {
    private static final String SECRET = "hunter2-secret-value";
    @TempDir Path temp;
    private Path project;
    private Path evidence;
    private Path repository;
    private int counter;

    @BeforeEach void setUp() throws IOException {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "the process tests need a POSIX shell");
        project = Files.createDirectories(temp.resolve("project"));
        evidence = Files.createDirectories(temp.resolve("evidence"));
        repository = Files.createDirectories(temp.resolve("repository"));
    }

    private ResourceLimits limits(Duration build, Duration start) {
        return new ResourceLimits(build, start, Duration.ofSeconds(5), 256, 256, 128, 8L * 1024 * 1024);
    }

    private Path script(String body) throws IOException {
        Path file = temp.resolve("tool-" + (++counter) + ".sh");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    private MavenProjectBuilder maven(String body, boolean offline) throws IOException {
        return new MavenProjectBuilder(script(body), repository, offline, Optional.empty(), List.of(SECRET));
    }

    private Path executableJar(String name, byte[] classBytes) throws IOException {
        Path jar = temp.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeApp.class.getName());
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out, manifest)) {
            if (classBytes != null) {
                jos.putNextEntry(new JarEntry(FakeApp.class.getName().replace('.', '/') + ".class"));
                jos.write(classBytes);
                jos.closeEntry();
            }
        }

        return jar;
    }

    private static byte[] fakeAppClass() throws IOException {
        try (InputStream in = FakeApp.class.getResourceAsStream("FakeApp.class")) {
            return in.readAllBytes();
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ------------------------------------------------------------------ Maven builder

    @Test void aSuccessfulBuildReturnsTheExecutableJarAndALogWhoseEvidenceIsExact() throws Exception {
        Path jar = executableJar("built.jar", null);
        var builder = maven("""
                if [ "$1" = "-v" ]; then echo "Apache Maven 9.9.9 (fake)"; exit 0; fi
                echo "ARGS: $*"
                mkdir -p target && cp %s target/app.jar
                echo "BUILD SUCCESS"
                """.formatted(jar), true);
        var outcome = builder.build(project, evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));

        assertEquals(StageStatus.PASSED, outcome.outcome().status(), outcome.outcome().toString());
        assertEquals(project.resolve("target/app.jar"), outcome.artifact().orElseThrow());
        LogRef log = outcome.outcome().logs().get(0);
        assertEquals(LogRef.Kind.BUILD, log.kind());
        Path file = evidence.resolve(log.relativePath());
        assertEquals(Files.size(file), log.bytes());
        assertEquals(sha256(file), log.sha256());
        String text = Files.readString(file);
        assertTrue(text.contains(" -o "), "offline was asked for");
        assertTrue(text.contains("-Dmaven.repo.local=" + repository), text);
        assertTrue(text.contains(" -s " + evidence.resolve("maven-settings.xml")), "the user's own Maven settings are never read");
        assertTrue(text.contains("clean verify"));
        assertEquals("<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\"/>\n", Files.readString(evidence.resolve("maven-settings.xml")));
        assertEquals("Apache Maven 9.9.9 (fake)", builder.environment().get("maven.version"));
        assertEquals("true", builder.environment().get("repository.offline"));
    }

    @Test void onlineBuildsDoNotPassTheOfflineFlag() throws Exception {
        Path jar = executableJar("built.jar", null);
        var builder = maven("echo \"ARGS: $*\"\nmkdir -p target && cp " + jar + " target/app.jar", false);
        var outcome = builder.build(project, evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));

        assertEquals(StageStatus.PASSED, outcome.outcome().status());
        assertFalse(Files.readString(evidence.resolve("logs/build.log")).contains(" -o "));
    }

    @Test void aFailedBuildNamesItsErrorsAndKeepsTheLog() throws Exception {
        var builder = maven("echo '[INFO] noise'\necho '[ERROR] COMPILATION ERROR : cannot find symbol'\necho '[ERROR] BUILD FAILURE'\nexit 1", true);
        var outcome = builder.build(project, evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));

        assertEquals(StageStatus.FAILED, outcome.outcome().status());
        assertEquals("BUILD_EXIT_NONZERO", outcome.outcome().code());
        assertEquals(List.of("[ERROR] COMPILATION ERROR : cannot find symbol", "[ERROR] BUILD FAILURE"), outcome.outcome().diagnostics());
        assertEquals(1, outcome.outcome().logs().size());
        assertTrue(outcome.artifact().isEmpty());
    }

    @Test void aBuildThatRunsTooLongIsEndedWithItsWholeProcessTree() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        var builder = maven("sleep 300 &\necho $! > " + pidFile + "\nwait", true);
        long started = System.nanoTime();
        var outcome = builder.build(project, evidence, limits(Duration.ofSeconds(1), Duration.ofSeconds(5)));

        assertEquals("BUILD_TIMEOUT", outcome.outcome().code(), outcome.outcome().toString());
        assertTrue(outcome.outcome().message().contains("process tree was ended"), outcome.outcome().message());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 20, "the limit must end the build, not wait for it");
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertTrue(ProcessHandle.of(childPid).map(h -> !h.isAlive()).orElse(true), "the grandchild must not outlive the build");
    }

    @Test void aChattyBuildCannotBlockOrFillTheDisk() throws Exception {
        var builder = maven("yes 'a very noisy log line that goes on and on' | head -n 1500000\nexit 1", true);
        var limited = new ResourceLimits(Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(5), 256, 256, 128, 64L * 1024);
        var outcome = builder.build(project, evidence, limited);

        assertEquals("BUILD_EXIT_NONZERO", outcome.outcome().code());
        Path file = evidence.resolve("logs/build.log");
        assertTrue(Files.size(file) <= 64L * 1024 + 100, "the log is bounded: " + Files.size(file));
        assertTrue(Files.readString(file).contains("[log truncated at 65536 bytes]"));
        assertEquals(Files.size(file), outcome.outcome().logs().get(0).bytes());
    }

    @Test void secretsNeverReachTheLogEvenInAnOverlongLine() throws Exception {
        var builder = maven("echo 'jdbc password=" + SECRET + " ok'\nawk 'BEGIN { s = \"x\"; for (i = 0; i < 17; i++) s = s s; print s \"" + SECRET + "\" }'\nexit 1", true);
        var outcome = builder.build(project, evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));

        String text = Files.readString(evidence.resolve("logs/build.log"));
        assertFalse(text.contains(SECRET), "a declared secret must not be written");
        assertTrue(text.contains("jdbc password=" + LogRedactor.MASK + " ok"), text);
        assertTrue(text.contains("[line longer than 65536 bytes omitted]"), "an overlong line is dropped, never cut");
        assertEquals(StageStatus.FAILED, outcome.outcome().status());
    }

    @Test void theChildStartsFromAnEmptyEnvironmentWithTheStatedHeapLimits() throws Exception {
        var builder = maven("env\nexit 1", true);
        builder.build(project, evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));

        Set<String> allowed = Set.of("PATH", "JAVA_HOME", "LANG", "LC_ALL", "TZ", "MAVEN_OPTS", "PWD", "OLDPWD", "SHLVL", "_");
        List<String> lines = Files.readAllLines(evidence.resolve("logs/build.log"));
        for (String line : lines) {
            if (line.contains("=") && !line.startsWith("ARGS")) {
                String name = line.substring(0, line.indexOf('='));
                assertTrue(allowed.contains(name), "unexpected variable reached the build: " + name);
            }
        }

        assertTrue(lines.contains("MAVEN_OPTS=-Xmx256m -XX:MaxMetaspaceSize=128m"), lines.toString());
        assertTrue(lines.contains("TZ=UTC"));
        assertTrue(lines.contains("JAVA_HOME=" + System.getProperty("java.home")));
    }

    @Test void aMissingToolOrRepositoryIsNotRunNotAFailedCandidate() throws Exception {
        var noTool = new MavenProjectBuilder(temp.resolve("no-such-mvn"), repository, true, Optional.empty(), List.of(SECRET))
                .build(project, evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        assertEquals(StageStatus.NOT_RUN, noTool.outcome().status());
        assertEquals("BUILD_TOOL_UNAVAILABLE", noTool.outcome().code());

        var noRepository = new MavenProjectBuilder(script("exit 0"), temp.resolve("no-repo"), true, Optional.empty(), List.of(SECRET))
                .build(project, evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        assertEquals("BUILD_REPOSITORY_UNAVAILABLE", noRepository.outcome().code());
        assertEquals(StageStatus.NOT_RUN, noRepository.outcome().status());
        assertFalse(Files.exists(evidence.resolve("logs")), "nothing was run, so nothing was logged");
    }

    @Test void exactlyOneExecutableJarIsRequired() throws Exception {
        Path jar = executableJar("built.jar", null);
        var two = maven("mkdir -p target && cp " + jar + " target/a.jar && cp " + jar + " target/b.jar", true)
                .build(project, evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        assertEquals("BUILD_ARTIFACT_NOT_UNIQUE", two.outcome().code());
        assertTrue(two.outcome().message().endsWith("found 2"));

        Files.createDirectories(temp.resolve("empty"));
        var none = maven("mkdir -p target", true).build(Files.createDirectories(temp.resolve("project-2")), Files.createDirectories(temp.resolve("evidence-2")),
                limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        assertEquals("BUILD_ARTIFACT_NOT_UNIQUE", none.outcome().code());
        assertTrue(none.outcome().message().endsWith("found 0"));
    }

    // ------------------------------------------------------------------ Spring Boot host

    private SpringBootApplicationHost host(int port) {
        return new SpringBootApplicationHost(Path.of(System.getProperty("java.home"), "bin", "java"), port, "/ready", List.of(SECRET));
    }

    private static String get(URI uri) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test void aReadyApplicationRunsFromAnEmptyEnvironmentAndStopsWithProof() throws Exception {
        Path jar = executableJar("app.jar", fakeAppClass());
        Path pidFile = temp.resolve("grandchild.pid");
        int port = freePort();
        var start = host(port).start(jar, Map.of("FAKE_MODE", "ok", "FAKE_SECRET", SECRET, "FAKE_CHILD_PID_FILE", pidFile.toString()), evidence,
                limits(Duration.ofSeconds(30), Duration.ofSeconds(30)));

        assertEquals(StageStatus.PASSED, start.outcome().status(), start.outcome().toString());
        var running = start.application().orElseThrow();
        assertEquals(URI.create("http://127.0.0.1:" + port), running.baseUrl());
        Set<String> allowed = Set.of("PATH", "JAVA_HOME", "LANG", "LC_ALL", "TZ", "FAKE_MODE", "FAKE_SECRET", "FAKE_CHILD_PID_FILE", "PWD", "SHLVL", "_");
        for (String name : get(URI.create(running.baseUrl() + "/env")).split(",")) {
            assertTrue(allowed.contains(name), "unexpected variable reached the application: " + name);
        }

        String args = get(URI.create(running.baseUrl() + "/args"));
        assertTrue(args.contains("-Xmx256m") && args.contains("-XX:MaxMetaspaceSize=128m"), args);
        assertTrue(args.endsWith("address=127.0.0.1"), args);
        long grandchild = Long.parseLong(Files.readString(pidFile).trim());
        assertTrue(ProcessHandle.of(grandchild).map(ProcessHandle::isAlive).orElse(false), "the helper process is running before stop");

        var stopped = running.stop();
        assertTrue(stopped.cleanup().proved(), stopped.cleanup().checks().toString());
        assertTrue(stopped.cleanup().checks().stream().anyMatch(c -> c.contains("no longer accepts connections")));
        assertTrue(ProcessHandle.of(grandchild).map(h -> !h.isAlive()).orElse(true), "the helper process must not outlive the application");
        LogRef log = stopped.logs().get(0);
        assertEquals(LogRef.Kind.APPLICATION, log.kind());
        Path file = evidence.resolve(log.relativePath());
        assertEquals(sha256(file), log.sha256());
        String text = Files.readString(file);
        assertFalse(text.contains(SECRET));
        assertTrue(text.contains("connecting with password=" + LogRedactor.MASK), text);
        assertSame(stopped, running.stop(), "stopping again returns the same proof");
        assertThrows(IOException.class, () -> get(URI.create("http://127.0.0.1:" + port + "/ready")));
    }

    @Test void anApplicationThatExitsBeforeReadyFailsAndCanStillBeStopped() throws Exception {
        Path jar = executableJar("app.jar", fakeAppClass());
        var start = host(freePort()).start(jar, Map.of("FAKE_MODE", "exit"), evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(30)));

        assertEquals("START_PROCESS_EXITED", start.outcome().code(), start.outcome().toString());
        assertTrue(start.outcome().message().contains("status 3"));
        var stopped = start.application().orElseThrow().stop();
        assertTrue(stopped.cleanup().proved());
        assertTrue(Files.readString(evidence.resolve("logs/application.log")).contains("boom"));
    }

    @Test void anApplicationThatNeverAnswers200TimesOutAndIsEnded() throws Exception {
        Path jar = executableJar("app.jar", fakeAppClass());
        int port = freePort();
        long started = System.nanoTime();
        var start = host(port).start(jar, Map.of("FAKE_MODE", "never-ready"), evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(2)));

        assertEquals("START_TIMEOUT", start.outcome().code(), start.outcome().toString());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 20);
        var stopped = start.application().orElseThrow().stop();
        assertTrue(stopped.cleanup().proved(), stopped.cleanup().checks().toString());
    }

    @Test void aPortAlreadyInUseIsNotRunAndNothingIsStarted() throws Exception {
        Path jar = executableJar("app.jar", fakeAppClass());
        try (ServerSocket occupied = new ServerSocket(0)) {
            var start = host(occupied.getLocalPort()).start(jar, Map.of(), evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));

            assertEquals(StageStatus.NOT_RUN, start.outcome().status());
            assertEquals("START_PORT_IN_USE", start.outcome().code());
            assertTrue(start.application().isEmpty());
            assertFalse(Files.exists(evidence.resolve("logs")));
        }
    }

    @Test void aMissingJavaIsNotRunAndBadSettingsAreRefused() throws Exception {
        var start = new SpringBootApplicationHost(temp.resolve("no-java"), freePort(), "/ready", List.of(SECRET))
                .start(executableJar("app.jar", null), Map.of(), evidence, limits(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        assertEquals("START_TOOL_UNAVAILABLE", start.outcome().code());

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        assertThrows(IllegalArgumentException.class, () -> new SpringBootApplicationHost(java, 80, "/ready", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SpringBootApplicationHost(java, 18080, "ready", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LogRedactor(List.of("abc")));
    }

    @Test void theLongestSecretIsMaskedWholeWhenOneContainsAnother() {
        var redactor = new LogRedactor(List.of("secret", "secret-with-suffix"));
        assertEquals("a " + LogRedactor.MASK + " b " + LogRedactor.MASK, redactor.redact("a secret-with-suffix b secret"));
        assertNotNull(StageOutcome.passed("x"));
    }
}
