package io.kcg.sir.application.internal.version;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.MAX_PAYLOAD;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.budget;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.decodeSources;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.hex;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.problem;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.require;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.Bundle;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineStore;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.application.internal.projectbaseline.ProjectPublicationHistory;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Kind;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Lifetime;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Owned;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateReceipt;
import io.kcg.sir.application.internal.version.VersionBinding.Op;
import io.kcg.sir.application.internal.version.VersionBinding.OpKind;
import io.kcg.sir.application.internal.version.VersionTrail.OpState;
import io.kcg.sir.application.internal.version.VersionTrail.Phase;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Moves a stored project from one version to another: the generated files change and the stored
 * current version changes, as one operation that always ends in exactly one of the two versions.
 *
 * <p>The commit point is the existing one: the {@code CURRENT} pointer. Everything before it can be
 * undone from the evidence prepared in advance, everything after it is only verified and cleaned up.
 * That is why recovery needs no guess: if {@code CURRENT} still names the old version it only goes
 * back (restores backups, removes what was created, recreates what was removed); if it names the new
 * one it only goes forward (verifies and cleans).
 *
 * <p>The caller holds the state lock for the whole call. The generated project is only ever changed
 * by atomic moves and deletions of objects whose physical identity was recorded beforehand; anything
 * else found there is treated as an outside change and left alone.
 */
public final class VersionTransaction {
    public static final String ACTIVE = "project-transactions";
    public static final long MAX_STAGE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_DIRECTORY_ENTRIES = 4096;

    /** Compiles saved sources again; replaceable so tests can prove recovery does not trust the binding alone. */
    @FunctionalInterface
    public interface ReplayCompiler {
        ProjectBaselineVerification.Verified compile(SourceSnapshot sources, Path output, List<ExecutionDiagnostic> diagnostics) throws IOException;
    }

    private enum Situation { BEFORE, AFTER, OTHER }

    /** An operation while it is being prepared: everything the binding row will need except the staging identity. */
    private record Step(OpKind kind, String path, String parentKey, String baseKey, long baseSize, String baseSha, byte[] candidate, long candidateSize, String candidateSha) {
    }

    private final SecureFileAccess state;
    private final SecureFileAccess output;
    private final Consumer<String> checkpoint;
    private final String id;
    private final ReplayCompiler replay;
    private VersionBinding binding;
    private boolean started;

    public VersionTransaction(SecureFileAccess state, SecureFileAccess output, String id, Consumer<String> checkpoint) {
        this(state, output, id, checkpoint, ProjectBaselineVerification::compile);
    }

    VersionTransaction(SecureFileAccess state, SecureFileAccess output, String id, Consumer<String> checkpoint, ReplayCompiler replay) {
        hex(id);
        this.state = state;
        this.output = output;
        this.id = id;
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.replay = Objects.requireNonNull(replay);
    }

    /** Whether the first change to disk was attempted; a failure after this needs explicit recovery. */
    public boolean started() {
        return this.started;
    }

    public Optional<VersionBinding> binding() {
        return Optional.ofNullable(this.binding);
    }

    private String prefix() {
        return ACTIVE + "/" + this.id;
    }

    // ------------------------------------------------------------------ apply

