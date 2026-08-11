package io.kcg.sir.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.DiagnosticCode;
import io.kcg.sir.api.DiagnosticSeverity;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class DiagnosticCollector {
    private static final int MAX_DIAGNOSTICS = 50;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private boolean stopped;

    void error(String code, String message, SourceSpan span) {
        if (stopped) {
            return;
        }
        if (diagnostics.size() == MAX_DIAGNOSTICS - 1) {
            diagnostics.add(new Diagnostic(
                    new DiagnosticCode("SIR-PARSE-999"),
                    DiagnosticSeverity.ERROR,
                    "错误过多，停止继续分析",
                    span,
                    List.of(),
                    List.of()));
            stopped = true;
            return;
        }
        diagnostics.add(new Diagnostic(
                new DiagnosticCode(code),
                DiagnosticSeverity.ERROR,
                message,
                span,
                List.of(),
                List.of()));
    }

    boolean hasErrors() {
        return !diagnostics.isEmpty();
    }

    List<Diagnostic> diagnostics() {
        return diagnostics.stream()
                .sorted(Comparator.comparingInt(value -> value.primarySpan().start().codePointOffset()))
                .toList();
    }
}
