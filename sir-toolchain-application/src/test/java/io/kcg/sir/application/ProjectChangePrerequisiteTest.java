package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.*;
import io.kcg.sir.change.internal.*;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.semantic.model.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Q20 preflight: real compilation evidence, not a claimed public project plan. */
class ProjectChangePrerequisiteTest {
    @TempDir Path temp;
    static final String NAME="SearchCourseEnrollments";
    static Map<SourceId,byte[]> copy(SourceSnapshot snapshot) {
        var result=new LinkedHashMap<SourceId,byte[]>();snapshot.manifest().files().forEach(f->result.put(f.sourceId(),snapshot.bytes(f.sourceId())));return result;
    }
    static SourceSnapshot replace(SourceSnapshot snapshot,SourceId source,String from,String to) {
        var bytes=copy(snapshot);String old=new String(bytes.get(source),StandardCharsets.UTF_8);
        assertTrue(old.contains(from),from);bytes.put(source,old.replace(from,to).getBytes(StandardCharsets.UTF_8));return new SourceSnapshot(snapshot.entry(),bytes);
    }
    static SourceId capabilitySource(boolean fragment) { return fragment?SourceId.of("modules/query.sir"):ENTRY; }
    static Path prepared(Path root,boolean fragment) throws Exception {
        var source=fixture(root);
        if(fragment) {
            var path=source.resolve("project.sir");String text=Files.readString(path);int start=text.indexOf("    capability "+NAME);int open=text.indexOf('{',start),depth=1,end=open+1;
            while(depth>0) {char c=text.charAt(end++);if(c=='{')depth++;if(c=='}')depth--;}
            String declaration=text.substring(start,end);
            String rest=text.substring(0,start)+text.substring(end);
            rest=rest.replace("sources {","sources {\n    source \"modules/query.sir\";");Files.writeString(path,rest);
            Files.writeString(source.resolve("modules/query.sir"),"sir 0.2\nimports {\n"
                +"import Course from \"modules/course.sir\";\nimport Enrollment from \"modules/enrollment.sir\";\nimport EnrollmentStatus from \"modules/enrollment.sir\";\n"
                +"import SearchCourseEnrollmentsInput from \"project.sir\";\nimport CourseEnrollmentItem from \"project.sir\";\nimport InvalidPage from \"project.sir\";\n}\ndeclarations {\n"+declaration+"\n}\n");
        }
        return source;
    }
    static SirCompilation.CompilationSnapshot compile(SourceSnapshot s) {
        var diagnostics=new ArrayList<ExecutionDiagnostic>();var result=ProjectCompilation.compile(s,new SpringBootGenerator()::generate,diagnostics);
        assertTrue(result.isPresent(),diagnostics.toString());return result.orElseThrow();
    }
    static ProjectGraph graph(SourceSnapshot s,SirCompilation.CompilationSnapshot c) {
        return assertInstanceOf(ProjectGraphAnalysis.Success.class,new ProjectGraphBuilder().build(new SpringBootProjectGraphInputFactory().build(c.semanticModel(),c.loweredModel(),c.generatedFiles(),s))).graph();
    }
    static NormalizedCapability capability(SirCompilation.CompilationSnapshot c) {
        return c.semanticModel().declarations().stream().filter(d->d.name().equals(NAME)).map(d->(NormalizedCapability)d).findFirst().orElseThrow();
    }
    @Test void rootAndFragmentWorkflowChangesHaveExactUpdateClosureWithoutOriginalSources() throws Exception {
        for(boolean fragment:List.of(false,true)) {
            var root=Files.createDirectory(temp.resolve(fragment?"fragment":"entry"));var source=prepared(root,fragment);var output=root.resolve("output");var generated=generate(source,output);
            var registered=assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(),output,root.resolve("state"))));
            Files.move(source,root.resolve("unavailable"));var before=tree(root);
            var reopened=assertInstanceOf(ProjectBaselineResult.Success.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(root.resolve("state"),output,registered.receipt().baselineId())));
            var candidate=replace(reopened.sources(),capabilitySource(fragment),"status == EnrollmentStatus.ACTIVE","(status == EnrollmentStatus.ACTIVE or status == EnrollmentStatus.CANCELLED)");
            var b=compile(reopened.sources());var c=compile(candidate);var bc=capability(b);var cc=capability(c);
            assertEquals(bc.id(),cc.id());assertEquals(capabilitySource(fragment),bc.span().source());
            assertEquals(bc.inputSymbol(),cc.inputSymbol());assertEquals(bc.outputType(),cc.outputType());
            assertEquals(bc.fails(),cc.fails());assertEquals(bc.requires(),cc.requires());assertEquals(bc.exposure(),cc.exposure());
            for(var decl:b.semanticModel().declarations()) if(!decl.id().equals(bc.id())) {
                var other=c.semanticModel().declarations().stream().filter(d->d.id().equals(decl.id())).findFirst().orElseThrow();
                assertEquals(decl.id(),other.id());assertEquals(decl.name(),other.name());
            }
            var baseGraph=graph(reopened.sources(),b);var candidateGraph=graph(candidate,c);
            var closure=assertInstanceOf(ClosureComputer.ClosureResult.Success.class,ClosureComputer.compute(baseGraph,bc.id())).closure();
            var allowed=closure.files().stream().map(f->f.id().relativePath()).collect(java.util.stream.Collectors.toSet());
            var old=new TreeMap<String,String>();b.generatedFiles().forEach(f->old.put(f.relativePath(),f.content()));
            var fresh=new TreeMap<String,String>();c.generatedFiles().forEach(f->fresh.put(f.relativePath(),f.content()));
            assertEquals(old.keySet(),fresh.keySet());var diff=old.keySet().stream().filter(p->!old.get(p).equals(fresh.get(p))).toList();
            assertFalse(diff.isEmpty());assertTrue(allowed.containsAll(diff),diff.toString());
            System.out.println("Q20-P1 source="+capabilitySource(fragment).value()+" UPDATE="+diff);
            assertNotEquals(baseGraph.canonicalDigest(),candidateGraph.canonicalDigest());assertEquals(before,tree(root));
        }
    }
    @Test void commentChangesAllSourceEvidenceButNotSemanticOrGeneratedBytes() throws Exception {
        var generated=generate(prepared(temp,false),temp.resolve("output"));var snapshot=generated.sources();
        var candidate=replace(snapshot,SourceId.of("modules/course.sir"),"sir 0.2","sir 0.2\n// changed comment");var b=compile(snapshot);var c=compile(candidate);
        assertNotEquals(snapshot.sha256Hex(),candidate.sha256Hex());assertEquals(b.generatedFiles(),c.generatedFiles());
        assertEquals(b.semanticModel().declarations().stream().map(NormalizedDeclaration::id).toList(),c.semanticModel().declarations().stream().map(NormalizedDeclaration::id).toList());
        assertNotEquals(graph(snapshot,b).canonicalDigest(),graph(candidate,c).canonicalDigest());
    }
}
