package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The evidence of one verification: always all four stages in order, what each proved or why it did not run,
 * which candidate it is about, the resource contract and environment it ran under, and whether everything it
 * created is gone.
 *
 * <p>{@code binding} is empty when the sources did not generate a project: there is no candidate to bind to.
 * A stage can only have passed if every earlier stage passed.
 */
public record VerificationReport(String id, VerificationStage requested, Optional<VerificationBinding> binding,
        List<StageResult> stages, CleanupProof cleanup, Map<String, String> environment, ResourceLimits limits) {
    public static final int MAX_ENVIRONMENT_ENTRIES = 64;

    public VerificationReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(limits, "limits");
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        environment = Map.copyOf(new TreeMap<>(Objects.requireNonNull(environment, "environment")));
        VerificationStage[] all = VerificationStage.values();
        if (stages.size() != all.length) {
            throw new IllegalArgumentException("a report has exactly one result per stage");
        }

        boolean earlierPassed = true;
        for (int i = 0; i < all.length; i++) {
            StageResult result = stages.get(i);
            if (result.stage() != all[i]) {
                throw new IllegalArgumentException("stages must be in order");
            }

            if (result.status() == StageStatus.PASSED && !earlierPassed) {
                throw new IllegalArgumentException(result.stage() + " cannot pass after an earlier stage did not");
            }

            earlierPassed &= result.status() == StageStatus.PASSED;
        }

        if (binding.isEmpty() && stages.get(0).status() == StageStatus.PASSED) {
            throw new IllegalArgumentException("a passed static stage has a candidate to bind to");
        }

        if (environment.size() > MAX_ENVIRONMENT_ENTRIES) {
            throw new IllegalArgumentException("too many environment entries");
        }
    }

    public StageResult stage(VerificationStage stage) {
        return stages.get(stage.ordinal());
    }

    /** True when this stage and every earlier one passed. */
    public boolean passedThrough(VerificationStage stage) {
        for (int i = 0; i <= stage.ordinal(); i++) {
            if (stages.get(i).status() != StageStatus.PASSED) {
                return false;
            }
        }

        return true;
    }

    /** Stable text of everything except measured durations. */
    public String canonicalText() {
        List<String> lines = new ArrayList<>();
        lines.add("KCG-VERIFICATION-REPORT-V1");
        lines.add("id=" + escape(id));
        lines.add("requested=" + requested);
        lines.add(binding.map(b -> "binding=" + b.sourceSetSha256() + "|" + b.manifestDigest() + "|" + b.generatedDigest() + "|" + b.fileCount()
                + "|" + escape(b.targetId()) + "|" + escape(b.loweredIrVersion()) + "|" + b.inputKind()).orElse("binding=NONE"));
        for (StageResult result : stages) {
            StageOutcome o = result.outcome();
            lines.add("stage=" + result.stage() + "|" + o.status() + "|" + o.code() + "|" + escape(o.message()));
            for (String diagnostic : o.diagnostics()) {
                lines.add("diagnostic=" + result.stage() + "|" + escape(diagnostic));
            }

            for (LogRef log : o.logs()) {
                lines.add("log=" + result.stage() + "|" + log.kind() + "|" + escape(log.relativePath()) + "|" + log.bytes() + "|" + log.sha256());
            }
        }

        lines.add("cleanup=" + cleanup.proved());
        for (String check : cleanup.checks()) {
            lines.add("cleanupCheck=" + escape(check));
        }

        environment.forEach((key, value) -> lines.add("environment=" + escape(key) + "=" + escape(value)));
        lines.add("limits=" + limits.canonical());
        return String.join("\n", lines) + "\n";
    }

    public String sha256Hex() {
        return Sha256.hexDigest(canonicalText().getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("|", "\\p");
    }
}
