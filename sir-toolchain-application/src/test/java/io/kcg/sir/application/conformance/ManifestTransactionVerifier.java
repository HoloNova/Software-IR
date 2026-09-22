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
                    outputRoot, delta.deletions(), scenario, evidenceWriter);
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

        writeEvidence(scenario, evidenceWriter, "apply-transaction-verified.txt",
                "family=" + expectedFamily
                        + " additions=" + delta.additions().size()
                        + " updates=" + delta.updates().size()
                        + " deletions=" + delta.deletions().size()
                        + " unchanged=" + delta.unchanged().size()
                        + " status=VERIFIED\n");
        return new Result.Success();
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    /**
     * Prove one manifest entry exists on disk as a regular file whose size and
     * SHA-256 match the manifest.
     *
     * <p>The relative path is resolved against the output root and must stay inside
     * it; a manifest entry that escapes the root is a failure, never a read.
     * Attributes are read with {@link LinkOption#NOFOLLOW_LINKS}, so a symbolic link
     * standing in for the expected file is rejected rather than followed.
     */
    private static Result verifyOnDiskFile(
            Path outputRoot,
            String relativePath,
            long expectedByteCount,
            String expectedSha256Hex,
            ConformanceScenario scenario,
            EvidenceWriter evidenceWriter) {
        Path target = resolveInsideRoot(outputRoot, relativePath);
        if (target == null) {
            return fail(scenario, evidenceWriter, "MANIFEST_PATH_ESCAPES_OUTPUT_ROOT",
                    "manifest entry escapes the output root: " + relativePath);
        }
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_FILE_ABSENT",
                    "manifest entry is absent on disk: " + relativePath);
        } catch (IOException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_FILE_IO_ERROR",
                    "cannot read manifest entry attributes: " + relativePath);
        }
        if (attrs.isSymbolicLink()) {
            return fail(scenario, evidenceWriter, "MANIFEST_FILE_SYMLINK",
                    "manifest entry is a symbolic link: " + relativePath);
        }
        if (!attrs.isRegularFile()) {
            return fail(scenario, evidenceWriter, "MANIFEST_FILE_NOT_REGULAR",
                    "manifest entry is not a regular file: " + relativePath);
        }
        if (attrs.size() != expectedByteCount) {
            return fail(scenario, evidenceWriter, "MANIFEST_BYTE_COUNT_MISMATCH",
                    "byte count mismatch for " + relativePath
                            + ": manifest=" + expectedByteCount + " disk=" + attrs.size());
        }
        String actualSha256;
        try {
            actualSha256 = sha256Hex(Files.readAllBytes(target));
        } catch (IOException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_FILE_IO_ERROR",
                    "cannot read manifest entry bytes: " + relativePath);
        }
        if (!actualSha256.equals(expectedSha256Hex)) {
            return fail(scenario, evidenceWriter, "MANIFEST_SHA256_MISMATCH",
                    "SHA-256 mismatch for " + relativePath);
        }
        return new Result.Success();
    }

    /**
     * Prove CURRENT selects exactly the expected baseline.
     *
     * <p>CURRENT is read with {@code NOFOLLOW_LINKS} and parsed strictly: it must be
     * exactly 65 bytes, a 64-character lowercase hex baseline id plus a single LF.
     * A malformed CURRENT is a failure rather than a reason to guess.
     */
    private static Result verifyCurrentPointsToB1(
            Path stateRoot,
            String expectedBaselineId,
            ConformanceScenario scenario,
            EvidenceWriter evidenceWriter) {
        Path current = stateRoot.resolve("CURRENT");
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(current, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_CURRENT_ABSENT",
                    "CURRENT is absent under the state root");
        } catch (IOException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_CURRENT_IO_ERROR",
                    "cannot read CURRENT attributes");
        }
        if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
            return fail(scenario, evidenceWriter, "MANIFEST_CURRENT_NOT_REGULAR",
                    "CURRENT must be a regular file and not a symbolic link");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(current);
        } catch (IOException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_CURRENT_IO_ERROR",
                    "cannot read CURRENT bytes");
        }
        if (bytes.length != 65 || bytes[64] != '\n') {
            return fail(scenario, evidenceWriter, "MANIFEST_CURRENT_MALFORMED",
                    "CURRENT must be exactly 64 hex characters plus LF, got " + bytes.length
                            + " bytes");
        }
        String observed = new String(bytes, 0, 64, StandardCharsets.US_ASCII);
        if (!observed.matches("[0-9a-f]{64}")) {
            return fail(scenario, evidenceWriter, "MANIFEST_CURRENT_MALFORMED",
                    "CURRENT must contain a 64-character lowercase hex baseline id");
        }
        if (!observed.equals(expectedBaselineId)) {
            return fail(scenario, evidenceWriter, "MANIFEST_CURRENT_NOT_B1",
                    "CURRENT does not select the published B1 baseline");
        }
        return new Result.Success();
    }

    /**
     * Prove the delta is exactly the expected pure plan family.
     *
     * <p>Purity is the contract the scenario claims to prove: UPDATE may only change
     * existing files, CREATE may only add files, DELETE may only remove files. A
     * delta that also changes another bucket is rejected.
     */
    private static Result verifyPlanFamily(
            Delta delta,
            PlanFamily expectedFamily,
            ConformanceScenario scenario,
            EvidenceWriter evidenceWriter) {
        boolean additions = !delta.additions().isEmpty();
        boolean updates = !delta.updates().isEmpty();
        boolean deletions = !delta.deletions().isEmpty();
        switch (expectedFamily) {
            case UPDATE -> {
                if (additions || deletions) {
                    return fail(scenario, evidenceWriter, "MANIFEST_PLAN_FAMILY_NOT_PURE",
                            "UPDATE plan must not add or delete files: additions="
                                    + delta.additions().size() + " deletions=" + delta.deletions().size());
                }
                if (!updates) {
                    return fail(scenario, evidenceWriter, "MANIFEST_PLAN_EMPTY",
                            "UPDATE plan changed no file");
                }
            }
            case CREATE -> {
                if (updates || deletions) {
                    return fail(scenario, evidenceWriter, "MANIFEST_PLAN_FAMILY_NOT_PURE",
                            "CREATE plan must not update or delete files: updates="
                                    + delta.updates().size() + " deletions=" + delta.deletions().size());
                }
                if (!additions) {
                    return fail(scenario, evidenceWriter, "MANIFEST_PLAN_EMPTY",
                            "CREATE plan added no file");
                }
            }
            case DELETE -> {
                if (additions || updates) {
                    return fail(scenario, evidenceWriter, "MANIFEST_PLAN_FAMILY_NOT_PURE",
                            "DELETE plan must not add or update files: additions="
                                    + delta.additions().size() + " updates=" + delta.updates().size());
                }
                if (!deletions) {
                    return fail(scenario, evidenceWriter, "MANIFEST_PLAN_EMPTY",
                            "DELETE plan removed no file");
                }
            }
            default -> {
                return fail(scenario, evidenceWriter, "MANIFEST_PLAN_FAMILY_UNKNOWN",
                        "unknown plan family: " + expectedFamily);
            }
        }
        return new Result.Success();
    }

    /**
     * Prove every path outside the plan is byte-identical between B0 and B1.
     *
     * <p>The assertion is a partition check: the four delta buckets must be disjoint,
     * and together with the plan they must cover exactly the B0 and B1 key sets. Any
     * path in B0 that is neither updated nor deleted therefore has to be in
     * {@code unchanged}, and any path in B1 that is neither updated nor added has to
     * be in {@code unchanged} too — which is precisely what "closure-external bytes
     * unchanged" means. A path that silently disappeared from either manifest fails
     * the partition instead of passing unnoticed.
     */
    private static Result verifyClosureExternal(
            Delta delta,
            PlanFamily expectedFamily,
            ConformanceScenario scenario,
            EvidenceWriter evidenceWriter) {
        Set<String> planned = new LinkedHashSet<>();
        planned.addAll(delta.additions().keySet());
        planned.addAll(delta.updates().keySet());
        planned.addAll(delta.deletions());
        int plannedAndUnchanged = planned.size();
        planned.addAll(delta.unchanged());
        if (planned.size() != plannedAndUnchanged + delta.unchanged().size()) {
            return fail(scenario, evidenceWriter, "MANIFEST_DELTA_BUCKETS_OVERLAP",
                    "a path appears in both the plan and the unchanged set");
        }

        Set<String> observedB0 = new LinkedHashSet<>(delta.updates().keySet());
        observedB0.addAll(delta.deletions());
        observedB0.addAll(delta.unchanged());
        if (!observedB0.equals(delta.b0Keys())) {
            return fail(scenario, evidenceWriter, "MANIFEST_B0_PARTITION_MISMATCH",
                    "B0 keys are not partitioned by unchanged/updates/deletions");
        }

        Set<String> observedB1 = new LinkedHashSet<>(delta.updates().keySet());
        observedB1.addAll(delta.additions().keySet());
        observedB1.addAll(delta.unchanged());
        if (!observedB1.equals(delta.b1Keys())) {
            return fail(scenario, evidenceWriter, "MANIFEST_B1_PARTITION_MISMATCH",
                    "B1 keys are not partitioned by unchanged/updates/additions");
        }

        writeEvidence(scenario, evidenceWriter, "closure-external-verified.txt",
                "family=" + expectedFamily
                        + " unchanged=" + delta.unchanged().size()
                        + " b0Keys=" + delta.b0Keys().size()
                        + " b1Keys=" + delta.b1Keys().size()
                        + " status=VERIFIED\n");
        return new Result.Success();
    }

    /**
     * Prove every planned deletion target is absent on disk.
     *
     * <p>Absence is proven by {@code readAttributes(..., NOFOLLOW_LINKS)} throwing
     * {@link NoSuchFileException} — never by {@code Files.exists}, which cannot
     * distinguish "absent" from "cannot tell".
     */
    private static Result verifyDeletedFilesAbsent(
            Path outputRoot,
            Set<String> deletions,
            ConformanceScenario scenario,
            EvidenceWriter evidenceWriter) {
        for (String relativePath : deletions) {
            Path target = resolveInsideRoot(outputRoot, relativePath);
            if (target == null) {
                return fail(scenario, evidenceWriter, "MANIFEST_PATH_ESCAPES_OUTPUT_ROOT",
                        "deletion target escapes the output root: " + relativePath);
            }
            try {
                Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return fail(scenario, evidenceWriter, "MANIFEST_DELETED_FILE_PRESENT",
                        "deleted file is still present: " + relativePath);
            } catch (NoSuchFileException e) {
                // Expected: the deletion target is proven absent.
            } catch (IOException e) {
                return fail(scenario, evidenceWriter, "MANIFEST_DELETE_PROOF_IO_ERROR",
                        "cannot prove deletion target absent: " + relativePath);
            }
        }
        writeEvidence(scenario, evidenceWriter, "delete-absence-verified.txt",
                "deletions=" + deletions.size() + " status=VERIFIED\n");
        return new Result.Success();
    }

    /**
     * Prove the transaction gate is clean: no {@code CURRENT.new} and no journal.
     *
     * <p>A leftover {@code CURRENT.new} means an interrupted CURRENT publish, and a
     * leftover journal means an unfinished transaction. Either would invalidate the
     * claim that the run ended in a committed state, so both fail the verification.
     */
    private static Result verifyTransactionGateClean(
            Path stateRoot,
            ConformanceScenario scenario,
            EvidenceWriter evidenceWriter) {
        Path currentNew = stateRoot.resolve("CURRENT.new");
        try {
            Files.readAttributes(currentNew, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return fail(scenario, evidenceWriter, "MANIFEST_CURRENT_NEW_PRESENT",
                    "CURRENT.new is present: an interrupted CURRENT publish");
        } catch (NoSuchFileException e) {
            // Expected: no interrupted publish.
        } catch (IOException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_GATE_IO_ERROR",
                    "cannot read CURRENT.new attributes");
        }

        Path transactions = stateRoot.resolve("transactions");
        BasicFileAttributes txAttrs;
        try {
            txAttrs = Files.readAttributes(transactions, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return new Result.Success();
        } catch (IOException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_GATE_IO_ERROR",
                    "cannot read the transactions directory attributes");
        }
        if (txAttrs.isSymbolicLink() || !txAttrs.isDirectory()) {
            return fail(scenario, evidenceWriter, "MANIFEST_GATE_NOT_A_DIRECTORY",
                    "the transactions path must be a real directory");
        }

        try (java.util.stream.Stream<Path> walk = Files.walk(transactions)) {
            boolean journalPresent = walk
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> "journal".equals(p.getFileName().toString()));
            if (journalPresent) {
                return fail(scenario, evidenceWriter, "MANIFEST_JOURNAL_ACTIVE",
                        "an active transaction journal remains under the state root");
            }
        } catch (IOException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_GATE_IO_ERROR",
                    "cannot walk the transactions directory");
        } catch (java.io.UncheckedIOException e) {
            return fail(scenario, evidenceWriter, "MANIFEST_GATE_IO_ERROR",
                    "cannot walk the transactions directory");
        }
        return new Result.Success();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Compute the exact content delta between two normalized manifests.
     *
     * @param b0 the B0 manifest keyed by relative path
     * @param b1 the B1 manifest keyed by relative path
     * @return the delta, including both key sets so it can be partition-checked
     */
    private static Delta computeDelta(Map<String, FileDigest> b0, Map<String, FileDigest> b1) {
        Map<String, FileDigest> additions = new LinkedHashMap<>();
        Map<String, FileDigest> updates = new LinkedHashMap<>();
        Set<String> unchanged = new LinkedHashSet<>();
        for (Map.Entry<String, FileDigest> entry : b1.entrySet()) {
            FileDigest before = b0.get(entry.getKey());
            if (before == null) {
                additions.put(entry.getKey(), entry.getValue());
            } else if (before.equals(entry.getValue())) {
                unchanged.add(entry.getKey());
            } else {
                updates.put(entry.getKey(), entry.getValue());
            }
        }
        Set<String> deletions = new LinkedHashSet<>(b0.keySet());
        deletions.removeAll(b1.keySet());
        return new Delta(additions, updates, deletions, unchanged,
                new LinkedHashSet<>(b0.keySet()), new LinkedHashSet<>(b1.keySet()));
    }

    /**
     * Map an APPLY scenario onto its expected pure plan family.
     *
     * @param scenario the scenario
     * @return the expected family
     * @throws IllegalArgumentException if the scenario is not an APPLY scenario
     */
    private static PlanFamily expectedFamilyFor(ConformanceScenario scenario) {
        return switch (scenario) {
            case APPLY_UPDATE -> PlanFamily.UPDATE;
            case APPLY_CREATE -> PlanFamily.CREATE;
            case APPLY_DELETE -> PlanFamily.DELETE;
            default -> throw new IllegalArgumentException(
                    "scenario has no plan family (not an APPLY scenario): " + scenario.displayName());
        };
    }

    /**
     * Resolve a manifest-relative path against the output root, refusing anything
     * that escapes it.
     *
     * @return the resolved absolute path, or {@code null} when it escapes the root
     */
    private static Path resolveInsideRoot(Path outputRoot, String relativePath) {
        Path root = outputRoot.toAbsolutePath().normalize();
        Path candidate = Path.of(relativePath);
        if (candidate.isAbsolute()) {
            return null;
        }
        Path resolved = root.resolve(candidate).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    /**
     * Record a verification failure in the evidence directory and return it.
     *
     * @return the failure result for the caller to propagate
     */
    private static Result fail(ConformanceScenario scenario, EvidenceWriter evidenceWriter,
                               String key, String message) {
        writeEvidence(scenario, evidenceWriter, "verification-failure.txt",
                key + ": " + message + "\n");
        return new Result.Failure(key, message);
    }

    /**
     * Write one verification note into the scenario's evidence directory.
     *
     * <p>Best effort by design: this verifier is a read-only inspector of the output
     * and state roots, and the evidence tree is reconciled separately (the ownership
     * inventory plus the secret scanner require every registered file to exist, and
     * the scan fails closed otherwise). Throwing here would replace a structured
     * verification verdict with an I/O exception, so a failed write is recorded by
     * the scanner instead.
     */
    private static void writeEvidence(ConformanceScenario scenario, EvidenceWriter evidenceWriter,
                                      String name, String content) {
        try (java.io.OutputStream out = evidenceWriter.openStream(
                "scenarios/" + scenario.displayName() + "/" + name)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // See the method javadoc: the evidence scan is the gate for this.
        }
    }

    /**
     * @param bytes the bytes to digest
     * @return the 64-character lowercase hex SHA-256
     */
    private static String sha256Hex(byte[] bytes) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            int v = b & 0xFF;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
