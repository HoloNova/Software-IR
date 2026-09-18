package io.kcg.sir.projectgraph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.projectgraph.api.CanonicalProjectGraphDocument;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import io.kcg.sir.projectgraph.api.ProjectGraphLoader;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.SemanticDeclarationInput;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import io.kcg.sir.projectgraph.internal.SnapshotEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Canonical serialization contract: a valid graph must round-trip byte-for-byte, and every malformed
 * or non-canonical document must fail closed with a structured diagnostic instead of an exception.
 *
 * <p>The malformed cases are produced by mutating the document the encoder produced, so they exercise
 * the real format rather than a hand-written approximation. Each assert names the exact diagnostic
 * code, which also pins that the loader never accepts a document it would not re-produce.
 */
class ProjectGraphSerializationTest {

    private static final Set<String> HEADER_FIELDS = Set.of("formatVersion", "payloadByteCount",
            "payloadSha256Hex");

    @Test
    void headerLineModelMatchesTheFrozenFormat() {
        byte[] document = documentBytes(validGraph());

        for (String field : HEADER_FIELDS) {
            String value = headerFieldValue(document, field);
            assertArrayEquals(document, patchHeaderField(document, field, value),
                    "rewriting a header field with its own value must be a no-op for " + field);
        }
    }

    @Test
    void serializeThenLoadReproducesTheSameGraph() {
        ProjectGraph graph = validGraph();
        CanonicalProjectGraphDocument document = serialize(graph);

        ProjectGraphAnalysis.Success loaded = assertInstanceOf(ProjectGraphAnalysis.Success.class,
                new ProjectGraphLoader().load(document),
                "a document produced by this module must always load");

        assertEquals(graph.canonicalDigest(), loaded.graph().canonicalDigest());
        assertEquals(graph.nodes(), loaded.graph().nodes());
        assertEquals(graph.edges(), loaded.graph().edges());
        assertEquals(0, loaded.diagnostics().size(), () -> loaded.diagnostics().toString());
        assertArrayEquals(document.bytes(), documentBytes(loaded.graph()),
                "re-serializing a loaded graph must reproduce the exact same document");
    }

    @Test
    void loaderAcceptsRawBytesAndDoesNotModifyThem() {
        byte[] document = serialize(validGraph()).bytes();
        byte[] before = document.clone();

        ProjectGraphAnalysis.Success loaded = assertInstanceOf(ProjectGraphAnalysis.Success.class,
                new ProjectGraphLoader().load(document));

        assertArrayEquals(before, document, "the loader must not modify the bytes it was given");
        assertEquals(validGraph().canonicalDigest(), loaded.graph().canonicalDigest());
    }

    @Test
    void canonicalDocumentDefensivelyCopiesItsBytes() {
        byte[] source = serialize(validGraph()).bytes();
        byte[] original = source.clone();
        CanonicalProjectGraphDocument document = new CanonicalProjectGraphDocument(source);

        source[0] = 0;
        assertArrayEquals(original, document.bytes(), "the document must copy the bytes it is constructed with");

        byte[] returned = document.bytes();
        returned[0] = 0;
        assertArrayEquals(original, document.bytes(), "bytes() must return a copy");
        assertEquals(original.length, document.byteLength());
        assertEquals(new CanonicalProjectGraphDocument(original), document, "documents with equal bytes are equal");
        assertEquals(new CanonicalProjectGraphDocument(original).hashCode(), document.hashCode());
    }

    @Test
    void rejectsByteOrderMark() {
        byte[] withBom = prepend(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, documentBytes(validGraph()));

        assertRejected(withBom, "SIR-GRAPH-FORMAT-003");
    }

    @Test
    void rejectsCarriageReturnInTheMagicRegion() {
        byte[] document = documentBytes(validGraph());
        document[1] = '\r';

        assertRejected(document, "SIR-GRAPH-FORMAT-004");
    }

