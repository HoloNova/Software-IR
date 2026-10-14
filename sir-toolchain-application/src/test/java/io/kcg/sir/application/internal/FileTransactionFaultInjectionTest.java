package io.kcg.sir.application.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fault-injection coverage for the first-generation write: a failure leaves nothing behind, and an existing output root is never replaced. */
class FileTransactionFaultInjectionTest {

    private static final LoweredNodeId ARTIFACT = new LoweredNodeId("test-project");

    @TempDir
    Path temporaryDirectory;

    @Test
    void stagingFailureLeavesZeroModifications() {
        Path outputRoot = root("staging-failure");
        TransactionResult result = transaction(outputRoot,
                new TransactionHooks() {
                    @Override
                    public void beforeStagingWrite(Path stagingDir) {
                        throw new RuntimeException("injected staging failure");
                    }
                }).execute();

        TransactionResult.Failure failure =
                assertInstanceOf(TransactionResult.Failure.class, result);
        assertFalse(Files.exists(outputRoot));
    }

    @Test
    void anOutputRootThatAppearsWhileStagingIsNeverReplaced() throws Exception {
        Path outputRoot = root("concurrent-conflict");
        Path pom = outputRoot.resolve("pom.xml");

        TransactionResult result = transaction(outputRoot,
                new TransactionHooks() {
                    @Override
                    public void beforeStagingWrite(Path stagingDir) throws Exception {
                        Files.createDirectories(outputRoot);
                        Files.writeString(pom, "<concurrent/>", StandardCharsets.UTF_8);
                    }
                }).execute();

        TransactionResult.Failure failure =
                assertInstanceOf(TransactionResult.Failure.class, result);
        assertEquals("<concurrent/>", Files.readString(pom));
        assertTrue(failure.diagnostics().stream()
                .anyMatch(d -> d.code().equals("SIR-APP-CONFLICT-001")));
    }

    @Test
    void hooksUseNoGlobalMutableState() {
        assertNotSame(TransactionHooks.noOp(), TransactionHooks.noOp());
    }

    private FileTransaction transaction(Path outputRoot, TransactionHooks hooks) {
        return new FileTransaction(outputRoot, files(), hooks);
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
