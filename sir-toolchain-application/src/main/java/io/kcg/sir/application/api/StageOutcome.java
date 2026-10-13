package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;

/**
 * What one stage (or one port) reports. {@code code} is a stable upper-case reason; PASSED is always
 * {@code OK}. Messages and diagnostics are bounded text that the producer has already redacted: the
 * report is evidence that gets stored and shared.
 */
public record StageOutcome(StageStatus status, String code, String message, List<String> diagnostics, List<LogRef> logs) {
    public static final int MAX_MESSAGE_CHARS = 2000;
    public static final int MAX_DIAGNOSTICS = 50;
    public static final int MAX_DIAGNOSTIC_CHARS = 1000;
    public static final int MAX_LOGS = 8;

    public StageOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        logs = List.copyOf(Objects.requireNonNull(logs, "logs"));
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("code must be an upper-case reason");
        }

        if ((status == StageStatus.PASSED) != code.equals("OK")) {
            throw new IllegalArgumentException("only a passed stage has the code OK");
        }

        if (message.length() > MAX_MESSAGE_CHARS || diagnostics.size() > MAX_DIAGNOSTICS || logs.size() > MAX_LOGS) {
            throw new IllegalArgumentException("stage outcome exceeds its budget");
        }

        for (String line : diagnostics) {
            if (line.length() > MAX_DIAGNOSTIC_CHARS) {
                throw new IllegalArgumentException("diagnostic line exceeds its budget");
            }
        }
    }

    public static StageOutcome passed(String message) {
        return new StageOutcome(StageStatus.PASSED, "OK", message, List.of(), List.of());
    }

    public static StageOutcome passed(String message, List<LogRef> logs) {
        return new StageOutcome(StageStatus.PASSED, "OK", message, List.of(), logs);
    }

    public static StageOutcome failed(String code, String message, List<String> diagnostics, List<LogRef> logs) {
        return new StageOutcome(StageStatus.FAILED, code, message, diagnostics, logs);
    }

    public static StageOutcome failed(String code, String message) {
        return failed(code, message, List.of(), List.of());
    }

    public static StageOutcome notRun(String code, String message) {
        return new StageOutcome(StageStatus.NOT_RUN, code, message, List.of(), List.of());
    }
}
