package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import static io.kcg.sir.application.ProjectChangePrerequisiteTest.*;
import io.kcg.sir.change.api.*;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectChangePlannerContractTest {
    @TempDir Path temp;
    static ProjectChangeRevision revision(SourceSnapshot s,ProjectGraph g) { return new ProjectChangeRevision(1,s.manifest(),g.version(),g.canonicalDigest(),ProjectGraphCanonicalFormatVersion.V2); }
    static ProjectChangePlanningInput input(SourceSnapshot b,SourceSnapshot c) {
        var bc=compile(b);var cc=compile(c);var bg=graph(b,bc);var cg=graph(c,cc);var cap=capability(bc);
        return new ProjectChangePlanningInput(revision(b,bg),revision(c,cg),bc.semanticModel(),cc.semanticModel(),bg,cg,new ModifyCapabilityWorkflow(new ChangeTarget(cap.id(),cap.sourceNodeId(),cap.workflow().sourceNodeId())));
    }
    static SourceSnapshot modified(SourceSnapshot b,SourceId s) { return replace(b,s,"status == EnrollmentStatus.ACTIVE","(status == EnrollmentStatus.ACTIVE or status == EnrollmentStatus.CANCELLED)"); }
    @Test void projectPlanIsExactWholeGeneratedDiffAndRecomputesIdentically() throws Exception {
        for(boolean fragment:List.of(false,true)) {
            var root=Files.createDirectory(temp.resolve(fragment?"fragment":"root"));var b=generate(prepared(root,fragment),root.resolve("output")).sources();var c=modified(b,capabilitySource(fragment));var in=input(b,c);var planner=new ProjectChangePlanner();
            var p=assertInstanceOf(ProjectChangeAnalysis.Planned.class,planner.plan(in)).plan();
            assertEquals(List.of("src/main/java/com/example/courseadmin/application/SearchCourseEnrollmentsService.java"),p.fileChanges().stream().map(FileChange::relativePath).toList());
            var bytes=compile(c).generatedFiles().stream().filter(f->f.relativePath().equals(p.fileChanges().get(0).relativePath())).findFirst().orElseThrow().content().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(io.kcg.sir.application.internal.Sha256.hexDigest(bytes),p.fileChanges().get(0).candidateSha256Hex());
            assertEquals(p,assertInstanceOf(ProjectChangeAnalysis.Planned.class,planner.plan(in)).plan());assertTrue(p.sha256Hex().matches("[0-9a-f]{64}"));assertTrue(planner.verify(p,in).isEmpty());
        }
    }
    @Test void commentCandidateNoChangesStillCarriesDifferentSourceAndGraphEvidence() throws Exception {
        var b=generate(prepared(temp,false),temp.resolve("output")).sources();var c=replace(b,SourceId.of("modules/course.sir"),"sir 0.2","sir 0.2\n// changed");var in=input(b,c);
        var n=assertInstanceOf(ProjectChangeAnalysis.NoChanges.class,new ProjectChangePlanner().plan(in));assertEquals(NoChangeReason.SEMANTICALLY_IDENTICAL,n.reason());assertNotEquals(n.basedOn(),n.candidate());
        var same=assertInstanceOf(ProjectChangeAnalysis.NoChanges.class,new ProjectChangePlanner().plan(input(b,b)));assertEquals(same.basedOn(),same.candidate());
    }
    @Test void nonTargetInputAndCapabilityContractChangesCannotBecomeProjectUpdatePlans() throws Exception {
        var b=generate(prepared(temp,false),temp.resolve("output")).sources();var changed=modified(b,ENTRY);var planner=new ProjectChangePlanner();
        var dto=replace(changed,ENTRY,"length(1, 100)","length(1, 90)");
        var failure=assertInstanceOf(ProjectChangeAnalysis.Failure.class,planner.plan(input(b,dto)));assertTrue(failure.diagnostics().stream().anyMatch(d->d.code().equals("SIR-CHANGE-SCOPE-001")),failure.toString());
        var contract=replace(changed,ENTRY,"fails InvalidPage;","fails CourseNotFound;\n      fails InvalidPage;");
        failure=assertInstanceOf(ProjectChangeAnalysis.Failure.class,planner.plan(input(b,contract)));assertTrue(failure.diagnostics().stream().anyMatch(d->d.code().equals("SIR-CHANGE-SCOPE-002")),failure.toString());
    }
    @Test void unsupportedRevisionWrongManifestGraphAndTargetAreRejectedByBothPlanAndVerify() throws Exception {
        var b=generate(prepared(temp,false),temp.resolve("output")).sources();var good=input(b,modified(b,ENTRY));var planner=new ProjectChangePlanner();var plan=assertInstanceOf(ProjectChangeAnalysis.Planned.class,planner.plan(good)).plan();
        var revisions=List.of(new ProjectChangeRevision(2,good.basedOn().sources(),GraphVersion.V0_2,good.basedOn().graphCanonicalDigest(),ProjectGraphCanonicalFormatVersion.V2),
                new ProjectChangeRevision(1,good.basedOn().sources(),GraphVersion.V0_1,good.basedOn().graphCanonicalDigest(),ProjectGraphCanonicalFormatVersion.V1),
                new ProjectChangeRevision(1,good.candidate().sources(),GraphVersion.V0_2,good.basedOn().graphCanonicalDigest(),ProjectGraphCanonicalFormatVersion.V2),
                new ProjectChangeRevision(1,good.basedOn().sources(),GraphVersion.V0_2,"0".repeat(64),ProjectGraphCanonicalFormatVersion.V2));
        for(var r:revisions) {var bad=new ProjectChangePlanningInput(r,good.candidate(),good.baseSemanticModel(),good.candidateSemanticModel(),good.baseGraph(),good.candidateGraph(),good.operation());assertInstanceOf(ProjectChangeAnalysis.Failure.class,planner.plan(bad));assertFalse(planner.verify(plan,bad).isEmpty());}
        var target=good.operation().target();var invalid=new ModifyCapabilityWorkflow(new ChangeTarget(target.declarationSymbol(),target.declarationNodeId(),target.declarationNodeId()));
        var wrong=new ProjectChangePlanningInput(good.basedOn(),good.candidate(),good.baseSemanticModel(),good.candidateSemanticModel(),good.baseGraph(),good.candidateGraph(),invalid);
        assertInstanceOf(ProjectChangeAnalysis.Failure.class,planner.plan(wrong));assertFalse(planner.verify(plan,wrong).isEmpty());
        var mismatch=new ProjectChangePlanningInput(good.basedOn(),good.candidate(),good.baseSemanticModel(),good.baseSemanticModel(),good.baseGraph(),good.candidateGraph(),good.operation());
        assertInstanceOf(ProjectChangeAnalysis.Failure.class,planner.plan(mismatch));assertFalse(planner.verify(plan,mismatch).isEmpty());
    }
    private static ProjectChangePlanningInput syntheticFiles(SourceSnapshot b,SourceSnapshot c,String mode) {
        var in=input(b,c);var cc=compile(c);var raw=new io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory().build(cc.semanticModel(),cc.loweredModel(),cc.generatedFiles(),c);
        var files=new ArrayList<ProjectGraphInput.FileInput>();var edges=new ArrayList<>(raw.edges());
        for(var f:raw.files()) {
            if(mode.equals("delete") && f.relativePath().equals("pom.xml"))continue;
            if(mode.equals("outside") && f.relativePath().equals("pom.xml"))f=new ProjectGraphInput.FileInput(f.relativePath(),f.artifactId(),f.ownerSymbol(),f.byteCount()+1,"0".repeat(64));
            if(mode.equals("equivalent")) {var base=(ProjectGraphNode.ProjectFile)in.baseGraph().node(new GraphNodeId.File(f.relativePath())).orElseThrow();f=new ProjectGraphInput.FileInput(f.relativePath(),f.artifactId(),f.ownerSymbol(),base.provenance().byteCount(),base.provenance().sha256Hex());}
            files.add(f);
        }
        if(mode.equals("delete"))edges.removeIf(e->e.target().equals(new GraphNodeId.File("pom.xml")));
        if(mode.equals("create")) {
            var pom=files.stream().filter(f->f.relativePath().equals("pom.xml")).findFirst().orElseThrow();files.add(new ProjectGraphInput.FileInput("extra.txt",pom.artifactId(),pom.ownerSymbol(),1,"0".repeat(64)));
            edges.add(new ProjectGraphInput.EdgeBinding(GraphEdgeKind.GENERATES_FILE,new GraphNodeId.Lowered(pom.artifactId()),new GraphNodeId.File("extra.txt")));
        }
        var g=assertInstanceOf(ProjectGraphAnalysis.Success.class,new ProjectGraphBuilder().build(new ProjectGraphInput(raw.version(),raw.sourceId(),raw.projectDisplayName(),raw.semanticDeclarations(),raw.loweredDeclarations(),raw.artifacts(),files,edges,raw.sourceSet()))).graph();
        return new ProjectChangePlanningInput(in.basedOn(),revision(c,g),in.baseSemanticModel(),in.candidateSemanticModel(),in.baseGraph(),g,in.operation());
    }
    @Test void wholeManifestRejectsOutsideUpdateCreationAndDeletionEvenWhenTargetWorkflowIsValid() throws Exception {
        var b=generate(prepared(temp,false),temp.resolve("output")).sources();var c=modified(b,ENTRY);var planner=new ProjectChangePlanner();
        for(String mode:List.of("outside","create","delete")) {var bad=syntheticFiles(b,c,mode);var failure=assertInstanceOf(ProjectChangeAnalysis.Failure.class,planner.plan(bad));assertTrue(failure.diagnostics().stream().anyMatch(d->d.stage()==ChangeDiagnosticStage.IMPACT),failure.toString());}
    }
    @Test void outputEquivalentIsASeparateSyntheticPureDecisionNotAClaimAboutRealJavaGeneration() throws Exception {
        var b=generate(prepared(temp,false),temp.resolve("output")).sources();var c=modified(b,ENTRY);var in=syntheticFiles(b,c,"equivalent");
        var result=assertInstanceOf(ProjectChangeAnalysis.NoChanges.class,new ProjectChangePlanner().plan(in));assertEquals(NoChangeReason.OUTPUT_EQUIVALENT,result.reason());assertEquals(in.candidate(),result.candidate());assertNotEquals(result.basedOn(),result.candidate());
    }
    @Test void malformedAndOversizedFileEvidenceCannotBeSilentlyHashedIntoAValidPlan() throws Exception {
        var b=generate(prepared(temp,false),temp.resolve("output")).sources();var in=input(b,modified(b,ENTRY));var planner=new ProjectChangePlanner();var p=assertInstanceOf(ProjectChangeAnalysis.Planned.class,planner.plan(in)).plan();var f=p.fileChanges().get(0);
        for(String path:List.of("bad\ud800.java","x".repeat(16385)+".java")) {
            var malformed=new FileChange(path,f.artifactId(),f.ownerSymbol(),f.baseByteCount(),f.baseSha256Hex(),f.candidateByteCount(),f.candidateSha256Hex());
            var forged=new ProjectChangePlan(1,p.basedOn(),p.candidate(),p.operation(),p.artifactChanges(),List.of(malformed));assertThrows(IllegalArgumentException.class,forged::sha256Hex);assertFalse(planner.verify(forged,in).isEmpty());
        }
    }
    @Test void realLegacyAndProjectWorkflowPlansReuseTheSameFileAndArtifactDecision() throws Exception {
        var b=generate(prepared(temp,false),temp.resolve("output")).sources();var projectInput=input(b,modified(b,ENTRY));var project=assertInstanceOf(ProjectChangeAnalysis.Planned.class,new ProjectChangePlanner().plan(projectInput)).plan();
        String source=resource("valid/course-admin-enrollment.sir").replace("capability SearchCourseEnrollments {","capability SearchCourseEnrollments @id(\"search-course-enrollments\") {");String candidate=source.replace("status == EnrollmentStatus.ACTIVE","(status == EnrollmentStatus.ACTIVE or status == EnrollmentStatus.CANCELLED)");assertNotEquals(source,candidate);
        var sourceId=SourceId.of("legacy.sir");var diagnostics=new ArrayList<io.kcg.sir.application.api.ExecutionDiagnostic>();var generator=new io.kcg.sir.generator.springboot.api.SpringBootGenerator();
        var old=io.kcg.sir.application.internal.SirCompilation.compile(source,sourceId,generator::generate,diagnostics).orElseThrow();var fresh=io.kcg.sir.application.internal.SirCompilation.compile(candidate,sourceId,generator::generate,diagnostics).orElseThrow();assertTrue(diagnostics.isEmpty(),diagnostics.toString());
        var factory=new io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory();var builder=new ProjectGraphBuilder();
        var base=assertInstanceOf(ProjectGraphAnalysis.Success.class,builder.build(factory.build(old.semanticModel(),old.loweredModel(),old.generatedFiles(),sourceId))).graph();var next=assertInstanceOf(ProjectGraphAnalysis.Success.class,builder.build(factory.build(fresh.semanticModel(),fresh.loweredModel(),fresh.generatedFiles(),sourceId))).graph();
        var cap=capability(old);var op=new ModifyCapabilityWorkflow(new ChangeTarget(cap.id(),cap.sourceNodeId(),cap.workflow().sourceNodeId()));var revision=new ChangeBaseRevision(sourceId,io.kcg.sir.application.internal.Sha256.hexDigest(source),base.version(),base.canonicalDigest(),ProjectGraphCanonicalFormatVersion.V1);
        var changeSet=new ChangeSet(ChangeIrVersion.V0_1,revision,List.of(op));var oldInput=new ChangePlanningInput(old.semanticModel(),fresh.semanticModel(),base,next,changeSet);var legacy=assertInstanceOf(ChangeAnalysis.Planned.class,new ChangePlanner().plan(oldInput)).plan();
        assertEquals(project.fileChanges(),legacy.fileChanges());assertEquals(project.artifactChanges(),legacy.artifactChanges());
        assertInstanceOf(ChangeAnalysis.NoChanges.class,new ChangePlanner().plan(new ChangePlanningInput(old.semanticModel(),old.semanticModel(),base,base,changeSet)));
    }
    @Test void samePathDeclarationReorderIsRejectedRatherThanRetargetedByName() throws Exception {
        var b=generate(prepared(temp,false),temp.resolve("output")).sources();String root=new String(b.bytes(ENTRY),java.nio.charset.StandardCharsets.UTF_8);int first=root.indexOf("    input GetCourseInput");int second=root.indexOf("    input CreateCourseInput");int end=root.indexOf("    input UpdateCourseInput");
        assertTrue(first>=0 && second>first && end>second);var reordered=root.substring(0,first)+root.substring(second,end)+root.substring(first,second)+root.substring(end);var values=copy(b);values.put(ENTRY,reordered.getBytes(java.nio.charset.StandardCharsets.UTF_8));var c=new SourceSnapshot(ENTRY,values);
        var failed=assertInstanceOf(ProjectChangeAnalysis.Failure.class,new ProjectChangePlanner().plan(input(b,c)));assertTrue(failed.diagnostics().stream().anyMatch(d->d.code().equals("SIR-PROJECT-CHANGE-SOURCE-001")),failed.toString());
    }
    @Test void recomputedDigestCannotLegitimizeForgedFileOrArtifactRecords() throws Exception {
        var b=generate(prepared(temp,false),temp.resolve("output")).sources();var in=input(b,modified(b,ENTRY));var planner=new ProjectChangePlanner();var p=assertInstanceOf(ProjectChangeAnalysis.Planned.class,planner.plan(in)).plan();var f=p.fileChanges().get(0);
        var altered=new FileChange(f.relativePath(),f.artifactId(),f.ownerSymbol(),f.baseByteCount(),f.baseSha256Hex(),f.candidateByteCount(),"0".repeat(64));
        var forged=new ProjectChangePlan(1,p.basedOn(),p.candidate(),p.operation(),p.artifactChanges(),List.of(altered));assertNotEquals(p.sha256Hex(),forged.sha256Hex());assertFalse(planner.verify(forged,in).isEmpty());
        var lostArtifact=new ProjectChangePlan(1,p.basedOn(),p.candidate(),p.operation(),List.of(),p.fileChanges());assertFalse(planner.verify(lostArtifact,in).isEmpty());
        assertThrows(IllegalArgumentException.class,()->new ProjectChangePlan(1,p.basedOn(),p.candidate(),p.operation(),p.artifactChanges(),List.of()));assertThrows(UnsupportedOperationException.class,()->p.fileChanges().clear());
        assertFalse(planner.verify(new ProjectChangePlan(2,p.basedOn(),p.candidate(),p.operation(),p.artifactChanges(),p.fileChanges()),in).isEmpty());
    }
}
