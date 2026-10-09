package io.kcg.sir.application.internal.projectupdate;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import static io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.*;
import static io.kcg.sir.application.internal.projectupdate.ProjectUpdateTrail.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.change.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Internal UPDATE transaction only. The explicit public execution facade holds the existing state lock. */
public final class ProjectUpdateTransaction {
    public static final String ACTIVE="project-transactions";
    public static final long MAX_STAGE_BYTES=64L*1024*1024;
    private final SecureFileAccess state,output;
    private final Consumer<String> checkpoint;
    private final String id;
    @FunctionalInterface interface ReplayCompiler {ProjectBaselineVerification.Verified compile(io.kcg.sir.source.SourceSnapshot sources,Path output,List<ExecutionDiagnostic> diagnostics) throws IOException;}
    private final ReplayCompiler replayCompiler;
    private ProjectUpdateBinding binding;
    private boolean started;
    public ProjectUpdateTransaction(SecureFileAccess state,SecureFileAccess output,String id,Consumer<String> checkpoint) {this(state,output,id,checkpoint,ProjectBaselineVerification::compile);}
    ProjectUpdateTransaction(SecureFileAccess state,SecureFileAccess output,String id,Consumer<String> checkpoint,ReplayCompiler replayCompiler) {hex(id);this.state=state;this.output=output;this.id=id;this.checkpoint=checkpoint;this.replayCompiler=Objects.requireNonNull(replayCompiler);}
    public boolean started() {return started;}
    public Optional<ProjectUpdateBinding> binding() {return Optional.ofNullable(binding);}
    private String prefix() {return ACTIVE+"/"+id;}
    public void apply(ProjectBaselineVerification.Verified base,ProjectBaselineVerification.Verified candidate,ProjectChangePlanningResult.Planned planned) throws IOException {
        var b=base.bundle();var c=candidate.bundle();var store=new ProjectBaselineStore(state);
        require(state.root().equals(planned.context().stateRoot()) && output.root().equals(b.descriptor().outputRoot()) && output.root().equals(c.descriptor().outputRoot()),"transaction roots differ from complete planning evidence");
        store.guardState(b.baselineId(),false);store.verifyPointerIdentity(b.baselineId(),false);require(store.current().equals(Optional.of(b.baselineId())),"CURRENT changed before transaction");
        require(!b.baselineId().equals(c.baselineId()),"UPDATE cannot publish identical baseline");
        var before=manifest(b);var after=manifest(c);require(before.keySet().equals(after.keySet()),"UPDATE transaction cannot create/delete managed paths");
        var bytes=generated(candidate);var changes=planned.plan().fileChanges();budget(!changes.isEmpty() && changes.size()<=ProjectUpdateTrail.MAX_FILES,"UPDATE count outside budget");
        var delta=before.keySet().stream().filter(p->before.get(p).byteCount()!=after.get(p).byteCount() || !before.get(p).sha256Hex().equals(after.get(p).sha256Hex())).toList();
        require(delta.equals(changes.stream().map(FileChange::relativePath).toList()),"plan is not the exact whole-manifest UPDATE delta");
        long stageBytes=0,baseBytes=0;int bindingEstimate=18000;var preflightKeys=new TreeMap<String,String>();var parentKeys=new TreeMap<String,String>();
        for(var f:changes) {
            var old=before.get(f.relativePath());var fresh=after.get(f.relativePath());
            require(f.baseByteCount()==old.byteCount() && f.baseSha256Hex().equals(old.sha256Hex()) && f.candidateByteCount()==fresh.byteCount() && f.candidateSha256Hex().equals(fresh.sha256Hex()),"plan UPDATE bytes differ from compiled Bundle");
            String key=output.identity(f.relativePath()).toString();String parentKey=output.directoryIdentity(parent(f.relativePath())).toString();preflightKeys.put(f.relativePath(),key);parentKeys.put(f.relativePath(),parentKey);
            check(output,f.relativePath(),key,old.byteCount(),old.sha256Hex());baseBytes+=old.byteCount();stageBytes+=fresh.byteCount();bindingEstimate+=f.relativePath().getBytes(StandardCharsets.UTF_8).length+3000;
        }
        budget(stageBytes<=MAX_STAGE_BYTES && bindingEstimate<=65536,"staging/binding byte budget exceeded before preparation");
        ProjectBaselineVerification.verifyOutput(b);require(Files.getFileStore(state.root()).equals(Files.getFileStore(output.root())),"cross-FileStore transaction refused");
        var history=state.exists(ProjectPublicationHistory.DIRECTORY)?ProjectPublicationHistory.validate(state,b.baselineId()):new ProjectPublicationHistory.View(b.baselineId(),Set.of(b.baselineId()),Set.of(),b.descriptorBytes().length+b.payloadBytes().length+b.graphBytes().length+65,Set.of(),0);
        var edge=ProjectPublicationHistory.Edge.of(history.origin(),b,c,planned.context().contextId(),planned.sha256Hex(),planned.plan().operation().target().declarationSymbol().value());
        budget(history.receiptCount()<ProjectUpdateReceipt.MAX_RECEIPTS,"terminal receipt budget exhausted");
        budget(history.baselineIds().contains(c.baselineId()) || history.baselineIds().size()<ProjectPublicationHistory.MAX_BASELINES,"baseline history count exhausted");
        budget(history.edgeIds().contains(edge.id()) || history.edgeIds().size()<ProjectPublicationHistory.MAX_EDGES,"publication history count exhausted");
        long added=history.baselineIds().contains(c.baselineId())?0:c.descriptorBytes().length+c.payloadBytes().length+c.graphBytes().length+65;
        budget(history.byteCount()+2*added+2L*edge.bytes().length+2L*c.payloadBytes().length+baseBytes+stageBytes+4L*65536<=ProjectPublicationHistory.MAX_HISTORY_BYTES,"history/receipt byte budget exhausted before writes");
        require(!state.exists(ProjectUpdateReceipt.DIRECTORY+"/"+id),"transaction ID already has a terminal receipt");
        if(state.exists(ACTIVE))require(state.list(ACTIVE,1).isEmpty(),"active project transaction blocks apply");
        checkpoint.accept("before-prepare");store.guardState(b.baselineId(),false);store.verifyPointerIdentity(b.baselineId(),false);ProjectBaselineVerification.verifyOutput(b);state.assertRootUnchanged();output.assertRootUnchanged();
        for(var f:changes) {require(output.directoryIdentity(parent(f.relativePath())).toString().equals(parentKeys.get(f.relativePath())),"output parent changed after preflight");check(output,f.relativePath(),preflightKeys.get(f.relativePath()),f.baseByteCount(),f.baseSha256Hex());}
        started=true;
        if(!state.exists(ACTIVE))state.createDirectory(ACTIVE);state.createDirectory(prefix());checkpoint.accept("transaction-directory");
        var owned=new ArrayList<Owned>();directory(prefix()+"/pins",Lifetime.RECEIPT,owned);directory(prefix()+"/staging",Lifetime.TRANSACTION,owned);directory(prefix()+"/backups",Lifetime.TRANSACTION,owned);
        if(!state.exists(ProjectPublicationHistory.DIRECTORY))directory(ProjectPublicationHistory.DIRECTORY,Lifetime.STATE,owned);
        if(!state.exists(ProjectUpdateReceipt.DIRECTORY))directory(ProjectUpdateReceipt.DIRECTORY,Lifetime.STATE,owned);
        if(!state.exists(ProjectPublicationHistory.DIRECTORY+"/origin"))write(ProjectPublicationHistory.DIRECTORY+"/origin",(history.origin()+"\n").getBytes(StandardCharsets.US_ASCII),Lifetime.STATE,owned);
        if(!state.exists("baselines/"+c.baselineId())) {
            String dir="baselines/"+c.baselineId();directory(dir,Lifetime.CANDIDATE,owned);
            write(dir+"/"+ProjectBaselineStore.SOURCES,c.payloadBytes(),Lifetime.CANDIDATE,owned);write(dir+"/"+ProjectBaselineStore.GRAPH,c.graphBytes(),Lifetime.CANDIDATE,owned);
            write(dir+"/"+ProjectBaselineStore.DESCRIPTOR,c.descriptorBytes(),Lifetime.CANDIDATE,owned);write(dir+"/"+ProjectBaselineStore.POINTER,(c.baselineId()+"\n").getBytes(StandardCharsets.US_ASCII),Lifetime.CANDIDATE,owned);
        }
        same(c,store.load(c.baselineId()));
        String relation=ProjectPublicationHistory.DIRECTORY+"/"+edge.id();
        if(!state.exists(relation))write(relation,edge.bytes(),Lifetime.CANDIDATE,owned);else require(Arrays.equals(state.read(relation,65536),edge.bytes()),"existing publication relation differs");
        write(prefix()+"/candidate.sources",c.payloadBytes(),Lifetime.RECEIPT,owned);
        var rows=new ArrayList<Update>();
        for(int i=0;i<changes.size();i++) {
            var f=changes.get(i);String path=f.relativePath();Object key=output.identity(path);require(key.toString().equals(preflightKeys.get(path)) && output.directoryIdentity(parent(path)).toString().equals(parentKeys.get(path)),"output ownership changed during preparation");check(output,path,key.toString(),f.baseByteCount(),f.baseSha256Hex());
            output.linkOwned(path,key,state,prefix()+"/backups/"+i);owned.add(new Owned(prefix()+"/backups/"+i,Kind.FILE,key.toString(),Lifetime.TRANSACTION,f.baseByteCount(),f.baseSha256Hex()));pin(prefix()+"/backups/"+i,f.baseByteCount(),f.baseSha256Hex(),owned);checkpoint.accept("backup:"+i);
            write(prefix()+"/staging/"+i,bytes.get(path),Lifetime.TRANSACTION,owned);String fresh=state.identity(prefix()+"/staging/"+i).toString();
            rows.add(new Update(path,output.directoryIdentity(parent(path)).toString(),key.toString(),fresh,f.baseByteCount(),f.baseSha256Hex(),f.candidateByteCount(),f.candidateSha256Hex()));
        }
        state.writeNew(prefix()+"/journal.A",new byte[0],checkpoint);state.writeNew(prefix()+"/journal.B",new byte[0],checkpoint);
        for(String slot:List.of("journal.A","journal.B"))state.linkOwned(prefix()+"/"+slot,state.identity(prefix()+"/"+slot),state,prefix()+"/"+slot+".anchor");
        binding=new ProjectUpdateBinding(id,state.root(),output.root(),state.directoryIdentity("").toString(),output.directoryIdentity("").toString(),state.directoryIdentity(ACTIVE).toString(),state.directoryIdentity(prefix()).toString(),state.identity(prefix()+"/journal.A").toString(),state.identity(prefix()+"/journal.B").toString(),edge,rows,owned);
        byte[] encoded=binding.bytes();state.writeNew(prefix()+"/binding",encoded,checkpoint);state.linkOwned(prefix()+"/binding",state.identity(prefix()+"/binding"),state,prefix()+"/binding.anchor");checkpoint.accept("binding-complete");
        var journal=journal();var trail=ProjectUpdateTrail.start(binding.digest(),rows.size());journal.initialize(trail);trail=trail.phase(Phase.UPDATING);journal.store(trail);
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);verifyDirectories();check(output,row.relativePath(),row.baseKey(),row.baseSize(),row.baseSha());check(state,prefix()+"/backups/"+i,row.baseKey(),row.baseSize(),row.baseSha());check(state,prefix()+"/staging/"+i,row.candidateKey(),row.candidateSize(),row.candidateSha());
            trail=trail.file(i,FileState.REPLACING);journal.store(trail);checkpoint.accept("replace-intent:"+i);
            check(output,row.relativePath(),row.baseKey(),row.baseSize(),row.baseSha());state.moveOwned(prefix()+"/staging/"+i,state.identity(prefix()+"/staging/"+i),output,row.relativePath(),output.identity(row.relativePath()),checkpoint);checkpoint.accept("output-replaced:"+i);
            check(output,row.relativePath(),row.candidateKey(),row.candidateSize(),row.candidateSha());trail=trail.file(i,FileState.UPDATED);journal.store(trail);
        }
        trail=trail.phase(Phase.FILES_DONE);journal.store(trail);ProjectBaselineVerification.verifyOutput(c);same(c,store.load(c.baselineId()));verifyDirectories();
        trail=trail.phase(Phase.COMMITTING);journal.store(trail);checkpoint.accept("before-publication");
        require(store.current().equals(Optional.of(b.baselineId())),"CURRENT changed before publication");store.verifyPointerIdentity(b.baselineId(),false);
        require(!state.exists("CURRENT.new"),"external pending pointer blocks publication");Object anchor=state.identity(ProjectBaselineStore.anchor(c.baselineId()));
        state.linkOwned(ProjectBaselineStore.anchor(c.baselineId()),anchor,state,"CURRENT.new");checkpoint.accept("current-pending");
        ProjectBaselineVerification.verifyOutput(c);same(c,store.load(c.baselineId()));verifyDirectories();preflightCleanup(true);store.verifyPointerIdentity(b.baselineId(),false);
        require(state.identity("CURRENT.new").equals(anchor),"candidate pointer was replaced");state.moveOwned("CURRENT.new",anchor,state,"CURRENT",state.identity("CURRENT"),point->{
            checkpoint.accept(point);
            if(point.startsWith("before-move:"))try {verifyDirectories();preflightCleanup(true);ProjectBaselineVerification.verifyOutput(c);same(c,store.load(c.baselineId()));store.verifyPointerIdentity(b.baselineId(),false);}catch(IOException e) {throw new java.io.UncheckedIOException(e);}
        });checkpoint.accept("current-published");
        trail=trail.phase(Phase.PUBLISHED);journal.store(trail);finish(true,journal,trail);
    }
    /** Explicit recovery, never invoked by read/plan. Requires complete anchored binding before output changes. */
    public boolean recover(Optional<String> expectedDigest) throws IOException {
        if(!state.exists(prefix())) {
            var receipt=ProjectUpdateReceipt.read(state,id);binding=receipt.binding();
            if(expectedDigest.isPresent())require(expectedDigest.get().equals(binding.digest()),"terminal recovery handle differs");
            require(output.root().equals(binding.outputRoot()) && output.directoryIdentity("").toString().equals(binding.outputKey()),"terminal output root differs");
            boolean published=receipt.trail().state().phase()==Phase.COMPLETED;String current=published?binding.publication().b1():binding.publication().b0();
            var store=new ProjectBaselineStore(state);require(store.current().equals(Optional.of(current)),"terminal handle is stale after a later update");store.guardState(current,false);store.verifyPointerIdentity(current,false);
            var saved=store.load(current);var replay=replayCompiler.compile(saved.sources(),output.root(),new ArrayList<>());require(replay!=null,"terminal CURRENT sources do not compile");same(saved,replay.bundle());ProjectBaselineVerification.verifyOutput(saved);return published;
        }
        binding=loadBinding(state,id);started=true;
        if(expectedDigest.isPresent())require(expectedDigest.get().equals(binding.digest()),"recovery handle differs from complete binding");
        require(output.root().equals(binding.outputRoot()),"recovery bound to another output root");verifyDirectories();verifyRecoveryPlan();
        var store=new ProjectBaselineStore(state);String current=store.current().orElseThrow(()->problem("STATE-001","CURRENT missing; direction cannot be inferred"));
        var p=binding.publication();require(current.equals(p.b0()) || current.equals(p.b1()),"CURRENT is neither B0 nor B1");store.verifyPointerIdentity(current,false);
        var journal=journal();
        if(!state.exists(prefix()+"/journal")) {
            require(current.equals(p.b0()) && !state.exists(prefix()+"/journal.new"),"missing selected journal is not an initial preparation");
            for(int i=0;i<binding.updates().size();i++) {var row=binding.updates().get(i);check(output,row.relativePath(),row.baseKey(),row.baseSize(),row.baseSha());check(state,prefix()+"/backups/"+i,row.baseKey(),row.baseSize(),row.baseSha());check(state,prefix()+"/staging/"+i,row.candidateKey(),row.candidateSize(),row.candidateSha());}
            journal.initialize(ProjectUpdateTrail.start(binding.digest(),binding.updates().size()));
        }
        var trail=journal.read();preflightRecovery(current.equals(p.b1()),trail);journal.discardPending();
        if(state.exists("CURRENT.new")) {
            Object key=state.identity("CURRENT.new");require(key.equals(state.identity(ProjectBaselineStore.anchor(p.b1()))) && ProjectBaselineStore.pointer(state.read("CURRENT.new",65)).equals(p.b1()),"external pending CURRENT retained");state.deleteOwned("CURRENT.new",key);
        }
        if(current.equals(p.b1())) {
            require(EnumSet.of(Phase.COMMITTING,Phase.PUBLISHED,Phase.CLEANING,Phase.COMPLETED).contains(trail.state().phase()),"CURRENT B1 contradicts journal progress");
            ProjectBaselineVerification.verifyOutput(store.load(p.b1()));
            if(trail.state().phase()==Phase.COMMITTING) {trail=trail.phase(Phase.PUBLISHED);journal.store(trail);}finish(true,journal,trail);return true;
        }
        require(!EnumSet.of(Phase.PUBLISHED,Phase.CLEANING,Phase.COMPLETED).contains(trail.state().phase()),"journal claims publication while CURRENT is B0");
        if(trail.state().phase()!=Phase.ROLLING_BACK && trail.state().phase()!=Phase.ROLLED_BACK) {trail=trail.phase(Phase.ROLLING_BACK);journal.store(trail);}
        if(trail.state().phase()!=Phase.ROLLED_BACK)for(int i=binding.updates().size()-1;i>=0;i--) {
            var row=binding.updates().get(i);verifyDirectories();var status=trail.state().files().get(i);if(status==FileState.RESTORED) {check(output,row.relativePath(),row.baseKey(),row.baseSize(),row.baseSha());continue;}
            if(status!=FileState.RESTORING) {trail=trail.file(i,FileState.RESTORING);journal.store(trail);}checkpoint.accept("restore-intent:"+i);
            String actual=output.identity(row.relativePath()).toString();
            if(actual.equals(row.candidateKey())) {
                check(output,row.relativePath(),row.candidateKey(),row.candidateSize(),row.candidateSha());check(state,prefix()+"/backups/"+i,row.baseKey(),row.baseSize(),row.baseSha());
                state.moveOwned(prefix()+"/backups/"+i,state.identity(prefix()+"/backups/"+i),output,row.relativePath(),output.identity(row.relativePath()),checkpoint);checkpoint.accept("output-restored:"+i);
            } else require(actual.equals(row.baseKey()),"output is an external replacement; rollback refused");
            check(output,row.relativePath(),row.baseKey(),row.baseSize(),row.baseSha());trail=trail.file(i,FileState.RESTORED);journal.store(trail);
        }
        ProjectBaselineVerification.verifyOutput(store.load(p.b0()));if(trail.state().phase()!=Phase.ROLLED_BACK) {trail=trail.phase(Phase.ROLLED_BACK);journal.store(trail);}finish(false,journal,trail);return false;
    }
    private void verifyRecoveryPlan() throws IOException {
        var store=new ProjectBaselineStore(state);var p=binding.publication();var b=store.load(p.b0());var diagnostics=new ArrayList<ExecutionDiagnostic>();
        var payload=binding.owned().stream().filter(o->o.relativePath().equals(prefix()+"/candidate.sources") && o.lifetime()==Lifetime.RECEIPT).findFirst().orElseThrow(()->problem("STATE-001","candidate replay evidence missing"));
        check(state,payload.relativePath(),payload.key(),payload.size(),payload.sha());var sources=decodeSources(state.read(payload.relativePath(),MAX_PAYLOAD));
        var base=replayCompiler.compile(b.sources(),output.root(),diagnostics);var candidate=replayCompiler.compile(sources,output.root(),diagnostics);require(base!=null && candidate!=null,"recovery sources do not recompile");same(b,base.bundle());require(candidate.bundle().baselineId().equals(p.b1()),"replayed candidate baseline ID differs");
        var context=LockedProjectPlanning.describe(state.root(),base,candidate);require(context.contextId().equals(p.contextDigest()),"recovery context differs from full source replay");
        var target=context.targets().stream().filter(t->t.target().declarationSymbol().value().equals(p.subject())).findFirst().orElseThrow(()->problem("STATE-001","recovery workflow target missing"));
        var analysis=new ProjectChangePlanner().plan(LockedProjectPlanning.input(context,base,candidate,target.target()));require(analysis instanceof ProjectChangeAnalysis.Planned,"recovery is not an independently valid UPDATE plan");
        var plan=((ProjectChangeAnalysis.Planned)analysis).plan();require(new ProjectChangePlanningResult.Planned(context,plan,List.of()).sha256Hex().equals(p.planDigest()),"recovery complete plan differs");
        require(plan.fileChanges().stream().map(FileChange::relativePath).toList().equals(binding.updates().stream().map(Update::relativePath).toList()),"recovery UPDATE delta is incomplete");
        for(int i=0;i<binding.updates().size();i++) {var f=plan.fileChanges().get(i);var u=binding.updates().get(i);require(f.baseByteCount()==u.baseSize() && f.baseSha256Hex().equals(u.baseSha()) && f.candidateByteCount()==u.candidateSize() && f.candidateSha256Hex().equals(u.candidateSha()),"recovery UPDATE byte evidence differs");}
    }
    private void preflightRecovery(boolean published,ProjectUpdateTrail trail) throws IOException {
        // Only a verified ROLLED_BACK record permits candidate materials already removed by cleanup.
        preflightCleanup(published || trail.state().phase()!=Phase.ROLLED_BACK);
        for(int i=0;i<binding.updates().size();i++) {
            var row=binding.updates().get(i);String actual=output.identity(row.relativePath()).toString();var status=trail.state().files().get(i);
            if(published || status==FileState.RESTORED)require(actual.equals(published?row.candidateKey():row.baseKey()),"recovery file contradicts committed/restored progress");
            require(actual.equals(row.baseKey()) || actual.equals(row.candidateKey()),"external output replacement retained before recovery writes");
            if(actual.equals(row.candidateKey()))check(output,row.relativePath(),row.candidateKey(),row.candidateSize(),row.candidateSha());else check(output,row.relativePath(),row.baseKey(),row.baseSize(),row.baseSha());
            if(!published && (actual.equals(row.candidateKey()) || (status!=FileState.RESTORING && status!=FileState.RESTORED)))check(state,prefix()+"/backups/"+i,row.baseKey(),row.baseSize(),row.baseSha());
        }
    }
    private void finish(boolean published,ProjectJournalSlots journal,ProjectUpdateTrail trail) throws IOException {
        if(published && trail.state().phase()==Phase.PUBLISHED) {trail=trail.phase(Phase.CLEANING);journal.store(trail);}verifyDirectories();preflightCleanup(published);checkpoint.accept("before-cleanup");
        var remove=binding.owned().stream().filter(o->o.lifetime()==Lifetime.TRANSACTION || (!published && o.lifetime()==Lifetime.CANDIDATE))
            .sorted(Comparator.comparingInt((Owned o)->o.relativePath().split("/").length).reversed().thenComparing(o->o.kind()==Kind.FILE?0:1)).toList();
        for(var o:remove)if(state.exists(o.relativePath())) {
            verifyDirectories();Object key=o.kind()==Kind.FILE?state.identity(o.relativePath()):state.directoryIdentity(o.relativePath());require(key.toString().equals(o.key()),"cleanup ownership changed");
            if(o.kind()==Kind.FILE)state.deleteOwned(o.relativePath(),key);else state.deleteOwnedDirectory(o.relativePath(),key);checkpoint.accept("cleaned:"+o.relativePath());
        }
        if(published && trail.state().phase()!=Phase.COMPLETED) {trail=trail.phase(Phase.COMPLETED);journal.store(trail);}require(state.list(prefix(),12).equals(ProjectUpdateReceipt.MEMBERS),"unknown terminal transaction members");
        checkpoint.accept("before-receipt-relocation");state.moveOwnedDirectory(prefix(),state.directoryIdentity(prefix()),ProjectUpdateReceipt.DIRECTORY+"/"+id,checkpoint);checkpoint.accept("receipt-relocated");
        var receipt=ProjectUpdateReceipt.read(state,id);require(receipt.binding().digest().equals(binding.digest()),"relocated terminal evidence differs");
        var store=new ProjectBaselineStore(state);String current=published?binding.publication().b1():binding.publication().b0();store.guardState(current,false);store.verifyPointerIdentity(current,false);ProjectBaselineVerification.verifyOutput(store.load(current));checkpoint.accept("finished");
    }
    private void preflightCleanup(boolean published) throws IOException {
        verifyDirectories();require(Set.of("LOCK","baselines","CURRENT","CURRENT.new",ProjectPublicationHistory.DIRECTORY,ACTIVE).containsAll(state.list("")),"unknown state object retained");var expected=new HashSet<String>(List.of("binding","binding.anchor","journal.A","journal.B","journal.A.anchor","journal.B.anchor","journal","journal.new","staging","backups","candidate.sources","pins"));
        require(expected.containsAll(state.list(prefix(),12)),"unknown transaction object retained");
        for(String dir:List.of("staging","backups"))if(state.exists(prefix()+"/"+dir)) {
            var allowed=new HashSet<String>();for(int i=0;i<binding.updates().size();i++)allowed.add(Integer.toString(i));require(allowed.containsAll(state.list(prefix()+"/"+dir,MAX_FILES)),"unknown staging/backup member retained");
        }
        var pins=binding.owned().stream().filter(o->o.kind()==Kind.FILE && o.relativePath().startsWith(prefix()+"/pins/")).map(o->o.relativePath().substring((prefix()+"/pins/").length())).collect(java.util.stream.Collectors.toSet());require(state.list(prefix()+"/pins",256).equals(pins),"unknown/missing retained physical pin");
        for(var o:binding.owned()) {
            if(!state.exists(o.relativePath())) {require(!(o.lifetime()==Lifetime.STATE || o.lifetime()==Lifetime.RECEIPT || (published && o.lifetime()==Lifetime.CANDIDATE)),"retained/published evidence is missing");continue;}
            Object key=o.kind()==Kind.FILE?state.identity(o.relativePath()):state.directoryIdentity(o.relativePath());require(key.toString().equals(o.key()),"owned cleanup object was replaced");
            if(o.kind()==Kind.FILE)check(state,o.relativePath(),o.key(),o.size(),o.sha());
            if(o.kind()==Kind.DIRECTORY && o.lifetime()==Lifetime.CANDIDATE)require(ProjectBaselineStore.MEMBERS.containsAll(state.list(o.relativePath())),"unknown candidate Bundle member retained");
        }
    }
    private void verifyDirectories() throws IOException {
        require(state.directoryIdentity("").toString().equals(binding.stateKey()) && output.directoryIdentity("").toString().equals(binding.outputKey()),"transaction roots physically changed");
        require(state.directoryIdentity(ACTIVE).toString().equals(binding.transactionsKey()) && state.directoryIdentity(prefix()).toString().equals(binding.directoryKey()),"transaction directories physically changed");
        for(var row:binding.updates())require(output.directoryIdentity(parent(row.relativePath())).toString().equals(row.parentKey()),"output parent directory changed");
    }
    public static ProjectUpdateBinding loadBinding(SecureFileAccess state,String id) throws IOException {
        hex(id);String prefix=ACTIVE+"/"+id;require(state.list(ACTIVE,1).equals(Set.of(id)),"unknown/multiple active transaction directories");
        byte[] bytes=state.read(prefix+"/binding",65536);var result=ProjectUpdateBinding.decode(bytes);require(result.transactionId().equals(id) && result.stateRoot().equals(state.root()),"transaction path/state binding mismatch");
        require(state.identity(prefix+"/binding").equals(state.identity(prefix+"/binding.anchor")),"transaction binding was externally replaced");return result;
    }
    private ProjectJournalSlots journal() {return new ProjectJournalSlots(state,prefix(),binding.digest(),binding.slotAKey(),binding.slotBKey(),checkpoint);}
    private void directory(String path,Lifetime lifetime,List<Owned> owned) throws IOException {state.createDirectory(path);owned.add(new Owned(path,Kind.DIRECTORY,state.directoryIdentity(path).toString(),lifetime,0,""));checkpoint.accept("directory:"+path);}
    private void write(String path,byte[] bytes,Lifetime lifetime,List<Owned> owned) throws IOException {state.writeNew(path,bytes,checkpoint);String sha=Sha256.hexDigest(bytes);owned.add(new Owned(path,Kind.FILE,state.identity(path).toString(),lifetime,bytes.length,sha));pin(path,bytes.length,sha,owned);}
    private void pin(String path,long size,String sha,List<Owned> owned) throws IOException {long index=owned.stream().filter(o->o.kind()==Kind.FILE && o.relativePath().startsWith(prefix()+"/pins/")).count();String pinned=prefix()+"/pins/"+index;Object key=state.identity(path);state.linkOwned(path,key,state,pinned);owned.add(new Owned(pinned,Kind.FILE,key.toString(),Lifetime.RECEIPT,size,sha));}
    private static String parent(String path) {int at=path.lastIndexOf('/');return at<0?"":path.substring(0,at);}
    private static void check(SecureFileAccess access,String path,String key,long size,String sha) throws IOException {require(access.identity(path).toString().equals(key),"file was externally replaced: "+path);byte[] bytes=access.read(path,(int)size);require(bytes.length==size && Sha256.hexDigest(bytes).equals(sha),"file byte evidence changed: "+path);}
    private static Map<String,io.kcg.sir.application.internal.bundle.BaselineManifestEntry> manifest(Bundle b) {var map=new TreeMap<String,io.kcg.sir.application.internal.bundle.BaselineManifestEntry>();b.descriptor().manifest().forEach(e->map.put(e.relativePath(),e));return map;}
    private static Map<String,byte[]> generated(ProjectBaselineVerification.Verified candidate) throws IOException {var map=new TreeMap<String,byte[]>();candidate.compilation().generatedFiles().forEach(f->map.put(f.relativePath(),f.content().getBytes(StandardCharsets.UTF_8)));for(var e:candidate.bundle().descriptor().manifest()) {var b=map.get(e.relativePath());require(b!=null && b.length==e.byteCount() && Sha256.hexDigest(b).equals(e.sha256Hex()),"candidate generated bytes do not match complete manifest");}return map;}
    private static void same(Bundle a,Bundle b) throws IOException {require(Arrays.equals(a.descriptorBytes(),b.descriptorBytes()) && Arrays.equals(a.payloadBytes(),b.payloadBytes()) && Arrays.equals(a.graphBytes(),b.graphBytes()),"stored Bundle differs from full byte replay");}
}
