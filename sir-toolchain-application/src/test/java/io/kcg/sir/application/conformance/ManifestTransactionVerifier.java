package io.kcg.sir.application.conformance;

import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ChangeApplyOutcome;
import io.kcg.sir.application.api.ChangeBaselineReceipt;
import io.kcg.sir.application.api.ChangeOutputManifest;
import io.kcg.sir.application.api.ExecutionManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Verifies the structured {@link MaterializationEvidence} for one scenario
 * on the real run path (ADR-017 sections 7.5 and 7.6, Stage E Task 4).
 *
 * <p>Replaces the former "manifest non-null = determinism" check with
 * explicit, structured transactional assertions. For IG-* scenarios the
 * verifier checks the {@link ExecutionManifest} entries against the on-disk
 * output root. For APPLY-* scenarios the verifier asserts the full
 * transactional contract:
 *
 * <pre>
 *   B0 registration receipt and manifest present
 *   plan family is exactly expected pure UPDATE / CREATE / DELETE
 *   B1 receipt != B0
 *   CURRENT points to B1
 *   complete B1 on-disk manifest (byteCount + SHA-256 per entry)
 *   B0 -&gt; B1 exact manifest delta == plan
 *   closure-external bytes unchanged
 *   transaction gate clean (no active journal, no CURRENT.new)
 * </pre>
 *
 * <p>For DELETE family, the verifier additionally proves each planned
 * deletion target is absent on disk via direct
 * {@code readAttributes(..., NOFOLLOW_LINKS)} throwing
 * {@link NoSuchFileException}—never {@code Files.exists}.
 *
 * <p>Any assertion failure produces a {@link Result.Failure} with a stable
 * message key. The conformance orchestration must not invoke the Maven
 * runner when verification fails.
 *
 * <p>This class performs only read-only filesystem and stateRoot inspection.
 * It never mutates the output root, stateRoot, or evidence directory.
 */
public final class ManifestTransactionVerifier {

    private ManifestTransactionVerifier() {
    }

    /**
     * Verify one scenario's materialization evidence.
     *
     * @param scenario       the conformance scenario
     * @param evidence       the structured materialization evidence
     * @param outputRoot     the generated project output root (B0 for IG-*,
     *                       B1 for APPLY-*)
     * @param stateRoot      the Application state root (only required for
     *                       APPLY-* scenarios; may be {@code null} for IG-*)
     * @param evidenceWriter the evidence writer (must not be {@code null})
     * @return {@link Result.Success} if all assertions passed;
     *         {@link Result.Failure} otherwise
     */
    public static Result verify(
            ConformanceScenario scenario,
            MaterializationEvidence evidence,
            Path outputRoot,
            Path stateRoot,
            EvidenceWriter evidenceWriter) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(evidenceWriter, "evidenceWriter");

