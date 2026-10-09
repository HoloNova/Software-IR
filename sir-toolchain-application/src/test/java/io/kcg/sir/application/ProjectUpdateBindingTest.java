package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.*;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding;
import io.kcg.sir.application.internal.projectbaseline.ProjectPublicationHistory;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectUpdateBindingTest {
    @TempDir Path temp;
    final String id="a".repeat(64),sha="1".repeat(64),other="2".repeat(64);
    ProjectUpdateBinding binding() {
        var edge=new ProjectPublicationHistory.Edge(sha,sha,other,temp.resolve("output"),sha,other,sha,other,sha,other,sha,other,"sir://course/capability/search");
        String prefix="project-transactions/"+id;
        var owned=List.of(new Owned(prefix+"/backups/0",Kind.FILE,"base-key",Lifetime.TRANSACTION,4,sha),new Owned(prefix+"/staging/0",Kind.FILE,"candidate-key",Lifetime.TRANSACTION,9,other),new Owned(prefix+"/pins",Kind.DIRECTORY,"pins",Lifetime.RECEIPT,0,""),new Owned(prefix+"/pins/0",Kind.FILE,"base-key",Lifetime.RECEIPT,4,sha),new Owned(prefix+"/pins/1",Kind.FILE,"candidate-key",Lifetime.RECEIPT,9,other));
        return new ProjectUpdateBinding(id,temp,temp.resolve("output"),"state-key","output-key","transactions-key","directory-key","A-key","B-key",edge,List.of(new Update("src/file kind=UPDATE state=x.java","parent-key","base-key","candidate-key",4,sha,9,other)),owned);
    }
    @Test void framedEvidenceIsCanonicalAndPreservesDelimiterPaths() throws Exception {
        var b=binding();assertEquals(b,ProjectUpdateBinding.decode(b.bytes()));assertEquals(b.digest(),ProjectUpdateBinding.decode(b.bytes()).digest());assertEquals("src/file kind=UPDATE state=x.java",b.updates().getFirst().relativePath());
    }
    @Test void truncatedTrailingOversizedAndForeignMagicCannotBeAccepted() {
        var bytes=binding().bytes();for(var bad:List.of(Arrays.copyOf(bytes,bytes.length-1),Arrays.copyOf(bytes,bytes.length+1),new byte[65537]))assertThrows(java.io.IOException.class,()->ProjectUpdateBinding.decode(bad));
    }
    @Test void ownershipCannotAuthorizeOtherTransactionsBaselinesOrJournalDeletion() {
        var b=binding();for(String path:List.of("project-transactions/"+"b".repeat(64)+"/staging/0",b.prefix()+"/journal",b.prefix()+"/binding","CURRENT","../user"))assertThrows(IllegalArgumentException.class,()->new ProjectUpdateBinding(b.transactionId(),b.stateRoot(),b.outputRoot(),b.stateKey(),b.outputKey(),b.transactionsKey(),b.directoryKey(),b.slotAKey(),b.slotBKey(),b.publication(),b.updates(),List.of(new Owned(path,Kind.FILE,"base-key",Lifetime.TRANSACTION,4,sha))));
        assertThrows(IllegalArgumentException.class,()->new ProjectUpdateBinding(b.transactionId(),b.stateRoot(),b.outputRoot(),b.stateKey(),b.outputKey(),b.transactionsKey(),b.directoryKey(),b.slotAKey(),b.slotBKey(),b.publication(),b.updates(),List.of()));
    }
    @Test void evenRehashedOwnedRecordsCannotClaimHistoricReceiptOrUnknownCandidateMembers() {
        var b=binding();for(String path:List.of("project-history/receipts/"+id+"/binding","project-history/origin","baselines/"+other+"/user.txt")) {
            var owned=new ArrayList<>(b.owned());owned.add(new Owned(path,Kind.FILE,"foreign",Lifetime.CANDIDATE,4,sha));
            assertThrows(IllegalArgumentException.class,()->new ProjectUpdateBinding(b.transactionId(),b.stateRoot(),b.outputRoot(),b.stateKey(),b.outputKey(),b.transactionsKey(),b.directoryKey(),b.slotAKey(),b.slotBKey(),b.publication(),b.updates(),owned));
        }
        var owned=new ArrayList<>(b.owned());owned.add(new Owned(b.prefix()+"/staging/99",Kind.FILE,"foreign",Lifetime.TRANSACTION,4,sha));assertThrows(IllegalArgumentException.class,()->new ProjectUpdateBinding(b.transactionId(),b.stateRoot(),b.outputRoot(),b.stateKey(),b.outputKey(),b.transactionsKey(),b.directoryKey(),b.slotAKey(),b.slotBKey(),b.publication(),b.updates(),owned));
    }
    @Test void missingBackupIdentityOrAliasesCannotPassARehashedBinding() {
        var b=binding();var row=b.updates().getFirst();assertThrows(IllegalArgumentException.class,()->new Update(row.relativePath(),row.parentKey(),row.baseKey(),row.baseKey(),4,sha,9,other));
        var owned=new ArrayList<>(b.owned());owned.set(0,new Owned(b.prefix()+"/backups/0",Kind.FILE,"wrong",Lifetime.TRANSACTION,4,sha));
        assertThrows(IllegalArgumentException.class,()->new ProjectUpdateBinding(b.transactionId(),b.stateRoot(),b.outputRoot(),b.stateKey(),b.outputKey(),b.transactionsKey(),b.directoryKey(),b.slotAKey(),b.slotBKey(),b.publication(),b.updates(),owned));
        assertThrows(IllegalArgumentException.class,()->new Update("safe\ud800", "parent","base","candidate",4,sha,9,other));
    }
}
