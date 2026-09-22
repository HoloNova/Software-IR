package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.kcg.sir.application.api.ChangeFaultMatrixSupport.Baseline;
import io.kcg.sir.application.api.ChangeFaultMatrixSupport.Family;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parts of the fault matrix that are about what the transactions must <em>refuse</em> to do:
 * back up by copying, guess a direction when CURRENT is ambiguous, or drop the evidence that makes
 * a run recoverable.
 *
 * <p>All cases start from a real interrupted transaction, not from a hand-built intermediate state,
 * so the state under test is one the production code actually produced.
 */
class FaultMatrixRecoveryAndVolumeTest {

    @TempDir
    Path tempDir;

    /**
     * DELETE must back up with a same-volume hard link, never a byte copy.
     *
     * <p>Observed live at the moment the link is created: the backup is the same file as the target
     * ({@code isSameFile}) and its link count is at least two, which a copy cannot satisfy. The
     * observation has to happen inside the transaction because a successful cleanup removes the
     * backup afterwards, so inspecting a finished run would prove nothing.
     */
    @Test
    void deleteBackupIsAHardLinkToTheTargetNotACopy() throws Exception {
        Baseline baseline = ChangeFaultMatrixSupport.prepare(tempDir, Family.DELETE);
        Map<Path, String> observed = new java.util.LinkedHashMap<>();
        List<String> relativePaths = new ArrayList<>();

        io.kcg.sir.application.internal.state.ApplyHooks observer =
                new io.kcg.sir.application.internal.state.ApplyHooks() {
                    @Override
                    public void afterBackupLinkCreate(int index, String relPath, Path backup)
                            throws Exception {
                        Path target = baseline.outputRoot().resolve(relPath);
                        Object links = Files.getAttribute(backup, "unix:nlink",
                                LinkOption.NOFOLLOW_LINKS);
                        observed.put(backup, "nlink=" + links
                                + " sameFile=" + Files.isSameFile(backup, target)
                                + " sameStore=" + Files.getFileStore(backup)
                                        .equals(Files.getFileStore(target)));
                        relativePaths.add(relPath);
                    }
                };

        ChangeApplyResult result = ChangeFaultMatrixSupport.apply(baseline, observer);

        assertInstanceOf(ChangeApplyResult.Applied.class, result,
                () -> "the observed run must complete: " + result);
        assertFalse(observed.isEmpty(), "no backup link was created, so nothing was verified");
        observed.forEach((backup, facts) -> {
            assertTrue(facts.contains("sameFile=true"),
                    "the backup must be the same file as the target: " + backup + " " + facts);
            assertTrue(facts.contains("sameStore=true"),
                    "a hard link cannot cross file stores: " + backup + " " + facts);
            long links = Long.parseLong(facts.substring("nlink=".length(),
                    facts.indexOf(' ')));
            assertTrue(links >= 2,
                    "a backup with one name is a copy, not a hard link: " + backup + " " + facts);
        });
    }

    /**
     * Recovery must fail closed, and keep its evidence, when CURRENT does not identify any baseline
     * the transaction could have produced.
     *
     * <p>The run first fails for real (leaving a recovery handle and journal evidence), then CURRENT
     * is replaced with a pointer to an unknown baseline. ADR-020 forbids guessing a direction in
     * that situation, so recovery must refuse and must not delete the transaction evidence.
     */
    @Test
    void ambiguousCurrentAfterARealFailureIsRejectedAndEvidenceKept() throws Exception {
        Baseline baseline = ChangeFaultMatrixSupport.prepare(tempDir, Family.UPDATE);
        FailingApplyHooks hooks = new FailingApplyHooks("afterBundlePublish");

        ChangeApplyResult interrupted = ChangeFaultMatrixSupport.apply(baseline, hooks);
        ChangeApplyResult.RecoveryRequired recoveryRequired =
                assertInstanceOf(ChangeApplyResult.RecoveryRequired.class, interrupted,
                        () -> "expected a recoverable interruption: " + interrupted);
        Map<String, Integer> evidenceBefore =
                ChangeFaultMatrixSupport.transactionEvidence(baseline.stateRoot());
        assertFalse(evidenceBefore.isEmpty(), "the interrupted run must leave transaction evidence");

        // Fabricate a CURRENT that names a baseline this transaction could never have produced.
        Files.writeString(baseline.stateRoot().resolve("CURRENT"), "0".repeat(64) + "\n",
                StandardCharsets.US_ASCII);

        ChangeRecoveryResult recovery =
                ChangeFaultMatrixSupport.recover(baseline, recoveryRequired.handle());

        assertTrue(recovery instanceof ChangeRecoveryResult.Failure
                        || recovery instanceof ChangeRecoveryResult.RecoveryRequired,
                "recovery must refuse while CURRENT names an unknown baseline, not recover: "
                        + recovery);
        assertEquals(evidenceBefore,
                ChangeFaultMatrixSupport.transactionEvidence(baseline.stateRoot()),
                "fail-closed recovery must keep the transaction evidence for a later decision");
    }

