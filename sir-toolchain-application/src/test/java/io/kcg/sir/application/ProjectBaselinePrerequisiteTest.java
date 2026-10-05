package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.application.internal.bundle.*;
import io.kcg.sir.application.internal.state.StateRootLock;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preflight evidence only; does not masquerade as the new registration API. */
class ProjectBaselinePrerequisiteTest {
    @TempDir Path temp;

    @Test void savedSnapshotReplaysExistingTargetAndGraphWithoutOriginalDirectoryOrOutputWrites() throws Exception {
        var source = fixture(temp); var output = temp.resolve("output"); var generated = generate(source, output);
        Files.move(source, temp.resolve("unavailable-original"));
        var before = tree(temp);
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = SirCompilation.lowerAndGenerate(model(generated.sources()), new SpringBootGenerator()::generate, diagnostics).orElseThrow();
        var expectedFiles = BaselineBuilder.buildManifest(compiled.generatedFiles());
        assertEquals(35, expectedFiles.size());
        for (var f : compiled.generatedFiles()) assertArrayEquals(Files.readAllBytes(output.resolve(f.relativePath())), f.content().getBytes(StandardCharsets.UTF_8));
        var input = new SpringBootProjectGraphInputFactory().build(compiled.semanticModel(), compiled.loweredModel(), compiled.generatedFiles(), generated.sources());
        var replay = assertInstanceOf(ProjectGraphAnalysis.Success.class, new ProjectGraphBuilder().build(input)).graph();
        assertEquals(generated.graph().canonicalDigest(), replay.canonicalDigest());
        assertArrayEquals(graphBytes(generated.graph()), graphBytes(replay));
        assertEquals(before, tree(temp)); assertFalse(Files.exists(source));
    }

