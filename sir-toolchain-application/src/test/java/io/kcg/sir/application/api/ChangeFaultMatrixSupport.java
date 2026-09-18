package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.state.ApplyHooks;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangePlanner;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.AddCapability;
import io.kcg.sir.change.api.ModifyInputFieldConstraints;
import io.kcg.sir.change.api.RemoveCapability;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Support for the transaction fault matrix (Q6).
 *
 * <p>The matrix needs two things the public API alone does not give: a real B0 baseline built
 * through the real pipeline, and a way to inject a failure at one exact interruption point. The
 * latter exists as {@link ApplyHooks}, whose accepting constructors are package-private in this
 * package — which is why the matrix tests live here.
 *
 * <p>Nothing in this class asserts a verdict. It builds the fixture, drives one apply with one
 * injected fault, and exposes the raw post-state (CURRENT, output tree bytes, transaction
 * evidence) so each test can state its own invariants.
 */
final class ChangeFaultMatrixSupport {

    private ChangeFaultMatrixSupport() {
    }

    /**
     * A registered B0 baseline plus everything needed to apply one change against it.
     *
     * @param sourceFile    the base SIR file
     * @param outputRoot    the generated output root registered as B0
     * @param stateRoot     the state root holding CURRENT, bundles and transaction evidence
     * @param baselineId    the B0 baseline id
     * @param candidateFile the candidate SIR file
     * @param changeSet     the change set to apply
     * @param b0Bytes       the B0 output tree (relative path to bytes) captured before apply
     */
    record Baseline(Path sourceFile, Path outputRoot, Path stateRoot, String baselineId,
                    Path candidateFile, ChangeSet changeSet, Map<String, byte[]> b0Bytes) {
        Baseline {
            b0Bytes = Map.copyOf(b0Bytes);
        }
    }

    /**
     * The three change families the matrix covers.
     *
     * <p>The families are the ones the Change IR defines: an UPDATE modifies existing declarations
     * and is served by the apply transaction, a CREATE adds a declaration, a DELETE removes one.
     */
    enum Family {
        /** Modifies an existing declaration; served by the apply (UPDATE) transaction. */
        UPDATE("/valid/campus-market.sir",
                "/valid/campus-market-candidate-modify-input-field-constraints.sir"),
        /** Adds a capability; served by the create transaction. */
        CREATE("/valid/campus-market-minimal.sir",
                "/valid/campus-market-minimal-add-search-goods.sir"),
        /** Removes a capability; served by the delete transaction. */
        DELETE("/valid/campus-market-two-capabilities.sir",
                "/valid/campus-market-two-capabilities-remove-publish-goods.sir");

        private final String baseResource;
        private final String candidateResource;

        Family(String baseResource, String candidateResource) {
            this.baseResource = baseResource;
            this.candidateResource = candidateResource;
        }
    }

    /** Prepare a registered B0 baseline and the ChangeSet for one family. */
    static Baseline prepare(Path tempDir, Family family) throws IOException {
        return prepare(tempDir, family, family.baseResource, family.candidateResource);
    }

    /**
     * Prepare a baseline from an explicit fixture pair while keeping the family's ChangeSet shape.
     *
     * <p>Some interruption points only exist for particular inputs. The CREATE transaction's
     * directory points, for example, are only reached when a new file needs a directory that does
     * not exist yet — the default CREATE fixture pair already generates every directory it uses, so
     * its directory plan is empty. This overload lets a test supply a pair that does need a new
     * directory, without inventing a new family.
     *
     * @param tempDir           the per-test temporary directory
     * @param family            the family whose ChangeSet shape to build
     * @param baseResource      the base SIR resource path
     * @param candidateResource the candidate SIR resource path
     * @return the prepared baseline
     */
    static Baseline prepare(Path tempDir, Family family, String baseResource,
                            String candidateResource) throws IOException {
        return prepare(tempDir, tempDir, family, baseResource, candidateResource);
    }

