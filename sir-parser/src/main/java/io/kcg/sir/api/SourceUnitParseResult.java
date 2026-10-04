package io.kcg.sir.api;

import io.kcg.sir.ast.AstSourceUnit;
import java.util.*;

public record SourceUnitParseResult(Optional<AstSourceUnit> unit, List<Diagnostic> diagnostics) {
    public SourceUnitParseResult {
        unit = Objects.requireNonNull(unit); diagnostics = List.copyOf(diagnostics);
        if (unit.isPresent() == diagnostics.stream().anyMatch(Diagnostic::isError))
            throw new IllegalArgumentException("source unit presence must match parse success");
    }
    public boolean isSuccess() { return unit.isPresent(); }
}
