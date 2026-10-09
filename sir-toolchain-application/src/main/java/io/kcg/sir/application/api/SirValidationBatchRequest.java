package io.kcg.sir.application.api;

import java.io.InputStream;
import java.io.IOException;
import java.util.Objects;

/** Caller-owned streams; batch processing neither closes them nor opens files. */
public record SirValidationBatchRequest(InputStream input, ResultSink sink) {
    public SirValidationBatchRequest { Objects.requireNonNull(input); Objects.requireNonNull(sink); }
    @FunctionalInterface public interface ResultSink { void accept(SirValidationResult result) throws IOException; }
}
