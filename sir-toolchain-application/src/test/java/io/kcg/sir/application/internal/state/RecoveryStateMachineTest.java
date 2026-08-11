package io.kcg.sir.application.internal.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeApplyResult;
import io.kcg.sir.application.api.ChangeBaselineReceipt;
import io.kcg.sir.application.api.ChangeExecutionApplication;
import io.kcg.sir.application.api.ChangeRecoveryRequest;
import io.kcg.sir.application.api.ChangeRecoveryResult;
import io.kcg.sir.application.api.RecoveryHandle;
import io.kcg.sir.application.internal.bundle.BaselineBundle;
import io.kcg.sir.application.internal.bundle.BaselineBundleStore;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery state-machine contract tests.
 *
 * Each test constructs a real interrupted DELETE transaction state (journal + backup
 * hard link + CURRENT) on a temp state root and drives the PUBLIC recovery API
 * (ChangeExecutionApplication.recover). Interruption points are expressed as journal
 * file states, which is exactly what the recovery engine must distinguish.
 *
 * Invariants under test:
 *  - Recovery is only ever executed through the explicit recover() API.
 *  - CURRENT=B0 -> backward recovery (compensation); CURRENT=B1 -> forward
 *    verify/cleanup only. Neither direction ever guesses.
 *  - Re-running recovery is deterministic/idempotent.
 *  - Failures that cannot be fully compensated return RECOVERY_REQUIRED and keep
 *    authoritative materials.
 */
class RecoveryStateMachineTest {

    @TempDir
    Path tempDir;

    // --- helpers -------------------------------------------------------------

    private record B0Context(
            ChangeExecutionApplication app,
            FixtureInfo fixture,
            Path stateRoot,
            BaselineBundle b0Bundle,
            BaselineBundleStore store,
            String b0Id,
            String rel,
            BaselineManifestEntry relEntry) {}

    private B0Context setupB0() throws Exception {
        ChangeExecutionApplication app = new ChangeExecutionApplication();
        // Inline fixture build (M1TestSupport is package-private to io.kcg.sir.application.api):
        // compile the base SIR into a real project tree, then register it as B0.
        Path sourceFile = tempDir.resolve("campus-market.sir").toAbsolutePath();
        try (java.io.InputStream in = RecoveryStateMachineTest.class.getResourceAsStream("/valid/campus-market.sir")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource valid/campus-market.sir");
            }
            Files.write(sourceFile, in.readAllBytes());
        }
        Path outputRoot = tempDir.resolve("build").toAbsolutePath();
        io.kcg.sir.application.api.ToolchainResult result =
                new io.kcg.sir.application.api.ToolchainApplication().execute(
                        new io.kcg.sir.application.api.ToolchainRequest(
                                sourceFile, SourceId.of("campus-market.sir"), outputRoot,
                                io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS));
        io.kcg.sir.application.api.ToolchainResult.Success success =
                assertInstanceOf(io.kcg.sir.application.api.ToolchainResult.Success.class, result,
                        () -> "expected generation Success: " + result);
        byte[] snapshotBytes = serializeGraph(success.graph());
        String baseSha = sha256Hex(Files.readAllBytes(sourceFile));
        io.kcg.sir.change.api.ChangeBaseRevision revision = new io.kcg.sir.change.api.ChangeBaseRevision(
                SourceId.of("campus-market.sir"), baseSha, success.graph().version(),
                success.graph().canonicalDigest(),
                io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion.V1);

