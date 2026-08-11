package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ToolchainResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Determinism: repeated execution of the same SIR through the full pipeline
 * must produce identical manifest order, SHA-256 digests, byte counts, and
 * on-disk content. The pipeline must also be deterministic under the Turkish
 * locale (Locale.of("tr","TR")) because all locale-sensitive operations use
 * {@link Locale#ROOT}.
 */
class ToolchainDeterminismTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void repeatedExecutionProducesIdenticalManifest() throws Exception {
        Path sourceA = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outA = temporaryDirectory.resolve("out-a").toAbsolutePath();
        ToolchainResult.Success a = ApplicationTestSupport.runCampusMarket(sourceA, outA);

        Path sourceB = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outB = temporaryDirectory.resolve("out-b").toAbsolutePath();
        ToolchainResult.Success b = ApplicationTestSupport.runCampusMarket(sourceB, outB);

        List<AppliedFile> filesA = a.manifest().files();
        List<AppliedFile> filesB = b.manifest().files();

        assertEquals(filesA.size(), filesB.size(), "file count must match across runs");

        // Manifest order (relative paths) must be identical.
        List<String> pathsA = filesA.stream().map(AppliedFile::relativePath)
                .collect(Collectors.toList());
        List<String> pathsB = filesB.stream().map(AppliedFile::relativePath)
                .collect(Collectors.toList());
        assertIterableEquals(pathsA, pathsB,
                "manifest order must be identical across runs: " + pathsA + " vs " + pathsB);

        // Per-file: action, byteCount, sha256 must all match.
        for (int i = 0; i < filesA.size(); i++) {
            AppliedFile fa = filesA.get(i);
            AppliedFile fb = filesB.get(i);
            assertEquals(fa.relativePath(), fb.relativePath(),
                    "relativePath must match at index " + i);
            assertEquals(fa.action(), fb.action(),
                    "action must match at index " + i + " for " + fa.relativePath());
            assertEquals(fa.byteCount(), fb.byteCount(),
                    "byteCount must match at index " + i + " for " + fa.relativePath());
            assertEquals(fa.sha256Hex(), fb.sha256Hex(),
                    "sha256Hex must match at index " + i + " for " + fa.relativePath());
        }
    }

    @Test
    void repeatedExecutionProducesIdenticalContent() throws Exception {
        Path sourceA = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outA = temporaryDirectory.resolve("out-a").toAbsolutePath();
        ApplicationTestSupport.runCampusMarket(sourceA, outA);

        Path sourceB = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outB = temporaryDirectory.resolve("out-b").toAbsolutePath();
        ApplicationTestSupport.runCampusMarket(sourceB, outB);

        // Walk both output roots and compare every file byte-for-byte.
        List<Path> filesA = walkRegularFiles(outA);
        List<Path> filesB = walkRegularFiles(outB);

        assertEquals(filesA.size(), filesB.size(),
                "file count on disk must match: " + filesA + " vs " + filesB);

        for (int i = 0; i < filesA.size(); i++) {
            Path relA = outA.relativize(filesA.get(i));
            Path relB = outB.relativize(filesB.get(i));
            assertEquals(relA.toString().replace('\\', '/'),
                    relB.toString().replace('\\', '/'),
                    "relative path must match at index " + i);
            byte[] bytesA = Files.readAllBytes(filesA.get(i));
            byte[] bytesB = Files.readAllBytes(filesB.get(i));
            assertIterableEquals(byteList(bytesA), byteList(bytesB),
                    "content must match for " + relA);
        }
    }

    @Test
    void manifestSha256MatchesActualUtf8Bytes() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path out = temporaryDirectory.resolve("out").toAbsolutePath();
        ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(source, out);

        for (AppliedFile f : success.manifest().files()) {
            Path onDisk = out.resolve(f.relativePath());
            byte[] bytes = Files.readAllBytes(onDisk);
            String expected = sha256Hex(bytes);
            assertEquals(expected, f.sha256Hex(),
                    "manifest sha256 must match actual UTF-8 bytes for " + f.relativePath());
            assertEquals(bytes.length, f.byteCount(),
                    "manifest byteCount must match actual UTF-8 byte count for "
                            + f.relativePath());
        }
    }

    @Test
    void deterministicUnderTurkishLocale() throws Exception {
        Path sourceDefault = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outDefault = temporaryDirectory.resolve("out-default").toAbsolutePath();
        ToolchainResult.Success defaultResult =
                ApplicationTestSupport.runCampusMarket(sourceDefault, outDefault);

        Path sourceTr = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outTr = temporaryDirectory.resolve("out-tr").toAbsolutePath();
        ToolchainResult.Success trResult;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("tr", "TR"));
            trResult = ApplicationTestSupport.runCampusMarket(sourceTr, outTr);
        } finally {
            Locale.setDefault(previous);
        }

        List<AppliedFile> filesDefault = defaultResult.manifest().files();
        List<AppliedFile> filesTr = trResult.manifest().files();
        assertEquals(filesDefault.size(), filesTr.size(),
                "file count must match under Turkish locale");

        for (int i = 0; i < filesDefault.size(); i++) {
            AppliedFile fd = filesDefault.get(i);
            AppliedFile ft = filesTr.get(i);
            assertEquals(fd.relativePath(), ft.relativePath(),
                    "relativePath must match under Turkish locale at index " + i);
            assertEquals(fd.sha256Hex(), ft.sha256Hex(),
                    "sha256Hex must match under Turkish locale at index " + i
                            + " for " + fd.relativePath());
            assertEquals(fd.byteCount(), ft.byteCount(),
                    "byteCount must match under Turkish locale at index " + i
                            + " for " + fd.relativePath());
        }

        // Verify content on disk matches between default and Turkish-locale runs.
        for (AppliedFile fd : filesDefault) {
            byte[] bytesDefault = Files.readAllBytes(outDefault.resolve(fd.relativePath()));
            byte[] bytesTr = Files.readAllBytes(outTr.resolve(fd.relativePath()));
            assertIterableEquals(byteList(bytesDefault), byteList(bytesTr),
                    "content must match under Turkish locale for " + fd.relativePath());
        }
    }

    @Test
    void manifestOrderStableAcrossThreeRuns() throws Exception {
        // Triple-check determinism: three independent runs must produce
        // identical manifest path orderings.
        List<List<String>> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
            Path out = temporaryDirectory.resolve("out-" + i).toAbsolutePath();
            ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(source, out);
            runs.add(success.manifest().files().stream()
                    .map(AppliedFile::relativePath)
                    .collect(Collectors.toList()));
        }
        assertIterableEquals(runs.get(0), runs.get(1), "runs 0 and 1 must match");
        assertIterableEquals(runs.get(0), runs.get(2), "runs 0 and 2 must match");
    }

    @Test
    void manifestConflictPolicyMatchesRequest() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path out = temporaryDirectory.resolve("out").toAbsolutePath();
        ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(
                source, out, io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS);
        assertEquals(io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS,
                success.manifest().conflictPolicy(),
                "manifest conflictPolicy must match request");
    }

    @Test
    void manifestOutputRootMatchesRequest() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path out = temporaryDirectory.resolve("expected-root").toAbsolutePath();
        ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(source, out);
        assertEquals(out, success.manifest().outputRoot(),
                "manifest outputRoot must match request");
    }

    // ---- helpers ----

    private static List<Path> walkRegularFiles(Path root) throws java.io.IOException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(result::add);
        }
        // Sort by relative path string (forward-slash normalized) so the
        // comparison is independent of filesystem walk order.
        result.sort((a, b) -> {
            String ra = root.relativize(a).toString().replace('\\', '/');
            String rb = root.relativize(b).toString().replace('\\', '/');
            return ra.compareTo(rb);
        });
        return result;
    }

    private static List<Integer> byteList(byte[] bytes) {
        List<Integer> list = new ArrayList<>(bytes.length);
        for (byte b : bytes) {
            list.add((int) b & 0xFF);
        }
        return list;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}