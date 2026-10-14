package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.Sha256;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything that must change to move a project from the SIR sources on disk to a candidate set of
 * sources: the source files, and the generated files to create, update or delete.
 *
 * <p>It is the same idea as {@link VersionPlan} without a state directory. The current version is no
 * longer a stored record; it is the SIR on disk, and the plan is bound to it and to the candidate by
 * their identities. Two plans are equal exactly when everything below is equal, which is how an apply
 * proves that the plan the caller saw is the one it is about to carry out.
 */
public record WorkspacePlan(ProjectBaselineReceipt base, ProjectBaselineReceipt candidate, SourceDelta sources,
        List<VersionPlan.FileOp> files, int unchangedFiles) {

    public WorkspacePlan {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(sources, "sources");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (base.baselineId().equals(candidate.baselineId()) || !base.boundOutputRoot().equals(candidate.boundOutputRoot())) {
            throw new IllegalArgumentException("a plan joins two different versions of one output root");
        }

        if (files.size() > VersionPlan.MAX_FILE_OPS || unchangedFiles < 0) {
            throw new IllegalArgumentException("file operation count outside budget");
        }

        String previous = null;
        for (VersionPlan.FileOp op : files) {
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

    public long count(VersionPlan.Action action) {
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
        for (VersionPlan.FileOp f : this.files) {
            lines.add(String.join("|", f.path(), f.action().name(), f.owner().orElse(""), Long.toString(f.beforeBytes()), f.beforeSha256(), Long.toString(f.afterBytes()), f.afterSha256()));
        }

        return Sha256.hexDigest(String.join("\n", lines) + "\n");
    }
}
