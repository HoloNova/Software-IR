package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.SirValidationApplication;
import io.kcg.sir.application.api.SirValidationRequest;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Candidate input must replace the reference, never silently fall back to it. No DB/app is started. */
class DualTokenCandidateSupportTest {
    @TempDir Path root;
    private static final String REFERENCE = "/valid/dual-token-written.sir";

    @Test void absentCandidatePropertiesRetainTheOrdinaryReferenceMode() throws Exception {
        assertTrue(DualTokenCandidateSupport.read(name -> null).isEmpty());
    }

    @Test void partialCandidateConfigurationCannotTriggerReferenceFallback() {
        assertThrows(Exception.class, () -> DualTokenCandidateSupport.read(
                Map.of("kcg.slm.bundle-root", root.toString())::get));
        assertThrows(Exception.class, () -> DualTokenCandidateSupport.read(
                Map.of("kcg.slm.candidate-root", "relative", "kcg.slm.bundle-root", root.toString(),
                        "kcg.slm.repo-root", root.toString())::get));
    }

    @Test void externalSourceIsMaterializedAndItsActualFingerprintIsReported() throws Exception {
        String reference = ConformanceFixtures.readResource(REFERENCE);
        String candidate = reference.replace("15 minutes", "16 minutes");
        var snapshot = snapshot(candidate);
        var harness = harness();

        var files = harness.compileAndMaterialize(snapshot);

        assertFalse(files.isEmpty());
        assertTrue(harness.report().contains("EXTERNAL_CANDIDATE:candidate.sir"));
        assertTrue(harness.report().contains(ConformanceFixtures.sha256Hex(candidate.getBytes(StandardCharsets.UTF_8))));
        assertNotEquals(new SirValidationApplication().check(new SirValidationRequest("reference", reference)).digest(),
                new SirValidationApplication().check(new SirValidationRequest("candidate", candidate)).digest(),
                "the static-valid wrong TTL candidate must not be replaced with the golden reference");
        assertTrue(harness.report().contains("generatedProjectBuild=NOT_RUN"));
        assertTrue(harness.report().contains("httpReadiness=NOT_RUN"));
    }

    @Test void completeMetadataBindsTheActualSourceAndAllowsNullableProviderMetadata() throws Exception {
        var source = snapshot(ConformanceFixtures.readResource(REFERENCE));
        var manifest = Map.<String, Object>of("task_sha256", "a".repeat(64),
                "oracle_sha256", "b".repeat(64), "toolchain_core_sha256", "c".repeat(64));
        var metadata = new java.util.LinkedHashMap<String, Object>(manifest);
        metadata.put("id", "auth-candidate-1");
        metadata.put("schema_version", "sir-candidate/1");
        metadata.put("task_revision", "1");
        metadata.put("source_sha256", ConformanceFixtures.sha256Hex(source.bytes(source.entry())));
        metadata.put("system_fingerprint", null);
        var input = DualTokenCandidateSupport.bind(source, metadata, manifest);
        assertTrue(input.staticCheck().ok());
        assertEquals("auth-candidate-1", input.staticCheck().id());
        assertThrows(UnsupportedOperationException.class, () -> input.candidate().put("id", "changed"));
        assertEquals("auth-candidate-1", metadata.get("id"));
    }

    @Test void sourceAndMetadataMismatchIsRejectedBeforeAnyRuntimeWork() throws Exception {
        var source = snapshot(ConformanceFixtures.readResource(REFERENCE));
        assertThrows(IllegalArgumentException.class, () -> DualTokenCandidateSupport.bind(source,
                Map.of("source_sha256", "0".repeat(64)), Map.of()));
    }

    @Test void invalidExternalSourceDoesNotFallBackOrLeaveAProject() throws Exception {
        var harness = harness();
        assertTrue(harness.compileAndMaterialize(snapshot("sir 0.3\nsoftware Bad { nonsense }\n")).isEmpty());
        assertFalse(harness.passed());
        assertFalse(Files.exists(harness.projectRoot()));
    }

    @Test void compilationOnlyCanNeverProduceBusinessPassedEvidence() throws Exception {
        var source = snapshot(ConformanceFixtures.readResource(REFERENCE));
        var manifest = Map.<String, Object>of("task_sha256", "a".repeat(64),
                "oracle_sha256", "b".repeat(64), "toolchain_core_sha256", "c".repeat(64));
        var metadata = new java.util.LinkedHashMap<String, Object>(manifest);
        metadata.putAll(Map.of("id", "static-only", "schema_version", "sir-candidate/1", "task_revision", "1",
                "source_sha256", ConformanceFixtures.sha256Hex(source.bytes(source.entry()))));
        var input = DualTokenCandidateSupport.bind(source, metadata, manifest);
        var harness = harness();
        var files = harness.compileAndMaterialize(source);
        DualTokenCandidateSupport.verifyGenerated(input, harness, files);
        harness.writeReport();
        DualTokenCandidateSupport.writeEvidence(input, harness);
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                harness.evidenceRoot().resolve("slm-runtime.json").toFile());
        assertEquals("PASSED", evidence.path("gates").path("static").asText());
        for (String gate : java.util.List.of("build", "start", "business"))
            assertEquals("NOT_RUN", evidence.path("gates").path(gate).asText(), gate);
        assertEquals(input.staticCheck().digest(), evidence.path("generated_digest").asText());
        assertFalse(evidence.path("artifacts").isEmpty());
    }

    @Test void checkerAndRuntimeCompilationShareTheExactGenerationDigest() throws Exception {
        String source = ConformanceFixtures.readResource(REFERENCE);
        var files = harness().compileAndMaterialize(snapshot(source));
        var checked = new SirValidationApplication().check(new SirValidationRequest("candidate", source));
        assertEquals(checked.digest(), io.kcg.sir.application.internal.ValidationHashes.generated(files));
    }

    private static SourceSnapshot snapshot(String text) {
        var entry = SourceId.of("candidate.sir");
        return new SourceSnapshot(entry, Map.of(entry, text.getBytes(StandardCharsets.UTF_8)));
    }

    private BusinessSliceHarness harness() {
        var fixture = new BusinessSliceHarness.Fixture("candidate test", REFERENCE,
                "/conformance/mysql/dual-token-ddl.sql", "/conformance/mysql/dual-token-seed.sql", "user",
                "/api/get-user?id=1", "dual-token-slice-report.txt", "candidate-test", "kcg.candidate-test.enabled");
        return new BusinessSliceHarness(fixture, BusinessSliceHarnessEvidenceTest.environment(
                root.resolve("work"), root.resolve("evidence"), fixture.enabledProperty()));
    }
}