    public void apply(ProjectBaselineVerification.Verified base, ProjectBaselineVerification.Verified candidate, VersionPlan plan) throws IOException {
        Bundle b = base.bundle();
        Bundle c = candidate.bundle();
        ProjectBaselineStore store = new ProjectBaselineStore(this.state);
        require(this.state.root().equals(plan.stateRoot()) && this.output.root().equals(b.descriptor().outputRoot()) && this.output.root().equals(c.descriptor().outputRoot()),
            "transaction roots differ from the plan");
        require(plan.base().baselineId().equals(b.baselineId()) && plan.candidate().baselineId().equals(c.baselineId()), "the plan joins other versions");
        store.guardState(b.baselineId(), false);
        store.verifyPointerIdentity(b.baselineId(), false);
        require(store.current().equals(Optional.of(b.baselineId())), "CURRENT changed before the transaction");
        require(!b.baselineId().equals(c.baselineId()), "a version change cannot publish the same version");
        require(VersionPlans.compare(this.state.root(), base, candidate).equals(plan), "the plan is not the exact whole-file-set difference of the two versions");

        Map<String, byte[]> candidateBytes = generated(candidate);
        List<Step> steps = this.steps(plan, candidateBytes);
        budget(steps.size() <= VersionTrail.MAX_OPS, "step count outside budget");
        long stageBytes = 0;
        long baseBytes = 0;
        for (Step step : steps) {
            stageBytes += step.candidateSize();
            baseBytes += step.baseSize();
        }

        budget(stageBytes <= MAX_STAGE_BYTES && 24000 + 450L * steps.size() <= VersionBinding.MAX_BYTES, "staging or binding byte budget exceeded before preparation");
        ProjectBaselineVerification.verifyOutput(b);
        require(Files.getFileStore(this.state.root()).equals(Files.getFileStore(this.output.root())), "cross-FileStore transaction refused");

        var history = this.state.exists(ProjectPublicationHistory.DIRECTORY)
            ? ProjectPublicationHistory.validate(this.state, b.baselineId())
            : new ProjectPublicationHistory.View(b.baselineId(), Set.of(b.baselineId()), Set.of(), b.descriptorBytes().length + b.payloadBytes().length + b.graphBytes().length + 65, Set.of(), 0);
        var edge = ProjectPublicationHistory.Edge.of(history.origin(), b, c, VersionPlans.contextDigest(this.state.root(), b.baselineId(), c.baselineId()), plan.sha256Hex(), VersionPlans.SUBJECT);
        budget(history.receiptCount() < ProjectUpdateReceipt.MAX_RECEIPTS, "terminal receipt budget exhausted");
        budget(history.baselineIds().contains(c.baselineId()) || history.baselineIds().size() < ProjectPublicationHistory.MAX_BASELINES, "baseline history count exhausted");
        budget(history.edgeIds().contains(edge.id()) || history.edgeIds().size() < ProjectPublicationHistory.MAX_EDGES, "publication history count exhausted");
        long added = history.baselineIds().contains(c.baselineId()) ? 0 : c.descriptorBytes().length + c.payloadBytes().length + c.graphBytes().length + 65;
        budget(history.byteCount() + 2 * added + 2L * edge.bytes().length + 2L * c.payloadBytes().length + baseBytes + stageBytes + 4L * 65536 <= ProjectPublicationHistory.MAX_HISTORY_BYTES,
            "history or receipt byte budget exhausted before writes");
        require(!this.state.exists(ProjectUpdateReceipt.DIRECTORY + "/" + this.id), "the transaction ID already has a terminal receipt");
        if (this.state.exists(ACTIVE)) {
            require(this.state.list(ACTIVE, 1).isEmpty(), "an active project transaction blocks this one");
        }

        this.checkpoint.accept("before-prepare");
        store.guardState(b.baselineId(), false);
        store.verifyPointerIdentity(b.baselineId(), false);
        ProjectBaselineVerification.verifyOutput(b);
        this.state.assertRootUnchanged();
        this.output.assertRootUnchanged();
        for (Step step : steps) {
            this.recheckParent(step);
        }

        this.started = true;
        if (!this.state.exists(ACTIVE)) {
            this.state.createDirectory(ACTIVE);
        }

        this.state.createDirectory(this.prefix());
        this.checkpoint.accept("transaction-directory");
        List<Owned> owned = new ArrayList<>();
        this.directory(this.prefix() + "/staging", Lifetime.TRANSACTION, owned);
        this.directory(this.prefix() + "/backups", Lifetime.TRANSACTION, owned);
        if (!this.state.exists(ProjectPublicationHistory.DIRECTORY)) {
            this.directory(ProjectPublicationHistory.DIRECTORY, Lifetime.STATE, owned);
        }

        if (!this.state.exists(ProjectUpdateReceipt.DIRECTORY)) {
            this.directory(ProjectUpdateReceipt.DIRECTORY, Lifetime.STATE, owned);
        }

        if (!this.state.exists(ProjectPublicationHistory.DIRECTORY + "/origin")) {
            this.write(ProjectPublicationHistory.DIRECTORY + "/origin", (history.origin() + "\n").getBytes(StandardCharsets.US_ASCII), Lifetime.STATE, owned);
        }

        if (!this.state.exists("baselines/" + c.baselineId())) {
            String dir = "baselines/" + c.baselineId();
            this.directory(dir, Lifetime.CANDIDATE, owned);
            this.write(dir + "/" + ProjectBaselineStore.SOURCES, c.payloadBytes(), Lifetime.CANDIDATE, owned);
            this.write(dir + "/" + ProjectBaselineStore.GRAPH, c.graphBytes(), Lifetime.CANDIDATE, owned);
            this.write(dir + "/" + ProjectBaselineStore.DESCRIPTOR, c.descriptorBytes(), Lifetime.CANDIDATE, owned);
            this.write(dir + "/" + ProjectBaselineStore.POINTER, (c.baselineId() + "\n").getBytes(StandardCharsets.US_ASCII), Lifetime.CANDIDATE, owned);
        }

        same(c, store.load(c.baselineId()));
        String relation = ProjectPublicationHistory.DIRECTORY + "/" + edge.id();
        if (!this.state.exists(relation)) {
            this.write(relation, edge.bytes(), Lifetime.CANDIDATE, owned);
        } else {
            require(Arrays.equals(this.state.read(relation, 65536), edge.bytes()), "the existing publication relation differs");
        }

        this.write(this.prefix() + "/candidate.sources", c.payloadBytes(), Lifetime.RECEIPT, owned);

        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            this.recheckParent(step);
            String stagedKey = "";
            if (step.kind() == OpKind.UPDATE || step.kind() == OpKind.DELETE) {
                Object key = this.output.identity(step.path());
                require(key.toString().equals(step.baseKey()), "output ownership changed during preparation: " + step.path());
                check(this.output, step.path(), step.baseKey(), step.baseSize(), step.baseSha());
                this.output.linkOwned(step.path(), key, this.state, this.prefix() + "/backups/" + i);
                owned.add(new Owned(this.prefix() + "/backups/" + i, Kind.FILE, step.baseKey(), Lifetime.TRANSACTION, step.baseSize(), step.baseSha()));
                this.checkpoint.accept("backup:" + i);
            }

            if (step.kind() == OpKind.CREATE || step.kind() == OpKind.UPDATE) {
                this.write(this.prefix() + "/staging/" + i, step.candidate(), Lifetime.TRANSACTION, owned);
                stagedKey = this.state.identity(this.prefix() + "/staging/" + i).toString();
            }

            ops.add(new Op(step.kind(), step.path(), step.parentKey(), step.baseKey(), stagedKey, step.baseSize(), step.baseSha(), step.candidateSize(), step.candidateSha()));
        }

        this.state.writeNew(this.prefix() + "/journal.A", new byte[0], this.checkpoint);
        this.state.writeNew(this.prefix() + "/journal.B", new byte[0], this.checkpoint);
        for (String slot : List.of("journal.A", "journal.B")) {
            this.state.linkOwned(this.prefix() + "/" + slot, this.state.identity(this.prefix() + "/" + slot), this.state, this.prefix() + "/" + slot + ".anchor");
        }

