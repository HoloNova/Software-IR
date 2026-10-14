package io.kcg.sir.application.api;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Bundle;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Problem;

import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineStore;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.application.internal.projectbaseline.ProjectPublicationHistory;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.state.StateRootLock;
import io.kcg.sir.application.internal.state.StateRootPathGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Continues a project in a fresh state directory once the old one has used up its history budget.
 *
 * <p>Why this exists: a state directory keeps every version it has stored, the relation between versions and a receipt
 * for every update, and re-reads and cross-checks all of it each time it is opened. That is why the history is bounded
 * (8 versions, 32 publications) and why nothing deletes it automatically: it is the evidence recovery relies on. When
 * the bound is reached the honest way forward is not to delete evidence but to start a new state at the current
 * version, which this class does with operations that already exist: it reads the old state through the same strict
 * verification an inspection uses, registers the very same sources in an empty new directory (the baseline ID is a pure
 * function of the sources, the generated files and the output root, so it is the same ID), proves the new state is
 * exactly that one version, and only then retires the old state.
 *
 * <p>Retiring writes one marker file ({@code RETIRED}) into the old state. The toolchain refuses any state with an
 * object it does not know, so after that no entry can update through the old directory by mistake, which would move
 * the project away from the new state. Nothing else in the old state is touched, and nothing is deleted. The old
 * directory then is evidence for manual inspection, valid only at its original path while the output still matches;
 * it is not promised to be re-openable by the toolchain, and deleting it is always safe for the new state.
 *
 * <p>Process-crash safety: the old state is changed last and only by that one marker. A crash before it leaves the old
 * state live and a new directory that is either partial (discard it) or already exactly the new state, in which case
 * running the same request again only completes the retirement.
 */
public final class VersionStateRolloverApplication {
    /** Name of the retirement marker in the old state root. */
    public static final String MARKER = "RETIRED";
    private static final String MARKER_MAGIC = "KCG-STATE-RETIRED-V1\n";
    private static final Set<String> FRESH_ROOT = Set.of("LOCK", "baselines", "CURRENT");

    private final Consumer<String> checkpoint;

    public VersionStateRolloverApplication() {
        this(ignored -> { });
    }

    /** Checkpoints are named steps for tests that stop the process at that point. */
    VersionStateRolloverApplication(Consumer<String> checkpoint) {
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
    }