    /**
     * Prepare a baseline whose output root and state root live under different parents.
     *
     * <p>Used for the cross-volume case: a transaction backs up an output file with a hard link
     * created inside its transaction directory, so the two roots must be on different file stores
     * for the link to be impossible.
     *
     * @param stateParent     the parent for the state root
     * @param outputParent    the parent for the generated output root
     * @param family          the family whose ChangeSet shape to build
     * @param baseResource    the base SIR resource path
     * @param candidateResource the candidate SIR resource path
     * @return the prepared baseline
     */
    static Baseline prepare(Path stateParent, Path outputParent, Family family,
                            String baseResource, String candidateResource) throws IOException {
        Objects.requireNonNull(stateParent, "stateParent");
        Objects.requireNonNull(outputParent, "outputParent");
        Objects.requireNonNull(family, "family");
        Path tempDir = stateParent;
        Path sourceFile = write(tempDir, "base.sir", baseResource);
        Path outputRoot = outputParent.resolve("build").toAbsolutePath();
        Path stateRoot = tempDir.resolve("state").toAbsolutePath();
        Files.createDirectories(stateRoot);

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                sourceFile.toAbsolutePath(), SourceId.of("base.sir"), outputRoot,
                ConflictPolicy.FAIL_IF_EXISTS));
        if (!(result instanceof ToolchainResult.Success success)) {
            throw new IllegalStateException("baseline generation failed: " + result);
        }

        Path snapshotFile = tempDir.resolve("baseline.kcg-psg").toAbsolutePath();
        Files.write(snapshotFile, serialize(success.graph()));

        ChangeBaseRevision revision = new ChangeBaseRevision(
                SourceId.of("base.sir"), sha256Hex(Files.readAllBytes(sourceFile)),
                success.graph().version(), success.graph().canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1);

        ChangeExecutionApplication app = new ChangeExecutionApplication();
        ChangeBaselineRegistrationResult registration = app.register(
                new ChangeBaselineRegistrationRequest(sourceFile, snapshotFile, outputRoot,
                        stateRoot, revision));
        ChangeBaselineRegistrationResult.Success registered =
                (ChangeBaselineRegistrationResult.Success) registration;

        Path candidateFile = write(tempDir, "candidate.sir", candidateResource);
        ChangePlanningContext context = inspect(app, stateRoot, outputRoot, candidateFile);
        ChangeSet changeSet = switch (family) {
            case UPDATE -> new ChangeSet(ChangeIrVersion.V0_4, context.baseline().revision(),
                    List.of(new ModifyInputFieldConstraints(
                            findTarget(context, ChangePlanningSide.BASE,
                                    ChangePlanningTargetKind.INPUT_FIELD, "PublishGoodsInput",
                                    "title").target())));
            case CREATE -> new ChangeSet(ChangeIrVersion.V0_2, context.baseline().revision(),
                    List.of(new AddCapability(findTarget(context, ChangePlanningSide.CANDIDATE,
                            ChangePlanningTargetKind.CAPABILITY_WORKFLOW, "SearchGoods", null)
                            .target())));
            case DELETE -> new ChangeSet(ChangeIrVersion.V0_3, context.baseline().revision(),
                    List.of(new RemoveCapability(findTarget(context, ChangePlanningSide.BASE,
                            ChangePlanningTargetKind.CAPABILITY_WORKFLOW, "PublishGoods", null)
                            .target())));
        };

        return new Baseline(sourceFile, outputRoot, stateRoot,
                registered.receipt().baselineId(), candidateFile, changeSet, readTree(outputRoot));
    }

    /**
     * Run one apply against the baseline with the supplied hooks.
     *
     * <p>Uses the package-private constructor that accepts {@link ApplyHooks}; the pipeline steps
     * are the same ones the public constructor installs, so the change itself is planned, lowered
     * and committed exactly as in production.
     *
     * @param baseline the prepared baseline
     * @param hooks    the hooks for this run
     * @return the apply result, never {@code null}
     */
    static ChangeApplyResult apply(Baseline baseline, ApplyHooks hooks) {
        ChangeExecutionApplication app = new ChangeExecutionApplication(
                new ChangePlanner(),
                model -> new SpringBootGenerator().generate(model),
                input -> new ProjectGraphBuilder().build(input),
                hooks);
        return app.apply(new ChangeApplyRequest(baseline.stateRoot(), baseline.baselineId(),
                baseline.candidateFile(), baseline.outputRoot(), baseline.changeSet()));
    }

    /**
     * Recover whatever the last run left behind, through the public recovery API.
     *
     * @param baseline the baseline fixture
     * @param handle   the handle reported by {@link ChangeApplyResult.RecoveryRequired}
     * @return the recovery result
     */
    static ChangeRecoveryResult recover(Baseline baseline, RecoveryHandle handle) {
        return new ChangeExecutionApplication().recover(
                new ChangeRecoveryRequest(baseline.stateRoot(), baseline.outputRoot(), handle));
    }

    /**
     * One row of the fault matrix: the observed terminal state for one injected point.
     *
     * @param path         the change family under test
     * @param hook         the injected interruption point
     * @param resultKind   Applied / Failure / RecoveryRequired
     * @param currentIsB0  whether CURRENT still points at the original baseline
     * @param currentPresent whether CURRENT exists at all
     * @param treeIsB0     whether the output tree is byte-identical to B0
     * @param currentNew   whether an unpublished CURRENT.new was left behind
     * @param evidenceFiles number of files still held under the transaction directory
     * @param diagnostics  diagnostic codes, or the recovery transaction id
     */
    record Observation(String path, String hook, String resultKind, boolean currentIsB0,
                       boolean currentPresent, boolean treeIsB0, boolean currentNew,
                       int evidenceFiles, List<String> diagnostics) {

        /** @return the line printed for every point, so the table can be transcribed as evidence */
        String row() {
            return "[FAULT-MATRIX] path=" + path + " hook=" + hook
                    + " result=" + resultKind
                    + " currentIsB0=" + currentIsB0
                    + " currentPresent=" + currentPresent
                    + " treeIsB0=" + treeIsB0
                    + " currentNew=" + currentNew
                    + " evidenceFiles=" + evidenceFiles
                    + " diagnostics=" + diagnostics;
        }
    }

    /**
     * Assert the invariants every interruption point must satisfy, and return the observed row.
     *
     * <p>The invariants are deliberately about <em>direction</em>, not about a hardcoded verdict per
     * point: a point may legitimately be compensated, reported for recovery, or (after the current
     * pointer moves) already included in the new baseline. What may never happen is a state that is
     * neither B0 nor an explicitly recoverable partial state, a lost CURRENT, restored old bytes
     * after B1 was published, or a silently unreached injection point.
     *
     * @param path     the change family, for messages
     * @param hook     the injected point
     * @param baseline the baseline fixture
     * @param hooks    the hooks used for this run
     * @param result   the apply result
     * @return the observed row
     */
    static Observation assertDirectionConsistent(String path, String hook, Baseline baseline,
                                                 FailingApplyHooks hooks, ChangeApplyResult result)
            throws IOException {
        Map<String, byte[]> tree = readTree(baseline.outputRoot());
        String current = readCurrentOrNull(baseline.stateRoot());
        boolean treeIsB0 = treeEquals(baseline.b0Bytes(), tree);
        Map<String, Integer> evidence = transactionEvidence(baseline.stateRoot());
        int evidenceFiles = evidence.values().stream().mapToInt(Integer::intValue).sum();
        boolean recoveryRequired = result instanceof ChangeApplyResult.RecoveryRequired;
        boolean applied = result instanceof ChangeApplyResult.Applied;
        boolean currentIsB0 = baseline.baselineId().equals(current);

        Observation observation = new Observation(path, hook,
                result instanceof ChangeApplyResult.Applied ? "Applied"
                        : result instanceof ChangeApplyResult.Failure ? "Failure"
                        : result instanceof ChangeApplyResult.RecoveryRequired ? "RecoveryRequired"
                        : result.getClass().getSimpleName(),
                currentIsB0, current != null, treeIsB0, currentNewExists(baseline.stateRoot()),
                evidenceFiles, result.diagnostics().stream().map(ChangeExecutionDiagnostic::code)
                        .toList());

        // V1: the injected point must have been reached, otherwise this case proves nothing.
        if (!hooks.firedAt(hook)) {
            throw new AssertionError("injected hook was never reached, so the case would be "
                    + "vacuous: " + path + "/" + hook + "; fired=" + hooks.firedHooks());
        }
        // V2: the CURRENT pointer is never lost, and a success leaves no half-published pointer.
        if (current == null) {
            throw new AssertionError("CURRENT was lost by a fault at " + path + "/" + hook);
        }
        if (applied && observation.currentNew()) {
            throw new AssertionError("a successful apply left CURRENT.new behind at " + path + "/"
                    + hook);
        }
        // V3: no half-applied state may be reported as a completed or compensated run.
        if (currentIsB0 && !treeIsB0 && !recoveryRequired) {
            throw new AssertionError("CURRENT still points at B0 but the tree is neither B0 nor "
                    + "declared as RecoveryRequired at " + path + "/" + hook + ": "
                    + describeTreeDiff(baseline.b0Bytes(), tree));
        }
        // V4: once B1 is published, the old bytes must never be restored.
        if (!currentIsB0 && treeIsB0) {
            throw new AssertionError("CURRENT points at B1 but the output tree is byte-identical "
                    + "to B0, which means old bytes were restored at " + path + "/" + hook);
        }
        // V5: a successful run means B1 is published.
        if (applied && currentIsB0) {
            throw new AssertionError("an applied change must move CURRENT to B1 at " + path + "/"
                    + hook);
        }
        return observation;
    }

    /**
     * The outcome of trying to register a baseline whose output root is on a different file store.
     *
     * @param registered  whether registration succeeded
     * @param outputRoot  the output root the attempt used
     * @param diagnostics diagnostics reported by the attempt
     * @param treeBeforeRegistration the output tree as it was before registration was attempted
     */
    record CrossVolumeAttempt(boolean registered, Path outputRoot,
                              List<ChangeExecutionDiagnostic> diagnostics,
                              Map<String, byte[]> treeBeforeRegistration) {
    }

    /**
     * Try to register a baseline with the state root and output root on different file stores.
     *
     * <p>Used to observe where a cross-store layout is refused: the DELETE family backs files up
     * with hard links, which cannot cross file stores, so the layout has to be rejected rather than
     * quietly served by copying.
     *
     * @param stateParent  the parent for the state root
     * @param outputParent the parent for the output root (a different file store)
     * @return the attempt outcome
     */
    static CrossVolumeAttempt attemptRegistrationAcrossStores(Path stateParent, Path outputParent)
            throws IOException {
        Path sourceFile = write(stateParent, "cross-store.sir",
                "/valid/campus-market-two-capabilities.sir");
        Path outputRoot = outputParent.resolve("cross-store-build").toAbsolutePath();
        Path stateRoot = stateParent.resolve("cross-store-state").toAbsolutePath();
        Files.createDirectories(stateRoot);

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                sourceFile.toAbsolutePath(), SourceId.of("cross-store.sir"), outputRoot,
                ConflictPolicy.FAIL_IF_EXISTS));
        if (!(result instanceof ToolchainResult.Success success)) {
            throw new IllegalStateException("cross-store generation failed: " + result);
        }

        Path snapshotFile = stateParent.resolve("cross-store.kcg-psg").toAbsolutePath();
        Files.write(snapshotFile, serialize(success.graph()));
        ChangeBaseRevision revision = new ChangeBaseRevision(
                SourceId.of("cross-store.sir"), sha256Hex(Files.readAllBytes(sourceFile)),
                success.graph().version(), success.graph().canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1);

        Map<String, byte[]> treeBefore = readTree(outputRoot);
        ChangeBaselineRegistrationResult registration = new ChangeExecutionApplication().register(
                new ChangeBaselineRegistrationRequest(sourceFile, snapshotFile, outputRoot,
                        stateRoot, revision));
        if (registration instanceof ChangeBaselineRegistrationResult.Success) {
            return new CrossVolumeAttempt(true, outputRoot, List.of(), treeBefore);
        }
        if (registration instanceof ChangeBaselineRegistrationResult.Failure failure) {
            return new CrossVolumeAttempt(false, outputRoot, failure.diagnostics(), treeBefore);
        }
        return new CrossVolumeAttempt(false, outputRoot, List.of(), treeBefore);
    }

    private static ChangePlanningContext inspect(ChangeExecutionApplication app, Path stateRoot,
                                                 Path outputRoot, Path candidate) {
        ChangePlanningContextResult result = app.inspectChangePlanningContext(
                new ChangePlanningContextRequest(stateRoot, outputRoot, candidate));
        return ((ChangePlanningContextResult.Success) result).context();
    }

    /** Find exactly one planning target, failing loudly when the catalog does not match. */
    private static ChangePlanningTarget findTarget(ChangePlanningContext ctx,
                                                   ChangePlanningSide side,
                                                   ChangePlanningTargetKind kind,
                                                   String declarationName,
                                                   String targetName) {
        List<ChangePlanningTarget> matches = ctx.targets().stream()
                .filter(t -> t.side() == side)
                .filter(t -> t.kind() == kind)
                .filter(t -> t.declarationDisplayName().equals(declarationName))
                .filter(t -> targetName == null
                        || t.targetDisplayName().orElse("").equals(targetName))
                .toList();
        if (matches.size() != 1) {
            throw new AssertionError("expected exactly one " + side + "/" + kind + "/"
                    + declarationName + "/" + targetName + " target, got " + matches.size());
        }
        return matches.get(0);
    }

    static byte[] serialize(io.kcg.sir.projectgraph.api.ProjectGraph graph) {
        ProjectGraphSerialization serialization =
                new ProjectGraphSerializer().serialize(graph, ProjectGraphCanonicalFormatVersion.V1);
        return ((ProjectGraphSerialization.Success) serialization).document().bytes();
    }

    /** Read the whole output tree as relative path to bytes. */
    static Map<String, byte[]> readTree(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        if (!Files.exists(root)) {
            return files;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString().replace('\\', '/'),
                        Files.readAllBytes(path));
            }
        }
        return files;
    }

    /** Read CURRENT, or {@code null} when it does not exist. */
    static String readCurrentOrNull(Path stateRoot) throws IOException {
        Path current = stateRoot.resolve("CURRENT");
        if (!Files.exists(current)) {
            return null;
        }
        return Files.readString(current, StandardCharsets.US_ASCII).trim();
    }

    /** @return true iff both maps have the same relative paths with identical bytes */
    static boolean treeEquals(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        if (!expected.keySet().equals(actual.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            if (!Arrays.equals(entry.getValue(), actual.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** @return relative paths present in {@code actual} only */
    static List<String> extraPaths(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        List<String> extra = new ArrayList<>(actual.keySet());
        extra.removeAll(expected.keySet());
        return extra;
    }

    /** @return relative paths present in {@code expected} only */
    static List<String> missingPaths(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        List<String> missing = new ArrayList<>(expected.keySet());
        missing.removeAll(actual.keySet());
        return missing;
    }

    /** @return relative paths whose bytes differ */
    static List<String> changedPaths(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            byte[] other = actual.get(entry.getKey());
            if (other != null && !Arrays.equals(entry.getValue(), other)) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    /** A compact tree comparison, for failure messages. */
    static String describeTreeDiff(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        return "expected=" + expected.size() + " actual=" + actual.size()
                + " extra=" + extraPaths(expected, actual)
                + " missing=" + missingPaths(expected, actual)
                + " changed=" + changedPaths(expected, actual);
    }

    /**
     * The files currently present under each transaction directory in the state root.
     *
     * <p>Used to assert that {@code RECOVERY_REQUIRED} preserves its evidence: an empty map means
     * the gate is clean, a non-empty entry means transaction evidence is still on disk.
     *
     * @param stateRoot the state root
     * @return transaction directory name to file count
     */
    static Map<String, Integer> transactionEvidence(Path stateRoot) throws IOException {
        Map<String, Integer> evidence = new LinkedHashMap<>();
        Path transactions = stateRoot.resolve("transactions");
        if (!Files.exists(transactions)) {
            return evidence;
        }
        try (var stream = Files.list(transactions)) {
            for (Path dir : stream.toList()) {
                AtomicInteger count = new AtomicInteger();
                try (var inner = Files.walk(dir)) {
                    inner.filter(Files::isRegularFile).forEach(p -> count.incrementAndGet());
                }
                evidence.put(dir.getFileName().toString(), count.get());
            }
        }
        return evidence;
    }

    /** @return true iff {@code stateRoot/CURRENT.new} exists (an unpublished current pointer) */
    static boolean currentNewExists(Path stateRoot) {
        return Files.exists(stateRoot.resolve("CURRENT.new"));
    }

    static Path write(Path tempDir, String name, String resourcePath) throws IOException {
        Path target = tempDir.resolve(name).toAbsolutePath();
        try (var stream = ChangeFaultMatrixSupport.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing resource: " + resourcePath);
            }
            Files.write(target, stream.readAllBytes());
        }
        return target;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
