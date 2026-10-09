package io.kcg.sir.application.api;

import io.kcg.sir.change.api.NoChangeReason;
import java.util.*;

/** Publication and cleanup are separate observable outcomes; no failure after writes claims NO_CHANGES. */
public sealed interface ProjectChangeApplyResult {
    List<ExecutionDiagnostic> diagnostics();
    enum Publication { NOT_PUBLISHED,PUBLISHED,UNKNOWN }
    record Applied(ProjectBaselineReceipt baseline,ProjectBaselineReceipt candidate,List<ExecutionDiagnostic> diagnostics) implements ProjectChangeApplyResult {
        public Applied {Objects.requireNonNull(baseline);Objects.requireNonNull(candidate);diagnostics=clean(diagnostics);}
    }
    record NoChanges(ProjectChangeContext context,NoChangeReason reason,List<ExecutionDiagnostic> diagnostics) implements ProjectChangeApplyResult {
        public NoChanges {Objects.requireNonNull(context);Objects.requireNonNull(reason);diagnostics=clean(diagnostics);}
    }
    record Failure(ExecutionStage stage,List<ExecutionDiagnostic> diagnostics) implements ProjectChangeApplyResult {
        public Failure {Objects.requireNonNull(stage);diagnostics=failed(diagnostics);}
        public FailureDisposition disposition() {return FailureDisposition.NO_CHANGES;}
    }
    record RecoveryRequired(ProjectRecoveryHandle handle,Publication publication,List<ExecutionDiagnostic> diagnostics) implements ProjectChangeApplyResult {
        public RecoveryRequired {Objects.requireNonNull(handle);Objects.requireNonNull(publication);diagnostics=failed(diagnostics);}
        public FailureDisposition disposition() {return FailureDisposition.RECOVERY_REQUIRED;}
    }
    private static List<ExecutionDiagnostic> clean(List<ExecutionDiagnostic> d) {var copy=List.copyOf(d);if(copy.stream().anyMatch(ExecutionDiagnostic::isError))throw new IllegalArgumentException("success has ERROR");return copy;}
    private static List<ExecutionDiagnostic> failed(List<ExecutionDiagnostic> d) {var copy=List.copyOf(d);if(copy.stream().noneMatch(ExecutionDiagnostic::isError))throw new IllegalArgumentException("failure needs ERROR");return copy;}
}
