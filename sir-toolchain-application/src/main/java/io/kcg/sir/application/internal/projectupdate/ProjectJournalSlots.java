package io.kcg.sir.application.internal.projectupdate;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Two fixed physically bound slots preserve the last complete trail during a partial next write. */
public final class ProjectJournalSlots {
    private final SecureFileAccess files;
    private final String prefix,binding,keyA,keyB;
    private final Consumer<String> checkpoint;
    public ProjectJournalSlots(SecureFileAccess files,String prefix,String binding,String keyA,String keyB,Consumer<String> checkpoint) {
        if(!prefix.matches("project-transactions/[0-9a-f]{64}"))throw new IllegalArgumentException("invalid project transaction path");hex(binding);
        if(keyA==null || keyB==null || keyA.isBlank() || keyB.isBlank() || keyA.equals(keyB))throw new IllegalArgumentException("invalid slot identity binding");
        this.files=Objects.requireNonNull(files);this.prefix=prefix;this.binding=binding;this.keyA=keyA;this.keyB=keyB;this.checkpoint=Objects.requireNonNull(checkpoint);
    }
    private String name(String leaf) {return prefix+"/"+leaf;}
    private Object slot(String leaf,String expected) throws IOException {var key=files.identity(name(leaf));require(key.toString().equals(expected) && key.equals(files.identity(name(leaf+".anchor"))),"journal slot is an external replacement: "+leaf);return key;}
    public void initialize(ProjectUpdateTrail initial) throws IOException {
        require(initial.bindingDigest().equals(binding) && initial.events().equals(List.of("P:PREPARED")),"invalid initial journal");
        require(!files.exists(name("journal")) && !files.exists(name("journal.new")),"journal already present");
        Object a=slot("journal.A",keyA);slot("journal.B",keyB);files.overwriteOwned(name("journal.A"),a,initial.bytes(),checkpoint);
        files.linkOwned(name("journal.A"),a,files,name("journal"));checkpoint.accept("journal-initialized");require(read().equals(initial),"initialized journal differs");
    }
    public ProjectUpdateTrail read() throws IOException {
        var identity=files.identity(name("journal")).toString();require(identity.equals(keyA)||identity.equals(keyB),"journal selector has external physical identity");
        var trail=ProjectUpdateTrail.decode(files.read(name("journal"),65536));require(trail.bindingDigest().equals(binding),"journal belongs to another transaction binding");
        if(files.exists(name("journal.A")))slot("journal.A",keyA);
        else require(trail.state().phase()==ProjectUpdateTrail.Phase.COMPLETED || trail.state().phase()==ProjectUpdateTrail.Phase.ROLLED_BACK,"active journal anchor A missing");
        if(files.exists(name("journal.B")))slot("journal.B",keyB);
        else require(trail.state().phase()==ProjectUpdateTrail.Phase.COMPLETED || trail.state().phase()==ProjectUpdateTrail.Phase.ROLLED_BACK,"active journal anchor B missing");
        if(files.exists(name("journal.new"))) {
            var pending=files.identity(name("journal.new")).toString();require(pending.equals(keyA)||pending.equals(keyB),"pending journal is an external replacement");
            var next=ProjectUpdateTrail.decode(files.read(name("journal.new"),65536));require(next.bindingDigest().equals(binding) && next.fileCount()==trail.fileCount(),"pending journal binding mismatch");
            require(next.events().size()==trail.events().size()+1 && next.events().subList(0,trail.events().size()).equals(trail.events()),"pending journal is not the next complete transition");
        }
        return trail;
    }
    public void store(ProjectUpdateTrail next) throws IOException {
        var before=read();require(!files.exists(name("journal.new")),"pending journal requires explicit recovery");
        require(next.bindingDigest().equals(binding) && next.fileCount()==before.fileCount() && next.events().size()==before.events().size()+1
            && next.events().subList(0,before.events().size()).equals(before.events()),"journal write is not one legal next event");
        Object current=files.identity(name("journal"));boolean a=current.toString().equals(keyA);String target=a?"journal.B":"journal.A";Object inactive=slot(target,a?keyB:keyA);
        files.overwriteOwned(name(target),inactive,next.bytes(),checkpoint);checkpoint.accept("journal-slot-written");
        require(Arrays.equals(files.read(name(target),65536),next.bytes()),"new journal slot readback differs");
        files.linkOwned(name(target),inactive,files,name("journal.new"));checkpoint.accept("journal-pending");
        files.moveOwned(name("journal.new"),inactive,files,name("journal"),current,checkpoint);checkpoint.accept("journal-selected");
        require(read().equals(next),"journal transition not published exactly");
    }
    /** Only recovery removes the physically verified unpublished selector; no read-side cleanup. */
    public void discardPending() throws IOException {
        read();if(files.exists(name("journal.new"))) {Object key=files.identity(name("journal.new"));files.deleteOwned(name("journal.new"),key);}
    }
}
