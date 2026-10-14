package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.PathGuard;
import io.kcg.sir.application.internal.Sha256;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything that must change to move a project from the SIR sources on disk to a candidate set of
 * sources: the source files, and the generated files to create, update or delete.
 *
 * <p>The current version is not a stored record; it is the SIR on disk, and the plan is bound to it and to
 * the candidate by their identities. Two plans are equal exactly when everything below is equal, which is how an apply
 * proves that the plan the caller saw is the one it is about to carry out.
 */
public record WorkspacePlan(ProjectBaselineReceipt base, ProjectBaselineReceipt candidate, SourceDelta sources,
        List<FileOp> files, int unchangedFiles) {

    /** Adding one entity touches dozens of files. */
    public static final int MAX_FILE_OPS = 128;

    public enum Action { CREATE, UPDATE, DELETE }

    /** One generated file. {@code before*} is empty for a CREATE and {@code after*} for a DELETE. */
    public record FileOp(String path, Action action, Optional<String> owner, long beforeBytes, String beforeSha256, long afterBytes, String afterSha256) {
        public FileOp {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(action, "action");
            owner = Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(beforeSha256, "beforeSha256");
            Objects.requireNonNull(afterSha256, "afterSha256");
            if (PathGuard.validateRelativePath(path) != null) {
                throw new IllegalArgumentException("unsafe generated path: " + path);
            }

            boolean hasBefore = !beforeSha256.isEmpty();
            boolean hasAfter = !afterSha256.isEmpty();
            boolean valid = switch (action) {
                case CREATE -> !hasBefore && beforeBytes == 0 && hasAfter && afterBytes >= 0;
                case DELETE -> hasBefore && beforeBytes >= 0 && !hasAfter && afterBytes == 0;
                case UPDATE -> hasBefore && hasAfter && beforeBytes >= 0 && afterBytes >= 0 && !beforeSha256.equals(afterSha256);
            };
            if (!valid || (hasBefore && !beforeSha256.matches("[0-9a-f]{64}")) || (hasAfter && !afterSha256.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("inconsistent " + action + " evidence for " + path);
            }
        }
    }

    public WorkspacePlan {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(sources, "sources");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (base.baselineId().equals(candidate.baselineId()) || !base.boundOutputRoot().equals(candidate.boundOutputRoot())) {
            throw new IllegalArgumentException("a plan joins two different versions of one output root");
        }

        if (files.size() > MAX_FILE_OPS || unchangedFiles < 0) {
            throw new IllegalArgumentException("file operation count outside budget");
        }

        String previous = null;
        for (FileOp op : files) {
            if (previous != null && previous.compareTo(op.path()) >= 0) {
                throw new IllegalArgumentException("file operations must be strictly sorted by path");
            }

            previous = op.path();
        }
    }

    /** The source changed and no generated file does. */
    public boolean sourceOnly() {
        return this.files.isEmpty();
    }

    public long count(Action action) {
        return this.files.stream().filter(f -> f.action() == action).count();
    }

    /** SHA-256 of a canonical text of everything above. */
    public String sha256Hex() {
        List<String> lines = new ArrayList<>();
        lines.add("KCG-WORKSPACE-PLAN-V1");
        lines.add(this.base.boundOutputRoot().toString());
        for (ProjectBaselineReceipt r : List.of(this.base, this.candidate)) {
            lines.add(String.join("|", r.baselineId(), r.entry().value(), r.sourceSetSha256Hex(), r.graphCanonicalDigest(), r.manifestDigest()));
        }

        lines.add("sources " + this.sources.entries().size());
        for (SourceDelta.Entry e : this.sources.entries()) {
            lines.add(String.join("|", e.source().value(), e.action().name(), e.beforeSha256(), e.afterSha256()));
        }

        lines.add("files " + this.files.size() + " unchanged " + this.unchangedFiles);
        for (FileOp f : this.files) {
            lines.add(String.join("|", f.path(), f.action().name(), f.owner().orElse(""), Long.toString(f.beforeBytes()), f.beforeSha256(), Long.toString(f.afterBytes()), f.afterSha256()));
        }

        return Sha256.hexDigest(String.join("\n", lines) + "\n");
    }
}
