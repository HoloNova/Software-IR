package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The version history the product relies on: a Git working tree that holds both the SIR sources and
 * the generated project.
 *
 * <p>The product keeps no history of its own (ADR-027). It only needs three things from Git: to know
 * whether a usable repository is there, to create one when there is none, and to save a snapshot of a
 * named set of files. Everything else a user does with history, such as going back to an older
 * version, is done with Git itself.
 *
 * <p>This is a port so that the application layer knows nothing about the {@code git} program; the
 * adapter that runs it lives in the {@code execution} package.
 */
public interface VersionControl {

    /** What is at the root the caller named. */
    enum State {
        /** The {@code git} program cannot be run. A change that must be saved in Git is refused. */
        UNAVAILABLE,
        /** The directory exists and is not in any repository. */
        NO_REPOSITORY,
        /** The directory is the top level of a repository. */
        REPOSITORY,
        /** The directory is inside a repository whose top level is somewhere else. */
        NOT_TOP_LEVEL
    }

    record Status(State state, String detail) {
        public Status {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** The result of saving a snapshot. */
    sealed interface SnapshotOutcome permits Taken, Failed {
    }

    /**
     * The files are saved. {@code created} is false when they were already saved exactly as they are, and
     * then {@code commit} is the existing latest commit, if the repository has one.
     */
    record Taken(Optional<String> commit, boolean created) implements SnapshotOutcome {
        public Taken {
            Objects.requireNonNull(commit, "commit");
        }
    }

    record Failed(String reason) implements SnapshotOutcome {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Inspects {@code root}; never changes anything. */
    Status inspect(Path root);

    /** Creates a repository at {@code root}. Returns the failure reason, or empty on success. */
    Optional<String> initialize(Path root);

    /**
     * Saves exactly {@code paths} (relative to {@code root}, using {@code /}) as one commit. Files that
     * exist are saved as they are, files that are gone are saved as removed, and nothing else in the
     * working tree or the index is touched. Paths are taken literally: no pattern is expanded.
     */
    SnapshotOutcome snapshot(Path root, List<String> paths, String message);
}
