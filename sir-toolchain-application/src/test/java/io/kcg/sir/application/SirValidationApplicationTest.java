package io.kcg.sir.application;

import io.kcg.sir.api.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.*;
import io.kcg.sir.generator.springboot.api.*;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SirValidationApplicationTest {
    @TempDir Path temp;
    final SirValidationApplication app = new SirValidationApplication();
    static String source(String file) throws Exception { return ValidationFixtureProbeTest.resource("valid/" + file); }
    @Test void allTwentyMatchActualToolchainOutputBytesAndIndependentDigest() throws Exception {
        for (String name : ValidationFixtureProbeTest.VALID) {
            String sir = source(name); var result = app.check(new SirValidationRequest(name, sir));
            assertTrue(result.ok(), () -> name + result.diagnostics()); assertEquals(ValidationStopAfter.GENERATION, result.stage());
            Path input = temp.resolve(name); Files.writeString(input, sir); Path out = temp.resolve(name + "-output");
            var old = new ToolchainApplication().execute(new ToolchainRequest(input.toAbsolutePath(), SourceId.of("sample.sir"), out.toAbsolutePath(), ConflictPolicy.FAIL_IF_EXISTS));
            assertInstanceOf(ToolchainResult.Success.class, old, () -> name + old);
            try (var paths = Files.walk(out)) {
                var files = paths.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> out.relativize(p).toString())).toList();
                assertEquals(files.size(), result.fileCount());
                var hash = MessageDigest.getInstance("SHA-256"); hash.update("KCG-SIR-CHECK-GENERATED-V1\0".getBytes(StandardCharsets.US_ASCII));
                hash.update(ByteBuffer.allocate(8).putLong(files.size()).array());
                var direct = SirCompilation.compile(sir, SourceId.of("sample.sir"), new SpringBootGenerator()::generate, new ArrayList<>()).orElseThrow();
                for (var file : files) {
                    String path = out.relativize(file).toString().replace('\\','/'); byte[] content = Files.readAllBytes(file);
                    assertArrayEquals(direct.generatedFiles().stream().filter(f -> f.relativePath().equals(path)).findFirst().orElseThrow().content().getBytes(StandardCharsets.UTF_8), content);
                    byte[] p = path.getBytes(StandardCharsets.UTF_8); hash.update(ByteBuffer.allocate(8).putLong(p.length).array()); hash.update(p);
                    hash.update(ByteBuffer.allocate(8).putLong(content.length).array()); hash.update(content);
                }
                assertEquals(HexFormat.of().formatHex(hash.digest()), result.digest());
            }
        }
    }
    @Test void stopAfterActuallySkipsLaterStagesIncludingKnownLoweringFailure() throws Exception {
        String sir = source("campus-market.sir");
        for (var stop : ValidationStopAfter.values()) {
            var reached = new ArrayList<ValidationStopAfter>();
            var snapshot = CompilationStages.compile(sir, SourceId.of("sample.sir"), stop,
                m -> { assertEquals(ValidationStopAfter.GENERATION, stop); return new SpringBootGenerator().generate(m); }, new ArrayList<>(), reached::add);
            assertTrue(snapshot.success()); assertEquals(List.of(ValidationStopAfter.values()).subList(0, stop.ordinal()+1), reached);
            var result = app.check(new SirValidationRequest("opaque", sir, stop)); assertTrue(result.ok()); assertEquals(stop, result.stage());
            if (stop != ValidationStopAfter.GENERATION) { assertEquals(0, result.fileCount()); assertNull(result.digest()); }
        }
        String invalid = ValidationFixtureProbeTest.resource("invalid/actor-non-identity.sir");
        assertTrue(app.check(new SirValidationRequest("early", invalid, ValidationStopAfter.SEMANTIC)).ok());
        assertFalse(app.check(new SirValidationRequest("late", invalid, ValidationStopAfter.LOWERING)).ok());
    }
    @Test void originalDiagnosticCodesMessagesAndPositionsRemainExact() throws Exception {
        for (String fixture : List.of("semantic-unresolved-name.sir", "actor-non-identity.sir")) {
            String sir = ValidationFixtureProbeTest.resource("invalid/" + fixture); var legacy = new ArrayList<ExecutionDiagnostic>();
            SirCompilation.compile(sir, SourceId.of("sample.sir"), new SpringBootGenerator()::generate, legacy);
            var checked = app.check(new SirValidationRequest(fixture, sir)); assertFalse(checked.ok());
            assertEquals(legacy.size(), checked.diagnostics().size());
            for (int i=0;i<legacy.size();i++) {
                var a = legacy.get(i); var b = checked.diagnostics().get(i);
                assertEquals(a.code(),b.code()); assertEquals(a.stage().name(),b.stage().name()); assertEquals(a.message(),b.message());
                assertEquals(a.severity().name(),b.severity()); assertEquals(a.sourceSpan().orElse(null),b.span());
            }
        }
        assertEquals(ValidationStopAfter.PARSE, app.check(new SirValidationRequest("bad", "not valid SIR")).stage());
        String duplicate = source("campus-market-two-capabilities.sir").replaceAll("capability ([A-Za-z]+)", "capability $1 @id(\"same\")");
        var result = app.check(new SirValidationRequest("duplicate", duplicate)); assertFalse(result.ok());
        assertTrue(result.diagnostics().stream().anyMatch(d -> !d.related().isEmpty()), result.toString());
    }
    @Test void syntheticFixAndRelatedTransportIsLosslessNotNewProductionHintCoverage() {
        var span = new SourceSpan(SourceId.of("sample.sir"), new SourcePosition(0,1,1), new SourcePosition(2,1,3));
        var original = new Diagnostic(new DiagnosticCode("SIR-TEST-001"), DiagnosticSeverity.ERROR, "原文\n😀", span,
            List.of(new RelatedLocation("first declaration",span)), List.of(new FixHint("suggestion",span,"替换")));
        var mapped = CompilationStages.fromParser(original, ValidationStopAfter.SEMANTIC);
        assertEquals(original.message(),mapped.message()); assertEquals(original.primarySpan(),mapped.span());
        assertEquals(original.related(),mapped.related()); assertEquals(original.fixes(),mapped.fixes());
    }
    @Test void generationFailureUsesOriginalCodeMessageAndNode() throws Exception {
        var node = new LoweredNodeId("lir://test/node");
        var original = GenerationDiagnostic.of("GEN-TEST", "injected generator failure", node);
        var snapshot = CompilationStages.compile(source("campus-market.sir"), SourceId.of("sample.sir"), ValidationStopAfter.GENERATION,
            m -> new GenerationResult.Failure(List.of(original)), new ArrayList<>(), s -> {});
        assertFalse(snapshot.success()); assertEquals(ValidationStopAfter.GENERATION,snapshot.stage());
        var d = snapshot.diagnostics().getLast(); assertEquals(original.code(),d.code()); assertEquals(original.message(),d.message()); assertEquals(node.value(),d.loweredNodeId());
    }
    @Test void inputBudgetsUnicodeHashesAndOpaqueIds() throws Exception {
        String sir = source("campus-market.sir"); var a = app.check(new SirValidationRequest("a",sir)); var b = app.check(new SirValidationRequest("b",sir));
        assertEquals(a.digest(),b.digest()); assertEquals(a.sourceSha256(),b.sourceSha256()); assertEquals(a.diagnostics(),b.diagnostics());
        assertFalse(app.check(null).ok()); assertFalse(app.check(new SirValidationRequest(null,sir)).ok());
        assertFalse(app.check(new SirValidationRequest("x"," ")).ok()); assertFalse(app.check(new SirValidationRequest("x","\ud800")).ok());
        assertFalse(app.check(new SirValidationRequest("x","x".repeat(SirValidationApplication.MAX_SIR_BYTES+1))).ok());
        String atLimit = sir + " ".repeat(SirValidationApplication.MAX_SIR_BYTES - sir.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(app.check(new SirValidationRequest("",atLimit,ValidationStopAfter.PARSE)).ok());
        assertFalse(app.check(new SirValidationRequest("x".repeat(1025),sir)).ok());
        assertNull(app.check(new SirValidationRequest("x","x".repeat(SirValidationApplication.MAX_SIR_BYTES+1))).sourceSha256());
        var bom = app.check(new SirValidationRequest("a","\ufeff"+sir)); assertTrue(bom.ok()); assertEquals(a.digest(),bom.digest()); assertNotEquals(a.sourceSha256(),bom.sourceSha256());
        assertFalse(app.check(new SirValidationRequest("v2",sir.replace("sir 0.1","sir 0.2"))).ok());
    }
    @Test void callerOwnedOriginalDiagnosticTraceSurvivesLaterStageException() throws Exception {
        var span = new SourceSpan(SourceId.of("sample.sir"), new SourcePosition(0,1,1), new SourcePosition(2,1,3));
        var original = new Diagnostic(new DiagnosticCode("SIR-TEST-INFO"), DiagnosticSeverity.INFO, "earlier stage message", span,
            List.of(new RelatedLocation("related",span)), List.of(new FixHint("hint",span,"x")));
        var trace = new ArrayList<ValidationDiagnostic>(); trace.add(CompilationStages.fromParser(original,ValidationStopAfter.PARSE));
        assertThrows(IllegalStateException.class, () -> CompilationStages.compile(source("campus-market.sir"), SourceId.of("sample.sir"), ValidationStopAfter.GENERATION,
            m -> { throw new IllegalStateException("injected"); },new ArrayList<>(),s -> {},trace));
        assertEquals(original.message(),trace.getFirst().message()); assertEquals(original.related(),trace.getFirst().related()); assertEquals(original.fixes(),trace.getFirst().fixes());
    }
    @Test void digestFramingSortAndLineEndingVectorsAreIndependent() {
        var node = new LoweredNodeId("lir://test/file");
        var a = new GeneratedFile("a", "bc",node,Optional.empty()); var b = new GeneratedFile("ab","c",node,Optional.empty());
        assertNotEquals(ValidationHashes.generated(List.of(a)),ValidationHashes.generated(List.of(b)));
        assertEquals(ValidationHashes.generated(List.of(a,b)),ValidationHashes.generated(List.of(b,a)));
        assertNotEquals(ValidationHashes.source("x\n"),ValidationHashes.source("x\r\n"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",ValidationHashes.source(""));
    }
}
