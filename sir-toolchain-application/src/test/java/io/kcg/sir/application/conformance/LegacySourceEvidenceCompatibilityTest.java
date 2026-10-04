package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Goldens copied from ab2ce09, CI run 36404651840, conformance-evidence (not a regenerated baseline). */
class LegacySourceEvidenceCompatibilityTest {
    @Test void fourBusinessOutputsAndSixV1SnapshotsStillMatchTheAcceptedPreQ18CiBytes() throws Exception {
        Map<String, SirCompilation.CompilationSnapshot> compiled = new HashMap<>();
        var sourceId = SourceId.of("change-loop.sir");
        List<BusinessEvidence> business = List.of(
                new BusinessEvidence("course-catalog.sir", "4d3e134fd80143b0b4d0d84ac861304698f0153c03125d63eae784be57430c3f", 15, "1b3a7d6a6fb19cc32d900145fe8b48440b2598a3d2404938a1c8a6adab1fae1d"),
                new BusinessEvidence("course-admin.sir", "cf1618026ea28bc0609eadb4853505ecfcc14123b01daf53ff2d3ad1beb662e5", 22, "580d174e951c932c986c620e3a196a241a263e00f0464a4ac860de465f04b4e5"),
                new BusinessEvidence("course-enrollment.sir", "b5e6bd5b7507e8558def599b4cb713729c71bfd4653cfefdd1749d92b443ead7", 22, "b143ae3e5cc2b132d7da3d9aca023ed86faabda2ac2954dbd2d31c1e92e2c85d"),
                new BusinessEvidence("course-admin-enrollment.sir", "e56343cf740193e35b16f6e072693f4da705c58ecdb3cf80d4f25385986642fd", 35, "1a7775853b33acd1a123524990c9ad3b1072442e2d14c7e66699a2d016cecf3f"));
        assertEquals(4, business.size());
        for (var evidence : business) {
            byte[] bytes;
            try (var resource = getClass().getResourceAsStream("/valid/" + evidence.resource)) { bytes = Objects.requireNonNull(resource).readAllBytes(); }
            assertEquals(evidence.sourceSha, sha(bytes), "the comparison must cover the same input bytes: " + evidence.resource);
            List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
            var snapshot = SirCompilation.compile(new String(bytes, StandardCharsets.UTF_8), sourceId, m -> new SpringBootGenerator().generate(m), diagnostics).orElseThrow();
            assertTrue(diagnostics.isEmpty(), diagnostics.toString()); compiled.put(evidence.resource, snapshot);
            assertEquals(evidence.count, snapshot.generatedFiles().size());
            assertEquals(evidence.generatedSha, BusinessSliceHarness.digestOfGeneratedFiles(snapshot.generatedFiles()), evidence.resource);
        }
        List<GraphEvidence> graphs = List.of(
                new GraphEvidence("course-admin-enrollment.sir", "786d0c63ab8ede82c07790742e0d2e6810f47ece74a48d067fa8aff111ed8c88"),
                new GraphEvidence("course-admin-enrollment-filter-any.sir", "dfec4245ba964a38a7ae9c3ff86b25a73bb2f1f56d3132d6a5e6c008f9825420"),
                new GraphEvidence("course-admin-enrollment-tighten.sir", "6cee8393fa2553a49ec3d274dcad117dd66234c448c3a06298d66faead6288aa"),
                new GraphEvidence("course-admin-enrollment-remove-update.sir", "784ff82e67370a9468fb7f8e2580d44b7384f811c1423bf333b0376c904bb74f"),
                new GraphEvidence("course-admin-enrollment-readonly.sir", "449798d99c264acb7a4f94d82e227ecce1e10eaeca8d46420166c9da740ed2ac"),
                new GraphEvidence("course-admin-enrollment-add-list-refs.sir", "6cd5c8f5c793188bd44c7df88135cb6df1d56331db065cb062899ccae19ae3a2"));
        assertEquals(6, graphs.size());
        for (var evidence : graphs) {
            var snapshot = compiled.get(evidence.resource);
            if (snapshot == null) {
                byte[] bytes;
                try (var resource = getClass().getResourceAsStream("/valid/" + evidence.resource)) { bytes = Objects.requireNonNull(resource).readAllBytes(); }
                List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
                snapshot = SirCompilation.compile(new String(bytes, StandardCharsets.UTF_8), sourceId, m -> new SpringBootGenerator().generate(m), diagnostics).orElseThrow();
                assertTrue(diagnostics.isEmpty(), diagnostics.toString());
            }
            var input = new SpringBootProjectGraphInputFactory().build(snapshot.semanticModel(), snapshot.loweredModel(), snapshot.generatedFiles(), sourceId);
            var graph = assertInstanceOf(ProjectGraphAnalysis.Success.class, new ProjectGraphBuilder().build(input)).graph();
            var document = assertInstanceOf(ProjectGraphSerialization.Success.class, new ProjectGraphSerializer().serialize(graph, ProjectGraphCanonicalFormatVersion.V1)).document();
            assertEquals(evidence.snapshotSha, sha(document.bytes()), evidence.resource);
            var restored = assertInstanceOf(ProjectGraphAnalysis.Success.class, new ProjectGraphLoader().load(document)).graph();
            assertArrayEquals(document.bytes(), assertInstanceOf(ProjectGraphSerialization.Success.class, new ProjectGraphSerializer().serialize(restored, ProjectGraphCanonicalFormatVersion.V1)).document().bytes());
        }
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private record BusinessEvidence(String resource, String sourceSha, int count, String generatedSha) {}
    private record GraphEvidence(String resource, String snapshotSha) {}
}
