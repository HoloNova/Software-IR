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

/** Shared project byte replay/verification. Caller owns the existing lock; never publishes or cleans state. */
public final class ProjectBaselineVerification {
    private ProjectBaselineVerification() {}
    public record Verified(Bundle bundle,SirCompilation.CompilationSnapshot compilation) {}
    public static Verified compile(SourceSnapshot sources,Path output,List<ExecutionDiagnostic> diagnostics) throws IOException {
        var compiled=ProjectCompilation.compile(sources,new SpringBootGenerator()::generate,diagnostics);
        if(compiled.isEmpty())return null;var c=compiled.get();
        var graph=new ProjectGraphBuilder().build(new SpringBootProjectGraphInputFactory().build(c.semanticModel(),c.loweredModel(),c.generatedFiles(),sources));
        for(var d:graph.diagnostics())diagnostics.add(new ExecutionDiagnostic(d.code(),ExecutionStage.GRAPH,d.isError()?ExecutionSeverity.ERROR:ExecutionSeverity.valueOf(d.severity().name()),d.message(),Optional.empty(),Optional.empty(),Optional.empty()));
        if(!(graph instanceof ProjectGraphAnalysis.Success ok))return null;
        return new Verified(ProjectBaselineCodec.build(sources,c,ok.graph(),output),c);
    }
    public static Verified read(SecureFileAccess state,String expected,Path output,List<ExecutionDiagnostic> diagnostics) throws IOException {
        hex(expected);var store=new ProjectBaselineStore(state);var current=store.current();
        if(!current.equals(Optional.of(expected)))throw problem("REQUEST-001","CURRENT does not match explicit expected baseline");
        store.guardState(expected,false);var saved=store.load(expected);store.verifyPointerIdentity(expected,false);
        if(!saved.descriptor().outputRoot().equals(output))throw problem("REQUEST-001","baseline bound to another outputRoot");
        var replay=compile(saved.sources(),output,diagnostics);if(replay==null)return null;
        require(Arrays.equals(saved.descriptorBytes(),replay.bundle().descriptorBytes()) && Arrays.equals(saved.graphBytes(),replay.bundle().graphBytes()),"saved graph/target/manifest disagrees with source-byte recompilation");
        verifyOutput(replay.bundle());revalidate(state,saved);
        return new Verified(saved,replay.compilation());
    }
    public static void revalidate(SecureFileAccess state,Bundle original) throws IOException {
        var store=new ProjectBaselineStore(state);String id=original.baselineId();
        require(store.current().equals(Optional.of(id)),"CURRENT changed during inspection/planning");
        store.guardState(id,false);store.verifyPointerIdentity(id,false);var fresh=store.load(id);
        require(Arrays.equals(original.descriptorBytes(),fresh.descriptorBytes()) && Arrays.equals(original.payloadBytes(),fresh.payloadBytes()) && Arrays.equals(original.graphBytes(),fresh.graphBytes()),"Bundle changed during inspection/planning");
        verifyOutput(original);state.assertRootUnchanged();
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