    @Test void semanticInputAlreadyRejectsMissingAndUnlistedSnapshotMembersBeforeResolve() throws Exception {
        var generated = generate(fixture(temp), temp.resolve("output"));
        Map<SourceId,byte[]> bytes = new LinkedHashMap<>();
        generated.sources().manifest().files().forEach(f -> bytes.put(f.sourceId(), generated.sources().bytes(f.sourceId())));
        bytes.put(SourceId.of("modules/extra.sir"), "sir 0.2\nimports {}\ndeclarations {}".getBytes(StandardCharsets.UTF_8));
        var extra = new SirSemanticAnalyzer().analyzeProject(parse(new SourceSnapshot(ENTRY, bytes)));
        assertFalse(extra.isSuccess()); assertTrue(extra.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-SOURCE-SET-001")));
        bytes.remove(SourceId.of("modules/extra.sir")); bytes.remove(SourceId.of("modules/student.sir"));
        var missing = new SirSemanticAnalyzer().analyzeProject(parse(new SourceSnapshot(ENTRY, bytes)));
        assertFalse(missing.isSuccess()); assertTrue(missing.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-SOURCE-SET-001")));
    }

    @Test void measuresActualFourSourceWireInputsRatherThanAssumingSmallBundles() throws Exception {
        var generated = generate(fixture(temp), temp.resolve("output"));
        var compiled = SirCompilation.lowerAndGenerate(model(generated.sources()), new SpringBootGenerator()::generate, new ArrayList<>()).orElseThrow();
        int raw = generated.sources().manifest().files().stream().mapToInt(e -> (int)e.byteCount()).sum();
        // Candidate framed container measurement; final codec must separately prove strict round-trip/refusal.
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            data.write("KCG-SOURCE-PAYLOAD-V1\n".getBytes(StandardCharsets.UTF_8));
            text(data, ENTRY.value()); data.writeInt(generated.sources().manifest().files().size());
            for (var f : generated.sources().manifest().files()) { text(data, f.sourceId().value()); data.writeLong(f.byteCount()); data.write(generated.sources().bytes(f.sourceId())); }
        }
        var manifest = BaselineBuilder.buildManifest(compiled.generatedFiles());
        int manifestBlock = BaselineDescriptorCodec.encodeManifestBlock(manifest).length;
        int graph = graphBytes(generated.graph()).length;
        System.out.printf("Q19-P3 sources=%d rawBytes=%d sourceManifestBytes=%d payloadPrototypeBytes=%d graphBytes=%d legacyManifestBlockBytes=%d generatedFiles=%d%n",
                generated.sources().manifest().files().size(),raw,generated.sources().manifest().canonicalBytes().length,out.size(),graph,manifestBlock,manifest.size());
        assertEquals(4, generated.sources().manifest().files().size()); assertEquals(35,manifest.size());
        assertTrue(out.size() < 8*1024*1024+128*1024); assertTrue(graph < 16*1024*1024); assertTrue(manifestBlock < 2*1024*1024);
    }

    @Test void exclusiveHardLinkPublishesCompletePointerWithoutReplacingAnOccupiedCurrent() throws Exception {
        var state = Files.createDirectory(temp.resolve("state")); var pending = state.resolve("CURRENT.new"); var current = state.resolve("CURRENT");
        byte[] expected = ("a".repeat(64)+"\n").getBytes(StandardCharsets.US_ASCII);
        Files.write(pending, expected, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        assertFalse(Files.exists(current));
        Files.createLink(current, pending);
        assertArrayEquals(expected, Files.readAllBytes(current)); assertTrue(Files.isSameFile(current,pending));
        var different = state.resolve("different"); Files.writeString(different,"b".repeat(64)+"\n");
        assertThrows(FileAlreadyExistsException.class, () -> Files.createLink(current,different));
        assertArrayEquals(expected,Files.readAllBytes(current));
        var foreign = state.resolve("foreign"); Files.write(foreign,expected);
        assertFalse(Files.isSameFile(current,foreign),"same bytes alone are not cleanup ownership");
    }

    @Test void partialAndOccupiedSavesCanBeRetainedWithoutClobberingOrInventingPublication() throws Exception {
        var state = Files.createDirectory(temp.resolve("state")); var dir = Files.createDirectory(state.resolve("candidate"));
        var partial = dir.resolve("sources.kcg-source-set"); Files.writeString(partial,"partial source bytes",StandardOpenOption.CREATE_NEW);
        var before = tree(state);
        assertThrows(FileAlreadyExistsException.class, () -> Files.writeString(partial,"overwrite",StandardOpenOption.CREATE_NEW));
        assertEquals(before,tree(state)); assertFalse(Files.exists(state.resolve("CURRENT")));
        var user = temp.resolve("user"); Files.writeString(user,"keep"); var occupied = dir.resolve("descriptor.kcg-baseline"); Files.createSymbolicLink(occupied,user);
        assertThrows(IOException.class, () -> Files.newByteChannel(occupied,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)));
        assertEquals("keep",Files.readString(user)); assertEquals("partial source bytes",Files.readString(partial));
    }

    @Test void existingStateLockDoesNotCreateOrModifyBytesAndStillSerializesInspectors() throws Exception {
        var state = Files.createDirectory(temp.resolve("state")); Files.createFile(state.resolve("LOCK"));
        Files.createDirectory(state.resolve("transactions")); var before = tree(state);
        try (var held = assertInstanceOf(StateRootLock.HeldLock.class,new StateRootLock(state).tryAcquireExisting())) {
            assertInstanceOf(StateRootLock.LockFailure.class,new StateRootLock(state).tryAcquireExisting());
            assertEquals(before,tree(state));
        }
        assertEquals(before,tree(state));
        var missing = Files.createDirectory(temp.resolve("missing-lock"));
        assertInstanceOf(StateRootLock.LockFailure.class,new StateRootLock(missing).tryAcquireExisting());
        assertTrue(tree(missing).isEmpty());
    }

    private static byte[] graphBytes(ProjectGraph graph) {
        return assertInstanceOf(ProjectGraphSerialization.Success.class,new ProjectGraphSerializer().serialize(graph,ProjectGraphCanonicalFormatVersion.V2)).document().bytes();
    }
    private static void text(DataOutputStream data,String value) throws IOException { var bytes=value.getBytes(StandardCharsets.UTF_8);data.writeInt(bytes.length);data.write(bytes); }
}