    /**
     * Recovery must fail closed when CURRENT is missing entirely.
     *
     * <p>A missing pointer is not evidence that the previous baseline is still current, so no
     * direction may be inferred from it.
     */
    @Test
    void missingCurrentAfterARealFailureIsRejectedAndEvidenceKept() throws Exception {
        Baseline baseline = ChangeFaultMatrixSupport.prepare(tempDir, Family.DELETE);
        FailingApplyHooks hooks = new FailingApplyHooks("afterBundlePublish");

        ChangeApplyResult interrupted = ChangeFaultMatrixSupport.apply(baseline, hooks);
        ChangeApplyResult.RecoveryRequired recoveryRequired =
                assertInstanceOf(ChangeApplyResult.RecoveryRequired.class, interrupted,
                        () -> "expected a recoverable interruption: " + interrupted);
        Map<String, Integer> evidenceBefore =
                ChangeFaultMatrixSupport.transactionEvidence(baseline.stateRoot());
        assertFalse(evidenceBefore.isEmpty(), "the interrupted run must leave transaction evidence");

        Files.delete(baseline.stateRoot().resolve("CURRENT"));

        ChangeRecoveryResult recovery =
                ChangeFaultMatrixSupport.recover(baseline, recoveryRequired.handle());

        assertTrue(recovery instanceof ChangeRecoveryResult.Failure
                        || recovery instanceof ChangeRecoveryResult.RecoveryRequired,
                "recovery must refuse without CURRENT, not recover: " + recovery);
        assertEquals(evidenceBefore,
                ChangeFaultMatrixSupport.transactionEvidence(baseline.stateRoot()),
                "fail-closed recovery must keep the transaction evidence");
    }

    /**
     * A setup whose state root and output root live on different file stores is refused before any
     * file is touched.
     *
     * <p>This matters for the DELETE constraint: its backup is a hard link, and a hard link cannot
     * cross file stores. The system therefore has to refuse such a layout instead of quietly
     * switching to a copy — and the observed refusal happens at registration, i.e. before any
     * generated file exists to protect.
     *
     * <p>The test skips itself when this machine has no second file store, and reports that as a
     * skip rather than as a pass.
     */
    @Test
    void crossVolumeLayoutIsRefusedBeforeAnythingIsWritten() throws Exception {
        Path otherStore = findForeignFileStore(tempDir);
        assumeTrue(otherStore != null,
                "no second file store is available on this machine, so the cross-volume case "
                        + "cannot be exercised here (recorded as NOT_RUN, not as a pass)");

        Path foreignOutput = Files.createDirectory(otherStore.resolve(
                "kcg-fault-matrix-" + System.nanoTime()));
        try {
            // Registration is the first step that has to relate the two roots, so it is where a
            // cross-store layout must be rejected.
            ChangeFaultMatrixSupport.CrossVolumeAttempt attempt =
                    ChangeFaultMatrixSupport.attemptRegistrationAcrossStores(tempDir, foreignOutput);

            assertFalse(attempt.registered(),
                    "a state root and output root on different file stores must be refused, not "
                            + "silently accepted with copied backups");
            assertFalse(attempt.diagnostics().isEmpty(),
                    "the refusal must carry structured diagnostics rather than failing silently");
            assertTrue(ChangeFaultMatrixSupport.treeEquals(attempt.treeBeforeRegistration(),
                            ChangeFaultMatrixSupport.readTree(attempt.outputRoot())),
                    "a refused cross-store registration must not modify the generated output");
        } finally {
            deleteRecursively(foreignOutput);
        }
    }

    /**
     * @param tempDir a directory on the machine's primary store
     * @return a directory on a different file store, or {@code null} when none exists
     */
    private static Path findForeignFileStore(Path tempDir) throws IOException {
        Object ownStore = Files.getFileStore(tempDir).name();
        for (String candidate : List.of("/dev/shm", "/run/shm", "/tmp")) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path) && Files.isWritable(path)
                    && !Files.getFileStore(path).name().equals(ownStore)) {
                return path;
            }
        }
        return null;
    }

    /**
     * @param root        the directory to walk
     * @param pathFragment only files whose path contains this fragment are returned
     * @return the matching regular files, following no links
     */
    private static List<Path> filesUnder(Path root, String pathFragment) throws IOException {
        List<Path> found = new ArrayList<>();
        if (!Files.exists(root)) {
            return found;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && path.toString().replace('\\', '/').contains(pathFragment)) {
                    found.add(path);
                }
            }
        }
        return found;
    }

    /**
     * @param baseline the fixture
     * @param backup   a file inside the transaction backup directory
     * @return the output-relative path the backup stands for, or {@code null} when the file is not
     *         under a backup directory
     */
    private static String relativeOutputPath(Baseline baseline, Path backup) {
        String text = backup.toString().replace('\\', '/');
        int marker = text.indexOf("/backup/");
        return marker < 0 ? null : text.substring(marker + "/backup/".length());
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