    @Test
    void rejectsInvalidMagic() {
        byte[] document = documentBytes(validGraph());
        document[0] = 'X';

        assertRejected(document, "SIR-GRAPH-FORMAT-001");
    }

    @Test
    void rejectsEmptyInput() {
        assertRejected(new byte[0], "SIR-GRAPH-FORMAT-001");
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] document = documentBytes(validGraph());
        byte[] truncated = Arrays.copyOf(document, document.length - 1);

        assertRejected(truncated, "SIR-GRAPH-FORMAT-006");
    }

    @Test
    void rejectsTrailingBytesAfterThePayload() {
        byte[] document = documentBytes(validGraph());

        assertRejected(Arrays.copyOf(document, document.length + 1), "SIR-GRAPH-FORMAT-005");
    }

    @Test
    void rejectsNonShortestDecimalPayloadByteCount() {
        byte[] document = documentBytes(validGraph());
        String value = headerFieldValue(document, "payloadByteCount");
        byte[] patched = patchHeaderField(document, "payloadByteCount", "0" + value);

        // A leading zero is rejected by the "non-negative decimal" pre-check, which runs before the
        // dedicated shortest-decimal branch of this field.
        assertRejected(patched, "SIR-GRAPH-FORMAT-012");
    }

    @Test
    void rejectsNonShortestDecimalHeaderFieldCount() {
        byte[] document = documentBytes(validGraph());

        // The HEADER field count is the one place where the shortest-decimal rule itself reports.
        assertRejected(patchHeaderFieldCount(document, "03"), "SIR-GRAPH-FORMAT-007");
    }

    @Test
    void rejectsUnsupportedFormatVersion() {
        byte[] document = documentBytes(validGraph());
        byte[] patched = patchHeaderField(document, "formatVersion", "2");

        assertRejected(patched, "SIR-GRAPH-COMPAT-001");
    }

    @Test
    void rejectsPayloadDigestMismatch() {
        byte[] document = documentBytes(validGraph());
        byte[] patched = patchHeaderField(document, "payloadSha256Hex", "0".repeat(64));

        assertRejected(patched, "SIR-GRAPH-INTEGRITY-001");
    }

    @Test
    void rejectsUppercasePayloadDigest() {
        byte[] document = documentBytes(validGraph());
        byte[] patched = patchHeaderField(document, "payloadSha256Hex",
                headerFieldValue(document, "payloadSha256Hex").toUpperCase(java.util.Locale.ROOT));

        assertRejected(patched, "SIR-GRAPH-FORMAT-012");
    }

    // ---- reachable guards for the version / provenance-kind invariants ---------------------------

    /**
     * The reachable counterpart of the unreachable {@code SIR-GRAPH-PROVENANCE-001}: a document
     * whose {@code provenance.kind} disagrees with its {@code nodeKind} must be rejected.
     *
     * <p>{@code ArtifactProvenance} is used as the bogus value because it has the same length as
     * {@code SemanticProvenance}, so the patch is length-preserving and only the payload digest has
     * to be repaired. The decoder reports {@code SIR-GRAPH-COMPAT-010} and returns before any node is
     * constructed, which is exactly why the validator rule can never see a mismatched pair.
     */
    @Test
    void rejectsDocumentWhoseProvenanceKindDoesNotMatchTheNodeKind() {
        byte[] document = documentBytes(validGraph());
        byte[] patched = withConsistentIntegrity(
                patchRecordField(document, "provenance.kind", "ArtifactProvenance"));

        assertRejected(patched, "SIR-GRAPH-COMPAT-010");
    }

    @Test
    void encoderRejectsUnsupportedCanonicalFormatVersion() {
        List<ProjectGraphDiagnostic> diagnostics = new ArrayList<>();

        SnapshotEncoder.EncodeResult result = SnapshotEncoder.encode(validGraph(), null, diagnostics);

        assertNull(result, "a non-V1 format version must not produce a document");
        assertEquals(Set.of("SIR-GRAPH-COMPAT-001"),
                diagnostics.stream().map(ProjectGraphDiagnostic::code)
                        .collect(Collectors.toCollection(TreeSet::new)),
                () -> diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(ProjectGraphDiagnostic::isError));
    }

    /**
     * Evidence for the version-skew guard that is actually reachable from a file: the decoder checks
     * the declared {@code graphVersion} as a raw string and rejects anything but {@code V0_1}. The
     * integrity fields are repaired first, so the document really reaches this rule instead of
     * failing earlier at the payload digest.
     *
     * <p>This is the document-level counterpart of the validator's unreachable
     * {@code SIR-GRAPH-VERSION-002}: the same concern is guarded twice, once by an enum comparison in
     * the validator and once by a string comparison in the decoder. Only the decoder path can be
     * reached from untrusted bytes, and it is covered here.
     */
    @Test
    void rejectsDocumentThatDeclaresAnotherGraphVersion() {
        byte[] document = documentBytes(validGraph());
        byte[] patched = withConsistentIntegrity(patchRecordField(document, "graphVersion", "V0_2"));

        assertRejected(patched, "SIR-GRAPH-COMPAT-002");
    }

    /**
     * The coverage inventory states that {@code SIR-GRAPH-VERSION-002} and the serializer's
     * {@code SIR-GRAPH-COMPAT-001} are unreachable because both version enums have exactly one
     * constant. This test keeps that stated reason honest: if a second constant is ever added, this
     * fails and the new value needs real tests instead of the documented gap.
     */
    @Test
    void versionCodesThatCannotBeReachedTodayAreDocumented() {
        assertEquals(List.of("V0_1"), Arrays.stream(GraphVersion.values()).map(Enum::name).toList(),
                "a second GraphVersion only makes VERSION-002 reachable once a non-V0_1 version can be "
                        + "passed to ProjectGraphValidator.validate");
        assertEquals(List.of("V1"),
                Arrays.stream(ProjectGraphCanonicalFormatVersion.values()).map(Enum::name).toList(),
                "a second format version makes the serializer COMPAT-001 path reachable");
    }

    @Test
    void rejectsNodeFieldsThatCannotBeEncodedAsUtf8() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        SemanticDeclarationInput original = ProjectGraphFixtures.first(base.semanticDeclarations(),
                declaration -> declaration.symbolId().equals(ProjectGraphFixtures.ENUM_SYMBOL));

        // A lone high surrogate is not a valid Unicode scalar value: String.getBytes(UTF_8) would
        // silently replace it, which is exactly what the encoder refuses to do.
        SemanticDeclarationInput nonEncodable = new SemanticDeclarationInput(original.symbolId(),
                original.kind(), "\uD800", original.sourceNodeId(), original.span());
        ProjectGraphInput input = ProjectGraphFixtures.withSemantics(base,
                ProjectGraphFixtures.replace(base.semanticDeclarations(),
                        base.semanticDeclarations().indexOf(original), nonEncodable));

        ProjectGraph graph = assertInstanceOf(ProjectGraphAnalysis.Success.class,
                new ProjectGraphBuilder().build(input)).graph();

        ProjectGraphSerialization.Failure failure = assertInstanceOf(ProjectGraphSerialization.Failure.class,
                new ProjectGraphSerializer().serialize(graph, ProjectGraphCanonicalFormatVersion.V1),
                "a node field that cannot be encoded as UTF-8 must fail serialization instead of being replaced");

        assertEquals(Set.of("SIR-GRAPH-SERIALIZE-001"),
                failure.diagnostics().stream().map(ProjectGraphDiagnostic::code)
                        .collect(Collectors.toCollection(TreeSet::new)),
                () -> failure.diagnostics().toString());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static void assertRejected(byte[] document, String expectedCode) {
        ProjectGraphAnalysis analysis = new ProjectGraphLoader().load(document);

        ProjectGraphAnalysis.Failure failure = assertInstanceOf(ProjectGraphAnalysis.Failure.class, analysis,
                "the document must be rejected, not accepted: " + analysis);
        Set<String> codes = failure.diagnostics().stream()
                .map(diagnostic -> diagnostic.code())
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(List.of(expectedCode)), codes,
                () -> "unexpected rejection diagnostics: " + failure.diagnostics());
        assertTrue(failure.diagnostics().stream().allMatch(diagnostic -> diagnostic.isError()),
                () -> "every rejection diagnostic must be an ERROR: " + failure.diagnostics());
    }

    private static ProjectGraph validGraph() {
        return assertInstanceOf(ProjectGraphAnalysis.Success.class,
                new ProjectGraphBuilder().build(ProjectGraphFixtures.campusMarket())).graph();
    }

    private static CanonicalProjectGraphDocument serialize(ProjectGraph graph) {
        return assertInstanceOf(ProjectGraphSerialization.Success.class,
                new ProjectGraphSerializer().serialize(graph, ProjectGraphCanonicalFormatVersion.V1)).document();
    }

    private static byte[] documentBytes(ProjectGraph graph) {
        return serialize(graph).bytes();
    }

    /** Every byte maps to exactly one char in ISO-8859-1, so this is a lossless byte/line model. */
    private static String[] lines(byte[] document) {
        return new String(document, StandardCharsets.ISO_8859_1).split("\n", -1);
    }

    private static byte[] fromLines(String[] lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String headerFieldValue(byte[] document, String fieldName) {
        String[] lines = lines(document);
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals(fieldName)) {
                String encoded = lines[i + 1];
                int colon = encoded.indexOf(':');
                return encoded.substring(colon + 1);
            }
        }
        throw new AssertionError("header field not found: " + fieldName);
    }

    private static byte[] patchHeaderField(byte[] document, String fieldName, String newValue) {
        String[] lines = lines(document);
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals(fieldName)) {
                lines[i + 1] = newValue.getBytes(StandardCharsets.UTF_8).length + ":" + newValue;
                return fromLines(lines);
            }
        }
        throw new AssertionError("header field not found: " + fieldName);
    }

    /** The field count is a raw decimal line directly after the {@code HEADER} tag. */
    private static byte[] patchHeaderFieldCount(byte[] document, String newValue) {
        String[] lines = lines(document);
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals("HEADER")) {
                lines[i + 1] = newValue;
                return fromLines(lines);
            }
        }
        throw new AssertionError("HEADER tag not found");
    }

    /** Patches the value line of the first record field with the given name. */
    private static byte[] patchRecordField(byte[] document, String fieldName, String newValue) {
        String[] lines = lines(document);
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals(fieldName)) {
                lines[i + 1] = encodedField(newValue);
                return fromLines(lines);
            }
        }
        throw new AssertionError("record field not found: " + fieldName);
    }

    private static String encodedField(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    /**
     * Recomputes {@code payloadSha256Hex} (and {@code payloadByteCount}) after a payload patch, so a
     * mutated document still passes the integrity layer and reaches the rule under test.
     */
    private static byte[] withConsistentIntegrity(byte[] document) {
        String[] lines = lines(document);
        int payloadLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals("PAYLOAD")) {
                payloadLine = i;
                break;
            }
        }
        if (payloadLine < 0) {
            throw new AssertionError("PAYLOAD marker not found");
        }

        byte[] payload = String.join("\n", Arrays.copyOfRange(lines, payloadLine + 1, lines.length))
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] withCount = patchHeaderField(document, "payloadByteCount", Integer.toString(payload.length));
        return patchHeaderField(withCount, "payloadSha256Hex", ProjectGraphFixtures.sha256Hex(payload));
    }

    private static byte[] prepend(byte[] prefix, byte[] document) {
        byte[] result = new byte[prefix.length + document.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(document, 0, result, prefix.length, document.length);
        return result;
    }
}
