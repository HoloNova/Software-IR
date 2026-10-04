package io.kcg.sir.projectgraph;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class MultiSourceGraphFormatTest {
    private static final SourceId ROOT = ProjectGraphFixtures.SOURCE_ID, PART = SourceId.of("modules/model.sir");
    private static final SourceSnapshot SOURCES = new SourceSnapshot(ROOT, Map.of(ROOT, new byte[100], PART, new byte[100]));

    @Test void newFormatRoundTripsEverySourceAndOriginWithoutChangingLegacyBytes() {
        var graph = graph(input(SOURCES)); byte[] bytes = document(graph, ProjectGraphCanonicalFormatVersion.V2);
        var loaded = assertInstanceOf(ProjectGraphAnalysis.Success.class, new ProjectGraphLoader().load(bytes));
        assertEquals(graph.nodes(), loaded.graph().nodes()); assertEquals(graph.edges(), loaded.graph().edges());
        assertEquals(graph.canonicalDigest(), loaded.graph().canonicalDigest());
        assertArrayEquals(bytes, document(loaded.graph(), ProjectGraphCanonicalFormatVersion.V2));
        var root = graph.nodes().stream().filter(n -> n instanceof ProjectGraphNode.Project).map(n -> (ProjectGraphNode.Project)n).findFirst().orElseThrow();
        assertEquals(SOURCES.manifest(), root.provenance().sourceSet().orElseThrow());
        assertTrue(graph.nodes().stream().filter(n -> n instanceof ProjectGraphNode.SemanticDeclaration).allMatch(n -> n.provenance().sourceId().equals(PART)));
        var legacy = graph(ProjectGraphFixtures.campusMarket()); byte[] v1 = document(legacy, ProjectGraphCanonicalFormatVersion.V1);
        assertArrayEquals(v1, document(assertInstanceOf(ProjectGraphAnalysis.Success.class, new ProjectGraphLoader().load(v1)).graph(), ProjectGraphCanonicalFormatVersion.V1));
        assertInstanceOf(ProjectGraphSerialization.Failure.class, new ProjectGraphSerializer().serialize(graph, ProjectGraphCanonicalFormatVersion.V1));
        assertInstanceOf(ProjectGraphSerialization.Failure.class, new ProjectGraphSerializer().serialize(legacy, ProjectGraphCanonicalFormatVersion.V2));
    }

    @Test void sourceManifestDigestAndGraphDigestAreSensitiveToEachSourceButNotMapInsertionOrder() {
        var reversed = new LinkedHashMap<SourceId, byte[]>(); reversed.put(PART, SOURCES.bytes(PART)); reversed.put(ROOT, SOURCES.bytes(ROOT));
        assertArrayEquals(document(graph(input(SOURCES)), ProjectGraphCanonicalFormatVersion.V2), document(graph(input(new SourceSnapshot(ROOT, reversed))), ProjectGraphCanonicalFormatVersion.V2));
        for (SourceId changed : List.of(ROOT, PART)) {
            var edited = new LinkedHashMap<>(reversed); byte[] bytes = edited.get(changed).clone(); bytes[0] = 1; edited.put(changed, bytes);
            assertNotEquals(SOURCES.sha256Hex(), new SourceSnapshot(ROOT, edited).sha256Hex());
            assertNotEquals(graph(input(SOURCES)).canonicalDigest(), graph(input(new SourceSnapshot(ROOT, edited))).canonicalDigest());
        }
    }

    @Test void missingAndFakeManifestMembersSpansAndOwnershipFailClosed() {
        var input = input(SOURCES);
        assertFailed(new ProjectGraphInput(input.version(), ROOT, input.projectDisplayName(), input.semanticDeclarations(), input.loweredDeclarations(), input.artifacts(), input.files(), input.edges()), "SIR-GRAPH-SOURCES-001");
        assertFailed(input(new SourceSnapshot(ROOT, Map.of(ROOT, new byte[100]))), "SIR-GRAPH-SOURCES-002");
        assertFailed(input(new SourceSnapshot(ROOT, Map.of(ROOT, new byte[100], PART, new byte[1]))), "SIR-GRAPH-SOURCES-003");
        var semantics = new ArrayList<>(input.semanticDeclarations()); var original = semantics.getFirst();
        semantics.set(0, new ProjectGraphInput.SemanticDeclarationInput(original.symbolId(), original.kind(), original.displayName(), original.sourceNodeId(),
                new SourceSpan(ROOT, original.span().start(), original.span().end())));
        assertFailed(new ProjectGraphInput(GraphVersion.V0_2, ROOT, input.projectDisplayName(), semantics, input.loweredDeclarations(), input.artifacts(), input.files(), input.edges(), input.sourceSet()), "SIR-GRAPH-SOURCES-004");
        // A V1 graph must NOT inherit the V2 exception merely because it carries the same extra data.
        assertFailed(new ProjectGraphInput(GraphVersion.V0_1, ROOT, input.projectDisplayName(), input.semanticDeclarations(), input.loweredDeclarations(), input.artifacts(), input.files(), input.edges(), input.sourceSet()), "SIR-GRAPH-SOURCES-001");
    }

    @Test void decoderRejectsUnknownAndCrossPairedHeaderVersionsWithRecomputedOuterIntegrity() {
        byte[] v2 = document(graph(input(SOURCES)), ProjectGraphCanonicalFormatVersion.V2);
        reject(patch(v2, "formatVersion", "1"), "SIR-GRAPH-COMPAT-002");
        reject(patch(v2, "formatVersion", "3"), "SIR-GRAPH-COMPAT-001");
        reject(patch(v2, "graphVersion", "V0_1"), "SIR-GRAPH-COMPAT-002");
        reject(patch(v2, "graphVersion", "V0_9"), "SIR-GRAPH-COMPAT-002");
        byte[] v1 = document(graph(ProjectGraphFixtures.campusMarket()), ProjectGraphCanonicalFormatVersion.V1);
        reject(patch(v1, "formatVersion", "2"), "SIR-GRAPH-COMPAT-002");
        reject(patch(v1, "graphVersion", "V0_2"), "SIR-GRAPH-COMPAT-002");
    }

    @Test void innerManifestChecksumFramingOrderingAndMembershipAreValidatedNotJustOuterHash() {
        byte[] v2 = document(graph(input(SOURCES)), ProjectGraphCanonicalFormatVersion.V2);
        reject(patch(v2, "sources.sha256Hex", "0".repeat(64)), "SIR-GRAPH-SOURCES-005");
        reject(patch(v2, "sources.base64", "invalid!base64"), "SIR-GRAPH-SOURCES-005");
        byte[] future = SOURCES.manifest().canonicalBytes(); future[3] = 2;
        reject(patch(v2, "sources.base64", Base64.getEncoder().encodeToString(future)), "SIR-GRAPH-SOURCES-005");
        byte[] extra = Arrays.copyOf(SOURCES.manifest().canonicalBytes(), SOURCES.manifest().canonicalBytes().length + 1);
        reject(patch(v2, "sources.base64", Base64.getEncoder().encodeToString(extra)), "SIR-GRAPH-SOURCES-005");
        reject(patch(v2, "span.source", ROOT.value()), "SIR-GRAPH-SOURCES-003");
        reject(patch(v2, "provenance.sourceId", PART.value()), "SIR-GRAPH-SOURCES-001");
        String text = new String(v2, StandardCharsets.UTF_8).replace("sources.base64\n", "sources.base65\n");
        var damaged = assertInstanceOf(ProjectGraphAnalysis.Failure.class, new ProjectGraphLoader().load(integrity(text)));
        assertTrue(damaged.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-GRAPH-FORMAT-011")), damaged.diagnostics().toString());
    }

    private static ProjectGraphInput input(SourceSnapshot sources) {
        var old = ProjectGraphFixtures.campusMarket();
        var semantics = old.semanticDeclarations().stream().map(s -> new ProjectGraphInput.SemanticDeclarationInput(s.symbolId(), s.kind(), s.displayName(), s.sourceNodeId(),
                new SourceSpan(PART, s.span().start(), s.span().end()))).toList();
        var lowered = old.loweredDeclarations().stream().map(l -> new ProjectGraphInput.LoweredDeclarationInput(l.nodeId(), l.sourceSymbol(), l.displayName(),
                new LoweredOrigin(l.origin().ownerSymbol(), l.origin().sourceNodeId(), new SourceSpan(PART, l.origin().span().start(), l.origin().span().end())))).toList();
        var zero = new SourcePosition(0, 1, 1);
        var artifacts = old.artifacts().stream().map(a -> new ProjectGraphInput.ArtifactInput(a.artifactId(), a.ownerSymbol(),
                a.ownerSymbol().isPresent() ? new LoweredOrigin(a.origin().ownerSymbol(), a.origin().sourceNodeId(), new SourceSpan(PART, a.origin().span().start(), a.origin().span().end()))
                        : new LoweredOrigin(Optional.empty(), new AstNodeId("project"), new SourceSpan(SourceId.of("project"), zero, zero)), a.role(), a.qualifiedName())).toList();
        return new ProjectGraphInput(GraphVersion.V0_2, ROOT, old.projectDisplayName(), semantics, lowered, artifacts, old.files(), old.edges(), Optional.of(sources.manifest()));
    }
    private static ProjectGraph graph(ProjectGraphInput input) {
        var result = new ProjectGraphBuilder().build(input); return assertInstanceOf(ProjectGraphAnalysis.Success.class, result, result.diagnostics().toString()).graph();
    }
    private static byte[] document(ProjectGraph graph, ProjectGraphCanonicalFormatVersion version) {
        var result = new ProjectGraphSerializer().serialize(graph, version); return assertInstanceOf(ProjectGraphSerialization.Success.class, result, result.diagnostics().toString()).document().bytes();
    }
    private static void assertFailed(ProjectGraphInput input, String code) {
        var failure = assertInstanceOf(ProjectGraphAnalysis.Failure.class, new ProjectGraphBuilder().build(input));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().equals(code)), failure.diagnostics().toString());
    }
    private static void reject(byte[] bytes, String code) {
        var failure = assertInstanceOf(ProjectGraphAnalysis.Failure.class, new ProjectGraphLoader().load(bytes));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().equals(code)), failure.diagnostics().toString());
    }
    /** Every test value is ASCII; keep outer hash/size correct so inner checks are really reached. */
    private static byte[] patch(byte[] bytes, String name, String value) {
        String text = new String(bytes, StandardCharsets.UTF_8); String marker = name + "\n";
        int start = text.indexOf(marker) + marker.length(), end = text.indexOf('\n', start);
        assertTrue(start >= marker.length());
        return integrity(text.substring(0, start) + value.length() + ":" + value + text.substring(end));
    }
    private static byte[] integrity(String text) {
        int payload = text.indexOf("PAYLOAD\n") + 8; byte[] data = text.substring(payload).getBytes(StandardCharsets.UTF_8);
        text = rawPatch(text, "payloadByteCount", Integer.toString(data.length));
        text = rawPatch(text, "payloadSha256Hex", ProjectGraphFixtures.sha256Hex(data));
        return text.getBytes(StandardCharsets.UTF_8);
    }
    private static String rawPatch(String text, String name, String value) {
        int start = text.indexOf(name + "\n") + name.length() + 1, end = text.indexOf('\n', start);
        return text.substring(0, start) + value.length() + ":" + value + text.substring(end);
    }
}
