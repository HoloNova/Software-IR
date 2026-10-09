package io.kcg.sir.application.api;

/** Untrusted sample input. Validation, rather than construction, reports malformed values. */
public record SirValidationRequest(String id, String sir, ValidationStopAfter stopAfter) {
    public SirValidationRequest(String id, String sir) { this(id, sir, ValidationStopAfter.GENERATION); }
}
