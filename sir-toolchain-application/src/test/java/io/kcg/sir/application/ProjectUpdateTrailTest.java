package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.internal.projectupdate.ProjectUpdateTrail.*;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateTrail;
import io.kcg.sir.application.internal.projectbaseline.ProjectPublicationHistory;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectUpdateTrailTest {
    final String digest="1".repeat(64);
    ProjectUpdateTrail updated() {return ProjectUpdateTrail.start(digest,2).phase(Phase.UPDATING).file(0,FileState.REPLACING).file(0,FileState.UPDATED).file(1,FileState.REPLACING).file(1,FileState.UPDATED).phase(Phase.FILES_DONE).phase(Phase.COMMITTING);}
    @Test void fullOrderedCommitRoundTripsAndTerminalRejectsAppend() throws Exception {
        var t=updated().phase(Phase.PUBLISHED).phase(Phase.CLEANING).phase(Phase.COMPLETED);assertEquals(t,ProjectUpdateTrail.decode(t.bytes()));assertThrows(IllegalArgumentException.class,()->t.phase(Phase.COMPLETED));assertThrows(IllegalArgumentException.class,()->t.phase(Phase.ROLLING_BACK));
    }
    @Test void rollbackBeforeAndAfterFileReplacementIsStrictAndReverseOrdered() throws Exception {
        var t=ProjectUpdateTrail.start(digest,2).phase(Phase.UPDATING).file(0,FileState.REPLACING).phase(Phase.ROLLING_BACK);
        var captured=t;assertThrows(IllegalArgumentException.class,()->captured.file(0,FileState.RESTORING));
        t=t.file(1,FileState.RESTORING).file(1,FileState.RESTORED).file(0,FileState.RESTORING).file(0,FileState.RESTORED).phase(Phase.ROLLED_BACK);assertEquals(t,ProjectUpdateTrail.decode(t.bytes()));
    }
    @Test void declaredFinalPhaseCannotHideSkippedTransitionsOrUnfinishedFiles() throws Exception {
        for(var phase:List.of(Phase.COMPLETED,Phase.PUBLISHED,Phase.COMMITTING,Phase.FILES_DONE,Phase.ROLLED_BACK))assertThrows(IllegalArgumentException.class,()->ProjectUpdateTrail.start(digest,1).phase(phase));
        assertThrows(IllegalArgumentException.class,()->ProjectUpdateTrail.start(digest,1).phase(Phase.UPDATING).phase(Phase.FILES_DONE));
        assertThrows(IllegalArgumentException.class,()->ProjectUpdateTrail.start(digest,1).phase(Phase.ROLLING_BACK).phase(Phase.ROLLED_BACK));
        var forged=ProjectPublicationHistory.encode(MAGIC,List.of(digest,"1","P:PREPARED","P:COMPLETED"));assertThrows(java.io.IOException.class,()->ProjectUpdateTrail.decode(forged));
    }
    @Test void replacingMustBeRecordedAndPublicationForbidsRollback() {
        assertThrows(IllegalArgumentException.class,()->ProjectUpdateTrail.start(digest,2).phase(Phase.UPDATING).file(0,FileState.UPDATED));
        assertThrows(IllegalArgumentException.class,()->ProjectUpdateTrail.start(digest,2).phase(Phase.UPDATING).file(1,FileState.REPLACING));
        assertThrows(IllegalArgumentException.class,()->updated().phase(Phase.PUBLISHED).phase(Phase.ROLLING_BACK));
        assertThrows(IllegalArgumentException.class,()->updated().phase(Phase.PUBLISHED).file(0,FileState.RESTORING));
    }
    @Test void malformedReorderedTruncatedExtraAndExcessiveEvidenceRefusesDecode() {
        var valid=updated().bytes();assertThrows(java.io.IOException.class,()->ProjectUpdateTrail.decode(Arrays.copyOf(valid,valid.length-1)));assertThrows(java.io.IOException.class,()->ProjectUpdateTrail.decode(Arrays.copyOf(valid,valid.length+1)));
        for(var events:List.of(List.of("P:PREPARED","F:0:UPDATED"),List.of("P:PREPARED","P:UPDATING","F:00:REPLACING"),List.of("P:PREPARED","P:UPDATING","unknown"))) {
            var fields=new ArrayList<String>(List.of(digest,"1"));fields.addAll(events);assertThrows(java.io.IOException.class,()->ProjectUpdateTrail.decode(ProjectPublicationHistory.encode(MAGIC,fields)));
        }
        assertThrows(IllegalArgumentException.class,()->ProjectUpdateTrail.start(digest,MAX_FILES+1));assertThrows(java.io.IOException.class,()->ProjectUpdateTrail.decode(new byte[ProjectPublicationHistory.MAX_EDGE_BYTES+1]));
    }
}
