package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ExecutionManifest;
import io.kcg.sir.application.api.ToolchainResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * campus-market through the full public pipeline must write a complete Spring
 * Boot project: pom.xml, Application, Entity, Mapper, DTO, Exception, Service,
 * Controller.
 */
class ToolchainHappyPathTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void campusMarketWritesCompleteProject() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("generated-project").toAbsolutePath();

        ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(source, outputRoot);

        ExecutionManifest manifest = success.manifest();
        assertEquals(outputRoot, manifest.outputRoot());
        assertFalse(manifest.files().isEmpty(), "manifest must contain files");

        Set<String> relativePaths = new HashSet<>();
        for (AppliedFile f : manifest.files()) {
            relativePaths.add(f.relativePath());
        }

        assertTrue(relativePaths.contains("pom.xml"), "must contain pom.xml: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("Application.java")),
                "must contain Application.java: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("GoodsStatus.java")),
                "must contain enum: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("User.java")),
                "must contain entity User: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("Goods.java")),
                "must contain entity Goods: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("UserMapper.java")),
                "must contain mapper: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("GoodsMapper.java")),
                "must contain mapper: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("PublishGoodsInput.java")),
                "must contain DTO: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("InvalidGoodsPriceException.java")),
                "must contain exception: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("PublishGoodsService.java")),
                "must contain service: " + relativePaths);
        assertTrue(relativePaths.stream().anyMatch(p -> p.endsWith("PublishGoodsController.java")),
                "must contain controller: " + relativePaths);
    }

    @Test
    void allManifestFilesExistOnDisk() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();

        ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(source, outputRoot);

        for (AppliedFile f : success.manifest().files()) {
            Path onDisk = outputRoot.resolve(f.relativePath());
            assertTrue(Files.isRegularFile(onDisk),
                    "file must exist on disk: " + f.relativePath());
        }
    }

    @Test
    void allAppliedFilesMarkedCreatedForNewRoot() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();

        ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(source, outputRoot);

        for (AppliedFile f : success.manifest().files()) {
            assertEquals(io.kcg.sir.application.api.FileAction.CREATED, f.action(),
                    "new root files must be CREATED: " + f.relativePath());
        }
    }

    @Test
    void sha256MatchesActualUtf8Bytes() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();

        ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(source, outputRoot);

        for (AppliedFile f : success.manifest().files()) {
            Path onDisk = outputRoot.resolve(f.relativePath());
            byte[] bytes = Files.readAllBytes(onDisk);
            String expected = sha256Hex(bytes);
            assertEquals(expected, f.sha256Hex(),
                    "SHA-256 must match for " + f.relativePath());
            assertEquals(bytes.length, f.byteCount(),
                    "byte count must match for " + f.relativePath());
        }
    }

    @Test
    void noTempDirNamesInManifest() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();

        ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(source, outputRoot);

        for (AppliedFile f : success.manifest().files()) {
            assertFalse(f.relativePath().contains(".sir-tx-"),
                    "temp dir name must not appear in manifest: " + f.relativePath());
            assertFalse(f.relativePath().contains(".sir-bak-"),
                    "backup dir name must not appear in manifest: " + f.relativePath());
        }
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
            return sb.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