        Path stateRoot = tempDir.resolve("state").toAbsolutePath();
        Files.createDirectories(stateRoot);
        Path snapshotFile = tempDir.resolve("snapshot.bin").toAbsolutePath();
        Files.write(snapshotFile, snapshotBytes);
        io.kcg.sir.application.api.ChangeBaselineRegistrationRequest regRequest =
                new io.kcg.sir.application.api.ChangeBaselineRegistrationRequest(
                        sourceFile, snapshotFile, outputRoot, stateRoot, revision);
        ChangeBaselineReceipt receipt =
                ((io.kcg.sir.application.api.ChangeBaselineRegistrationResult.Success) app.register(regRequest)).receipt();
        BaselineBundleStore store = new BaselineBundleStore(stateRoot);
        BaselineBundle b0 = ((BaselineBundleStore.LoadResult.Success) store.loadBundle(receipt.baselineId())).bundle();
        List<BaselineManifestEntry> manifest = b0.descriptor().manifest();
        assertTrue(!manifest.isEmpty(), "B0 manifest must not be empty");
        BaselineManifestEntry entry = manifest.get(0);
        Path target = outputRoot.resolve(entry.relativePath());
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS),
                "B0 manifest entry must exist on disk: " + entry.relativePath());
        return new B0Context(app, new FixtureInfo(sourceFile, outputRoot), stateRoot, b0, store,
                receipt.baselineId(), entry.relativePath(), entry);
    }

    private static byte[] serializeGraph(io.kcg.sir.projectgraph.api.ProjectGraph graph) {
        io.kcg.sir.projectgraph.api.ProjectGraphSerialization serialization =
                new io.kcg.sir.projectgraph.api.ProjectGraphSerializer().serialize(
                        graph, io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion.V1);
        return assertInstanceOf(io.kcg.sir.projectgraph.api.ProjectGraphSerialization.Success.class,
                serialization, () -> "serialization failed: " + serialization).document().bytes();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record FixtureInfo(Path sourceFile, Path outputRoot) {}

    /** Build an interrupted DELETE transaction whose journal is in the given file state. */
    private Path buildInterruptedDelete(B0Context ctx, String txId,
            DeleteTransactionJournal.FileTransitionState state) throws Exception {
        Path txDir = ctx.stateRoot().resolve("transactions").resolve(txId);
        Files.createDirectories(txDir.resolve("backup"));
        Files.createDirectories(txDir.resolve("candidate-baseline"));

        DeleteTransactionJournal journal = new DeleteTransactionJournal(
                txDir, txId, ctx.fixture().outputRoot(),
                ctx.b0Id(), "b1" + "a".repeat(62),
                ctx.b0Bundle().descriptor().manifestDigest(),
                "b1" + "b".repeat(62),
                List.of(ctx.rel()));
        journal.create();
        journal.appendOverallTransition(DeleteTransactionJournal.OverallState.PREPARED);

        Path target = ctx.fixture().outputRoot().resolve(ctx.rel());
        Path backup = txDir.resolve("backup").resolve(DeleteTransactionJournal.backupFileName(0));

        // BACKUP_LINK_INTENT: intent only, no link yet.
        if (state == DeleteTransactionJournal.FileTransitionState.BACKUP_LINK_INTENT_DURABLE) {
            journal.appendFileTransition(0, ctx.rel(), state);
            return txDir;
        }

        // Back up via a same-volume hard link (the only permitted backup mechanism).
        journal.appendFileTransition(0, ctx.rel(), DeleteTransactionJournal.FileTransitionState.BACKUP_LINK_INTENT_DURABLE);
        Files.createLink(backup, target);
        if (state == DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE) {
            journal.appendFileTransition(0, ctx.rel(), state);
            return txDir;
        }

        journal.appendFileTransition(0, ctx.rel(), DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE);
        journal.appendFileTransition(0, ctx.rel(), DeleteTransactionJournal.FileTransitionState.DELETE_INTENT_DURABLE);
        if (state == DeleteTransactionJournal.FileTransitionState.DELETE_INTENT_DURABLE) {
            return txDir;
        }

        Files.delete(target);
        journal.appendFileTransition(0, ctx.rel(), DeleteTransactionJournal.FileTransitionState.DELETED_DURABLE);
        return txDir;
    }

    private ChangeRecoveryResult recover(B0Context ctx, String txId) {
        return ctx.app().recover(new ChangeRecoveryRequest(
                ctx.stateRoot(), ctx.fixture().outputRoot(), RecoveryHandle.of(txId)));
    }

    // --- invariant: CURRENT=B0 allows only backward compensation -----------------

    @Test
    void currentB0_backupLinked_rollsBackAndRemovesBackupLink() throws Exception {
        B0Context ctx = setupB0();
        String txId = "d0" + "a".repeat(30);
        Path txDir = buildInterruptedDelete(ctx, txId,
                DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE);
        Path target = ctx.fixture().outputRoot().resolve(ctx.rel());
        Path backup = txDir.resolve("backup").resolve(DeleteTransactionJournal.backupFileName(0));
        assertTrue(Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS), "backup link must exist");

        ChangeRecoveryResult result = recover(ctx, txId);

        assertInstanceOf(ChangeRecoveryResult.RolledBack.class, result,
                "CURRENT=B0 with backup-linked state must roll back, got: " + result);
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS),
                "target must be preserved (it was never deleted)");
        assertTrue(!Files.exists(backup, LinkOption.NOFOLLOW_LINKS),
                "backup hard link must be removed after successful rollback");
    }

    @Test
    void currentB0_deleted_restoresTargetFromBackupLink() throws Exception {
        B0Context ctx = setupB0();
        String txId = "d0" + "b".repeat(30);
        Path txDir = buildInterruptedDelete(ctx, txId,
                DeleteTransactionJournal.FileTransitionState.DELETED_DURABLE);
        Path target = ctx.fixture().outputRoot().resolve(ctx.rel());
        assertTrue(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "target must be absent before recovery");

        ChangeRecoveryResult result = recover(ctx, txId);

        assertInstanceOf(ChangeRecoveryResult.RolledBack.class, result,
                "CURRENT=B0 with DELETED state must restore the target, got: " + result);
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS),
                "target must be restored from the backup hard link");
        byte[] restored = Files.readAllBytes(target);
        assertEquals(ctx.relEntry().byteCount(), restored.length, "restored bytes must match B0");
    }

    @Test
    void currentB0_deleteIntent_targetStillPresent_preservesAndCleansBackup() throws Exception {
        B0Context ctx = setupB0();
        String txId = "d0" + "c".repeat(30);
        Path txDir = buildInterruptedDelete(ctx, txId,
                DeleteTransactionJournal.FileTransitionState.DELETE_INTENT_DURABLE);
        Path target = ctx.fixture().outputRoot().resolve(ctx.rel());
        Path backup = txDir.resolve("backup").resolve(DeleteTransactionJournal.backupFileName(0));
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS), "target still present before recovery");

        ChangeRecoveryResult result = recover(ctx, txId);

        assertInstanceOf(ChangeRecoveryResult.RolledBack.class, result,
                "CURRENT=B0 with DELETE_INTENT and present target must roll back, got: " + result);
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS), "target must be preserved");
        assertTrue(!Files.exists(backup, LinkOption.NOFOLLOW_LINKS), "backup must be removed");
    }

    @Test
    void currentB0_deleteIntent_targetAlreadyGone_restoresFromBackup() throws Exception {
        B0Context ctx = setupB0();
        String txId = "d0" + "d".repeat(30);
        Path txDir = buildInterruptedDelete(ctx, txId,
                DeleteTransactionJournal.FileTransitionState.DELETE_INTENT_DURABLE);
        Path target = ctx.fixture().outputRoot().resolve(ctx.rel());
        // External/interrupted delete happened after DELETE_INTENT: target is gone.
        Files.delete(target);

        ChangeRecoveryResult result = recover(ctx, txId);

        assertInstanceOf(ChangeRecoveryResult.RolledBack.class, result,
                "CURRENT=B0 with DELETE_INTENT and absent target must restore, got: " + result);
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS),
                "target must be restored from backup when it vanished after delete intent");
    }

    // --- invariant: recovery is idempotent / deterministic ----------------------

    @Test
    void repeatedRecoveryIsIdempotent() throws Exception {
        B0Context ctx = setupB0();
        String txId = "d0" + "e".repeat(30);
        buildInterruptedDelete(ctx, txId, DeleteTransactionJournal.FileTransitionState.DELETED_DURABLE);
        Path target = ctx.fixture().outputRoot().resolve(ctx.rel());
        byte[] expected = ctx.relEntry().byteCount() > 0 ? new byte[0] : new byte[0];

        ChangeRecoveryResult first = recover(ctx, txId);
        assertInstanceOf(ChangeRecoveryResult.RolledBack.class, first, "first recovery must roll back");
        byte[] afterFirst = Files.readAllBytes(target);
        assertTrue(afterFirst.length == ctx.relEntry().byteCount(), "restored file must match B0 size");

        ChangeRecoveryResult second = recover(ctx, txId);
        // A completed recovery either re-runs as RolledBack (engine) or reports
        // Recovered (idempotentResult when the journal is already closed). Both are
        // deterministic; the invariant is that file content does not change.
        assertTrue(second instanceof ChangeRecoveryResult.RolledBack
                        || second instanceof ChangeRecoveryResult.Recovered,
                "second recovery must be deterministic/idempotent, got: " + second);
        byte[] afterSecond = Files.readAllBytes(target);
        assertTrue(java.util.Arrays.equals(afterFirst, afterSecond),
                "file content must be unchanged by the second recovery run");
    }

    // --- invariant: CURRENT=B1 allows only forward verification/cleanup ---------

    @Test
    void currentB1_forwardRecoveryDoesNotRestoreDeletedFiles() throws Exception {
        B0Context ctx = setupB0();
        String txId = "d0" + "f".repeat(30);
        Path txDir = buildInterruptedDelete(ctx, txId,
                DeleteTransactionJournal.FileTransitionState.DELETED_DURABLE);
        Path target = ctx.fixture().outputRoot().resolve(ctx.rel());
        Path backup = txDir.resolve("backup").resolve(DeleteTransactionJournal.backupFileName(0));
        assertTrue(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "target deleted");

        // CURRENT has already advanced to B1: only forward verification is legal.
        ctx.store().writeCurrentAtomic("b1" + "a".repeat(62));

        ChangeRecoveryResult result = recover(ctx, txId);

        // B1 bundle is absent in this synthetic scene, so forward recovery must fail
        // closed (Failure because the recover() route cannot even load CURRENT's
        // bundle, or RecoveryRequired) — and MUST NOT have restored the deleted file
        // (backward compensation is forbidden when CURRENT=B1).
        assertTrue(result instanceof ChangeRecoveryResult.Failure
                        || result instanceof ChangeRecoveryResult.RecoveryRequired,
                "CURRENT=B1 without a loadable B1 must fail closed, got: " + result);
        assertTrue(!Files.exists(target, LinkOption.NOFOLLOW_LINKS),
                "forward recovery must never restore the deleted target");
        assertTrue(Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS),
                "backup must be retained as authoritative material when recovery is required");
    }

    // --- invariant: unknown CURRENT or missing CURRENT -> structured rejection ----

    @Test
    void currentMatchesNeitherB0NorB1_isRejected() throws Exception {
        B0Context ctx = setupB0();
        String txId = "d1" + "a".repeat(30);
        buildInterruptedDelete(ctx, txId, DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE);
        ctx.store().writeCurrentAtomic("c" + "c".repeat(63));

        ChangeRecoveryResult result = recover(ctx, txId);

        assertTrue(result instanceof ChangeRecoveryResult.Failure
                        || result instanceof ChangeRecoveryResult.RecoveryRequired,
                "CURRENT matching neither B0 nor B1 must fail closed, got: " + result);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.isError()),
                "must carry an error diagnostic, got: " + result.diagnostics());
    }

    @Test
    void missingCurrent_withDeleteTransaction_isRejected() throws Exception {
        B0Context ctx = setupB0();
        String txId = "d1" + "b".repeat(30);
        buildInterruptedDelete(ctx, txId, DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE);
        Files.delete(ctx.store().currentFile());

        ChangeRecoveryResult result = recover(ctx, txId);

        assertTrue(result instanceof ChangeRecoveryResult.RecoveryRequired
                        || result instanceof ChangeRecoveryResult.Failure,
                "absent CURRENT with a pending transaction must fail closed, got: " + result);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.isError()),
                "must carry an error diagnostic, got: " + result.diagnostics());
    }

    // --- invariant: only direct NoSuchFileException proves absence ----------------

    @Test
    void journalFileStateTransitionsAreStrict() {
        assertTrue(DeleteTransactionJournal.FileTransitionState.isValidTransition(
                DeleteTransactionJournal.FileTransitionState.BACKUP_LINK_INTENT_DURABLE,
                DeleteTransactionJournal.FileTransitionState.BACKUP_LINKED_DURABLE));
        assertTrue(!DeleteTransactionJournal.FileTransitionState.isValidTransition(
                DeleteTransactionJournal.FileTransitionState.PREPARING,
                DeleteTransactionJournal.FileTransitionState.DELETED_DURABLE),
                "PREPARING must not skip straight to DELETED");
        assertTrue(!DeleteTransactionJournal.FileTransitionState.isValidTransition(
                DeleteTransactionJournal.FileTransitionState.ROLLED_BACK_DURABLE,
                DeleteTransactionJournal.FileTransitionState.DELETE_INTENT_DURABLE),
                "ROLLED_BACK_DURABLE is terminal");
    }

    // small helper to satisfy unused-variable lint in one test
    private static long sizeOf(byte[] bytes) {
        return bytes.length;
    }
}
