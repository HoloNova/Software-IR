package io.kcg.sir.application.internal.version;

import io.kcg.sir.application.api.FileDelta;
import io.kcg.sir.application.api.ProjectBaselineReceipt;
import io.kcg.sir.application.api.SourceDelta;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Bundle;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one place that turns two compiled versions into a {@link VersionPlan}.
 *
 * <p>Planning uses it to build the plan, and a transaction uses it again, from the saved sources,
 * to prove a plan or a recovery record is exactly what those two versions produce. Having one
 * function is what makes "independently rebuilt" mean something.
 */
public final class VersionPlans {
    private VersionPlans() {
    }

    /** Compare with no look at the disk: the pure difference between two compiled versions. */
    public static VersionPlan compare(Path stateRoot, ProjectBaselineVerification.Verified base, ProjectBaselineVerification.Verified candidate) {
        FileDelta delta = FileDelta.compare(base.compilation().generatedFiles(), candidate.compilation().generatedFiles(), null);
        return compare(stateRoot, base, candidate, delta);
    }

    public static VersionPlan compare(Path stateRoot, ProjectBaselineVerification.Verified base, ProjectBaselineVerification.Verified candidate, FileDelta delta) {
        Bundle b = base.bundle();
        Bundle c = candidate.bundle();
        Map<String, GeneratedFile> before = byPath(base.compilation().generatedFiles());
        Map<String, GeneratedFile> after = byPath(candidate.compilation().generatedFiles());
        List<VersionPlan.FileOp> operations = new ArrayList<>();
        for (FileDelta.Entry entry : delta.entries()) {
            if (entry.action() == FileDelta.Action.UNCHANGED) {
                continue;
            }

            GeneratedFile was = before.get(entry.path());
            GeneratedFile now = after.get(entry.path());
            operations.add(new VersionPlan.FileOp(entry.path(), VersionPlan.Action.valueOf(entry.action().name()), entry.owner(),
                was == null ? 0 : bytes(was), entry.beforeSha256(), now == null ? 0 : bytes(now), entry.afterSha256()));
        }

        SourceDelta sources = SourceDelta.between(b.sources().manifest(), c.sources().manifest());
        return new VersionPlan(stateRoot, receipt(b), receipt(c), sources, operations, (int)delta.count(FileDelta.Action.UNCHANGED));
    }

    public static ProjectBaselineReceipt receipt(Bundle bundle) {
        var d = bundle.descriptor();
        return new ProjectBaselineReceipt(bundle.baselineId(), d.outputRoot(), d.entry(), d.sourceSetSha(), d.graphDigest(), d.manifestDigest());
    }

    /** Names the pair of versions and the state directory a publication belongs to. */
    public static String contextDigest(Path stateRoot, String baseId, String candidateId) {
        return Sha256.hexDigest("KCG-VERSION-CONTEXT-V1\n" + stateRoot + "\n" + baseId + "\n" + candidateId + "\n");
    }

    public static final String SUBJECT = "version-update";

    private static long bytes(GeneratedFile file) {
        return file.content().getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, GeneratedFile> byPath(List<GeneratedFile> files) {
        Map<String, GeneratedFile> map = new TreeMap<>();
        files.forEach(f -> map.put(f.relativePath(), f));
        return map;
    }
}
