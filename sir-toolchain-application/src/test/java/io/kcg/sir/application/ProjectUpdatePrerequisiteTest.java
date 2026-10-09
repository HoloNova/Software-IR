package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import static io.kcg.sir.application.ProjectChangePrerequisiteTest.*;
import static io.kcg.sir.application.ProjectChangePlanningApplicationTest.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real preconditions and filesystem primitives only; not a claimed public apply/recover. */
class ProjectUpdatePrerequisiteTest {
    @TempDir Path temp;
    @Test void twoRealCandidatesBindFullBundlesAndUpdateBytesInRootAndFragment() throws Exception {
        for(boolean fragment:List.of(false,true)) {
            var test=new ProjectChangePlanningApplicationTest();var s=test.scenario(temp.resolve(fragment?"fragment":"root"),fragment);
            Files.move(s.source(),s.root().resolve("unavailable"));var before=tree(s.root());var api=new ProjectChangePlanningApplication();
            var ctx=context(api.context(s.request()));var planned=assertInstanceOf(ProjectChangePlanningResult.Planned.class,api.plan(request(ctx,s.candidate())));
            var diagnostics=new ArrayList<ExecutionDiagnostic>();var b1=ProjectBaselineVerification.compile(s.candidate(),s.output(),diagnostics);
            assertNotNull(b1,diagnostics.toString());assertEquals(ctx.candidate().sources(),b1.bundle().sources().manifest());
            assertEquals(ctx.candidate().graphCanonicalDigest(),b1.bundle().graph().canonicalDigest());
            var f=planned.plan().fileChanges().getFirst();var generated=b1.compilation().generatedFiles().stream().filter(g->g.relativePath().equals(f.relativePath())).findFirst().orElseThrow();
            assertEquals(f.candidateByteCount(),generated.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertEquals(f.candidateSha256Hex(),io.kcg.sir.application.internal.Sha256.hexDigest(generated.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var second=replace(s.candidate(),capabilitySource(fragment),"order by code ascending","order by code descending");
            diagnostics.clear();var b2=ProjectBaselineVerification.compile(second,s.output(),diagnostics);assertNotNull(b2,diagnostics.toString());
            assertNotEquals(b1.bundle().baselineId(),b2.bundle().baselineId());assertEquals(b1.bundle().descriptor().manifest().stream().map(e->e.relativePath()).toList(),b2.bundle().descriptor().manifest().stream().map(e->e.relativePath()).toList());
            assertEquals(before,tree(s.root()));
        }
    }
    @Test void exactReversionReusesOriginalIdSoHistoryMustNotBeOneParentPerBaseline() throws Exception {
        var s=new ProjectChangePlanningApplicationTest().scenario(temp,false);var ds=new ArrayList<ExecutionDiagnostic>();
        var b1=ProjectBaselineVerification.compile(s.candidate(),s.output(),ds);assertNotNull(b1);
        var back=replace(s.candidate(),ENTRY,"(status == EnrollmentStatus.ACTIVE or status == EnrollmentStatus.CANCELLED)","status == EnrollmentStatus.ACTIVE");
        assertEquals(s.saved().sources().manifest(),back.manifest());var reverted=ProjectBaselineVerification.compile(back,s.output(),ds);assertNotNull(reverted);
        assertEquals(s.saved().receipt().baselineId(),reverted.bundle().baselineId());assertNotEquals(b1.bundle().baselineId(),reverted.bundle().baselineId());
        var id0=reverted.bundle().baselineId();var id1=b1.bundle().baselineId();var publishedEdges=List.of(List.of(id0,id1),List.of(id1,id0));
        var reachable=new HashSet<String>(Set.of(id0));for(var edge:publishedEdges) { assertTrue(reachable.contains(edge.getFirst()));reachable.add(edge.getLast()); }
        assertEquals(Set.of(id0,id1),reachable); // Immutable transition edges admit reversion; a parent-per-ID chain cycles.
    }
    @Test void currentReadGuardsReallyRejectASecondCompleteBundleWithoutDeletingAnything() throws Exception {
        var s=new ProjectChangePlanningApplicationTest().scenario(temp,false);var ds=new ArrayList<ExecutionDiagnostic>();var b1=ProjectBaselineVerification.compile(s.candidate(),s.output(),ds);assertNotNull(b1);
        try(var files=new SecureFileAccess(s.state())) {
            String dir="baselines/"+b1.bundle().baselineId();files.createDirectory(dir);
            files.writeNew(dir+"/"+ProjectBaselineStore.SOURCES,b1.bundle().payloadBytes(),x->{});
            files.writeNew(dir+"/"+ProjectBaselineStore.GRAPH,b1.bundle().graphBytes(),x->{});
            files.writeNew(dir+"/"+ProjectBaselineStore.DESCRIPTOR,b1.bundle().descriptorBytes(),x->{});
            files.writeNew(dir+"/"+ProjectBaselineStore.POINTER,(b1.bundle().baselineId()+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII),x->{});
            assertEquals(b1.bundle().baselineId(),new ProjectBaselineStore(files).load(b1.bundle().baselineId()).baselineId());
        }
        var before=tree(temp);assertInstanceOf(ProjectBaselineResult.Failure.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(s.state(),s.output(),s.saved().receipt().baselineId())));
        assertInstanceOf(ProjectChangeContextResult.Failure.class,new ProjectChangePlanningApplication().context(s.request()));assertEquals(before,tree(temp));
    }
    @SuppressWarnings("unchecked") private static SecureDirectoryStream<Path> stream(Path root) throws Exception {
        var result=Files.newDirectoryStream(root);assertInstanceOf(SecureDirectoryStream.class,result);return (SecureDirectoryStream<Path>)result;
    }
    @Test void anchoredMoveReplacesExistingCurrentWithoutRemovingEitherBaselineAnchor() throws Exception {
        Files.writeString(temp.resolve("b0"),"0".repeat(64)+"\n");Files.writeString(temp.resolve("b1"),"1".repeat(64)+"\n");
        Files.createLink(temp.resolve("CURRENT"),temp.resolve("b0"));Files.createLink(temp.resolve("CURRENT.new"),temp.resolve("b1"));
        var before=Files.readAttributes(temp.resolve("b1"),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        try(var root=stream(temp)) { root.move(Path.of("CURRENT.new"),root,Path.of("CURRENT")); }
        assertFalse(Files.exists(temp.resolve("CURRENT.new")));assertTrue(Files.isSameFile(temp.resolve("CURRENT"),temp.resolve("b1")));
        assertEquals(before,Files.readAttributes(temp.resolve("CURRENT"),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey());
        assertEquals("0".repeat(64)+"\n",Files.readString(temp.resolve("b0")));assertTrue(Files.exists(temp.resolve("b1")));
    }
    @Test void anchoredCrossDirectoryUpdatePreservesHardLinkedBackupAndRollbackRestoresIdentity() throws Exception {
        var output=Files.createDirectory(temp.resolve("output"));var stage=Files.createDirectory(temp.resolve("stage"));var backup=Files.createDirectory(temp.resolve("backup"));
        Files.writeString(output.resolve("file kind=UPDATE state=x"),"base");Files.writeString(stage.resolve("0"),"candidate");
        Files.createLink(backup.resolve("0"),output.resolve("file kind=UPDATE state=x"));
        try(var o=stream(output);var s=stream(stage);var b=stream(backup)) {
            s.move(Path.of("0"),o,Path.of("file kind=UPDATE state=x"));assertEquals("base",Files.readString(backup.resolve("0")));assertEquals("candidate",Files.readString(output.resolve("file kind=UPDATE state=x")));
            b.move(Path.of("0"),o,Path.of("file kind=UPDATE state=x"));assertEquals("base",Files.readString(output.resolve("file kind=UPDATE state=x")));
        }
        assertFalse(Files.exists(backup.resolve("0")));assertFalse(Files.exists(stage.resolve("0")));
    }
}
