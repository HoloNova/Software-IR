package io.kcg.sir.application.internal.projectupdate;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.application.MultiSourceTestSupport;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.change.api.*;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Explicit synthetic renderer variation tests the two-UPDATE engine, not a claimed Spring Boot product output. */
class ProjectUpdateFaultMatrixTest {
    @TempDir Path temp;
    final String tx="a".repeat(64);
    record Case(Path state,Path output,ProjectBaselineVerification.Verified base,ProjectBaselineVerification.Verified candidate,ProjectChangePlanningResult.Planned plan,ProjectUpdateTransaction.ReplayCompiler compiler) {}
    Case scenario(Path root) throws Exception {
        Files.createDirectories(root);Path source=MultiSourceTestSupport.fixture(root),output=root.resolve("output"),state=root.resolve("state");
        var generated=assertInstanceOf(ProjectToolchainResult.Success.class,new ToolchainApplication().executeProject(new ProjectToolchainRequest(source,MultiSourceTestSupport.ENTRY,output)));
        var saved=assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(),output,state)));
        var bytes=new LinkedHashMap<SourceId,byte[]>();saved.sources().manifest().files().forEach(e->bytes.put(e.sourceId(),saved.sources().bytes(e.sourceId())));
        String old=new String(bytes.get(MultiSourceTestSupport.ENTRY),StandardCharsets.UTF_8);bytes.put(MultiSourceTestSupport.ENTRY,old.replace("status == EnrollmentStatus.ACTIVE","(status == EnrollmentStatus.ACTIVE or status == EnrollmentStatus.CANCELLED)").getBytes(StandardCharsets.UTF_8));
        var candidate=new SourceSnapshot(MultiSourceTestSupport.ENTRY,bytes);String original=saved.sources().sha256Hex();
        ProjectUpdateTransaction.ReplayCompiler compiler=(sources,path,diagnostics)-> {
            var actual=ProjectBaselineVerification.compile(sources,path,diagnostics);if(actual==null || sources.sha256Hex().equals(original))return actual;
            var c=actual.compilation();var files=c.generatedFiles().stream().map(f->f.relativePath().endsWith("SearchCourseEnrollmentsController.java")?new GeneratedFile(f.relativePath(),f.content()+"\n// controlled synthetic two-file renderer\n",f.artifactId(),f.symbolId()):f).toList();
            var synthetic=new SirCompilation.CompilationSnapshot(c.semanticModel(),c.loweredModel(),files,c.diagnostics());
            var graph=assertInstanceOf(ProjectGraphAnalysis.Success.class,new ProjectGraphBuilder().build(new SpringBootProjectGraphInputFactory().build(c.semanticModel(),c.loweredModel(),files,sources))).graph();
            return new ProjectBaselineVerification.Verified(ProjectBaselineCodec.build(sources,synthetic,graph,path),synthetic);
        };
        var diagnostics=new ArrayList<ExecutionDiagnostic>();var base=compiler.compile(saved.sources(),output,diagnostics);var fresh=compiler.compile(candidate,output,diagnostics);var context=LockedProjectPlanning.describe(state,base,fresh);
        var target=context.targets().stream().filter(t->t.displayName().equals("SearchCourseEnrollments")).findFirst().orElseThrow();var analysis=assertInstanceOf(ProjectChangeAnalysis.Planned.class,new ProjectChangePlanner().plan(LockedProjectPlanning.input(context,base,fresh,target.target())));
        var plan=new ProjectChangePlanningResult.Planned(context,analysis.plan(),List.of());assertEquals(2,plan.plan().fileChanges().size());Files.move(source,root.resolve("unavailable"));
        return new Case(state,output,base,fresh,plan,compiler);
    }
    @Test void probeCheckpointManifestAndTwoFileSyntheticSourceReplay() throws Exception {
        var c=scenario(temp);var checkpoints=new ArrayList<String>();
        try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {new ProjectUpdateTransaction(s,o,tx,checkpoints::add,c.compiler).apply(c.base,c.candidate,c.plan);}
        for(var f:c.candidate.compilation().generatedFiles())assertEquals(f.content(),Files.readString(c.output.resolve(f.relativePath())));
        var counts=new TreeMap<String,Integer>();for(var p:checkpoints)counts.merge(p.replaceAll("[0-9a-f]{64}","<id>"),1,Integer::sum);
        assertEquals(150,checkpoints.size());assertEquals(64,counts.size());assertEquals(expectedManifest(),counts);
        assertTrue(checkpoints.contains("replace-intent:0"));assertTrue(checkpoints.contains("replace-intent:1"));assertTrue(checkpoints.contains("output-replaced:0"));assertTrue(checkpoints.contains("output-replaced:1"));
        try(var s=new SecureFileAccess(c.state)) {assertEquals(ProjectUpdateTrail.Phase.COMPLETED,ProjectUpdateReceipt.read(s,tx).trail().state().phase());}
    }
    @Test void everyFrozenApplyCheckpointReopensWithNoSourceRootAndRecoveryIsIdempotent() throws Exception {
        var seed=scenario(temp.resolve("trace"));var trace=new ArrayList<String>();
        try(var s=new SecureFileAccess(seed.state);var o=new SecureFileAccess(seed.output)) {new ProjectUpdateTransaction(s,o,tx,trace::add,seed.compiler).apply(seed.base,seed.candidate,seed.plan);}
        assertEquals(150,trace.size());int complete=trace.indexOf("binding-complete");assertTrue(complete>0);
        int retained=0,rolled=0,committed=0;
        for(int index=0;index<trace.size();index++) {
            var c=scenario(temp.resolve("case-"+index));int stop=index;var observed=new ArrayList<String>();
            try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {
                var transaction=new ProjectUpdateTransaction(s,o,tx,p->{observed.add(p);if(observed.size()-1==stop)throw new IllegalStateException("process-stop:"+stop);},c.compiler);
                assertThrows(IllegalStateException.class,()->transaction.apply(c.base,c.candidate,c.plan),"checkpoint "+index);assertEquals(normalize(trace.get(index)),normalize(observed.getLast()),"checkpoint order "+index);
            }
            boolean published=Files.readString(c.state.resolve("CURRENT")).trim().equals(c.candidate.bundle().baselineId());
            if(index<complete) {
                for(var f:c.base.compilation().generatedFiles())assertEquals(f.content(),Files.readString(c.output.resolve(f.relativePath())));
                if(index>0) {var before=MultiSourceTestSupport.tree(c.state);try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {assertThrows(java.io.IOException.class,()->new ProjectUpdateTransaction(s,o,tx,x->{},c.compiler).recover(Optional.empty()),"incomplete preparation "+index);}assertEquals(before,MultiSourceTestSupport.tree(c.state));}
                retained++;continue;
            }
            try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {assertEquals(published,new ProjectUpdateTransaction(s,o,tx,x->{},c.compiler).recover(Optional.empty()),"direction "+index);}
            var expected=published?c.candidate:c.base;for(var f:expected.compilation().generatedFiles())assertEquals(f.content(),Files.readString(c.output.resolve(f.relativePath())),"file "+index+" "+f.relativePath());
            var before=MultiSourceTestSupport.tree(c.state);
            try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {assertEquals(published,new ProjectUpdateTransaction(s,o,tx,x->{},c.compiler).recover(Optional.empty()));assertEquals(Set.of(),s.list(ProjectUpdateTransaction.ACTIVE,1));}
            assertEquals(before,MultiSourceTestSupport.tree(c.state),"repeat recovery changed disk "+index);
            if(published)committed++;else rolled++;
        }
        System.out.println("Q21-MATRIX apply=150 retained="+retained+" rolled="+rolled+" committed="+committed);
        assertEquals(150,retained+rolled+committed);assertEquals(64,retained);assertEquals(56,rolled);assertEquals(30,committed);
    }
    @Test void probeRecoveryCheckpointManifestInBothDirections() throws Exception {
        for(boolean published:List.of(false,true)) {
            var c=scenario(temp.resolve(published?"committed":"rollback"));stopApply(c,published?"current-published":"output-replaced:0");var trace=new ArrayList<String>();
            try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {assertEquals(published,new ProjectUpdateTransaction(s,o,tx,trace::add,c.compiler).recover(Optional.empty()));}
            assertRecoveryManifest(published,trace);
            var expected=published?c.candidate:c.base;for(var f:expected.compilation().generatedFiles())assertEquals(f.content(),Files.readString(c.output.resolve(f.relativePath())));
        }
    }
    @Test void everyFrozenRecoveryCheckpointCanBeInterruptedAgainWithoutLosingDirection() throws Exception {
        int total=0;
        for(boolean published:List.of(false,true)) {
            var seed=scenario(temp.resolve(published?"commit-trace":"rollback-trace"));stopApply(seed,published?"current-published":"output-replaced:0");var trace=new ArrayList<String>();
            try(var s=new SecureFileAccess(seed.state);var o=new SecureFileAccess(seed.output)) {new ProjectUpdateTransaction(s,o,tx,trace::add,seed.compiler).recover(Optional.empty());}
            assertRecoveryManifest(published,trace);
            for(int index=0;index<trace.size();index++) {
                var c=scenario(temp.resolve((published?"c-":"r-")+index));stopApply(c,published?"current-published":"output-replaced:0");int stop=index;var observed=new ArrayList<String>();
                try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {assertThrows(IllegalStateException.class,()->new ProjectUpdateTransaction(s,o,tx,p->{observed.add(p);if(observed.size()-1==stop)throw new IllegalStateException("recovery-stop:"+stop);},c.compiler).recover(Optional.empty()));}
                assertEquals(normalize(trace.get(index)),normalize(observed.getLast()),"recovery checkpoint order "+published+"/"+index);
                try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {assertEquals(published,new ProjectUpdateTransaction(s,o,tx,x->{},c.compiler).recover(Optional.empty()));}
                var expected=published?c.candidate:c.base;for(var f:expected.compilation().generatedFiles())assertEquals(f.content(),Files.readString(c.output.resolve(f.relativePath())));
                var before=MultiSourceTestSupport.tree(c.state);try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {assertEquals(published,new ProjectUpdateTransaction(s,o,tx,x->{},c.compiler).recover(Optional.empty()));}
                assertEquals(before,MultiSourceTestSupport.tree(c.state));total++;
            }
        }
        assertEquals(85,total);System.out.println("Q21-MATRIX recovery=85 rollback=57 committed-cleanup=28");
    }
    static void assertRecoveryManifest(boolean published,List<String> trace) {
        assertEquals(published?28:57,trace.size());String hash=io.kcg.sir.application.internal.Sha256.hexDigest(String.join("\n",trace.stream().map(ProjectUpdateFaultMatrixTest::normalize).toList()).getBytes(StandardCharsets.UTF_8));
        assertEquals(published?"e68e249f0bc875dda4412bd918fe58f244d5a05b2dff81355d3fe7653c50604b":"8720fa2962e90aa129f592723cf6b5c60e6c46acbf33edb8e17ae144d8f40169",hash);
    }
    void stopApply(Case c,String point) throws Exception {
        var hit=new java.util.concurrent.atomic.AtomicBoolean();try(var s=new SecureFileAccess(c.state);var o=new SecureFileAccess(c.output)) {assertThrows(IllegalStateException.class,()->new ProjectUpdateTransaction(s,o,tx,p->{if(p.equals(point)){hit.set(true);throw new IllegalStateException("stop");}},c.compiler).apply(c.base,c.candidate,c.plan));}assertTrue(hit.get(),point);
    }
    static String normalize(String s) {return s.replaceAll("[0-9a-f]{64}","<id>");}
    static Map<String,Integer> expectedManifest() throws Exception {
        var expected=new TreeMap<String,Integer>();try(var in=ProjectUpdateFaultMatrixTest.class.getClassLoader().getResourceAsStream("project-update-checkpoints.properties")) {
            for(String line:new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8).split("\\n")) {if(line.isBlank())continue;int at=line.lastIndexOf('=');expected.put(line.substring(0,at),Integer.parseInt(line.substring(at+1)));}
        }return expected;
    }
}
