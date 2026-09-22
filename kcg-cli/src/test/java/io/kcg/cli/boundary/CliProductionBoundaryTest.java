package io.kcg.cli.boundary;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Static boundary check for the CLI's production classes.
 *
 * <p>The claim under test is narrow and checkable: the published command surface talks to the rest of
 * the system only through the typed Application API, and does no file I/O of its own. That claim is
 * what makes the read-only contract believable — a command that opened {@code CURRENT} itself could
 * observe or disturb state the Application is supposed to own.
 *
 * <p>Two rules are enforced on the constant pool of every compiled class:
 *
 * <ol>
 *   <li>no reference to any {@code internal} package of Application or Change — those are not part
 *       of the published surface;</li>
 *   <li>no reference to a filesystem API from a command-path class — file access belongs to the
 *       Application.</li>
 * </ol>
 *
 * <p>One documented exception exists, and it is bounded rather than ignored: the
 * {@code io.kcg.cli.mvp} package is a non-command read-only evidence utility. Its own classes may use
 * filesystem APIs, but they may not touch Application internals, and <b>no command-path class may
 * reference them</b>. That last assertion is what keeps the exception from silently widening into the
 * published surface.
 *
 * <p>The check is proved non-vacuous by scanning {@link CliBoundaryProbe}, a class that violates every
 * rule on purpose.
 */
class CliProductionBoundaryTest {

    /** Package prefix of the documented exception. */
    private static final String MVP_PREFIX = "io/kcg/cli/mvp/";

    /** Application/Change internals that the CLI must never reference. */
    private static final List<String> FORBIDDEN_INTERNAL_PREFIXES = List.of(
            "io/kcg/sir/application/internal/",
            "io/kcg/sir/change/internal/",
            "io/kcg/sir/projectgraph/internal/",
            "io/kcg/sir/generator/");

    /** Filesystem APIs the command path must not use. */
    private static final List<String> FORBIDDEN_IO = List.of(
            "java/nio/file/Files",
            "java/nio/file/Paths",
            "java/nio/file/FileChannel",
            "java/nio/file/spi/FileSystemProvider",
            "java/io/File");

    @Test
    void productionClassesStayInsideThePublishedApi() throws IOException {
        TreeMap<String, List<String>> found = scan(productionClasses());

        List<String> violations = new ArrayList<>();
        for (var entry : found.entrySet()) {
            String relative = entry.getKey();
            for (String constant : entry.getValue()) {
                if (FORBIDDEN_INTERNAL_PREFIXES.stream().anyMatch(constant::startsWith)) {
                    violations.add(relative + " -> " + constant);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "CLI production classes must not reference internals: " + violations);
    }

    @Test
    void commandPathClassesDoNoFileIoOfTheirOwn() throws IOException {
        TreeMap<String, List<String>> found = scan(productionClasses());

        List<String> violations = new ArrayList<>();
        for (var entry : found.entrySet()) {
            if (entry.getKey().startsWith(MVP_PREFIX)) {
                continue; // documented exception, bounded by the next test
            }
            for (String constant : entry.getValue()) {
                if (FORBIDDEN_IO.contains(constant)) {
                    violations.add(entry.getKey() + " -> " + constant);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "command-path classes must not do their own file I/O: " + violations);
    }

    @Test
    void theNonCommandExceptionIsUnreachableFromTheCommandPath() throws IOException {
        TreeMap<String, List<String>> found = scan(productionClasses());

        List<String> violations = new ArrayList<>();
        for (var entry : found.entrySet()) {
            if (entry.getKey().startsWith(MVP_PREFIX)) {
                continue;
            }
            for (String constant : entry.getValue()) {
                if (constant.startsWith(MVP_PREFIX)) {
                    violations.add(entry.getKey() + " -> " + constant);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "the mvp package is a non-command path, so no command class may reference it: "
                        + violations
                        + "; either wire it into a command (not published) or move it out of the "
                        + "command path");
    }

    @Test
    void theScanReportsARealViolation() throws IOException {
        // The probe is test-only code, so it is compiled into the test output, not the production
        // output this gate scans.
        Path probe = Path.of("target", "test-classes", "io", "kcg", "cli", "boundary",
                "CliBoundaryProbe.class").toAbsolutePath();
        assertTrue(Files.isRegularFile(probe), () -> "probe class missing: " + probe);

        // Scan a directory that contains only the probe, so the result is exactly what the probe
        // contributes and nothing else can mask it.
        Path census = Files.createTempDirectory("cli-boundary-probe");
        try {
            Path nested = Files.createDirectories(census.resolve("io/kcg/cli/boundary"));
            Files.copy(probe, nested.resolve("CliBoundaryProbe.class"),
                    StandardCopyOption.REPLACE_EXISTING);
            List<Path> probeFiles = new ArrayList<>();
            probeFiles.add(nested.resolve("CliBoundaryProbe.class"));
            TreeMap<String, List<String>> found = scan(probeFiles);

            List<String> all = new ArrayList<>();
            found.values().forEach(all::addAll);
            assertTrue(all.stream().anyMatch(c -> c.startsWith("io/kcg/sir/application/internal/")),
                    () -> "the scan must report a real internal reference, otherwise it proves "
                            + "nothing: " + all);
            assertTrue(all.contains("java/nio/file/Files"),
                    () -> "the scan must report real file I/O, otherwise it proves nothing: " + all);
            assertTrue(all.stream().anyMatch(c -> c.startsWith(MVP_PREFIX)),
                    () -> "the scan must report a real mvp reference: " + all);
        } finally {
            deleteRecursively(census);
        }
    }

    /** @return every {@code .class} file under the module's production output */
    private static List<Path> productionClasses() throws IOException {
        Path classes = targetClassesDir();
        assertTrue(Files.isDirectory(classes),
                () -> "production classes not found at " + classes
                        + "; run the module build before this test");
        List<Path> found = new ArrayList<>();
        try (var stream = Files.walk(classes)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path) && path.toString().endsWith(".class")) {
                    found.add(path);
                }
            }
        }
        assertFalse(found.isEmpty(), "no compiled classes found to scan");
        return found;
    }

    /**
     * @return class file to the constant-pool strings that name classes, in file order
     */
    private static TreeMap<String, List<String>> scan(List<Path> classFiles) throws IOException {
        Path classes = targetClassesDir();
        TreeMap<String, List<String>> found = new TreeMap<>();
        for (Path classFile : classFiles) {
            String relative = classes.relativize(classFile).toString().replace('\\', '/');
            List<String> classNames = new ArrayList<>();
            for (String constant : CliClassFileConstantPool.utf8Entries(classFile)) {
                if (looksLikeInternalName(constant)) {
                    classNames.add(constant);
                }
            }
            found.put(relative, classNames);
        }
        return found;
    }

    /** @return the module's production class directory */
    private static Path targetClassesDir() {
        return Path.of("target", "classes").toAbsolutePath();
    }

    /**
     * @param constant a constant-pool string
     * @return true when the string is a JVM internal class name, which is how class references and
     *         descriptors appear in the pool
     */
    private static boolean looksLikeInternalName(String constant) {
        if (constant.isEmpty() || constant.startsWith("(") || constant.startsWith("[")) {
            return false;
        }
        if (constant.indexOf('.') >= 0 || constant.indexOf(' ') >= 0) {
            return false;
        }
        return constant.indexOf('/') >= 0;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
