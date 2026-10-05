package io.kcg.sir.application.api;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.application.internal.state.*;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.SourceSnapshot;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Explicit project storage API. No Change/Rename/apply or implicit recovery. */
public final class ProjectBaselineApplication {
    private final Consumer<String> checkpoint;
    public ProjectBaselineApplication() { this(ignored->{}); }
    ProjectBaselineApplication(Consumer<String> checkpoint) { this.checkpoint=Objects.requireNonNull(checkpoint); }

    public ProjectBaselineResult register(ProjectBaselineRegistrationRequest request) {
        Objects.requireNonNull(request);var diagnostics=new ArrayList<ExecutionDiagnostic>();boolean saving=false;
        try {
            validateRequestPaths(request.stateRoot(),request.outputRoot(),false,diagnostics);
            if (hasErrors(diagnostics)) return failed(diagnostics,FailureDisposition.NO_CHANGES);
            var candidate=compile(request.sources(),request.outputRoot(),diagnostics);
            if (candidate==null) return failed(diagnostics,FailureDisposition.NO_CHANGES);
            verifyOutput(candidate);
            Files.createDirectories(request.stateRoot());
            try (var state=new SecureFileAccess(request.stateRoot())) {
                if (!state.exists("LOCK")) state.writeNew("LOCK",new byte[0],ignored->{});
                var locked=new StateRootLock(request.stateRoot()).tryAcquireExisting();
                if (!(locked instanceof StateRootLock.HeldLock held)) return lockFailure((StateRootLock.LockFailure)locked);
                try (held) {
                    var store=new ProjectBaselineStore(state);saving=true;
                    boolean fresh=store.publish(candidate,b->verifyOutput(b),checkpoint);
                    return success(fresh?ProjectBaselineResult.Outcome.REGISTERED:ProjectBaselineResult.Outcome.ALREADY_REGISTERED,candidate,diagnostics);
                }
            }
        } catch (Problem e) {
            diagnostics.add(ExecutionDiagnostic.error(e.code,saving?ExecutionStage.WRITE:ExecutionStage.READ,e.getMessage()));
            return failed(diagnostics,saving?FailureDisposition.RECOVERY_REQUIRED:FailureDisposition.NO_CHANGES);
        } catch (IOException|IllegalArgumentException|SecurityException e) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-PROJECT-BASELINE-"+(saving?"PUBLISH-001":"READ-001"),saving?ExecutionStage.WRITE:ExecutionStage.READ,
                "project baseline refused; retained state is not automatically deleted: "+e.getMessage()));
            return failed(diagnostics,saving?FailureDisposition.RECOVERY_REQUIRED:FailureDisposition.NO_CHANGES);
        } catch (RuntimeException e) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-PROJECT-BASELINE-PUBLISH-001",ExecutionStage.WRITE,"registration interrupted: "+e.getMessage()));
            return failed(diagnostics,saving?FailureDisposition.RECOVERY_REQUIRED:FailureDisposition.NO_CHANGES);
        }
    }

    public ProjectBaselineResult inspect(ProjectBaselineInspectionRequest request) {
        Objects.requireNonNull(request);var diagnostics=new ArrayList<ExecutionDiagnostic>();
        try {
            try { hex(request.expectedBaselineId()); } catch (IllegalArgumentException e) { throw problem("REQUEST-001","expectedBaselineId must be lowercase SHA-256 hex"); }
            validateRequestPaths(request.stateRoot(),request.outputRoot(),true,diagnostics);
            if (hasErrors(diagnostics)) return failed(diagnostics,FailureDisposition.NO_CHANGES);
            var locked=new StateRootLock(request.stateRoot()).tryAcquireExisting();
            if (!(locked instanceof StateRootLock.HeldLock held)) return lockFailure((StateRootLock.LockFailure)locked);
            try (held;var state=new SecureFileAccess(request.stateRoot())) {
                var store=new ProjectBaselineStore(state);var current=store.current();
                if (!current.equals(Optional.of(request.expectedBaselineId()))) throw problem("REQUEST-001","CURRENT does not match explicit expected baseline");
                store.guardState(request.expectedBaselineId(),false);
                var saved=store.load(request.expectedBaselineId());store.verifyPointerIdentity(saved.baselineId(),false);
                if (!saved.descriptor().outputRoot().equals(request.outputRoot())) throw problem("REQUEST-001","baseline bound to another outputRoot");
                var replay=compile(saved.sources(),request.outputRoot(),diagnostics);
                if (replay==null) return failed(diagnostics,FailureDisposition.NO_CHANGES);
                require(Arrays.equals(saved.descriptorBytes(),replay.descriptorBytes()) && Arrays.equals(saved.graphBytes(),replay.graphBytes()),"saved graph/target/manifest disagrees with source-byte recompilation");
                verifyOutput(replay);
                require(store.current().equals(current),"CURRENT changed during inspection");store.guardState(saved.baselineId(),false);store.verifyPointerIdentity(saved.baselineId(),false);state.assertRootUnchanged();
                return success(ProjectBaselineResult.Outcome.VERIFIED,saved,diagnostics);
            }
        } catch (Problem e) {
            diagnostics.add(ExecutionDiagnostic.error(e.code,ExecutionStage.READ,e.getMessage()));return failed(diagnostics,FailureDisposition.NO_CHANGES);
        } catch (IOException|RuntimeException e) {
            diagnostics.add(ExecutionDiagnostic.error("SIR-APP-PROJECT-BASELINE-READ-001",ExecutionStage.READ,"inspection refused without mutation: "+e.getMessage()));
            return failed(diagnostics,FailureDisposition.NO_CHANGES);
        }
    }

    private static Bundle compile(SourceSnapshot sources,Path output,List<ExecutionDiagnostic> diagnostics) throws IOException {
        var compiled=ProjectCompilation.compile(sources,new SpringBootGenerator()::generate,diagnostics);
        if (compiled.isEmpty()) return null;
        var c=compiled.get();var graph=new ProjectGraphBuilder().build(new SpringBootProjectGraphInputFactory().build(c.semanticModel(),c.loweredModel(),c.generatedFiles(),sources));
        for (var d : graph.diagnostics()) diagnostics.add(new ExecutionDiagnostic(d.code(),ExecutionStage.GRAPH,
            d.isError()?ExecutionSeverity.ERROR:ExecutionSeverity.valueOf(d.severity().name()),d.message(),Optional.empty(),Optional.empty(),Optional.empty()));
        if (!(graph instanceof ProjectGraphAnalysis.Success ok)) return null;
        return ProjectBaselineCodec.build(sources,c,ok.graph(),output);
    }
    private static void verifyOutput(Bundle bundle) throws IOException {
        try (var output=new SecureFileAccess(bundle.descriptor().outputRoot())) {
            for (var entry : bundle.descriptor().manifest()) {
                byte[] bytes=output.read(entry.relativePath(),(int)entry.byteCount());
                if (bytes.length!=entry.byteCount() || !Sha256.hexDigest(bytes).equals(entry.sha256Hex())) throw problem("OUTPUT-001","tracked file differs from candidate: "+entry.relativePath());
            }
            output.assertRootUnchanged();
        }
    }
    private static void validateRequestPaths(Path state,Path output,boolean existing,List<ExecutionDiagnostic> diagnostics) throws IOException {
        if (!state.isAbsolute() || !state.equals(state.normalize()) || !output.isAbsolute() || !output.equals(output.normalize())) throw problem("REQUEST-001","state/output roots must be absolute normalized");
        var guard=StateRootPathGuard.validate(state,output,existing);
        for (var d : guard.errors()) diagnostics.add(new ExecutionDiagnostic(d.code(),ExecutionStage.PREFLIGHT,d.severity(),d.message(),Optional.empty(),Optional.empty(),Optional.empty()));
    }
    private static ProjectBaselineResult.Success success(ProjectBaselineResult.Outcome outcome,Bundle bundle,List<ExecutionDiagnostic> diagnostics) {
        var d=bundle.descriptor();return new ProjectBaselineResult.Success(outcome,new ProjectBaselineReceipt(bundle.baselineId(),d.outputRoot(),d.entry(),d.sourceSetSha(),d.graphDigest(),d.manifestDigest()),bundle.sources(),bundle.graph(),diagnostics);
    }
    private static boolean hasErrors(List<ExecutionDiagnostic> diagnostics) { return diagnostics.stream().anyMatch(ExecutionDiagnostic::isError); }
    private static ProjectBaselineResult.Failure failed(List<ExecutionDiagnostic> diagnostics,FailureDisposition disposition) {
        var stage=diagnostics.stream().filter(ExecutionDiagnostic::isError).reduce((a,b)->b).orElseThrow().stage();return new ProjectBaselineResult.Failure(stage,disposition,diagnostics);
    }
    private static ProjectBaselineResult.Failure lockFailure(StateRootLock.LockFailure failure) {
        return new ProjectBaselineResult.Failure(ExecutionStage.PREFLIGHT,FailureDisposition.NO_CHANGES,
            List.of(ExecutionDiagnostic.error(failure.code(),ExecutionStage.PREFLIGHT,failure.message())));
    }
}
