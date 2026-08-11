package io.kcg.sir.application.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.FailureDisposition;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fault-injection coverage for the package-private transaction seam. */
class FileTransactionFaultInjectionTest {

    private static final LoweredNodeId ARTIFACT = new LoweredNodeId("test-project");

    @TempDir
    Path temporaryDirectory;

    @Test
    void stagingFailureLeavesZeroModifications() {
        Path outputRoot = root("staging-failure");
        TransactionResult result = transaction(outputRoot, ConflictPolicy.FAIL_IF_EXISTS,
                new TransactionHooks() {
                    @Override
                    public void beforeStagingWrite(Path stagingDir) {
                        throw new RuntimeException("injected staging failure");
                    }
                }).execute();

        TransactionResult.Failure failure =
                assertInstanceOf(TransactionResult.Failure.class, result);
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertFalse(Files.exists(outputRoot));
    }

    @Test
    void commitFailureAtThirdFileRollsBackCompletely() throws Exception {
        Path outputRoot = root("third-file");
        Files.createDirectories(outputRoot);
        TransactionResult result = transaction(outputRoot, ConflictPolicy.REPLACE_EXISTING,
                new TransactionHooks() {
                    @Override
                    public void beforeCommitFile(int index, Path stagedPath, Path target) {
                        if (index == 2) {
                            throw new RuntimeException("injected commit failure");
                        }
                    }
                }).execute();

        TransactionResult.Failure failure =
                assertInstanceOf(TransactionResult.Failure.class, result);
        assertEquals(FailureDisposition.ROLLED_BACK, failure.disposition());
        try (var paths = Files.walk(outputRoot)) {
            assertFalse(paths.anyMatch(Files::isRegularFile));
        }
    }

    @Test
    void postReplaceFailureRestoresOriginalContent() throws Exception {
        Path outputRoot = root("post-replace");
        Files.createDirectories(outputRoot);
        Path pom = outputRoot.resolve("pom.xml");
        Files.writeString(pom, "<original/>", StandardCharsets.UTF_8);

        TransactionResult result = transaction(outputRoot, ConflictPolicy.REPLACE_EXISTING,
                new TransactionHooks() {
                    @Override
                    public void beforeCommitFile(int index, Path stagedPath, Path target) {
                        if (index == 1) {
                            throw new RuntimeException("injected failure after replace");
                        }
                    }
                }).execute();

        TransactionResult.Failure failure =
                assertInstanceOf(TransactionResult.Failure.class, result);
        assertEquals(FailureDisposition.ROLLED_BACK, failure.disposition());
        assertEquals("<original/>", Files.readString(pom));
    }

    @Test
    void failureAfterBackingUpCurrentFileRestoresThatFile() throws Exception {
        Path outputRoot = root("current-backup");
        Files.createDirectories(outputRoot);
        Path pom = outputRoot.resolve("pom.xml");
        Files.writeString(pom, "<original-current/>", StandardCharsets.UTF_8);

        TransactionResult result = transaction(outputRoot, ConflictPolicy.REPLACE_EXISTING,
                new TransactionHooks() {
                    @Override
                    public void afterBackupBeforeCommit(int index, Path backup, Path target) {
                        if (index == 0) {
                            throw new RuntimeException("injected failure after backup");
                        }
                    }
                }).execute();

        TransactionResult.Failure failure =
                assertInstanceOf(TransactionResult.Failure.class, result);
        assertEquals(FailureDisposition.ROLLED_BACK, failure.disposition());
        assertTrue(Files.exists(pom));
        assertEquals("<original-current/>", Files.readString(pom));
    }

    @Test
    void failIfExistsRechecksConflictAtCommitTime() throws Exception {
        Path outputRoot = root("concurrent-conflict");
        Files.createDirectories(outputRoot);
        Path pom = outputRoot.resolve("pom.xml");

        TransactionResult result = transaction(outputRoot, ConflictPolicy.FAIL_IF_EXISTS,
                new TransactionHooks() {
                    @Override
                    public void beforeStagingWrite(Path stagingDir) throws Exception {
                        Files.writeString(pom, "<concurrent/>", StandardCharsets.UTF_8);
                    }
                }).execute();

        TransactionResult.Failure failure =
                assertInstanceOf(TransactionResult.Failure.class, result);
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertEquals("<concurrent/>", Files.readString(pom));
        assertTrue(failure.diagnostics().stream()
                .anyMatch(d -> d.code().equals("SIR-APP-CONFLICT-001")));
    }

    @Test
    void rollbackFailureReturnsRecoveryRequired() throws Exception {
        Path outputRoot = root("rollback-failure");
        Files.createDirectories(outputRoot);
        Files.writeString(outputRoot.resolve("pom.xml"), "<original/>");
        AtomicInteger rollbackCalls = new AtomicInteger();

        TransactionResult result = transaction(outputRoot, ConflictPolicy.REPLACE_EXISTING,
                new TransactionHooks() {
                    @Override
                    public void beforeCommitFile(int index, Path stagedPath, Path target) {
                        if (index == 1) {
                            throw new RuntimeException("injected commit failure");
                        }
                    }

                    @Override
                    public void beforeRollbackStep(int stepIndex, String description) {
                        if (rollbackCalls.getAndIncrement() == 0) {
                            throw new RuntimeException("injected rollback failure");
                        }
                    }
                }).execute();

        TransactionResult.Failure failure =
                assertInstanceOf(TransactionResult.Failure.class, result);
        assertEquals(FailureDisposition.RECOVERY_REQUIRED, failure.disposition());
        assertTrue(failure.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("SIR-APP-ROLLBACK")));
    }

    @Test
    void hooksUseNoGlobalMutableState() {
        assertNotSame(TransactionHooks.noOp(), TransactionHooks.noOp());
    }

    private FileTransaction transaction(
            Path outputRoot, ConflictPolicy policy, TransactionHooks hooks) {
        return new FileTransaction(outputRoot, files(), policy, hooks);
    }

    private Path root(String name) {
        return temporaryDirectory.resolve(name).toAbsolutePath();
    }

    private static List<GeneratedFile> files() {
        return List.of(
                file("pom.xml", "<generated/>\n"),
                file("src/main/java/App.java", "class App {}\n"),
                file("src/main/resources/application.properties", "app.name=test\n"));
    }

    private static GeneratedFile file(String path, String content) {
        return new GeneratedFile(path, content, ARTIFACT, Optional.empty());
    }
}