        this.binding = new VersionBinding(this.id, this.state.root(), this.output.root(), this.state.directoryIdentity("").toString(), this.output.directoryIdentity("").toString(),
            this.state.directoryIdentity(ACTIVE).toString(), this.state.directoryIdentity(this.prefix()).toString(), this.state.identity(this.prefix() + "/journal.A").toString(),
            this.state.identity(this.prefix() + "/journal.B").toString(), edge, plan.sha256Hex(), ops, owned);
        byte[] encoded = this.binding.bytes();
        this.state.writeNew(this.prefix() + "/binding", encoded, this.checkpoint);
        this.state.linkOwned(this.prefix() + "/binding", this.state.identity(this.prefix() + "/binding"), this.state, this.prefix() + "/binding.anchor");
        this.checkpoint.accept("binding-complete");

        VersionJournalSlots journal = this.journal();
        VersionTrail trail = VersionTrail.start(this.binding.digest(), ops.size());
        journal.initialize(trail);
        trail = trail.phase(Phase.UPDATING);
        journal.store(trail);
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            this.verifyDirectories();
            trail = trail.op(i, OpState.APPLYING);
            journal.store(trail);
            this.checkpoint.accept("apply-intent:" + i);
            this.forward(i, op);
            this.checkpoint.accept("output-applied:" + i);
            trail = trail.op(i, OpState.APPLIED);
            journal.store(trail);
        }

        trail = trail.phase(Phase.FILES_DONE);
        journal.store(trail);
        ProjectBaselineVerification.verifyOutput(c);
        same(c, store.load(c.baselineId()));
        this.verifyDirectories();
        trail = trail.phase(Phase.COMMITTING);
        journal.store(trail);
        this.checkpoint.accept("before-publication");
        require(store.current().equals(Optional.of(b.baselineId())), "CURRENT changed before publication");
        store.verifyPointerIdentity(b.baselineId(), false);
        require(!this.state.exists("CURRENT.new"), "an external pending pointer blocks publication");
        Object anchor = this.state.identity(ProjectBaselineStore.anchor(c.baselineId()));
        this.state.linkOwned(ProjectBaselineStore.anchor(c.baselineId()), anchor, this.state, "CURRENT.new");
        this.checkpoint.accept("current-pending");
        ProjectBaselineVerification.verifyOutput(c);
        same(c, store.load(c.baselineId()));
        this.verifyDirectories();
        this.preflightCleanup(true);
        store.verifyPointerIdentity(b.baselineId(), false);
        require(this.state.identity("CURRENT.new").equals(anchor), "the candidate pointer was replaced");
        this.state.moveOwned("CURRENT.new", anchor, this.state, "CURRENT", this.state.identity("CURRENT"), point -> {
            this.checkpoint.accept(point);
            if (point.startsWith("before-move:")) {
                try {
                    this.verifyDirectories();
                    this.preflightCleanup(true);
                    ProjectBaselineVerification.verifyOutput(c);
                    same(c, store.load(c.baselineId()));
                    store.verifyPointerIdentity(b.baselineId(), false);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        });
        this.checkpoint.accept("current-published");
        trail = trail.phase(Phase.PUBLISHED);
        journal.store(trail);
        this.finish(true, journal, trail);
    }

    /** Which directories to create, which files to change, which directories to remove - in the order they will run. */
    private List<Step> steps(VersionPlan plan, Map<String, byte[]> candidateBytes) throws IOException {
        List<Step> steps = new ArrayList<>();
        TreeSet<String> parentsToCreate = new TreeSet<>();
        Set<String> deleted = new HashSet<>();
        for (VersionPlan.FileOp f : plan.files()) {
            if (f.action() == VersionPlan.Action.CREATE) {
                parentsToCreate.addAll(ancestors(f.path()));
            } else if (f.action() == VersionPlan.Action.DELETE) {
                deleted.add(f.path());
            }
        }

        for (String dir : parentsToCreate) {
            if (this.output.exists(dir)) {
                this.output.directoryIdentity(dir);
            } else {
                steps.add(new Step(OpKind.MKDIR, dir, this.parentKey(dir), "", 0, "", null, 0, ""));
            }
        }

        for (VersionPlan.FileOp f : plan.files()) {
            switch (f.action()) {
                case CREATE -> {
                    require(!this.output.exists(f.path()), "a new file would land on an existing path: " + f.path());
                    steps.add(new Step(OpKind.CREATE, f.path(), this.parentKey(f.path()), "", 0, "", candidateBytes.get(f.path()), f.afterBytes(), f.afterSha256()));
                }
                case UPDATE -> {
                    String key = this.output.identity(f.path()).toString();
                    check(this.output, f.path(), key, f.beforeBytes(), f.beforeSha256());
                    steps.add(new Step(OpKind.UPDATE, f.path(), this.parentKey(f.path()), key, f.beforeBytes(), f.beforeSha256(), candidateBytes.get(f.path()), f.afterBytes(), f.afterSha256()));
                }
                case DELETE -> {
                    String key = this.output.identity(f.path()).toString();
                    check(this.output, f.path(), key, f.beforeBytes(), f.beforeSha256());
                    steps.add(new Step(OpKind.DELETE, f.path(), this.parentKey(f.path()), key, f.beforeBytes(), f.beforeSha256(), null, 0, ""));
                }
            }
        }

        // A directory goes away only if the new version has nothing under it and nothing else is left in it.
        Set<String> wanted = new HashSet<>();
        for (String path : candidateBytes.keySet()) {
            wanted.addAll(ancestors(path));
        }

        TreeSet<String> removable = new TreeSet<>(Comparator.reverseOrder());
        List<String> consider = new ArrayList<>();
        for (String path : deleted) {
            for (String dir : ancestors(path)) {
                if (!wanted.contains(dir) && !consider.contains(dir)) {
                    consider.add(dir);
                }
            }
        }

        consider.sort(Comparator.comparingInt((String d) -> d.split("/").length).reversed().thenComparing(Comparator.<String>reverseOrder()));
        for (String dir : consider) {
            if (!this.output.exists(dir)) {
                continue;
            }

            boolean empty = true;
            for (String name : this.output.list(dir, MAX_DIRECTORY_ENTRIES)) {
                String child = dir + "/" + name;
                if (!deleted.contains(child) && !removable.contains(child)) {
                    empty = false;
                    break;
                }
            }

            if (empty) {
                removable.add(dir);
            }
        }

        for (String dir : removable) {
            steps.add(new Step(OpKind.RMDIR, dir, this.output.directoryIdentity(parent(dir)).toString(), this.output.directoryIdentity(dir).toString(), 0, "", null, 0, ""));
        }

        return steps;
    }

    /** The identity of a path's parent directory, or empty when this transaction creates that directory. */
    private String parentKey(String path) throws IOException {
        String parent = parent(path);
        if (parent.isEmpty()) {
            return this.output.directoryIdentity("").toString();
        }

        return this.output.exists(parent) ? this.output.directoryIdentity(parent).toString() : "";
    }

    private void recheckParent(Step step) throws IOException {
        if (!step.parentKey().isEmpty()) {
            require(this.output.directoryIdentity(parent(step.path())).toString().equals(step.parentKey()), "the output parent directory changed: " + step.path());
        }
    }

    /** Runs one step. Every precondition is checked against the recorded identity right before the change. */
    private void forward(int index, Op op) throws IOException {
        String staging = this.prefix() + "/staging/" + index;
        String backup = this.prefix() + "/backups/" + index;
        switch (op.kind()) {
            case MKDIR -> {
                require(!this.output.exists(op.path()), "a directory to create already exists: " + op.path());
                this.output.createDirectory(op.path());
                this.output.directoryIdentity(op.path());
            }
            case CREATE -> {
                require(!this.output.exists(op.path()), "a file to create already exists: " + op.path());
                check(this.state, staging, op.candidateKey(), op.candidateSize(), op.candidateSha());
                this.state.moveOwned(staging, this.state.identity(staging), this.output, op.path(), null, this.checkpoint);
                check(this.output, op.path(), op.candidateKey(), op.candidateSize(), op.candidateSha());
            }
            case UPDATE -> {
                check(this.output, op.path(), op.baseKey(), op.baseSize(), op.baseSha());
                check(this.state, backup, op.baseKey(), op.baseSize(), op.baseSha());
                check(this.state, staging, op.candidateKey(), op.candidateSize(), op.candidateSha());
                this.state.moveOwned(staging, this.state.identity(staging), this.output, op.path(), this.output.identity(op.path()), this.checkpoint);
                check(this.output, op.path(), op.candidateKey(), op.candidateSize(), op.candidateSha());
            }
            case DELETE -> {
                check(this.output, op.path(), op.baseKey(), op.baseSize(), op.baseSha());
                check(this.state, backup, op.baseKey(), op.baseSize(), op.baseSha());
                this.output.deleteOwned(op.path(), this.output.identity(op.path()));
                require(!this.output.exists(op.path()), "a deleted file is still there: " + op.path());
            }
            case RMDIR -> {
                require(this.output.directoryIdentity(op.path()).toString().equals(op.baseKey()), "a directory to remove was replaced: " + op.path());
                require(this.output.list(op.path(), 2).isEmpty(), "a directory to remove is not empty: " + op.path());
                this.output.deleteOwnedDirectory(op.path(), this.output.directoryIdentity(op.path()));
                require(!this.output.exists(op.path()), "a removed directory is still there: " + op.path());
            }
        }
    }

    // ---------------------------------------------------------------- recovery

    /**
     * Explicit recovery; never run by reading or planning. Returns whether the new version is the
     * current one. Requires the complete, anchored binding before it changes anything in the output.
     */
    public boolean recover(Optional<String> expectedDigest) throws IOException {
        ProjectBaselineStore store = new ProjectBaselineStore(this.state);
        if (!this.state.exists(this.prefix())) {
            return this.recoverTerminal(expectedDigest, store);
        }

        this.binding = loadBinding(this.state, this.id);
        this.started = true;
        if (expectedDigest.isPresent()) {
            require(expectedDigest.get().equals(this.binding.digest()), "the recovery handle differs from the complete binding");
        }

        require(this.output.root().equals(this.binding.outputRoot()), "recovery is bound to another output root");
        this.verifyDirectories();
        this.verifyRecoveryPlan();
        String current = store.current().orElseThrow(() -> problem("STATE-001", "CURRENT is missing; the direction cannot be inferred"));
        var p = this.binding.publication();
        require(current.equals(p.b0()) || current.equals(p.b1()), "CURRENT is neither the old nor the new version");
        store.verifyPointerIdentity(current, false);
        VersionJournalSlots journal = this.journal();
        if (!this.state.exists(this.prefix() + "/journal")) {
            require(current.equals(p.b0()) && !this.state.exists(this.prefix() + "/journal.new"), "the missing journal is not an initial preparation");
            for (int i = 0; i < this.binding.ops().size(); i++) {
                Op op = this.binding.ops().get(i);
                require(this.situation(op) == Situation.BEFORE, "the output changed before the journal started: " + op.path());
                this.checkStaged(i, op);
            }

            journal.initialize(VersionTrail.start(this.binding.digest(), this.binding.ops().size()));
        }

        VersionTrail trail = journal.read();
        this.preflightRecovery(current.equals(p.b1()), trail);
        journal.discardPending();
        if (this.state.exists("CURRENT.new")) {
            Object key = this.state.identity("CURRENT.new");
            require(key.equals(this.state.identity(ProjectBaselineStore.anchor(p.b1()))) && ProjectBaselineStore.pointer(this.state.read("CURRENT.new", 65)).equals(p.b1()),
                "an external pending CURRENT is retained");
            this.state.deleteOwned("CURRENT.new", key);
        }

        if (current.equals(p.b1())) {
            require(EnumSet.of(Phase.COMMITTING, Phase.PUBLISHED, Phase.CLEANING, Phase.COMPLETED).contains(trail.state().phase()), "CURRENT is the new version but the journal says the files are not done");
            ProjectBaselineVerification.verifyOutput(store.load(p.b1()));
            if (trail.state().phase() == Phase.COMMITTING) {
                trail = trail.phase(Phase.PUBLISHED);
                journal.store(trail);
            }

            this.finish(true, journal, trail);
            return true;
        }

        require(!EnumSet.of(Phase.PUBLISHED, Phase.CLEANING, Phase.COMPLETED).contains(trail.state().phase()), "the journal claims publication while CURRENT is the old version");
        if (trail.state().phase() != Phase.ROLLING_BACK && trail.state().phase() != Phase.ROLLED_BACK) {
            trail = trail.phase(Phase.ROLLING_BACK);
            journal.store(trail);
        }

        if (trail.state().phase() != Phase.ROLLED_BACK) {
            for (int i = this.binding.ops().size() - 1; i >= 0; i--) {
                Op op = this.binding.ops().get(i);
                this.verifyDirectories();
                OpState status = trail.state().ops().get(i);
                if (status == OpState.RESTORED) {
                    this.requireRestored(op);
                    continue;
                }

                if (status != OpState.RESTORING) {
                    trail = trail.op(i, OpState.RESTORING);
                    journal.store(trail);
                }

                this.checkpoint.accept("restore-intent:" + i);
                this.restore(i, op);
                this.requireRestored(op);
                trail = trail.op(i, OpState.RESTORED);
                journal.store(trail);
            }
        }

        ProjectBaselineVerification.verifyOutput(store.load(p.b0()));
        if (trail.state().phase() != Phase.ROLLED_BACK) {
            trail = trail.phase(Phase.ROLLED_BACK);
            journal.store(trail);
        }

        this.finish(false, journal, trail);
        return false;
    }

    private boolean recoverTerminal(Optional<String> expectedDigest, ProjectBaselineStore store) throws IOException {
        VersionReceipt.Verified receipt = VersionReceipt.read(this.state, this.id);
        this.binding = receipt.binding();
        if (expectedDigest.isPresent()) {
            require(expectedDigest.get().equals(this.binding.digest()), "the terminal recovery handle differs");
        }

        require(this.output.root().equals(this.binding.outputRoot()) && this.output.directoryIdentity("").toString().equals(this.binding.outputKey()), "the terminal output root differs");
        boolean published = receipt.trail().state().phase() == Phase.COMPLETED;
        String current = published ? this.binding.publication().b1() : this.binding.publication().b0();
        require(store.current().equals(Optional.of(current)), "the terminal handle is stale after a later update");
        store.guardState(current, false);
        store.verifyPointerIdentity(current, false);
        Bundle saved = store.load(current);
        var verified = this.replay.compile(saved.sources(), this.output.root(), new ArrayList<>());
        require(verified != null, "the saved CURRENT sources do not compile");
        same(saved, verified.bundle());
        ProjectBaselineVerification.verifyOutput(saved);
        return published;
    }

    /** Proves the binding is what the saved sources produce, before trusting any row of it. */
    private void verifyRecoveryPlan() throws IOException {
        ProjectBaselineStore store = new ProjectBaselineStore(this.state);
        var p = this.binding.publication();
        Bundle b = store.load(p.b0());
        var payload = this.binding.owned().stream().filter(o -> o.relativePath().equals(this.prefix() + "/candidate.sources") && o.lifetime() == Lifetime.RECEIPT).findFirst()
            .orElseThrow(() -> problem("STATE-001", "the candidate replay evidence is missing"));
        check(this.state, payload.relativePath(), payload.key(), payload.size(), payload.sha());
        SourceSnapshot sources = decodeSources(this.state.read(payload.relativePath(), MAX_PAYLOAD));
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        var base = this.replay.compile(b.sources(), this.output.root(), diagnostics);
        var candidate = this.replay.compile(sources, this.output.root(), diagnostics);
        require(base != null && candidate != null, "the recovery sources do not recompile");
        same(b, base.bundle());
        require(candidate.bundle().baselineId().equals(p.b1()), "the replayed candidate version differs");
        require(p.contextDigest().equals(VersionPlans.contextDigest(this.state.root(), p.b0(), p.b1())) && p.subject().equals(VersionPlans.SUBJECT), "the recovery context differs");
        VersionPlan plan = VersionPlans.compare(this.state.root(), base, candidate);
        require(plan.sha256Hex().equals(this.binding.planDigest()) && plan.sha256Hex().equals(p.planDigest()), "the recovery plan differs from a full replay");

        List<Op> files = this.binding.ops().stream().filter(o -> o.kind() == OpKind.CREATE || o.kind() == OpKind.UPDATE || o.kind() == OpKind.DELETE).toList();
        require(files.size() == plan.files().size(), "the recovery file steps are incomplete");
        for (int i = 0; i < files.size(); i++) {
            Op op = files.get(i);
            VersionPlan.FileOp f = plan.files().get(i);
            require(op.kind().name().equals(f.action().name()) && op.path().equals(f.path()) && op.baseSize() == f.beforeBytes() && op.baseSha().equals(f.beforeSha256())
                && op.candidateSize() == f.afterBytes() && op.candidateSha().equals(f.afterSha256()), "the recovery step differs from the replayed plan: " + op.path());
        }

        Set<String> created = new HashSet<>();
        Set<String> deleted = new HashSet<>();
        plan.files().forEach(f -> (f.action() == VersionPlan.Action.CREATE ? created : deleted).add(f.path()));
        Set<String> wanted = new HashSet<>();
        candidate.compilation().generatedFiles().forEach(f -> wanted.addAll(ancestors(f.relativePath())));
        for (Op op : this.binding.ops()) {
            if (op.kind() == OpKind.MKDIR) {
                require(created.stream().anyMatch(path -> path.startsWith(op.path() + "/")), "a directory to create has no new file under it: " + op.path());
            } else if (op.kind() == OpKind.RMDIR) {
                require(deleted.stream().anyMatch(path -> path.startsWith(op.path() + "/")) && !wanted.contains(op.path()), "a directory to remove is still needed or unrelated: " + op.path());
            }
        }
    }

    /** Checks every step's output against the journal's progress and the direction, before any recovery write. */
    private void preflightRecovery(boolean published, VersionTrail trail) throws IOException {
        this.preflightCleanup(published || trail.state().phase() != Phase.ROLLED_BACK);
        for (int i = 0; i < this.binding.ops().size(); i++) {
            Op op = this.binding.ops().get(i);
            Situation now = this.situation(op);
            OpState status = trail.state().ops().get(i);
            boolean acceptable;
            if (published) {
                acceptable = now == Situation.AFTER;
            } else {
                // A directory removed and then recreated by an undo has a new identity; any directory there is fine.
                boolean recreated = op.kind() == OpKind.RMDIR && this.output.exists(op.path());
                acceptable = switch (status) {
                    case STAGED -> now == Situation.BEFORE;
                    case APPLYING -> now != Situation.OTHER;
                    case RESTORING -> now != Situation.OTHER || recreated;
                    case APPLIED -> now == Situation.AFTER;
                    case RESTORED -> now == Situation.BEFORE || recreated;
                };
            }

            require(acceptable, "an output path contradicts the recorded progress or was replaced from outside: " + op.path());
            if (now == Situation.AFTER) {
                this.checkAfter(op);
            } else if (now == Situation.BEFORE) {
                this.checkBefore(op);
            }

            if (!published && status != OpState.RESTORED) {
                this.checkEvidenceKept(i, op, now);
            }
        }
    }

    /** What a step still needs in the transaction directory so it can be undone. */
    private void checkEvidenceKept(int index, Op op, Situation now) throws IOException {
        String backup = this.prefix() + "/backups/" + index;
        String staging = this.prefix() + "/staging/" + index;
        if (op.backedUp() && now == Situation.AFTER) {
            check(this.state, backup, op.baseKey(), op.baseSize(), op.baseSha());
        }

        if (op.kind() == OpKind.CREATE && now == Situation.BEFORE) {
            check(this.state, staging, op.candidateKey(), op.candidateSize(), op.candidateSha());
        }

        if (op.kind() == OpKind.CREATE && now == Situation.AFTER) {
            require(!this.state.exists(staging), "the staging slot of a created file is occupied: " + op.path());
        }
    }

    private void checkStaged(int index, Op op) throws IOException {
        if (op.staged()) {
            check(this.state, this.prefix() + "/staging/" + index, op.candidateKey(), op.candidateSize(), op.candidateSha());
        }

        if (op.backedUp()) {
            check(this.state, this.prefix() + "/backups/" + index, op.baseKey(), op.baseSize(), op.baseSha());
        }
    }

    private void checkAfter(Op op) throws IOException {
        switch (op.kind()) {
            case UPDATE, CREATE -> check(this.output, op.path(), op.candidateKey(), op.candidateSize(), op.candidateSha());
            default -> { }
        }
    }

    private void checkBefore(Op op) throws IOException {
        switch (op.kind()) {
            case UPDATE, DELETE -> check(this.output, op.path(), op.baseKey(), op.baseSize(), op.baseSha());
            default -> { }
        }
    }

    /** Where an output path stands relative to what this step does to it. */
    private Situation situation(Op op) throws IOException {
        boolean exists = this.output.exists(op.path());
        switch (op.kind()) {
            case MKDIR -> {
                if (!exists) {
                    return Situation.BEFORE;
                }

                try {
                    this.output.directoryIdentity(op.path());
                    return Situation.AFTER;
                } catch (IOException e) {
                    return Situation.OTHER;
                }
            }
            case RMDIR -> {
                if (!exists) {
                    return Situation.AFTER;
                }

                try {
                    return this.output.directoryIdentity(op.path()).toString().equals(op.baseKey()) ? Situation.BEFORE : Situation.OTHER;
                } catch (IOException e) {
                    return Situation.OTHER;
                }
            }
            case CREATE -> {
                if (!exists) {
                    return Situation.BEFORE;
                }

                return this.output.identity(op.path()).toString().equals(op.candidateKey()) ? Situation.AFTER : Situation.OTHER;
            }
            case UPDATE -> {
                if (!exists) {
                    return Situation.OTHER;
                }

                String key = this.output.identity(op.path()).toString();
                return key.equals(op.baseKey()) ? Situation.BEFORE : key.equals(op.candidateKey()) ? Situation.AFTER : Situation.OTHER;
            }
            case DELETE -> {
                if (!exists) {
                    return Situation.AFTER;
                }

                return this.output.identity(op.path()).toString().equals(op.baseKey()) ? Situation.BEFORE : Situation.OTHER;
            }
        }

        throw new IllegalStateException();
    }

    /** Undoes one step, going back. Atomic moves and recorded identities keep each undo itself restartable. */
    private void restore(int index, Op op) throws IOException {
        String staging = this.prefix() + "/staging/" + index;
        String backup = this.prefix() + "/backups/" + index;
        Situation now = this.situation(op);
        switch (op.kind()) {
            case UPDATE -> {
                if (now == Situation.AFTER) {
                    check(this.output, op.path(), op.candidateKey(), op.candidateSize(), op.candidateSha());
                    check(this.state, backup, op.baseKey(), op.baseSize(), op.baseSha());
                    this.state.moveOwned(backup, this.state.identity(backup), this.output, op.path(), this.output.identity(op.path()), this.checkpoint);
                    this.checkpoint.accept("output-restored:" + index);
                }
            }
            case CREATE -> {
                if (now == Situation.AFTER) {
                    check(this.output, op.path(), op.candidateKey(), op.candidateSize(), op.candidateSha());
                    this.output.moveOwned(op.path(), this.output.identity(op.path()), this.state, staging, null, this.checkpoint);
                    this.checkpoint.accept("output-restored:" + index);
                }
            }
            case DELETE -> {
                if (now == Situation.AFTER) {
                    check(this.state, backup, op.baseKey(), op.baseSize(), op.baseSha());
                    this.state.moveOwned(backup, this.state.identity(backup), this.output, op.path(), null, this.checkpoint);
                    this.checkpoint.accept("output-restored:" + index);
                }
            }
            case MKDIR -> {
                if (now == Situation.AFTER) {
                    require(this.output.list(op.path(), 2).isEmpty(), "a created directory is no longer empty: " + op.path());
                    this.output.deleteOwnedDirectory(op.path(), this.output.directoryIdentity(op.path()));
                    this.checkpoint.accept("output-restored:" + index);
                }
            }
            case RMDIR -> {
                if (now == Situation.AFTER) {
                    this.output.createDirectory(op.path());
                    this.checkpoint.accept("output-restored:" + index);
                }
            }
        }
    }

    /** After undoing a step the output must be as it was. A recreated directory has a new identity, which is expected. */
    private void requireRestored(Op op) throws IOException {
        if (op.kind() == OpKind.RMDIR) {
            require(this.output.exists(op.path()), "a removed directory was not recreated: " + op.path());
            return;
        }

        require(this.situation(op) == Situation.BEFORE, "a step was not undone: " + op.path());
        this.checkBefore(op);
    }

    // ------------------------------------------------------------------ cleanup

    private void finish(boolean published, VersionJournalSlots journal, VersionTrail trail) throws IOException {
        if (published && trail.state().phase() == Phase.PUBLISHED) {
            trail = trail.phase(Phase.CLEANING);
            journal.store(trail);
        }

        this.verifyDirectories();
        this.preflightCleanup(published);
        this.checkpoint.accept("before-cleanup");
        var remove = this.binding.owned().stream().filter(o -> o.lifetime() == Lifetime.TRANSACTION || (!published && o.lifetime() == Lifetime.CANDIDATE))
            .sorted(Comparator.comparingInt((Owned o) -> o.relativePath().split("/").length).reversed().thenComparing(o -> o.kind() == Kind.FILE ? 0 : 1)).toList();
        for (Owned o : remove) {
            if (this.state.exists(o.relativePath())) {
                this.verifyDirectories();
                Object key = o.kind() == Kind.FILE ? this.state.identity(o.relativePath()) : this.state.directoryIdentity(o.relativePath());
                require(key.toString().equals(o.key()), "cleanup ownership changed");
                if (o.kind() == Kind.FILE) {
                    this.state.deleteOwned(o.relativePath(), key);
                } else {
                    this.state.deleteOwnedDirectory(o.relativePath(), key);
                }

                this.checkpoint.accept("cleaned:" + o.relativePath());
            }
        }

        if (published && trail.state().phase() != Phase.COMPLETED) {
            trail = trail.phase(Phase.COMPLETED);
            journal.store(trail);
        }

        require(this.state.list(this.prefix(), 12).equals(VersionReceipt.MEMBERS), "unknown terminal transaction members");
        this.checkpoint.accept("before-receipt-relocation");
        this.state.moveOwnedDirectory(this.prefix(), this.state.directoryIdentity(this.prefix()), ProjectUpdateReceipt.DIRECTORY + "/" + this.id, this.checkpoint);
        this.checkpoint.accept("receipt-relocated");
        VersionReceipt.Verified receipt = VersionReceipt.read(this.state, this.id);
        require(receipt.binding().digest().equals(this.binding.digest()), "the relocated terminal evidence differs");
        ProjectBaselineStore store = new ProjectBaselineStore(this.state);
        String current = published ? this.binding.publication().b1() : this.binding.publication().b0();
        store.guardState(current, false);
        store.verifyPointerIdentity(current, false);
        ProjectBaselineVerification.verifyOutput(store.load(current));
        this.checkpoint.accept("finished");
    }

    /** Before deleting anything: every owned object is still exactly what was recorded, and nothing unknown is around. */
    private void preflightCleanup(boolean published) throws IOException {
        this.verifyDirectories();
        require(Set.of("LOCK", "baselines", "CURRENT", "CURRENT.new", ProjectPublicationHistory.DIRECTORY, ACTIVE).containsAll(this.state.list("")), "an unknown state object is retained");
        Set<String> expected = new HashSet<>(List.of("binding", "binding.anchor", "journal.A", "journal.B", "journal.A.anchor", "journal.B.anchor", "journal", "journal.new", "staging", "backups", "candidate.sources"));
        require(expected.containsAll(this.state.list(this.prefix(), 12)), "an unknown transaction object is retained");
        for (String dir : List.of("staging", "backups")) {
            if (this.state.exists(this.prefix() + "/" + dir)) {
                Set<String> allowed = new HashSet<>();
                for (int i = 0; i < this.binding.ops().size(); i++) {
                    allowed.add(Integer.toString(i));
                }

                require(allowed.containsAll(this.state.list(this.prefix() + "/" + dir, VersionTrail.MAX_OPS)), "an unknown staging or backup member is retained");
            }
        }

        for (Owned o : this.binding.owned()) {
            if (!this.state.exists(o.relativePath())) {
                require(!(o.lifetime() == Lifetime.STATE || o.lifetime() == Lifetime.RECEIPT || (published && o.lifetime() == Lifetime.CANDIDATE)), "retained or published evidence is missing: " + o.relativePath());
                continue;
            }

            Object key = o.kind() == Kind.FILE ? this.state.identity(o.relativePath()) : this.state.directoryIdentity(o.relativePath());
            require(key.toString().equals(o.key()), "an owned object was replaced: " + o.relativePath());
            if (o.kind() == Kind.FILE) {
                check(this.state, o.relativePath(), o.key(), o.size(), o.sha());
            }

            if (o.kind() == Kind.DIRECTORY && o.lifetime() == Lifetime.CANDIDATE) {
                require(ProjectBaselineStore.MEMBERS.containsAll(this.state.list(o.relativePath())), "an unknown candidate Bundle member is retained");
            }
        }
    }

    /** Roots, the transaction directories, and every parent directory still where it was recorded. */
    private void verifyDirectories() throws IOException {
        require(this.state.directoryIdentity("").toString().equals(this.binding.stateKey()) && this.output.directoryIdentity("").toString().equals(this.binding.outputKey()),
            "the transaction roots physically changed");
        require(this.state.directoryIdentity(ACTIVE).toString().equals(this.binding.transactionsKey()) && this.state.directoryIdentity(this.prefix()).toString().equals(this.binding.directoryKey()),
            "the transaction directories physically changed");
        Set<String> volatileDirs = new HashSet<>();
        for (Op op : this.binding.ops()) {
            if (op.kind() == OpKind.MKDIR || op.kind() == OpKind.RMDIR) {
                volatileDirs.add(op.path());
            }
        }

        for (Op op : this.binding.ops()) {
            String parent = parent(op.path());
            if (!op.parentKey().isEmpty() && !volatileDirs.contains(parent) && (parent.isEmpty() || this.output.exists(parent))) {
                require(this.output.directoryIdentity(parent).toString().equals(op.parentKey()), "an output parent directory changed: " + op.path());
            }
        }
    }

    public static VersionBinding loadBinding(SecureFileAccess state, String id) throws IOException {
        hex(id);
        String prefix = ACTIVE + "/" + id;
        require(state.list(ACTIVE, 1).equals(Set.of(id)), "unknown or multiple active transaction directories");
        VersionBinding result = VersionBinding.decode(state.read(prefix + "/binding", VersionBinding.MAX_BYTES));
        require(result.transactionId().equals(id) && result.stateRoot().equals(state.root()), "the transaction path and state binding mismatch");
        require(state.identity(prefix + "/binding").equals(state.identity(prefix + "/binding.anchor")), "the transaction binding was externally replaced");
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private VersionJournalSlots journal() {
        return new VersionJournalSlots(this.state, this.prefix(), this.binding.digest(), this.binding.slotAKey(), this.binding.slotBKey(), this.checkpoint);
    }

    private void directory(String path, Lifetime lifetime, List<Owned> owned) throws IOException {
        this.state.createDirectory(path);
        owned.add(new Owned(path, Kind.DIRECTORY, this.state.directoryIdentity(path).toString(), lifetime, 0, ""));
        this.checkpoint.accept("directory:" + path);
    }

    private void write(String path, byte[] bytes, Lifetime lifetime, List<Owned> owned) throws IOException {
        this.state.writeNew(path, bytes, this.checkpoint);
        owned.add(new Owned(path, Kind.FILE, this.state.identity(path).toString(), lifetime, bytes.length, Sha256.hexDigest(bytes)));
    }

    private static String parent(String path) {
        int at = path.lastIndexOf('/');
        return at < 0 ? "" : path.substring(0, at);
    }

    /** Every directory above a file path, shallowest first, excluding the root. */
    private static List<String> ancestors(String path) {
        List<String> out = new ArrayList<>();
        int at = path.indexOf('/');
        while (at >= 0) {
            out.add(path.substring(0, at));
            at = path.indexOf('/', at + 1);
        }

        return out;
    }

    private static void check(SecureFileAccess access, String path, String key, long size, String sha) throws IOException {
        require(access.identity(path).toString().equals(key), "a file was externally replaced: " + path);
        byte[] bytes = access.read(path, (int)size);
        require(bytes.length == size && Sha256.hexDigest(bytes).equals(sha), "a file's bytes changed: " + path);
    }

    private static Map<String, byte[]> generated(ProjectBaselineVerification.Verified candidate) throws IOException {
        Map<String, byte[]> map = new TreeMap<>();
        candidate.compilation().generatedFiles().forEach(f -> map.put(f.relativePath(), f.content().getBytes(StandardCharsets.UTF_8)));
        for (var entry : candidate.bundle().descriptor().manifest()) {
            byte[] bytes = map.get(entry.relativePath());
            require(bytes != null && bytes.length == entry.byteCount() && Sha256.hexDigest(bytes).equals(entry.sha256Hex()), "the candidate's generated bytes do not match its manifest");
        }

        return map;
    }

    private static void same(Bundle a, Bundle b) throws IOException {
        require(Arrays.equals(a.descriptorBytes(), b.descriptorBytes()) && Arrays.equals(a.payloadBytes(), b.payloadBytes()) && Arrays.equals(a.graphBytes(), b.graphBytes()),
            "the stored Bundle differs from a full byte replay");
    }
}
