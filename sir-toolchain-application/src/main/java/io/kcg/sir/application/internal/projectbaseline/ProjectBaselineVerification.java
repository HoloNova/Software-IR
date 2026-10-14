package io.kcg.sir.application.internal.projectbaseline;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.*;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Compiles a source set into a candidate version (its identity and generated files) and checks the generated project on disk against it. */
public final class ProjectBaselineVerification {
    private ProjectBaselineVerification() {}
    public record Verified(Bundle bundle,ProjectCompilation.Compiled compilation) {}
    public static Verified compile(SourceSnapshot sources,Path output,List<ExecutionDiagnostic> diagnostics) throws IOException {
        var compiled=ProjectCompilation.compile(sources,new SpringBootGenerator()::generate,diagnostics);
        if(compiled.isEmpty())return null;var c=compiled.get();
        var graph=new ProjectGraphBuilder().build(new SpringBootProjectGraphInputFactory().build(c.semanticModel(),c.loweredModel(),c.generatedFiles(),sources));
        for(var d:graph.diagnostics())diagnostics.add(new ExecutionDiagnostic(d.code(),ExecutionStage.GRAPH,d.isError()?ExecutionSeverity.ERROR:ExecutionSeverity.valueOf(d.severity().name()),d.message(),Optional.empty(),Optional.empty(),Optional.empty()));
        if(!(graph instanceof ProjectGraphAnalysis.Success ok))return null;
        return new Verified(ProjectBaselineCodec.build(sources,c,ok.graph(),output),c);
    }
    public static void verifyOutput(Bundle bundle) throws IOException {
        try(var output=new SecureFileAccess(bundle.descriptor().outputRoot())) {
            for(var entry:bundle.descriptor().manifest()) {
                byte[] bytes=output.read(entry.relativePath(),(int)entry.byteCount());
                if(bytes.length!=entry.byteCount() || !Sha256.hexDigest(bytes).equals(entry.sha256Hex()))throw problem("OUTPUT-001","tracked file differs from candidate: "+entry.relativePath());
            }
            output.assertRootUnchanged();
        }
    }
}
