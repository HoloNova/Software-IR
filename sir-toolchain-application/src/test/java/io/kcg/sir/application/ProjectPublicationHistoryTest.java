package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import static io.kcg.sir.application.ProjectChangePrerequisiteTest.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectPublicationHistoryTest {
    @TempDir Path temp;
    record Case(ProjectChangePlanningApplicationTest.Scenario s,ProjectBaselineCodec.Bundle b0,ProjectBaselineCodec.Bundle b1,ProjectBaselineCodec.Bundle b2) {}
    Case scenario() throws Exception {
        var s=new ProjectChangePlanningApplicationTest().scenario(temp,false);var ds=new ArrayList<ExecutionDiagnostic>();
        var b0=ProjectBaselineVerification.compile(s.saved().sources(),s.output(),ds).bundle();var b1=ProjectBaselineVerification.compile(s.candidate(),s.output(),ds).bundle();
        var b2=ProjectBaselineVerification.compile(replace(s.candidate(),ENTRY,"order by code ascending","order by code descending"),s.output(),ds).bundle();return new Case(s,b0,b1,b2);
    }
    void bundle(ProjectBaselineCodec.Bundle b) throws Exception {
        var dir=temp.resolve("state/baselines/"+b.baselineId());Files.createDirectory(dir);
        Files.write(dir.resolve(ProjectBaselineStore.DESCRIPTOR),b.descriptorBytes());Files.write(dir.resolve(ProjectBaselineStore.SOURCES),b.payloadBytes());Files.write(dir.resolve(ProjectBaselineStore.GRAPH),b.graphBytes());Files.writeString(dir.resolve(ProjectBaselineStore.POINTER),b.baselineId()+"\n");
    }
    void history(Case c,List<ProjectPublicationHistory.Edge> edges,String current) throws Exception {
        var dir=Files.createDirectory(c.s.state().resolve(ProjectPublicationHistory.DIRECTORY));Files.writeString(dir.resolve("origin"),c.b0.baselineId()+"\n");
        for(var e:edges)Files.write(dir.resolve(e.id()),e.bytes());Files.delete(c.s.state().resolve("CURRENT"));Files.createLink(c.s.state().resolve("CURRENT"),c.s.state().resolve(ProjectBaselineStore.anchor(current)));
    }
    ProjectPublicationHistory.Edge edge(Case c,ProjectBaselineCodec.Bundle a,ProjectBaselineCodec.Bundle b) { return ProjectPublicationHistory.Edge.of(c.b0.baselineId(),a,b,"1".repeat(64),"2".repeat(64),"sir://CourseAdmin/declared/capability/search-course-enrollments"); }
    void apply(Case c,String current,ProjectBaselineCodec.Bundle candidate) throws Exception {
        var api=new ProjectChangePlanningApplication();var context=ProjectChangePlanningApplicationTest.context(api.context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),current,candidate.sources())));var request=ProjectChangePlanningApplicationTest.request(context,candidate.sources());var planned=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(request));assertEquals(candidate.baselineId(),assertInstanceOf(ProjectChangeApplyResult.Applied.class,new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(request,planned))).candidate().baselineId());
    }
    @Test void completeCandidateAndSelfConsistentEdgeWithoutCommitReceiptAreStillForeign() throws Exception {
        var c=scenario();bundle(c.b1);history(c,List.of(edge(c,c.b0,c.b1)),c.b0.baselineId());var before=tree(temp);
        try(var files=new SecureFileAccess(c.s.state())) {var error=assertThrows(java.io.IOException.class,()->ProjectPublicationHistory.validate(files,c.b0.baselineId()));assertTrue(error.getMessage().contains("completed transaction receipt"));}
        assertInstanceOf(ProjectBaselineResult.Failure.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s.state(),c.s.output(),c.b0.baselineId())));assertEquals(before,tree(temp));
    }
    @Test void threeImmutableBundlesAndTwoTransitionsCanBeReadWithoutMutation() throws Exception {
        var c=scenario();apply(c,c.b0.baselineId(),c.b1);apply(c,c.b1.baselineId(),c.b2);
        var before=tree(temp);try(var files=new SecureFileAccess(c.s.state())) { var result=ProjectPublicationHistory.validate(files,c.b2.baselineId());assertEquals(Set.of(c.b0.baselineId(),c.b1.baselineId(),c.b2.baselineId()),result.baselineIds());new ProjectBaselineStore(files).guardState(c.b2.baselineId(),false); }
        assertEquals(before,tree(temp));
        assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s.state(),c.s.output(),c.b2.baselineId())));
        var ctx=ProjectChangePlanningApplicationTest.context(new ProjectChangePlanningApplication().context(new ProjectChangeContextRequest(c.s.state(),c.s.output(),c.b2.baselineId(),c.b1.sources())));
        assertInstanceOf(ProjectChangePlanningResult.Planned.class,new ProjectChangePlanningApplication().plan(ProjectChangePlanningApplicationTest.request(ctx,c.b1.sources())));
        assertEquals(before,tree(temp));
    }
    @Test void reversionAndPreviouslySeenEdgeAreHistoryNotACycleError() throws Exception {
        var c=scenario();apply(c,c.b0.baselineId(),c.b1);apply(c,c.b1.baselineId(),c.b0);var before=tree(temp);
        try(var files=new SecureFileAccess(c.s.state())) {assertEquals(2,ProjectPublicationHistory.validate(files,c.b0.baselineId()).baselineIds().size());new ProjectBaselineStore(files).guardState(c.b0.baselineId(),false);}
        assertEquals(before,tree(temp));assertEquals(edge(c,c.b0,c.b1).id(),edge(c,c.b0,c.b1).id());
    }
    @Test void unexplainedCompleteBaselineAndHistoryMembersAreRejectedAndRetained() throws Exception {
        var c=scenario();bundle(c.b1);bundle(c.b2);history(c,List.of(edge(c,c.b0,c.b1)),c.b1.baselineId());var before=tree(temp);
        try(var files=new SecureFileAccess(c.s.state())) {assertThrows(java.io.IOException.class,()->ProjectPublicationHistory.validate(files,c.b1.baselineId()));}assertEquals(before,tree(temp));
        Files.delete(temp.resolve("state/baselines/"+c.b2.baselineId()+"/"+ProjectBaselineStore.POINTER)); // Leave all foreign material, including an incomplete Bundle.
        before=tree(temp);try(var files=new SecureFileAccess(c.s.state())) {assertThrows(java.io.IOException.class,()->new ProjectBaselineStore(files).guardState(c.b1.baselineId(),false));}assertEquals(before,tree(temp));
    }
    @Test void edgeFramingIsStrictAndBindsBothBundleSourceGraphAndManifestEvidence() throws Exception {
        var c=scenario();var e=edge(c,c.b0,c.b1);assertEquals(e,ProjectPublicationHistory.Edge.decode(e.bytes()));
        for(byte[] bytes:List.of(Arrays.copyOf(e.bytes(),e.bytes().length-1),Arrays.copyOf(e.bytes(),e.bytes().length+1),"KCG-OTHER\n".getBytes(StandardCharsets.UTF_8)))assertThrows(java.io.IOException.class,()->ProjectPublicationHistory.Edge.decode(bytes));
        assertThrows(IllegalArgumentException.class,()->new ProjectPublicationHistory.Edge(e.origin(),e.b0(),e.b1(),e.outputRoot(),e.baseSource(),e.candidateSource(),e.baseGraph(),e.candidateGraph(),e.baseManifest(),e.candidateManifest(),e.contextDigest(),e.planDigest(),"bad\ud800"));
        bundle(c.b1);var forged=new ProjectPublicationHistory.Edge(e.origin(),e.b0(),e.b1(),e.outputRoot(),e.baseSource(),"0".repeat(64),e.baseGraph(),e.candidateGraph(),e.baseManifest(),e.candidateManifest(),e.contextDigest(),e.planDigest(),e.subject());history(c,List.of(forged),c.b1.baselineId());var before=tree(temp);
        try(var files=new SecureFileAccess(c.s.state())) {assertThrows(java.io.IOException.class,()->ProjectPublicationHistory.validate(files,c.b1.baselineId()));}assertEquals(before,tree(temp));
    }
    @Test void enumerationAndEdgeBudgetsFailBeforeUnboundedAllocation() throws Exception {
        var c=scenario();bundle(c.b1);var edges=new ArrayList<ProjectPublicationHistory.Edge>();var e=edge(c,c.b0,c.b1);
        for(int i=0;i<ProjectPublicationHistory.MAX_EDGES;i++)edges.add(new ProjectPublicationHistory.Edge(e.origin(),e.b0(),e.b1(),e.outputRoot(),e.baseSource(),e.candidateSource(),e.baseGraph(),e.candidateGraph(),e.baseManifest(),e.candidateManifest(),e.contextDigest(),String.format("%064x",i),e.subject()));
        history(c,edges,c.b1.baselineId());try(var files=new SecureFileAccess(c.s.state())) {assertEquals(33,files.list(ProjectPublicationHistory.DIRECTORY,ProjectPublicationHistory.MAX_EDGES+2).size());assertThrows(java.io.IOException.class,()->ProjectPublicationHistory.validate(files,c.b1.baselineId()));}
        var extra=new ProjectPublicationHistory.Edge(e.origin(),e.b0(),e.b1(),e.outputRoot(),e.baseSource(),e.candidateSource(),e.baseGraph(),e.candidateGraph(),e.baseManifest(),e.candidateManifest(),e.contextDigest(),String.format("%064x",32),e.subject());Files.write(c.s.state().resolve(ProjectPublicationHistory.DIRECTORY).resolve(extra.id()),extra.bytes());var before=tree(temp);
        try(var files=new SecureFileAccess(c.s.state())) {assertThrows(java.io.IOException.class,()->ProjectPublicationHistory.validate(files,c.b1.baselineId()));}assertEquals(before,tree(temp));
        assertThrows(java.io.IOException.class,()->ProjectPublicationHistory.Edge.decode(new byte[ProjectPublicationHistory.MAX_EDGE_BYTES+1]));
    }
    @Test void sameByteExternalCurrentAndActiveTransactionRemainBlockedForReaders() throws Exception {
        var c=scenario();history(c,List.of(),c.b0.baselineId());byte[] bytes=Files.readAllBytes(c.s.state().resolve("CURRENT"));Files.delete(c.s.state().resolve("CURRENT"));Files.write(c.s.state().resolve("CURRENT"),bytes);var before=tree(temp);
        assertInstanceOf(ProjectBaselineResult.Failure.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(c.s.state(),c.s.output(),c.b0.baselineId())));assertEquals(before,tree(temp));
        Files.delete(c.s.state().resolve("CURRENT"));Files.createLink(c.s.state().resolve("CURRENT"),c.s.state().resolve(ProjectBaselineStore.anchor(c.b0.baselineId())));Files.createDirectory(c.s.state().resolve("project-transactions"));before=tree(temp);
        assertInstanceOf(ProjectChangeContextResult.Failure.class,new ProjectChangePlanningApplication().context(c.s.request()));assertEquals(before,tree(temp));
    }
    @Test void originOnlySupportsTheOriginalBaselineButNotAnUnconnectedCurrent() throws Exception {
        var c=scenario();history(c,List.of(),c.b0.baselineId());try(var files=new SecureFileAccess(c.s.state())) {assertEquals(Set.of(c.b0.baselineId()),ProjectPublicationHistory.validate(files,c.b0.baselineId()).baselineIds());}
        bundle(c.b1);Files.delete(c.s.state().resolve("CURRENT"));Files.createLink(c.s.state().resolve("CURRENT"),c.s.state().resolve(ProjectBaselineStore.anchor(c.b1.baselineId())));var before=tree(temp);
        try(var files=new SecureFileAccess(c.s.state())) {assertThrows(java.io.IOException.class,()->ProjectPublicationHistory.validate(files,c.b1.baselineId()));}assertEquals(before,tree(temp));
    }
}