        if (evidence instanceof MaterializationEvidence.IgEvidence ig) {
            return verifyIg(scenario, ig, outputRoot, evidenceWriter);
        }
        if (evidence instanceof MaterializationEvidence.ApplyEvidence apply) {
            if (stateRoot == null) {
                return new Result.Failure("MANIFEST_STATE_ROOT_MISSING",
                        "APPLY scenario requires a non-null stateRoot");
            }
            return verifyApply(scenario, apply, outputRoot, stateRoot, evidenceWriter);
        }
        return new Result.Failure("MANIFEST_EVIDENCE_UNKNOWN_TYPE",
                "unknown MaterializationEvidence type: " + evidence.getClass());
    }

    // ------------------------------------------------------------------
    // IG verification
    // ------------------------------------------------------------------

    private static Result verifyIg(
            ConformanceScenario scenario,
            MaterializationEvidence.IgEvidence evidence,
            Path outputRoot,
            EvidenceWriter evidenceWriter) {
        ExecutionManifest manifest = evidence.manifest();
        if (manifest.files().isEmpty()) {
            return fail(scenario, evidenceWriter, "MANIFEST_IG_EMPTY",
                    "IG ExecutionManifest has no files");
        }
        // Verify each manifest entry exists on disk as a regular file with
        // matching byteCount and SHA-256.
        for (AppliedFile af : manifest.files()) {
            Result r = verifyOnDiskFile(outputRoot, af.relativePath(),
                    af.byteCount(), af.sha256Hex(), scenario, evidenceWriter);
            if (r instanceof Result.Failure) {
                return r;
            }
        }
        writeEvidence(scenario, evidenceWriter, "ig-manifest-verified.txt",
                "files=" + manifest.files().size()
                        + " outputRoot=" + outputRoot
                        + " status=VERIFIED\n");
        return new Result.Success();
    }

    // ------------------------------------------------------------------
    // APPLY verification
    // ------------------------------------------------------------------

    private static Result verifyApply(
            ConformanceScenario scenario,
            MaterializationEvidence.ApplyEvidence evidence,
            Path outputRoot,
            Path stateRoot,
            EvidenceWriter evidenceWriter) {

        // 1. B0 registration receipt and manifest present.
        ChangeBaselineReceipt b0Receipt = evidence.b0Receipt();
        ExecutionManifest b0Manifest = evidence.b0Manifest();
        if (b0Manifest.files().isEmpty()) {
            return fail(scenario, evidenceWriter, "MANIFEST_B0_EMPTY",
                    "B0 ExecutionManifest has no files");
        }

        // 2. B1 receipt and manifest present.
        ChangeBaselineReceipt b1Receipt = evidence.b1Receipt();
        ChangeOutputManifest b1Manifest = evidence.b1Manifest();
        if (b1Manifest.entries().isEmpty()) {
            return fail(scenario, evidenceWriter, "MANIFEST_B1_EMPTY",
                    "B1 ChangeOutputManifest has no entries");
        }

        // 3. B1 receipt != B0 (baselineId must differ).
        if (b1Receipt.baselineId().equals(b0Receipt.baselineId())) {
            return fail(scenario, evidenceWriter, "MANIFEST_B1_EQUALS_B0",
                    "B1 baselineId equals B0 baselineId: " + b1Receipt.baselineId());
        }

        // 4. CURRENT points to B1.
        Result currentResult = verifyCurrentPointsToB1(
                stateRoot, b1Receipt.baselineId(), scenario, evidenceWriter);
        if (currentResult instanceof Result.Failure) {
            return currentResult;
        }

        // 5. Complete B1 on-disk manifest (byteCount + SHA-256 per entry).
        for (ChangeOutputManifest.Entry entry : b1Manifest.entries()) {
            Result r = verifyOnDiskFile(outputRoot, entry.relativePath(),
                    entry.byteCount(), entry.sha256Hex(), scenario, evidenceWriter);
            if (r instanceof Result.Failure) {
                return r;
            }
        }

        // 6. Build normalized B0 and B1 manifest maps for delta computation.
        Map<String, FileDigest> b0Map = new LinkedHashMap<>();
        for (AppliedFile af : b0Manifest.files()) {
            b0Map.put(af.relativePath(), new FileDigest(af.byteCount(), af.sha256Hex()));
        }
        Map<String, FileDigest> b1Map = new LinkedHashMap<>();
        for (ChangeOutputManifest.Entry e : b1Manifest.entries()) {
            b1Map.put(e.relativePath(), new FileDigest(e.byteCount(), e.sha256Hex()));
        }

        // 7. Compute B0 -> B1 delta and verify plan family.
        Delta delta = computeDelta(b0Map, b1Map);
        PlanFamily expectedFamily = expectedFamilyFor(scenario);
        Result familyResult = verifyPlanFamily(delta, expectedFamily, scenario, evidenceWriter);
        if (familyResult instanceof Result.Failure) {
            return familyResult;
        }

        // 8. Closure-external bytes unchanged: every path in both B0 and B1
        //    with same SHA-256 must be unchanged. Any path with different
        //    SHA-256 must be in the update set (already verified by
        //    verifyPlanFamily for the expected family).
        Result closureResult = verifyClosureExternal(delta, expectedFamily, scenario, evidenceWriter);
        if (closureResult instanceof Result.Failure) {
            return closureResult;
        }

        // 9. Transaction gate clean: no active journal, no CURRENT.new.
        Result gateResult = verifyTransactionGateClean(stateRoot, scenario, evidenceWriter);
        if (gateResult instanceof Result.Failure) {
            return gateResult;
        }

        // 10. For DELETE family: prove deleted files are absent on disk.
        if (expectedFamily == PlanFamily.DELETE) {
            Result deleteResult = verifyDeletedFilesAbsent(
                    outputRoot, delta.deletions, scenario, evidenceWriter);
            if (deleteResult instanceof Result.Failure) {
                return deleteResult;
            }
        }

        // 11. Verify outcome is FILES_AND_BASELINE for UPDATE/CREATE/DELETE
        //     (BASELINE_ONLY is not expected for the conformance scenarios
        //     because each candidate SIR produces a real byte-level change).
        if (evidence.outcome() != ChangeApplyOutcome.FILES_AND_BASELINE) {
            return fail(scenario, evidenceWriter, "MANIFEST_OUTCOME_UNEXPECTED",
                    "expected FILES_AND_BASELINE, got " + evidence.outcome());
        }

        // TODO(conformance): complete manifest-delta and terminal evidence checks.
    }
}
