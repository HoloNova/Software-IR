package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Complete Q20 decision and candidate; never a single-source ChangePlan request. */
public record ProjectChangeApplyRequest(ProjectChangePlanningRequest planning,ProjectChangePlanningResult expected,String transactionId) {
    public ProjectChangeApplyRequest {
        Objects.requireNonNull(planning);Objects.requireNonNull(expected);
        if(!(expected instanceof ProjectChangePlanningResult.Planned) && !(expected instanceof ProjectChangePlanningResult.NoChanges))throw new IllegalArgumentException("apply requires Planned or NoChanges evidence");
        if(transactionId==null || !transactionId.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid project transaction ID");
    }
    public ProjectChangeApplyRequest(ProjectChangePlanningRequest planning,ProjectChangePlanningResult expected) {
        this(planning,expected,Sha256.hexDigest(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII)));
    }
}
