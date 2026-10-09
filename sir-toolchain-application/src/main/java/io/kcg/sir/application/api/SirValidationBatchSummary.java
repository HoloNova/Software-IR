package io.kcg.sir.application.api;

/** Process-level status, not rendered into the sample JSONL stream. */
public record SirValidationBatchSummary(long lineCount, boolean internalFailure) {}
