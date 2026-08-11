package io.kcg.sir.api;

import io.kcg.sir.source.SourceId;
import java.util.Objects;

public record SirSource(SourceId id, String content) {
    public SirSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
    }
}
