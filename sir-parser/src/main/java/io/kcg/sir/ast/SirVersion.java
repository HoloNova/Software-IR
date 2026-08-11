package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.Objects;

public record SirVersion(BigInteger major, BigInteger minor, SourceSpan span) {
    public SirVersion {
        Objects.requireNonNull(major, "major");
        Objects.requireNonNull(minor, "minor");
        Objects.requireNonNull(span, "span");
        if (major.signum() < 0 || minor.signum() < 0) {
            throw new IllegalArgumentException("version components must not be negative");
        }
    }
}
