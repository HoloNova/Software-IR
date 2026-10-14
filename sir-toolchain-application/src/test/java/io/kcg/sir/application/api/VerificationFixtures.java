package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** A real verification run (static generation for real) with ports above it that pass or fail as told. */
final class VerificationFixtures {
    private VerificationFixtures() {
    }

    static VerificationReport verify(Path temp, AtomicInteger counter, SourceSnapshot candidate, VerificationStage through, boolean businessPasses, boolean cleanIsProved) {
        var sandbox = new VerificationPorts.Sandbox() {
            @Override public Lease open(boolean needsDatabase) {
                Path dir = temp.resolve("verify-" + counter.incrementAndGet());
                try {
                    Files.createDirectories(dir.resolve("candidate"));
                    Files.createDirectories(dir.resolve("evidence"));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }

                return new Lease() {
                    @Override public Path candidateRoot() { return dir.resolve("candidate"); }
                    @Override public Path evidenceRoot() { return dir.resolve("evidence"); }
                    @Override public Map<String, String> applicationEnvironment() { return Map.of(); }
                    @Override public CleanupProof release() { return cleanIsProved ? CleanupProof.proved("removed") : CleanupProof.notProved("left behind"); }
                };
            }
            @Override public Map<String, String> environment() { return Map.of(); }
        };
        VerificationPorts.Builder builder = new VerificationPorts.Builder() {
            @Override public VerificationPorts.BuildOutcome build(Path root, Path evidence, ResourceLimits limits) {
                try {
                    Files.createDirectories(root.resolve("target"));
                    Files.writeString(root.resolve("target/app.jar"), "jar");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }

                return new VerificationPorts.BuildOutcome(StageOutcome.passed("built"), Optional.of(root.resolve("target/app.jar")));
            }
            @Override public Map<String, String> environment() { return Map.of(); }
        };
        VerificationPorts.Host host = new VerificationPorts.Host() {
            @Override public VerificationPorts.StartOutcome start(Path artifact, Map<String, String> env, Path evidence, ResourceLimits limits) {
                return new VerificationPorts.StartOutcome(StageOutcome.passed("ready"), Optional.of(new VerificationPorts.Running() {
                    @Override public URI baseUrl() { return URI.create("http://127.0.0.1:1"); }
                    @Override public VerificationPorts.Stopped stop() { return new VerificationPorts.Stopped(CleanupProof.proved("exited"), List.of()); }
                }));
            }
            @Override public Map<String, String> environment() { return Map.of(); }
        };
        VerificationPorts.Oracle oracle = new VerificationPorts.Oracle() {
            @Override public String revision() { return "TEST_PACK_V1"; }
            @Override public StageOutcome check(URI baseUrl, Path evidence, ResourceLimits limits) {
                return businessPasses ? StageOutcome.passed("holds") : StageOutcome.failed("BUSINESS_ASSERTION_FAILED", "wrong rows");
            }
        };
        return new CandidateVerificationApplication(sandbox, builder, host, oracle, ResourceLimits.defaults())
                .verify(new VerificationRequest("candidate", candidate, through));
    }
}
