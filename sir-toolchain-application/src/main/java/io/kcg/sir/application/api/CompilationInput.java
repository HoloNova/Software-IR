package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * The exact source bytes of one software, and which kind of source it is.
 *
 * <p>The kind is stated, never guessed from the bytes: a single file is read by the legacy entry
 * (which keeps its older version rules) and a project is read by the multi-file entry (SIR 0.2
 * only). A single file is just a one-member snapshot, so both kinds travel the same way.
 */
public record CompilationInput(Kind kind, SourceSnapshot sources) {
    public enum Kind { SINGLE_FILE, PROJECT }

    public CompilationInput {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sources, "sources");
        if (kind == Kind.SINGLE_FILE && sources.manifest().files().size() != 1) {
            throw new IllegalArgumentException("a single-file input has exactly one source");
        }
    }

    public static CompilationInput singleFile(SourceId id, String text) {
        Objects.requireNonNull(text, "text");
        return new CompilationInput(Kind.SINGLE_FILE, new SourceSnapshot(id, Map.of(id, text.getBytes(StandardCharsets.UTF_8))));
    }

    public static CompilationInput project(SourceSnapshot sources) {
        return new CompilationInput(Kind.PROJECT, sources);
    }
}
