package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceSnapshot;
import java.util.Objects;

public record ProjectChangePlanningRequest(ProjectChangeContext context,SourceSnapshot candidate,String targetKey) {
    public ProjectChangePlanningRequest {
        Objects.requireNonNull(context);Objects.requireNonNull(candidate);Objects.requireNonNull(targetKey);
        if(!targetKey.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid workflow target key");
    }
}