    public VersionStateRolloverResult rollover(VersionStateRolloverRequest request) {
        Objects.requireNonNull(request, "request");
        Path oldRoot = request.oldStateRoot();
        Path newRoot = request.newStateRoot();
        Path output = request.outputRoot();
        String expected = request.expectedBaselineId();

        VersionStateRolloverResult.Refused paths = checkPaths(oldRoot, newRoot, output);
        if (paths != null) {
            return paths;
        }

        boolean newTouched = false;
        try {
            var oldLock = new StateRootLock(oldRoot).tryAcquireExisting();
            if (!(oldLock instanceof StateRootLock.HeldLock held)) {
                var failure = (StateRootLock.LockFailure) oldLock;
                return refused("OLD_STATE_LOCKED", failure.code() + ": " + failure.message(), false);
            }

            try (held; var oldState = new SecureFileAccess(oldRoot)) {
                if (oldState.exists(MARKER)) {
                    return refused("OLD_STATE_RETIRED", "the old state was already retired: " + describeMarker(oldState), false);
                }

                var diagnostics = new ArrayList<ExecutionDiagnostic>();
                var verified = ProjectBaselineVerification.read(oldState, expected, output, diagnostics);
                if (verified == null) {
                    String why = diagnostics.stream().filter(ExecutionDiagnostic::isError).map(d -> d.code() + ": " + d.message()).findFirst().orElse("unknown");
                    return refused("OLD_STATE_REFUSED", why, false);
                }

                Bundle old = verified.bundle();
                VersionStateRolloverResult.ArchiveFacts facts = archive(oldState, expected);
                this.checkpoint.accept("old-verified");

                boolean resumed;
                ProjectBaselineReceipt receipt;
                var emptiness = classify(newRoot);
                if (emptiness == Emptiness.OCCUPIED) {
                    // A previous call may have created exactly the new state and stopped before retiring the old one.
                    if (!isExactlyFresh(newRoot, expected, output, old)) {
                        return refused("NEW_ROOT_NOT_EMPTY", "the new state root exists and is not empty (and is not exactly the fresh state for "
                                + expected + "); it was not modified. Choose an empty or absent directory", false);
                    }

                    resumed = true;
                    receipt = receipt(old);
                } else {
                    resumed = false;
                    newTouched = true;
                    var registered = new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(old.sources(), output, newRoot));
                    if (!(registered instanceof ProjectBaselineResult.Success success)) {
                        var failure = (ProjectBaselineResult.Failure) registered;
                        return refused("NEW_STATE_REGISTRATION_FAILED", failure.diagnostics().stream().filter(ExecutionDiagnostic::isError)
                                .map(d -> d.code() + ": " + d.message()).findFirst().orElse("unknown"), true);
                    }

                    if (success.outcome() != ProjectBaselineResult.Outcome.REGISTERED) {
                        return refused("NEW_STATE_NOT_FRESH", "registration did not create a fresh state (" + success.outcome() + ")", true);
                    }

                    receipt = success.receipt();
                    this.checkpoint.accept("new-created");
                    if (!isExactlyFresh(newRoot, expected, output, old)) {
                        return refused("NEW_STATE_MISMATCH", "the new state is not exactly the old version as a single fresh version; discard it", true);
                    }
                }

                this.checkpoint.accept("new-verified");
                if (!receipt.baselineId().equals(expected)) {
                    return refused("NEW_STATE_MISMATCH", "the new state has a different baseline ID than the old version", newTouched);
                }

                ProjectBaselineVerification.revalidate(oldState, old);
                this.checkpoint.accept("old-revalidated");
                oldState.writeNew(MARKER, marker(expected, newRoot), ignored -> { });
                this.checkpoint.accept("retired");
                return new VersionStateRolloverResult.RolledOver(oldRoot, newRoot, output, receipt, facts, resumed, notes(oldRoot, newRoot));
            }
        } catch (Problem e) {
            return refused("OLD_STATE_REFUSED", e.code + ": " + e.getMessage(), newTouched);
        } catch (IOException | RuntimeException e) {
            // A thrown exception from the middle of the work: the old state may or may not carry the marker, so say what is known.
            return refused("ROLLOVER_INTERRUPTED", e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " (run the same request again; the old state is only changed by its last step)", newTouched);
        }
    }

    // ------------------------------------------------------------------ paths

    private static VersionStateRolloverResult.Refused checkPaths(Path oldRoot, Path newRoot, Path output) {
        String relation = relation(oldRoot, newRoot, output);
        if (relation == null) {
            // Resolve links too: two different spellings of one directory are the same directory.
            relation = relation(real(oldRoot), real(newRoot), real(output));
        }

        if (relation != null) {
            return refused("REQUEST_PATHS", relation, false);
        }

        for (var guard : List.of(StateRootPathGuard.validate(oldRoot, output, true), StateRootPathGuard.validate(newRoot, output, false))) {
            if (!guard.errors().isEmpty()) {
                return refused("REQUEST_PATHS", guard.errors().get(0).message(), false);
            }
        }

        return null;
    }

    private static String relation(Path oldRoot, Path newRoot, Path output) {
        if (nested(oldRoot, newRoot)) {
            return "the new state root must be a different directory that is neither inside nor around the old one";
        }

        if (nested(newRoot, output) || nested(oldRoot, output)) {
            return "state roots and the output root must be separate directories, none inside another";
        }

        return null;
    }

    private static boolean nested(Path a, Path b) {
        return a.equals(b) || a.startsWith(b) || b.startsWith(a);
    }

    /** The real path of what exists; for a path that does not exist yet, its nearest existing ancestor's real path plus the rest. */
    private static Path real(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }

        if (existing == null) {
            return path;
        }

        try {
            return existing.toRealPath().resolve(existing.relativize(path)).normalize();
        } catch (IOException e) {
            return path;
        }
    }

    // ------------------------------------------------------------------ the new state

    private enum Emptiness { FREE, OCCUPIED }

    private static Emptiness classify(Path newRoot) throws IOException {
        try {
            var attributes = Files.readAttributes(newRoot, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                return Emptiness.OCCUPIED;
            }
        } catch (NoSuchFileException absent) {
            return Emptiness.FREE;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(newRoot)) {
            return entries.iterator().hasNext() ? Emptiness.OCCUPIED : Emptiness.FREE;
        }
    }

    /**
     * True only when the directory is exactly a fresh state holding the one old version: nothing but the lock, one Bundle
     * and the pointer, no history and no transactions, the same Bundle bytes, and it opens through the strict read.
     */
    private boolean isExactlyFresh(Path newRoot, String expected, Path output, Bundle old) {
        try {
            var lock = new StateRootLock(newRoot).tryAcquireExisting();
            if (!(lock instanceof StateRootLock.HeldLock held)) {
                return false;
            }

            try (held; var state = new SecureFileAccess(newRoot)) {
                if (!state.list("").equals(FRESH_ROOT) || !state.list("baselines").equals(Set.of(expected))) {
                    return false;
                }

                var verified = ProjectBaselineVerification.read(state, expected, output, new ArrayList<>());
                if (verified == null) {
                    return false;
                }

                Bundle fresh = verified.bundle();
                return Arrays.equals(fresh.descriptorBytes(), old.descriptorBytes()) && Arrays.equals(fresh.payloadBytes(), old.payloadBytes())
                        && Arrays.equals(fresh.graphBytes(), old.graphBytes());
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the old state

    private static VersionStateRolloverResult.ArchiveFacts archive(SecureFileAccess oldState, String current) throws IOException {
        if (!oldState.list("").contains(ProjectPublicationHistory.DIRECTORY)) {
            return new VersionStateRolloverResult.ArchiveFacts(1, 0, 0, 0);
        }

        var view = ProjectPublicationHistory.validate(oldState, current);
        return new VersionStateRolloverResult.ArchiveFacts(Math.max(1, view.baselineIds().size()), view.edgeIds().size(), view.receiptCount(), view.byteCount());
    }

    private static byte[] marker(String baselineId, Path newRoot) {
        return (MARKER_MAGIC + "baselineId=" + baselineId + "\nnewStateRoot=" + newRoot + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String describeMarker(SecureFileAccess oldState) {
        try {
            String text = new String(oldState.read(MARKER, 4096), StandardCharsets.UTF_8);
            return text.startsWith(MARKER_MAGIC) ? text.substring(MARKER_MAGIC.length()).replace('\n', ' ').strip() : "unrecognized marker";
        } catch (IOException e) {
            return "unreadable marker";
        }
    }

    private static ProjectBaselineReceipt receipt(Bundle bundle) {
        var d = bundle.descriptor();
        return new ProjectBaselineReceipt(bundle.baselineId(), d.outputRoot(), d.entry(), d.sourceSetSha(), d.graphDigest(), d.manifestDigest());
    }

    private static List<String> notes(Path oldRoot, Path newRoot) {
        return List.of(
                "continue only in " + newRoot + "; the old state " + oldRoot + " is retired and the toolchain refuses it",
                "the old directory is evidence of the old history for manual inspection; it is valid only at its original path while the output matches",
                "deleting the old directory is safe for the new state and the output; they share no files",
                "to move the new state to another machine keep hard links (tar, cp -a, rsync -aH), the same absolute output path and the same toolchain build, and run an inspection there before using it");
    }

    private static VersionStateRolloverResult.Refused refused(String code, String message, boolean newTouched) {
        return new VersionStateRolloverResult.Refused(code, message,
                newTouched ? VersionStateRolloverResult.NewStateTouched.PARTIAL_STATE : VersionStateRolloverResult.NewStateTouched.NO);
    }

}
