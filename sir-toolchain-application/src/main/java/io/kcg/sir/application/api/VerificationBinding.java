package io.kcg.sir.application.api;

import java.util.Objects;

/**
 * Which candidate a report is about. It is the same identity a stored version carries
 * ({@code sourceSetSha256} and {@code manifestDigest}, see {@link ProjectBaselineReceipt}), so a report can be
 * matched to the version that would be published without trusting a caller's say-so.
 */
public record VerificationBinding(String sourceSetSha256, String manifestDigest, String generatedDigest, int fileCount,
        String targetId, String loweredIrVersion, CompilationInput.Kind inputKind) {
    public VerificationBinding {
        for (String digest : new String[]{sourceSetSha256, manifestDigest, generatedDigest}) {
            if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid digest");
            }
        }

        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(loweredIrVersion, "loweredIrVersion");
        Objects.requireNonNull(inputKind, "inputKind");
        if (fileCount < 1) {
            throw new IllegalArgumentException("a generated project has at least one file");
        }
    }
}
