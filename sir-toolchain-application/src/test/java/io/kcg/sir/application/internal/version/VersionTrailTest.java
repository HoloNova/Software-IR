package io.kcg.sir.application.internal.version;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.internal.version.VersionTrail.OpState;
import io.kcg.sir.application.internal.version.VersionTrail.Phase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The version journal replays every event and refuses any order the transaction could not have produced. */
class VersionTrailTest {
    private static final String DIGEST = "a".repeat(64);

    private static VersionTrail forward(int ops) {
        VersionTrail trail = VersionTrail.start(DIGEST, ops).phase(Phase.UPDATING);
        for (int i = 0; i < ops; i++) {
            trail = trail.op(i, OpState.APPLYING).op(i, OpState.APPLIED);
        }

        return trail;
    }

    private static VersionTrail completed(int ops) {
        return forward(ops).phase(Phase.FILES_DONE).phase(Phase.COMMITTING).phase(Phase.PUBLISHED).phase(Phase.CLEANING).phase(Phase.COMPLETED);
    }

    @Test
    void aTransactionWithNoOperationIsValidBecauseOnlyTheSourceChanges() throws Exception {
        VersionTrail done = completed(0);

        assertEquals(Phase.COMPLETED, done.state().phase());
        assertTrue(done.terminal());
        assertEquals(done, VersionTrail.decode(done.bytes()));
        VersionTrail back = VersionTrail.start(DIGEST, 0).phase(Phase.ROLLING_BACK).phase(Phase.ROLLED_BACK);
        assertTrue(back.terminal());
    }

    @Test
    void theForwardPathAndTheRollbackPathBothRunToTheirEnd() throws Exception {
        VersionTrail done = completed(3);
        assertEquals(List.of(OpState.APPLIED, OpState.APPLIED, OpState.APPLIED), done.state().ops());
        assertEquals(done, VersionTrail.decode(done.bytes()));

        VersionTrail partial = VersionTrail.start(DIGEST, 3).phase(Phase.UPDATING).op(0, OpState.APPLYING).op(0, OpState.APPLIED).op(1, OpState.APPLYING).phase(Phase.ROLLING_BACK);
        VersionTrail restored = partial.op(2, OpState.RESTORING).op(2, OpState.RESTORED).op(1, OpState.RESTORING).op(1, OpState.RESTORED)
            .op(0, OpState.RESTORING).op(0, OpState.RESTORED).phase(Phase.ROLLED_BACK);
        assertTrue(restored.terminal());
        assertEquals(restored, VersionTrail.decode(restored.bytes()));
        assertFalse(partial.terminal());
    }

    @Test
    void impossibleOrdersAreRefused() {
        VersionTrail start = VersionTrail.start(DIGEST, 2);
        assertThrows(IllegalArgumentException.class, () -> start.phase(Phase.COMMITTING), "cannot commit before the files");
        assertThrows(IllegalArgumentException.class, () -> start.op(0, OpState.APPLYING), "operations only run while UPDATING");
        VersionTrail updating = start.phase(Phase.UPDATING);
        assertThrows(IllegalArgumentException.class, () -> updating.op(1, OpState.APPLYING), "operations run in order");
        assertThrows(IllegalArgumentException.class, () -> updating.op(0, OpState.APPLIED), "an operation must be applying first");
        assertThrows(IllegalArgumentException.class, () -> updating.phase(Phase.FILES_DONE), "files are not done");
        VersionTrail one = updating.op(0, OpState.APPLYING).op(0, OpState.APPLIED);
        assertThrows(IllegalArgumentException.class, () -> one.phase(Phase.FILES_DONE), "one operation is still staged");
        VersionTrail rolling = one.phase(Phase.ROLLING_BACK);
        assertThrows(IllegalArgumentException.class, () -> rolling.phase(Phase.ROLLED_BACK), "rollback with unfinished operations");
        assertThrows(IllegalArgumentException.class, () -> rolling.op(0, OpState.RESTORED), "restoring comes first");
        assertThrows(IllegalArgumentException.class, () -> completed(1).phase(Phase.ROLLING_BACK), "a completed transaction does not roll back");
        assertThrows(IllegalArgumentException.class, () -> completed(0).phase(Phase.UPDATING), "nothing follows COMPLETED");
        assertThrows(IllegalArgumentException.class, () -> VersionTrail.start(DIGEST, VersionTrail.MAX_OPS + 1));
        assertThrows(IllegalArgumentException.class, () -> VersionTrail.start("short", 1));
    }

    @Test
    void decodingRejectsAnythingEncodingWouldNotHaveProduced() throws Exception {
        VersionTrail trail = completed(2);
        byte[] bytes = trail.bytes();

        assertEquals(trail, VersionTrail.decode(bytes));
        assertThrows(Exception.class, () -> VersionTrail.decode(Arrays.copyOf(bytes, bytes.length - 1)), "truncated");
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(Exception.class, () -> VersionTrail.decode(trailing), "trailing byte");
        byte[] magic = bytes.clone();
        magic[0] ^= 1;
        assertThrows(Exception.class, () -> VersionTrail.decode(magic), "wrong magic");
        byte[] event = bytes.clone();
        int at = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("F:0:APPLYING");
        assertTrue(at > 0);
        event[at + 2] = '1';
        assertThrows(Exception.class, () -> VersionTrail.decode(event), "a changed event is replayed and refused");
        assertThrows(Exception.class, () -> VersionTrail.decode(new byte[0]));
    }

    @Test
    void theEventBudgetBoundsTheJournal() {
        List<String> events = new ArrayList<>();
        events.add("P:PREPARED");
        for (int i = 0; i < VersionTrail.MAX_EVENTS; i++) {
            events.add("P:UPDATING");
        }

        assertThrows(IllegalArgumentException.class, () -> new VersionTrail(DIGEST, 0, events));
    }

    @Test
    void aFullSizedTransactionStillFitsOneJournalRecord() throws Exception {
        int ops = VersionTrail.MAX_OPS;
        VersionTrail trail = VersionTrail.start(DIGEST, ops).phase(Phase.UPDATING);
        for (int i = 0; i < ops; i++) {
            trail = trail.op(i, OpState.APPLYING).op(i, OpState.APPLIED);
        }

        trail = trail.phase(Phase.ROLLING_BACK);
        for (int i = ops - 1; i >= 0; i--) {
            trail = trail.op(i, OpState.RESTORING).op(i, OpState.RESTORED);
        }

        trail = trail.phase(Phase.ROLLED_BACK);
        assertTrue(trail.events().size() <= VersionTrail.MAX_EVENTS, "the longest legal journal is " + trail.events().size());
        assertEquals(trail, VersionTrail.decode(trail.bytes()));
    }
}
