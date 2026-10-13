package io.kcg.sir.application.internal.version;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.MultiSourceTestSupport;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineStore;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.version.VersionScenarios.Kind;
import io.kcg.sir.application.internal.version.VersionScenarios.Scenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A process can stop at any point. For every stop point of the apply (and then of the recovery
 * itself) the stored project must end up as exactly the old version or exactly the new one - the
 * generated files, the directory tree and {@code CURRENT} all agreeing - and recovering again must
 * change nothing.
 */
class VersionFaultMatrixTest {
    private static final String TX = VersionTransactionTest.TX;

    @TempDir Path temp;

    private static final class Stop extends IllegalStateException {
        Stop(String message) {
            super(message);
        }
    }

    /** Runs {@code body} with a checkpoint hook that stops the process at checkpoint number {@code at}. */
    private static void stopAt(Scenario s, int at, List<String> observed, Body body) throws Exception {
        try (var state = new SecureFileAccess(s.state()); var output = new SecureFileAccess(s.output())) {
            body.run(new VersionTransaction(state, output, TX, point -> {
                observed.add(point);
                if (observed.size() - 1 == at) {
                    throw new Stop("process stopped at " + at + ": " + point);
                }
            }));
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(VersionTransaction transaction) throws Exception;
    }

    static boolean recover(Scenario s) throws Exception {
        try (var state = new SecureFileAccess(s.state()); var output = new SecureFileAccess(s.output())) {
            return new VersionTransaction(state, output, TX, x -> { }).recover(Optional.empty());
        }
    }

    private static List<String> recoverTrace(Scenario s) throws Exception {
        List<String> trace = new ArrayList<>();
        try (var state = new SecureFileAccess(s.state()); var output = new SecureFileAccess(s.output())) {
            new VersionTransaction(state, output, TX, trace::add).recover(Optional.empty());
        }

        return trace;
    }

    private static boolean candidateIsCurrent(Scenario s) throws Exception {
        return Files.readString(s.state().resolve("CURRENT")).trim().equals(s.candidateId());
    }

    /** The project is entirely one of the two versions, and the stored state agrees with it. */
    private static void assertWhole(Scenario s, boolean candidate, String where) throws Exception {
        assertEquals(candidate ? s.candidateTree() : s.baseTree(), MultiSourceTestSupport.tree(s.output()), "generated project after " + where);
        assertEquals(candidate, candidateIsCurrent(s), "CURRENT after " + where);
        try (var state = new SecureFileAccess(s.state())) {
            String id = candidate ? s.candidateId() : s.baseId();
            new ProjectBaselineStore(state).guardState(id, false);
            assertEquals(Set.of(), state.list(VersionTransaction.ACTIVE, 1), "no active transaction after " + where);
            var receipt = VersionReceipt.read(state, TX);
            assertEquals(candidate ? VersionTrail.Phase.COMPLETED : VersionTrail.Phase.ROLLED_BACK, receipt.trail().state().phase(), "terminal phase after " + where);
        }
    }

    /** Indexes to try: all of them when affordable, otherwise every boundary checkpoint plus a regular sample. */
    private static List<Integer> selected(List<String> trace, int limit) {
        Set<Integer> chosen = new LinkedHashSet<>();
        int stride = Math.max(1, trace.size() / limit);
        for (int i = 0; i < trace.size(); i++) {
            String point = trace.get(i);
            boolean boundary = point.startsWith("apply-intent") || point.startsWith("output-applied") || point.startsWith("restore-intent") || point.startsWith("output-restored")
                || point.startsWith("before-") || point.startsWith("current-") || point.startsWith("binding-") || point.startsWith("journal-") || point.startsWith("cleaned") || point.equals("finished")
                || point.startsWith("moved:") || point.startsWith("receipt-") || point.startsWith("directory:") || point.startsWith("backup:") || point.startsWith("transaction-");
            if (i % stride == 0 || (boundary && trace.size() <= limit * 3) || i == 0 || i == trace.size() - 1) {
                chosen.add(i);
            }
        }

        return new ArrayList<>(chosen);
    }

    private void applyMatrix(Kind kind, int limit) throws Exception {
        Scenario seed = VersionScenarios.create(this.temp.resolve(kind + "-trace"), kind);
        List<String> trace = new ArrayList<>();
        VersionTransactionTest.apply(seed, trace);
        VersionTransactionTest.assertCandidateInPlace(seed);
        int complete = trace.indexOf("binding-complete");
        assertTrue(complete > 0);
        int retained = 0;
        int rolledBack = 0;
        int committed = 0;
        List<Integer> indexes = selected(trace, limit);
        for (int index : indexes) {
            Scenario s = VersionScenarios.create(this.temp.resolve(kind + "-apply-" + index), kind);
            List<String> observed = new ArrayList<>();
            assertThrows(Stop.class, () -> stopAt(s, index, observed, t -> t.apply(s.base(), s.candidate(), s.plan())), kind + " checkpoint " + index);
            assertEquals(normalize(trace.get(index)), normalize(observed.getLast()), "checkpoint order " + index);

            if (index < complete) {
                // the preparation was cut short: the project has not been touched and the evidence is kept, not guessed about
                assertEquals(s.baseTree(), MultiSourceTestSupport.tree(s.output()), kind + " untouched at " + index);
                assertFalse(candidateIsCurrent(s));
                if (index > 0) {
                    Map<String, String> before = MultiSourceTestSupport.tree(s.state());
                    assertThrows(IOException.class, () -> recover(s), "incomplete preparation " + index);
                    assertEquals(before, MultiSourceTestSupport.tree(s.state()), "a refused recovery changes nothing " + index);
                }

                retained++;
                continue;
            }

            boolean published = candidateIsCurrent(s);
            assertEquals(published, recover(s), kind + " direction at " + index);
            assertWhole(s, published, kind + " apply checkpoint " + index);
            Map<String, String> after = MultiSourceTestSupport.tree(s.state());
            assertEquals(published, recover(s), "recovering again");
            assertEquals(after, MultiSourceTestSupport.tree(s.state()), "recovering again changes nothing " + index);
            if (published) {
                committed++;
            } else {
                rolledBack++;
            }
        }

        System.out.println("R4-MATRIX apply " + kind + ": checkpoints=" + trace.size() + " tried=" + indexes.size() + " retained=" + retained + " rolledBack=" + rolledBack + " committed=" + committed);
        assertTrue(rolledBack > 0 && committed > 0, "both directions are exercised for " + kind);
    }

    private static String normalize(String s) {
        return s.replaceAll("[0-9a-f]{64}", "<id>");
    }

    /** Stops an apply at the first checkpoint equal to {@code point}. */
    static void stopApplyAt(Scenario s, String point) throws Exception {
        AtomicBoolean hit = new AtomicBoolean();
        try (var state = new SecureFileAccess(s.state()); var output = new SecureFileAccess(s.output())) {
            assertThrows(Stop.class, () -> new VersionTransaction(state, output, TX, p -> {
                if (!hit.get() && p.equals(point)) {
                    hit.set(true);
                    throw new Stop("stop at " + point);
                }
            }).apply(s.base(), s.candidate(), s.plan()));
        }

        assertTrue(hit.get(), "checkpoint " + point + " was reached");
    }

    private void recoveryMatrix(Kind kind, boolean published, String stopPoint, int limit) throws Exception {
        Scenario seed = VersionScenarios.create(this.temp.resolve(kind + "-rec-" + published + "-trace"), kind);
        stopApplyAt(seed, stopPoint);
        assertEquals(published, candidateIsCurrent(seed));
        List<String> trace = recoverTrace(seed);
        assertWhole(seed, published, kind + " traced recovery");
        List<Integer> indexes = selected(trace, limit);
        for (int index : indexes) {
            Scenario s = VersionScenarios.create(this.temp.resolve(kind + "-rec-" + published + "-" + index), kind);
            stopApplyAt(s, stopPoint);
            List<String> observed = new ArrayList<>();
            assertThrows(Stop.class, () -> stopAt(s, index, observed, t -> t.recover(Optional.empty())), kind + " recovery checkpoint " + index);
            assertEquals(normalize(trace.get(index)), normalize(observed.getLast()), "recovery checkpoint order " + index);
            assertEquals(published, recover(s), kind + " the interrupted recovery keeps its direction at " + index);
            assertWhole(s, published, kind + " recovery checkpoint " + index);
            Map<String, String> after = MultiSourceTestSupport.tree(s.state());
            assertEquals(published, recover(s));
            assertEquals(after, MultiSourceTestSupport.tree(s.state()));
        }

        System.out.println("R4-MATRIX recovery " + kind + " " + (published ? "forward" : "backward") + ": checkpoints=" + trace.size() + " tried=" + indexes.size());
    }

    @Test
    void mixedApplyStoppedAtEveryCheckpoint() throws Exception {
        this.applyMatrix(Kind.MIXED, 400);
    }

    @Test
    void sourceOnlyApplyStoppedAtEveryCheckpoint() throws Exception {
        this.applyMatrix(Kind.SOURCE_ONLY, 400);
    }

    @Test
    void mixedRecoveryStoppedAtEveryCheckpointKeepsItsDirection() throws Exception {
        this.recoveryMatrix(Kind.MIXED, false, "output-applied:3", 400);
        this.recoveryMatrix(Kind.MIXED, true, "current-published", 400);
    }

    @Test
    void addOnlyAndDeleteOnlyApplyStoppedAtEveryCheckpoint() throws Exception {
        this.applyMatrix(Kind.ADD_ONLY, 70);
        this.applyMatrix(Kind.DELETE_ONLY, 70);
    }

    /** A new package creates and removes whole directory trees: the hardest case for undoing. */
    @Test
    void aNewPackageApplyStoppedAtSampledCheckpoints() throws Exception {
        this.applyMatrix(Kind.RENAME, 60);
    }

    @Test
    void aNewPackageRecoveryStoppedAtSampledCheckpointsKeepsItsDirection() throws Exception {
        this.recoveryMatrix(Kind.RENAME, false, "output-applied:40", 40);
        this.recoveryMatrix(Kind.RENAME, true, "current-published", 40);
    }
}
