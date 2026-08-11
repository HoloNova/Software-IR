package io.kcg.sir.lowering.api;

public enum LoweringSeverity {
    ERROR,
    WARNING,
    INFO;

    public boolean isError() {
        return this == ERROR;
    }
}
