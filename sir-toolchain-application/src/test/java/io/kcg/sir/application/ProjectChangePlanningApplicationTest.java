package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import static io.kcg.sir.application.ProjectChangePrerequisiteTest.*;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineStore.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.change.api.*;
import io.kcg.sir.source.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectChangePlanningApplicationTest {
    @TempDir Path temp;
    record Scenario(Path root,Path source,Path output,Path state,ProjectBaselineResult.Success saved,SourceSnapshot candidate) {
        ProjectChangeContextRequest request() { return new ProjectChangeContextRequest(state,output,saved.receipt().baselineId(),candidate); }
    }
    Scenario scenario(Path root,boolean fragment) throws Exception {
        Files.createDirectories(root);var source=prepared(root,fragment);var output=root.resolve("output");var generated=generate(source,output);var state=root.resolve("state");
        var saved=assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(),output,state)));
        return new Scenario(root,source,output,state,saved,ProjectChangePlannerContractTest.modified(saved.sources(),capabilitySource(fragment)));
    }
    static ProjectChangeContext context(ProjectChangeContextResult result) { return assertInstanceOf(ProjectChangeContextResult.Success.class,result,result.diagnostics().toString()).context(); }
    static ProjectChangePlanningRequest request(ProjectChangeContext c,SourceSnapshot candidate) {
        var target=c.targets().stream().filter(t->t.displayName().equals(NAME)).findFirst().orElseThrow();return new ProjectChangePlanningRequest(c,candidate,c.targetKey(target));
    }
    static ProjectChangePlanningApplication hooked(Consumer<String> hook) throws Exception {
        var ctor=ProjectChangePlanningApplication.class.getDeclaredConstructor(Consumer.class);ctor.setAccessible(true);return ctor.newInstance(hook);
    }
    @Test void freshRootAndFragmentContextsPlanAndVerifyWithoutAnyDiskMutationOrOriginalSourceDirectory() throws Exception {
        for(boolean fragment:List.of(false,true)) {
            var s=scenario(temp.resolve(fragment?"fragment":"root"),fragment);Files.writeString(s.output.resolve("user.txt"),"not managed");Files.move(s.source,s.root.resolve("unavailable"));var before=tree(s.root);
            var api=new ProjectChangePlanningApplication();var c=context(api.context(s.request()));var t=c.targets().stream().filter(v->v.displayName().equals(NAME)).findFirst().orElseThrow();
            assertEquals(capabilitySource(fragment),t.workflowSpan().source());assertEquals(s.saved.sources().manifest(),c.basedOn().sources());assertEquals(s.candidate.manifest(),c.candidate().sources());
            var req=request(c,s.candidate);var result=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(req));
            assertEquals(1,result.plan().fileChanges().size());assertTrue(result.plan().fileChanges().get(0).relativePath().endsWith(NAME+"Service.java"));
            assertEquals(result,api.plan(req));assertEquals(result,api.verify(req,result));assertEquals(result.sha256Hex(),assertInstanceOf(ProjectChangePlanningResult.Planned.class,new ProjectChangePlanningApplication().plan(req)).sha256Hex());
            assertEquals(before,tree(s.root));assertEquals(35,s.saved.graph().nodes().stream().filter(n->n instanceof io.kcg.sir.projectgraph.api.ProjectGraphNode.ProjectFile).count());
        }
    }
    @Test void commentAndExactCandidatesAreEvidenceBearingNoChangesNotPublishedBaselines() throws Exception {
        var s=scenario(temp,false);var api=new ProjectChangePlanningApplication();var exact=s.saved.sources();var comment=replace(exact,SourceId.of("modules/course.sir"),"sir 0.2","sir 0.2\n// metadata-free comment");var before=tree(temp);
        var same=context(api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),exact)));
        var changed=context(api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),comment)));
        assertNotEquals(same.contextId(),changed.contextId());assertEquals(same.basedOn(),same.candidate());assertNotEquals(changed.basedOn(),changed.candidate());
        assertInstanceOf(ProjectChangePlanningResult.NoChanges.class,api.plan(request(same,exact)));assertInstanceOf(ProjectChangePlanningResult.NoChanges.class,api.plan(request(changed,comment)));assertEquals(before,tree(temp));
    }
    @Test void staleCandidateContextTargetAndPlanCannotBeReplayedEvenWhenJavaBytesMatch() throws Exception {
        var s=scenario(temp,false);var api=new ProjectChangePlanningApplication();var c=context(api.context(s.request()));var req=request(c,s.candidate);var plan=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(req));var before=tree(temp);
        var comment=replace(s.candidate,SourceId.of("modules/course.sir"),"sir 0.2","sir 0.2\n// changed after context");
        assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.plan(new ProjectChangePlanningRequest(c,comment,req.targetKey())));
        var fresh=context(api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),comment)));
        assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.plan(new ProjectChangePlanningRequest(fresh,comment,req.targetKey())));
        assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.plan(new ProjectChangePlanningRequest(c,s.candidate,"0".repeat(64))));
        assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.verify(request(fresh,comment),plan));
        var wrongVersion=new ProjectChangeContext(2,c.stateRoot(),c.baseline(),c.basedOn(),c.candidate(),c.targets());assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.plan(new ProjectChangePlanningRequest(wrongVersion,s.candidate,req.targetKey())));
        assertEquals(before,tree(temp));
    }
    @Test void forgedContextAndRehashedPlanAreComparedWithCompleteIndependentReplanning() throws Exception {
        var s=scenario(temp,false);var api=new ProjectChangePlanningApplication();var c=context(api.context(s.request()));var req=request(c,s.candidate);var p=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(req));var before=tree(temp);
        var catalog=new ArrayList<>(c.targets());var first=catalog.get(0);catalog.set(0,new ProjectWorkflowTarget(first.target(),first.displayName()+" altered",first.declarationSpan(),first.workflowSpan()));
        var forgedContext=new ProjectChangeContext(1,c.stateRoot(),c.baseline(),c.basedOn(),c.candidate(),catalog);
        assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.plan(request(forgedContext,s.candidate)));
        var f=p.plan().fileChanges().get(0);var fake=new FileChange(f.relativePath(),f.artifactId(),f.ownerSymbol(),f.baseByteCount(),f.baseSha256Hex(),f.candidateByteCount(),"0".repeat(64));
        var forged=new ProjectChangePlanningResult.Planned(c,new ProjectChangePlan(1,p.plan().basedOn(),p.plan().candidate(),p.plan().operation(),p.plan().artifactChanges(),List.of(fake)),List.of());
        assertNotEquals(p.sha256Hex(),forged.sha256Hex());assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.verify(req,forged));assertEquals(before,tree(temp));
    }
    @Test void changedOutputCurrentAndMissingLockAreNeverRepairedByContextPlanOrVerify() throws Exception {
        for(String dirty:List.of("output","current","lock","pending","transactions","extra","anchor")) {
            var s=scenario(temp.resolve(dirty),false);var api=new ProjectChangePlanningApplication();var c=context(api.context(s.request()));var req=request(c,s.candidate);var plan=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(req));
            switch(dirty) {
                case "output" -> Files.writeString(s.output.resolve("pom.xml"),"external edit");
                case "current" -> Files.writeString(s.state.resolve("CURRENT"),"0".repeat(64)+"\n");
                case "lock" -> Files.delete(s.state.resolve("LOCK"));
                case "pending" -> Files.writeString(s.state.resolve("CURRENT.new"),"retain");
                case "transactions" -> Files.createDirectory(s.state.resolve("transactions"));
                case "extra" -> Files.writeString(s.state.resolve("foreign"),"retain");
                case "anchor" -> {byte[] bytes=Files.readAllBytes(s.state.resolve("CURRENT"));Files.delete(s.state.resolve("CURRENT"));Files.write(s.state.resolve("CURRENT"),bytes);}
            }
            var before=tree(s.root);assertInstanceOf(ProjectChangeContextResult.Failure.class,api.context(s.request()));assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.plan(req));assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.verify(req,plan));assertEquals(before,tree(s.root),dirty);
        }
    }
    @Test void missingStateCannotCreateDirectoriesOrLocks() throws Exception {
        var s=scenario(temp,false);var missing=temp.resolve("missing");var before=tree(temp);var request=new ProjectChangeContextRequest(missing,s.output,s.saved.receipt().baselineId(),s.candidate);
        assertInstanceOf(ProjectChangeContextResult.Failure.class,new ProjectChangePlanningApplication().context(request));assertEquals(before,tree(temp));assertFalse(Files.exists(missing));
    }
    @Test void candidateParseAndCrossFileRelatedDiagnosticsKeepCurrentRealFileLocations() throws Exception {
        var s=scenario(temp,true);var api=new ProjectChangePlanningApplication();var before=tree(temp);var id=capabilitySource(true);
        var malformed=replace(s.candidate,id,"workflow {","workflow { ???");var f=assertInstanceOf(ProjectChangeContextResult.Failure.class,api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),malformed)));
        assertEquals(ProjectChangeStage.PARSE,f.failedStage());assertTrue(f.diagnostics().stream().anyMatch(d->d.sourceSpan().filter(span->span.source().equals(id)).isPresent()));
        var duplicate=replace(s.candidate,ENTRY,"capability GetCourse {","capability GetCourse @id(\"search-course-enrollments\") {");
        f=assertInstanceOf(ProjectChangeContextResult.Failure.class,api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),duplicate)));
        assertEquals(ProjectChangeStage.SEMANTIC,f.failedStage());assertTrue(f.diagnostics().stream().anyMatch(d->d.severity()==ExecutionSeverity.INFO && d.sourceSpan().filter(span->span.source().equals(ENTRY) || span.source().equals(id)).isPresent()),f.toString());assertEquals(before,tree(temp));
    }
    @Test void typeAndValidateFailuresKeepFragmentLocationsAndOriginalCodes() throws Exception {
        var s=scenario(temp,true);var api=new ProjectChangePlanningApplication();var before=tree(temp);var id=capabilitySource(true);
        var wrongType=replace(s.candidate,id,"status == EnrollmentStatus.ACTIVE","status == item.id");
        var type=assertInstanceOf(ProjectChangeContextResult.Failure.class,api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),wrongType)));
        assertEquals(ProjectChangeStage.SEMANTIC,type.failedStage());assertTrue(type.diagnostics().stream().anyMatch(d->d.code().startsWith("SIR-TYPE-") && d.sourceSpan().filter(span->span.source().equals(id)).isPresent()),type.toString());
        var wrongOrder=replace(s.candidate,id,"import InvalidPage from \"project.sir\";","import InvalidPage from \"project.sir\";\nimport CourseNotFound from \"project.sir\";");wrongOrder=replace(wrongOrder,id,"fails InvalidPage;","fails InvalidPage;\n      fails CourseNotFound;");
        var valid=assertInstanceOf(ProjectChangeContextResult.Failure.class,api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),wrongOrder)));
        assertEquals(ProjectChangeStage.SEMANTIC,valid.failedStage());assertTrue(valid.diagnostics().stream().anyMatch(d->d.code().equals("SIR-VALID-001") && d.sourceSpan().filter(span->span.source().equals(id)).isPresent()),valid.toString());assertEquals(before,tree(temp));
    }
    @Test void sourceShapeChangesAndNonTargetChangesAreRejectedWithoutWriting() throws Exception {
        var s=scenario(temp,false);var api=new ProjectChangePlanningApplication();var before=tree(temp);var bytes=copy(s.candidate);bytes.remove(SourceId.of("modules/student.sir"));
        assertInstanceOf(ProjectChangeContextResult.Failure.class,api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),new SourceSnapshot(ENTRY,bytes))));
        var other=replace(s.candidate,ENTRY,"length(1, 100)","length(1, 90)");var c=context(api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),other)));
        var fail=assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.plan(request(c,other)));assertEquals(ProjectChangeStage.PLAN,fail.failedStage());assertTrue(fail.diagnostics().stream().anyMatch(d->d.planningStage().filter(v->v==ChangeDiagnosticStage.SCOPE).isPresent()));assertEquals(before,tree(temp));
    }
    @Test void lastBoundaryRechecksOutputBundleAndPointerRatherThanTrustingEarlierVerification() throws Exception {
        for(String changed:List.of("output","bundle","pointer","output-root")) {
            var s=scenario(temp.resolve(changed),false);var c=context(new ProjectChangePlanningApplication().context(s.request()));var req=request(c,s.candidate);var hit=new boolean[1];
            var result=hooked(event->{if(event.equals("before-return")) {hit[0]=true;try {
                if(changed.equals("output"))Files.writeString(s.output.resolve("pom.xml"),"outside change");
                if(changed.equals("bundle"))Files.write(s.state.resolve("baselines/"+s.saved.receipt().baselineId()+"/sources.kcg-source-set"),new byte[]{1},StandardOpenOption.APPEND);
                if(changed.equals("pointer")){var p=s.state.resolve("CURRENT");var value=Files.readAllBytes(p);Files.delete(p);Files.write(p,value);}
                if(changed.equals("output-root")) {var old=s.root.resolve("old-output");Files.move(s.output,old);try(var paths=Files.walk(old)){for(var p:paths.toList()){var copy=s.output.resolve(old.relativize(p));if(Files.isDirectory(p))Files.createDirectories(copy);else Files.copy(p,copy);}}}
            }catch(Exception e){throw new IllegalStateException(e);}}}).plan(req);
            assertTrue(hit[0]);assertInstanceOf(ProjectChangePlanningResult.Failure.class,result);var after=tree(s.root);if(!changed.equals("output-root"))assertInstanceOf(ProjectChangeContextResult.Failure.class,new ProjectChangePlanningApplication().context(s.request()));assertEquals(after,tree(s.root));
        }
    }
    @Test void overBudgetOrLinkedBundleAndTrackedFilesAreRejectedWithoutCleanupOrNewLocks() throws Exception {
        for(String kind:List.of("descriptor","graph","sources","tracked-link","bundle-link")) {
            var s=scenario(temp.resolve(kind),false);var api=new ProjectChangePlanningApplication();var c=context(api.context(s.request()));var req=request(c,s.candidate);var directory=s.state.resolve("baselines/"+s.saved.receipt().baselineId());
            switch(kind) {
                case "descriptor" -> {try(var f=new java.io.RandomAccessFile(directory.resolve(DESCRIPTOR).toFile(),"rw")){f.setLength(2*1024*1024+1);}}
                case "graph" -> {try(var f=new java.io.RandomAccessFile(directory.resolve(GRAPH).toFile(),"rw")){f.setLength(16*1024*1024+1);}}
                case "sources" -> {try(var f=new java.io.RandomAccessFile(directory.resolve("sources.kcg-source-set").toFile(),"rw")){f.setLength(9*1024*1024+1);}}
                case "tracked-link" -> {var file=s.output.resolve("pom.xml");var outside=s.root.resolve("outside.xml");Files.move(file,outside);Files.createSymbolicLink(file,outside);}
                case "bundle-link" -> {var file=directory.resolve(GRAPH);var outside=s.root.resolve("outside.graph");Files.move(file,outside);Files.createSymbolicLink(file,outside);}
            }
            var before=tree(s.root);assertInstanceOf(ProjectChangeContextResult.Failure.class,api.context(s.request()));assertInstanceOf(ProjectChangePlanningResult.Failure.class,api.plan(req));assertEquals(before,tree(s.root),kind);
        }
    }
    @Test void contextEncodingRejectsMalformedUnicodeAndHashBoundarySubstitution() throws Exception {
        var s=scenario(temp,false);var c=context(new ProjectChangePlanningApplication().context(s.request()));var t=c.targets().get(0);var list=new ArrayList<>(c.targets());list.set(0,new ProjectWorkflowTarget(t.target(),"bad\ud800",t.declarationSpan(),t.workflowSpan()));
        assertThrows(IllegalArgumentException.class,()->new ProjectChangeContext(1,c.stateRoot(),c.baseline(),c.basedOn(),c.candidate(),list));
        assertThrows(IllegalArgumentException.class,()->new ProjectChangeContext(1,c.stateRoot(),c.baseline(),c.basedOn(),c.candidate(),c.targets(),"0".repeat(64)));
        var shifted=new ArrayList<>(c.targets());shifted.set(0,new ProjectWorkflowTarget(t.target(),t.displayName(),t.workflowSpan(),t.workflowSpan()));
        assertNotEquals(c.contextId(),new ProjectChangeContext(1,c.stateRoot(),c.baseline(),c.basedOn(),c.candidate(),shifted).contextId());
    }
    @Test void onlyContextPlanAndVerifyArePublicAndNoLegacyApplyWrapperCanCarryAProjectPlan() {
        var names=Arrays.stream(ProjectChangePlanningApplication.class.getDeclaredMethods()).filter(m->java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("context","plan","verify"),names);
        for(Class<?> root:List.of(ChangeExecutionApplication.class,ProjectBaselineApplication.class))for(var m:root.getDeclaredMethods()) {
            if(!java.lang.reflect.Modifier.isPublic(m.getModifiers()))continue;
            for(var argument:m.getParameterTypes()) {
                assertNotEquals(ProjectChangePlan.class,argument);assertNotEquals(ProjectChangePlanningResult.Planned.class,argument);
                if(argument.isRecord())for(var member:argument.getRecordComponents()) {assertNotEquals(ProjectChangePlan.class,member.getType());assertNotEquals(ProjectChangePlanningResult.Planned.class,member.getType());}
            }
        }
    }
    @Test void contextAndPlanDigestsAreLocaleAndSnapshotInsertionOrderIndependent() throws Exception {
        var s=scenario(temp,false);var api=new ProjectChangePlanningApplication();var expected=context(api.context(s.request()));var order=new ArrayList<>(s.candidate.manifest().files());Collections.reverse(order);var values=new LinkedHashMap<SourceId,byte[]>();order.forEach(f->values.put(f.sourceId(),s.candidate.bytes(f.sourceId())));var reordered=new SourceSnapshot(ENTRY,values);var locale=Locale.getDefault();var before=tree(temp);
        try {Locale.setDefault(Locale.forLanguageTag("tr-TR"));var c=context(api.context(new ProjectChangeContextRequest(s.state,s.output,s.saved.receipt().baselineId(),reordered)));assertEquals(expected,c);assertEquals(expected.contextId(),c.contextId());assertEquals(api.plan(request(expected,s.candidate)),api.plan(request(c,reordered)));}finally{Locale.setDefault(locale);}
        assertEquals(before,tree(temp));assertThrows(UnsupportedOperationException.class,()->expected.targets().clear());
    }
}
