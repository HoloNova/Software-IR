package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.*;

/** Explicit recovery scope, also usable to identify retained preparation before a complete binding exists. */
public record ProjectRecoveryHandle(Path stateRoot,Path outputRoot,String transactionId,String baselineId,String candidateId,Optional<String> bindingDigest) {
    public ProjectRecoveryHandle {
        Objects.requireNonNull(stateRoot);Objects.requireNonNull(outputRoot);bindingDigest=Objects.requireNonNull(bindingDigest);
        if(!stateRoot.isAbsolute() || !stateRoot.equals(stateRoot.normalize()) || !outputRoot.isAbsolute() || !outputRoot.equals(outputRoot.normalize()))throw new IllegalArgumentException("recovery roots must be absolute normalized");
        for(String v:List.of(transactionId,baselineId,candidateId))hex(v);bindingDigest.ifPresent(ProjectRecoveryHandle::hex);
    }
    private static void hex(String v) {if(v==null || !v.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid project recovery evidence");}
}
