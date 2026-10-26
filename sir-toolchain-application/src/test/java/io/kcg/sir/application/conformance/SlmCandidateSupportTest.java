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
class SlmCandidateSupportTest {
    @TempDir Path root;
    private static final String REFERENCE = "/valid/dual-token-written.sir";

    @Test void absentCandidatePropertiesRetainTheOrdinaryReferenceMode() throws Exception {
        assertTrue(SlmCandidateSupport.read(name -> null).isEmpty());
    }

    @Test void partialCandidateConfigurationCannotTriggerReferenceFallback() {
        assertThrows(Exception.class, () -> SlmCandidateSupport.read(
                Map.of("kcg.slm.bundle-root", root.toString())::get));
        assertThrows(Exception.class, () -> SlmCandidateSupport.read(
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
        var input = SlmCandidateSupport.bind(source, metadata, manifest);
        assertTrue(input.staticCheck().ok());
        assertEquals("auth-candidate-1", input.staticCheck().id());
        assertThrows(UnsupportedOperationException.class, () -> input.candidate().put("id", "changed"));
        assertEquals("auth-candidate-1", metadata.get("id"));
    }

    @Test void sourceAndMetadataMismatchIsRejectedBeforeAnyRuntimeWork() throws Exception {
        var source = snapshot(ConformanceFixtures.readResource(REFERENCE));
        assertThrows(IllegalArgumentException.class, () -> SlmCandidateSupport.bind(source,
                Map.of("source_sha256", "0".repeat(64)), Map.of()));
    }

    @Test void invalidExternalSourceDoesNotFallBackOrLeaveAProject() throws Exception {
        var harness = harness();
        assertTrue(harness.compileAndMaterialize(snapshot("sir 0.3\nsoftware Bad { nonsense }\n")).isEmpty());
        assertFalse(harness.passed());
        assertFalse(Files.exists(harness.projectRoot()));
    }

    @Test void configuredHarnessJudgesTheCandidateAndCompilationAloneNeverPassesBusiness() throws Exception {
        String candidate = ConformanceFixtures.readResource(REFERENCE).replace("15 minutes", "16 minutes");
        var properties = bundle(candidate);
        var harness = new BusinessSliceHarness(fixture(REFERENCE), environment(), properties::get);

        var files = harness.compileAndMaterialize();
        harness.writeReport();

        assertFalse(files.isEmpty());
        assertTrue(harness.report().contains("EXTERNAL_CANDIDATE:candidate.sir"));
        assertTrue(harness.report().contains("slmSampleId=auth-candidate-1"));
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                harness.evidenceRoot().resolve("slm-runtime.json").toFile());
        assertEquals("auth-candidate-1", evidence.path("id").asText());
        assertEquals("PASSED", evidence.path("gates").path("static").asText());
        for (String gate : java.util.List.of("build", "start", "business"))
            assertEquals("NOT_RUN", evidence.path("gates").path(gate).asText(), gate);
        assertEquals(new SirValidationApplication().check(new SirValidationRequest("c", candidate)).digest(),
                evidence.path("generated_digest").asText());
        assertEquals("candidate-report.txt", evidence.path("artifacts").path(0).path("path").asText(),
                "the evidence lists this slice's own report, not a fixed dual-token file name");
    }

    @Test void aCandidateForAnotherProblemIsRefusedBeforeAnyRuntimeWork() throws Exception {
        var properties = bundle(ConformanceFixtures.readResource(REFERENCE));
        var foreign = fixture("/valid/bearer-credential.sir");
        var error = assertThrows(IllegalArgumentException.class,
                () -> new BusinessSliceHarness(foreign, environment(), properties::get));
        assertTrue(error.getMessage().contains("does not answer the problem this IT judges"), error.getMessage());
        assertFalse(Files.exists(root.resolve("work")));
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
        return new BusinessSliceHarness(fixture(REFERENCE), environment());
    }

    private static BusinessSliceHarness.Fixture fixture(String reference) {
        return new BusinessSliceHarness.Fixture("candidate test", reference,
                "/conformance/mysql/dual-token-ddl.sql", "/conformance/mysql/dual-token-seed.sql", "user",
                "/api/get-user?id=1", "candidate-report.txt", "candidate-test", "kcg.candidate-test.enabled");
    }

    private BusinessSliceHarness.Environment environment() {
        return BusinessSliceHarnessEvidenceTest.environment(
                root.resolve("work"), root.resolve("evidence"), "kcg.candidate-test.enabled");
    }

    /** A minimal frozen bundle on disk: one candidate, the task's reference copy, and bound input fingerprints. */
    private Map<String, String> bundle(String candidate) throws Exception {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Path bundle = Files.createDirectories(root.resolve("bundle"));
        Path sample = Files.createDirectories(root.resolve("sample"));
        Files.writeString(repo.resolve("toolchain.txt"), "toolchain");
        Files.writeString(repo.resolve("oracle.txt"), "oracle");
        byte[] reference = ConformanceFixtures.readResource(REFERENCE).getBytes(StandardCharsets.UTF_8);
        Files.write(bundle.resolve("reference.sir"), reference);
        var toolchain = Map.of("toolchain.txt", ConformanceFixtures.fileDigest(repo.resolve("toolchain.txt")));
        var oracle = Map.of("oracle.txt", ConformanceFixtures.fileDigest(repo.resolve("oracle.txt")));
        var manifest = new java.util.LinkedHashMap<String, Object>();
        manifest.put("task_sha256", "a".repeat(64));
        manifest.put("oracle_sha256", fingerprint(oracle));
        manifest.put("toolchain_core_sha256", fingerprint(toolchain));
        manifest.put("toolchain_inputs", toolchain);
        manifest.put("oracle_inputs", oracle);
        manifest.put("bundle_inputs", Map.of("reference.sir", ConformanceFixtures.sha256Hex(reference)));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        json.writeValue(bundle.resolve("manifest.json").toFile(), manifest);
        Files.writeString(sample.resolve("candidate.sir"), candidate);
        var metadata = new java.util.LinkedHashMap<String, Object>();
        for (String key : java.util.List.of("task_sha256", "oracle_sha256", "toolchain_core_sha256")) metadata.put(key, manifest.get(key));
        metadata.putAll(Map.of("id", "auth-candidate-1", "schema_version", "sir-candidate/1", "task_revision", "1",
                "source_sha256", ConformanceFixtures.sha256Hex(candidate.getBytes(StandardCharsets.UTF_8))));
        json.writeValue(sample.resolve("candidate.json").toFile(), metadata);
        return Map.of("kcg.slm.candidate-root", sample.toString(), "kcg.slm.bundle-root", bundle.toString(),
                "kcg.slm.repo-root", repo.toString());
    }

    private static String fingerprint(Map<String, String> inputs) {
        var value = new StringBuilder();
        new java.util.TreeMap<>(inputs).forEach((path, hash) -> value.append(path).append('\0').append(hash).append('\n'));
        return ConformanceFixtures.sha256Hex(value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
