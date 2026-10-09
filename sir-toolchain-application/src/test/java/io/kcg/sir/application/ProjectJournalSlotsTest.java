package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.internal.projectupdate.ProjectUpdateTrail.*;
import io.kcg.sir.application.internal.projectupdate.*;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectJournalSlotsTest {
    @TempDir Path temp;
    final String prefix="project-transactions/"+"a".repeat(64),digest="1".repeat(64);
    String keyA,keyB;
    void prepare() throws Exception {Files.createDirectories(temp.resolve(prefix));Files.write(temp.resolve(prefix+"/journal.A"),new byte[0]);Files.write(temp.resolve(prefix+"/journal.B"),new byte[0]);for(String slot:List.of("journal.A","journal.B"))Files.createLink(temp.resolve(prefix+"/"+slot+".anchor"),temp.resolve(prefix+"/"+slot));try(var files=new SecureFileAccess(temp)){keyA=files.identity(prefix+"/journal.A").toString();keyB=files.identity(prefix+"/journal.B").toString();}}
    ProjectJournalSlots slots(SecureFileAccess f,Consumer<String> hook) {return new ProjectJournalSlots(f,prefix,digest,keyA,keyB,hook);}
    @Test void alternatesPhysicalSlotsWithoutAllowingAnUnrelatedTrail() throws Exception {
        prepare();try(var f=new SecureFileAccess(temp)) {var j=slots(f,x->{});var t=ProjectUpdateTrail.start(digest,1);j.initialize(t);assertEquals(t,j.read());
            t=t.phase(Phase.UPDATING);j.store(t);assertEquals(keyB,f.identity(prefix+"/journal").toString());t=t.file(0,FileState.REPLACING);j.store(t);assertEquals(keyA,f.identity(prefix+"/journal").toString());assertEquals(t,j.read());
            var old=t;assertThrows(java.io.IOException.class,()->j.store(ProjectUpdateTrail.start(digest,1).phase(Phase.UPDATING)));assertEquals(old,j.read());
        }
    }
    @Test void actualPartialInactiveRecordLeavesPriorCompleteTrailReadable() throws Exception {
        prepare();try(var f=new SecureFileAccess(temp)) {var j=slots(f,x->{});var t=ProjectUpdateTrail.start(digest,32);j.initialize(t);t=t.phase(Phase.UPDATING);j.store(t);
            for(int i=0;i<12;i++) {t=t.file(i,FileState.REPLACING);j.store(t);t=t.file(i,FileState.UPDATED);j.store(t);}
            var before=t;var next=t.file(12,FileState.REPLACING);assertTrue(next.bytes().length>512);
            var interrupted=slots(f,p->{if(p.startsWith("partial-owned:"))throw new IllegalStateException("simulated process stop");});
            assertThrows(IllegalStateException.class,()->interrupted.store(next));assertEquals(before,slots(f,x->{}).read());
            String inactive=f.identity(prefix+"/journal").toString().equals(keyA)?"journal.B":"journal.A";assertThrows(java.io.IOException.class,()->ProjectUpdateTrail.decode(f.read(prefix+"/"+inactive,65536)));
        }
    }
    @Test void pendingCompleteSelectorDoesNotPublishItselfDuringRead() throws Exception {
        prepare();try(var f=new SecureFileAccess(temp)) {var j=slots(f,x->{});var t=ProjectUpdateTrail.start(digest,1);j.initialize(t);var next=t.phase(Phase.UPDATING);
            assertThrows(IllegalStateException.class,()->slots(f,p->{if(p.equals("journal-pending"))throw new IllegalStateException("stop");}).store(next));
            assertEquals(t,slots(f,x->{}).read());assertTrue(f.exists(prefix+"/journal.new"));slots(f,x->{}).discardPending();assertFalse(f.exists(prefix+"/journal.new"));assertEquals(t,slots(f,x->{}).read());
            slots(f,x->{}).store(next);assertEquals(next,slots(f,x->{}).read());
        }
    }
    @Test void stopAfterAtomicSelectorMoveRetainsTheNewCompleteTrail() throws Exception {
        prepare();try(var f=new SecureFileAccess(temp)) {var j=slots(f,x->{});var t=ProjectUpdateTrail.start(digest,1);j.initialize(t);var next=t.phase(Phase.UPDATING);
            assertThrows(IllegalStateException.class,()->slots(f,p->{if(p.startsWith("moved:"))throw new IllegalStateException("stop");}).store(next));assertEquals(next,slots(f,x->{}).read());assertFalse(f.exists(prefix+"/journal.new"));
        }
    }
    @Test void sameByteExternalSlotOrSelectorCannotBeOverwrittenOrCleaned() throws Exception {
        prepare();try(var f=new SecureFileAccess(temp)) {var j=slots(f,x->{});var t=ProjectUpdateTrail.start(digest,1);j.initialize(t);byte[] bytes=Files.readAllBytes(temp.resolve(prefix+"/journal"));
            Files.move(temp.resolve(prefix+"/journal"),temp.resolve(prefix+"/external-retained"));Files.write(temp.resolve(prefix+"/journal"),bytes);assertThrows(java.io.IOException.class,j::read);assertThrows(java.io.IOException.class,()->j.store(t.phase(Phase.UPDATING)));assertArrayEquals(bytes,Files.readAllBytes(temp.resolve(prefix+"/journal")));
        }
    }
    @Test void activeSelectedTrailCorruptionIsRefusedNotRepairedFromAnOlderSlot() throws Exception {
        prepare();try(var f=new SecureFileAccess(temp)) {var j=slots(f,x->{});j.initialize(ProjectUpdateTrail.start(digest,1));j.store(j.read().phase(Phase.UPDATING));Files.writeString(temp.resolve(prefix+"/journal"),"corrupt");assertThrows(java.io.IOException.class,j::read);assertEquals("corrupt",Files.readString(temp.resolve(prefix+"/journal")));}
    }
}
