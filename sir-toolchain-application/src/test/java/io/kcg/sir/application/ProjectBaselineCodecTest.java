package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.*;
import io.kcg.sir.application.internal.bundle.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBaselineCodecTest {
    @TempDir Path temp;
    private Bundle bundle() throws Exception {
        var generated=generate(fixture(temp),temp.resolve("output"));
        var diagnostics=new ArrayList<ExecutionDiagnostic>();
        var c=ProjectCompilation.compile(generated.sources(),new io.kcg.sir.generator.springboot.api.SpringBootGenerator()::generate,diagnostics).orElseThrow();
        return build(generated.sources(),c,generated.graph(),temp.resolve("output"));
    }
    @Test void realBundleRoundTripIsCanonicalDefensivelyCopiedAndMeasured() throws Exception {
        var b=bundle();var loaded=load(b.descriptorBytes(),b.payloadBytes(),b.graphBytes());
        assertEquals(b.baselineId(),loaded.baselineId());assertEquals(b.descriptor(),loaded.descriptor());
        assertArrayEquals(b.descriptorBytes(),encodeDescriptor(decodeDescriptor(b.descriptorBytes())));
        assertArrayEquals(b.payloadBytes(),encodeSources(decodeSources(b.payloadBytes())));
        var copy=b.payloadBytes();copy[0]=0;assertNotEquals(0,b.payloadBytes()[0]);
        System.out.printf("Q19-final-wire descriptorBytes=%d payloadBytes=%d graphBytes=%d files=%d%n",b.descriptorBytes().length,b.payloadBytes().length,b.graphBytes().length,b.descriptor().manifest().size());
    }
    @Test void sourceContainerPreservesBomCrlfCommentsAndEmptyOriginalBytes() throws Exception {
        var raw=new LinkedHashMap<SourceId,byte[]>();raw.put(ENTRY,new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf,'a','\r','\n'});
        raw.put(SourceId.of("modules/z.sir"),"// exact\r\n".getBytes(StandardCharsets.UTF_8));raw.put(SourceId.of("modules/empty.sir"),new byte[0]);
        var sources=new SourceSnapshot(ENTRY,raw);var restored=decodeSources(encodeSources(sources));
        assertEquals(sources.manifest(),restored.manifest());raw.forEach((key,value)->assertArrayEquals(value,restored.bytes(key)));
    }
    @Test void sourceCountSizePathOrderUtf8AndTrailingPayloadCorruptionAreRejected() throws Exception {
        var sources=new SourceSnapshot(ENTRY,Map.of(ENTRY,"root".getBytes(StandardCharsets.UTF_8),SourceId.of("z.sir"),new byte[]{1,2}));var good=encodeSources(sources);
        var offsets=sourceOffsets(good);var bad=new ArrayList<byte[]>();
        var version=good.clone();version[0]=0;bad.add(version);
        for (int n:new int[]{-1,0,129,Integer.MAX_VALUE}) {var b=good.clone();ByteBuffer.wrap(b).putInt(offsets[0],n);bad.add(b);}
        for (long n:new long[]{-1,1024*1024+1,Long.MAX_VALUE}) {var b=good.clone();ByteBuffer.wrap(b).putLong(offsets[1],n);bad.add(b);}
        var invalidUtf=good.clone();invalidUtf["KCG-SOURCE-PAYLOAD-V1\n".length()+4]=(byte)0xff;bad.add(invalidUtf);
        bad.add(Arrays.copyOf(good,good.length+1));bad.add(Arrays.copyOf(good,good.length-1));
        // Duplicate the second path with the first one; canonical strictly-increasing order is required.
        var reverse=sourceWire(ENTRY,List.of("z.sir","project.sir"));bad.add(reverse);
        bad.add(sourceWire(ENTRY,List.of("project.sir","project.sir")));bad.add(sourceWire(SourceId.of("absent.sir"),List.of("project.sir")));
        for (var bytes:bad) assertThrows(IOException.class,()->decodeSources(bytes));
        assertArrayEquals(good,encodeSources(decodeSources(good)));
    }
    @Test void sourceBudgetsAcceptBoundaryAndRefuseOversizeBeforeMaterializingContent() throws Exception {
        var raw=new byte[1024*1024];var sources=new SourceSnapshot(ENTRY,Map.of(ENTRY,raw));assertArrayEquals(raw,decodeSources(encodeSources(sources)).bytes(ENTRY));
        assertThrows(Problem.class,()->encodeSources(new SourceSnapshot(ENTRY,Map.of(ENTRY,new byte[raw.length+1]))));
        var values=new LinkedHashMap<SourceId,byte[]>();values.put(ENTRY,raw);for(int i=0;i<7;i++)values.put(SourceId.of("m"+i+".sir"),raw);
        assertEquals(8,decodeSources(encodeSources(new SourceSnapshot(ENTRY,values))).manifest().files().size());
        values.put(SourceId.of("too.sir"),new byte[]{1});assertThrows(Problem.class,()->encodeSources(new SourceSnapshot(ENTRY,values)));
        assertThrows(Problem.class,()->decodeSources(new byte[MAX_PAYLOAD+1]));
    }
    @Test void descriptorVersionsMalformedLengthsManifestOrderAndTrailingBytesAreRejected() throws Exception {
        var b=bundle();var good=b.descriptorBytes();var corrupt=new ArrayList<byte[]>();
        var version=good.clone();ByteBuffer.wrap(version).putInt("KCG-PROJECT-BASELINE-V2\n".length(),3);corrupt.add(version);
        var length=good.clone();ByteBuffer.wrap(length).putInt("KCG-PROJECT-BASELINE-V2\n".length()+4,Integer.MAX_VALUE);corrupt.add(length);
        var v1=good.clone();replaceSameLength(v1,"V0_2","V0_1");corrupt.add(v1);
        var format=good.clone();replaceSameLength(format,"V2","V1");corrupt.add(format);
        corrupt.add(Arrays.copyOf(good,good.length-1));corrupt.add(Arrays.copyOf(good,good.length+1));
        for(var bytes:corrupt) assertThrows(IOException.class,()->decodeDescriptor(bytes));
        assertThrows(Problem.class,()->decodeDescriptor(new byte[MAX_DESCRIPTOR+1]));
        var d=b.descriptor();var reversed=new ArrayList<>(d.manifest());Collections.reverse(reversed);
        assertThrows(IllegalArgumentException.class,()->new Descriptor(d.outputRoot(),d.entry(),d.sourceSetSha(),d.payloadSize(),d.payloadSha(),d.graphDigest(),d.graphSize(),d.graphSha(),d.targetId(),d.loweredIrVersion(),reversed,BaselineDescriptorCodec.computeManifestDigest(reversed)));
    }
    @Test void recomputedOuterPayloadShaCannotHideWrongInnerSourceManifest() throws Exception {
        var b=bundle();var original=b.sources();var values=new LinkedHashMap<SourceId,byte[]>();original.manifest().files().forEach(e->values.put(e.sourceId(),original.bytes(e.sourceId())));
        var course=SourceId.of("modules/course.sir");var raw=values.get(course);var changed=Arrays.copyOf(raw,raw.length+1);changed[changed.length-1]='\n';values.put(course,changed);
        var payload=encodeSources(new SourceSnapshot(ENTRY,values));var d=b.descriptor();
        var forged=new Descriptor(d.outputRoot(),d.entry(),d.sourceSetSha(),payload.length,Sha256.hexDigest(payload),d.graphDigest(),d.graphSize(),d.graphSha(),d.targetId(),d.loweredIrVersion(),d.manifest(),d.manifestDigest());
        assertThrows(Problem.class,()->load(encodeDescriptor(forged),payload,b.graphBytes()));
    }
    @Test void internallyConsistentWrongTargetAndManifestAreStillRefusedBySourceRecompilation() throws Exception {
        var b=bundle();var original=b.descriptor();
        for(String kind:List.of("target","manifest")) {
            var entries=new ArrayList<>(original.manifest());
            if(kind.equals("manifest")) {var e=entries.getFirst();entries.set(0,new BaselineManifestEntry(e.relativePath(),e.byteCount(),"0".repeat(64),e.artifactId(),e.ownerSymbol()));}
            var forged=new Descriptor(original.outputRoot(),original.entry(),original.sourceSetSha(),original.payloadSize(),original.payloadSha(),original.graphDigest(),original.graphSize(),original.graphSha(),kind.equals("target")?"other-target":original.targetId(),original.loweredIrVersion(),entries,BaselineDescriptorCodec.computeManifestDigest(entries));
            var bytes=encodeDescriptor(forged);var id=id(bytes);var state=temp.resolve("forged-"+kind);var dir=state.resolve("baselines/"+id);Files.createDirectories(dir);Files.createFile(state.resolve("LOCK"));
            Files.write(dir.resolve(ProjectBaselineStore.DESCRIPTOR),bytes);Files.write(dir.resolve(ProjectBaselineStore.SOURCES),b.payloadBytes());Files.write(dir.resolve(ProjectBaselineStore.GRAPH),b.graphBytes());
            var pointer=dir.resolve(ProjectBaselineStore.POINTER);Files.writeString(pointer,id+"\n");Files.createLink(state.resolve("CURRENT"),pointer);
            var before=tree(state);var failure=assertInstanceOf(ProjectBaselineResult.Failure.class,new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(state,original.outputRoot(),id)));
            assertTrue(failure.diagnostics().stream().anyMatch(d->d.code().endsWith("FORMAT-001")),failure.diagnostics().toString());assertEquals(before,tree(state));
        }
    }
    @Test void encoderRefusesUnpairedSurrogatesRatherThanSilentlyReplacingIdentityFields() throws Exception {
        var b=bundle();var d=b.descriptor();
        var bad=new Descriptor(d.outputRoot(),d.entry(),d.sourceSetSha(),d.payloadSize(),d.payloadSha(),d.graphDigest(),d.graphSize(),d.graphSha(),String.valueOf((char)0xd800),d.loweredIrVersion(),d.manifest(),d.manifestDigest());
        var failure=assertThrows(Problem.class,()->encodeDescriptor(bad));assertTrue(failure.code.endsWith("FORMAT-001"));
        assertArrayEquals(b.descriptorBytes(),encodeDescriptor(d));
    }
    @Test void descriptorCountFieldAndAggregateBudgetsStopBeforeUnboundedEncoding() throws Exception {
        var b=bundle();var d=b.descriptor();var entries=new ArrayList<BaselineManifestEntry>();
        var longId=new io.kcg.sir.lowering.api.LoweredNodeId("x".repeat(MAX_TEXT));
        for(int i=0;i<200;i++) entries.add(new BaselineManifestEntry(String.format(java.util.Locale.ROOT,"f%04d",i),0,"0".repeat(64),longId,Optional.empty()));
        var oversized=new Descriptor(d.outputRoot(),d.entry(),d.sourceSetSha(),d.payloadSize(),d.payloadSha(),d.graphDigest(),d.graphSize(),d.graphSha(),d.targetId(),d.loweredIrVersion(),entries,BaselineDescriptorCodec.computeManifestDigest(entries));
        assertTrue(assertThrows(Problem.class,()->encodeDescriptor(oversized)).code.endsWith("LIMIT-001"));
        var badField=new Descriptor(d.outputRoot(),d.entry(),d.sourceSetSha(),d.payloadSize(),d.payloadSha(),d.graphDigest(),d.graphSize(),d.graphSha(),"x".repeat(MAX_TEXT+1),d.loweredIrVersion(),d.manifest(),d.manifestDigest());
        assertTrue(assertThrows(Problem.class,()->encodeDescriptor(badField)).code.endsWith("LIMIT-001"));
    }
    private static int[] sourceOffsets(byte[] bytes) {var b=ByteBuffer.wrap(bytes);b.position("KCG-SOURCE-PAYLOAD-V1\n".length());int len=b.getInt();b.position(b.position()+len);int count=b.position();b.getInt();len=b.getInt();b.position(b.position()+len);return new int[]{count,b.position()};}
    private static byte[] sourceWire(SourceId entry,List<String> names) throws Exception {var out=new ByteArrayOutputStream();try(var d=new DataOutputStream(out)){d.write("KCG-SOURCE-PAYLOAD-V1\n".getBytes(StandardCharsets.UTF_8));str(d,entry.value());d.writeInt(names.size());for(var name:names){str(d,name);d.writeLong(0);}}return out.toByteArray();}
    private static void str(DataOutputStream d,String text)throws Exception{var bytes=text.getBytes(StandardCharsets.UTF_8);d.writeInt(bytes.length);d.write(bytes);}
    private static void replaceSameLength(byte[] bytes,String before,String after){var text=new String(bytes,StandardCharsets.ISO_8859_1);int at=text.indexOf(before);assertTrue(at>=0);System.arraycopy(after.getBytes(StandardCharsets.ISO_8859_1),0,bytes,at,after.length());}
}
