package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratorDeterminismMatrixTest {

    private static final String CAMPUS_MARKET = "valid/campus-market.sir";
    private static final String COURSE_CATALOG = "valid/course-catalog.sir";
    private static final String COURSE_ADMIN = "valid/course-admin.sir";

    @TempDir
    Path tempDir;

    @Test
    void generatedFilesUseCanonicalLfAndRoundTripThroughUtf8() {
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(CAMPUS_MARKET);

        for (GeneratedFile file : files) {
            String content = file.content();
            assertFalse(content.contains("\r"),
                    () -> "generated content contains CR: " + file.relativePath());
            assertTrue(content.endsWith("\n"),
                    () -> "generated content must end with LF: " + file.relativePath());
            assertFalse(content.endsWith("\n\n"),
                    () -> "generated content has more than one trailing LF: " + file.relativePath());
            assertEquals(content,
                    new String(content.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                    () -> "generated content failed UTF-8 round-trip: " + file.relativePath());
        }
    }

    @Test
    void independentJvmsProduceTheSameCanonicalDigestAcrossCwdLocaleAndCharset()
            throws Exception {
        ProbeResult english = runProbe(
                Files.createDirectory(tempDir.resolve("english-cwd")),
                "en", "US", "ISO-8859-1",
                CAMPUS_MARKET);
        ProbeResult turkish = runProbe(
                Files.createDirectory(tempDir.resolve("turkish-cwd")),
                "tr", "TR", "UTF-8",
                CAMPUS_MARKET);

        assertEquals(0, english.exitCode(), english::diagnostic);
        assertEquals(0, turkish.exitCode(), turkish::diagnostic);
        assertTrue(english.stderr().isBlank(), english::diagnostic);
        assertTrue(turkish.stderr().isBlank(), turkish::diagnostic);
        assertTrue(english.stdout().trim().matches("[0-9a-f]{64}"), english::diagnostic);
        assertEquals(english.stdout().trim(), turkish.stdout().trim(),
                () -> "Generator digest changed across cwd/Locale/default Charset:\n"
                        + english.diagnostic() + "\n" + turkish.diagnostic());
    }

    @Test
    void pagedQuerySliceKeepsItsDigestAcrossCwdLocaleAndCharset() throws Exception {
        ProbeResult english = runProbe(
                Files.createDirectory(tempDir.resolve("paged-english-cwd")),
                "en", "US", "ISO-8859-1",
                COURSE_CATALOG);
        ProbeResult turkish = runProbe(
                Files.createDirectory(tempDir.resolve("paged-turkish-cwd")),
                "tr", "TR", "UTF-8",
                COURSE_CATALOG);

        assertEquals(0, english.exitCode(), english::diagnostic);
        assertEquals(0, turkish.exitCode(), turkish::diagnostic);
        assertEquals(english.stdout().trim(), turkish.stdout().trim(),
                () -> "Paged generator digest changed across cwd/Locale/default Charset:\n"
                        + english.diagnostic() + "\n" + turkish.diagnostic());
        assertTrue(english.stdout().trim().matches("[0-9a-f]{64}"), english::diagnostic);
    }

    @Test
    void writeSliceKeepsItsDigestAcrossCwdLocaleAndCharset() throws Exception {
        ProbeResult english = runProbe(
                Files.createDirectory(tempDir.resolve("write-english-cwd")),
                "en", "US", "ISO-8859-1",
                COURSE_ADMIN);
        ProbeResult turkish = runProbe(
                Files.createDirectory(tempDir.resolve("write-turkish-cwd")),
                "tr", "TR", "UTF-8",
                COURSE_ADMIN);

        assertEquals(0, english.exitCode(), english::diagnostic);
        assertEquals(0, turkish.exitCode(), turkish::diagnostic);
        assertEquals(english.stdout().trim(), turkish.stdout().trim(),
                () -> "Write slice generator digest changed across cwd/Locale/default Charset:\n"
                        + english.diagnostic() + "\n" + turkish.diagnostic());
        assertTrue(english.stdout().trim().matches("[0-9a-f]{64}"), english::diagnostic);
    }

    private static ProbeResult runProbe(
            Path workingDirectory,
            String language,
            String country,
            String fileEncoding,
            String resource
    ) throws IOException, InterruptedException {
        String executable = System.getProperty("os.name").startsWith("Windows")
                ? "java.exe"
                : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
                java.toString(),
                "-Duser.language=" + language,
                "-Duser.country=" + country,
                "-Dfile.encoding=" + fileEncoding,
                "-cp", classpath,
                GeneratorDeterminismProbe.class.getName(),
                resource)
                .directory(workingDirectory.toFile())
                .start();

        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new AssertionError("Generator determinism probe timed out in " + workingDirectory);
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProbeResult(workingDirectory, language + "-" + country, fileEncoding,
                process.exitValue(), stdout, stderr);
    }

    private record ProbeResult(
            Path workingDirectory,
            String locale,
            String fileEncoding,
            int exitCode,
            String stdout,
            String stderr
    ) {
        String diagnostic() {
            return "cwd=" + workingDirectory
                    + ", locale=" + locale
                    + ", file.encoding=" + fileEncoding
                    + ", exit=" + exitCode
                    + ", stdout=" + stdout
                    + ", stderr=" + stderr;
        }
    }
}
