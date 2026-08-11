package io.kcg.sir.api;

import io.kcg.sir.ast.AstDocument;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ParseResult(
        ParseStatus status,
        Optional<AstDocument> document,
        List<Diagnostic> diagnostics) {

    public ParseResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(document, "document");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));

        boolean hasError = diagnostics.stream().anyMatch(Diagnostic::isError);
        switch (status) {
            case SUCCESS -> {
                if (document.isEmpty()) {
                    throw new IllegalArgumentException("successful parse result must contain a document");
                }
                if (hasError) {
                    throw new IllegalArgumentException("successful parse result must not contain error diagnostics");
                }
            }
            case FAILED -> {
                if (document.isPresent()) {
                    throw new IllegalArgumentException("failed parse result must not contain a document");
                }
                if (!hasError) {
                    throw new IllegalArgumentException("failed parse result must contain at least one error diagnostic");
                }
            }
        }
    }

    public static ParseResult success(AstDocument document, List<Diagnostic> diagnostics) {
        return new ParseResult(
                ParseStatus.SUCCESS,
                Optional.of(Objects.requireNonNull(document, "document")),
                diagnostics);
    }

    public static ParseResult failure(List<Diagnostic> diagnostics) {
        return new ParseResult(ParseStatus.FAILED, Optional.empty(), diagnostics);
    }

    public boolean isSuccess() {
        return status == ParseStatus.SUCCESS;
    }
}
