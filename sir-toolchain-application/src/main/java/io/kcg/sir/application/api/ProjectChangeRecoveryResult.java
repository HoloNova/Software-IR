package io.kcg.sir.application.api;

import java.util.*;

public sealed interface ProjectChangeRecoveryResult {
    List<ExecutionDiagnostic> diagnostics();
    enum Outcome { ROLLED_BACK,COMMITTED_AND_CLEANED,ALREADY_CLEAN }
    record Recovered(Outcome outcome,ProjectBaselineReceipt current,List<ExecutionDiagnostic> diagnostics) implements ProjectChangeRecoveryResult {
        public Recovered {Objects.requireNonNull(outcome);Objects.requireNonNull(current);diagnostics=List.copyOf(diagnostics);if(diagnostics.stream().anyMatch(ExecutionDiagnostic::isError))throw new IllegalArgumentException("success has ERROR");}
    }
    record Failure(ProjectRecoveryHandle handle,List<ExecutionDiagnostic> diagnostics) implements ProjectChangeRecoveryResult {
        public Failure {Objects.requireNonNull(handle);diagnostics=List.copyOf(diagnostics);if(diagnostics.stream().noneMatch(ExecutionDiagnostic::isError))throw new IllegalArgumentException("failure needs ERROR");}
        public FailureDisposition disposition() {return FailureDisposition.RECOVERY_REQUIRED;}
    }
}
