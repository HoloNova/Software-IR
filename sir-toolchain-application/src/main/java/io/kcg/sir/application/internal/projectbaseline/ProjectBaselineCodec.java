package io.kcg.sir.application.internal.projectbaseline;

import io.kcg.sir.application.internal.*;
import io.kcg.sir.application.internal.bundle.*;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.Path;
import java.util.*;

/** New formats only. No filesystem access and no reinterpretation of the legacy descriptor. */
public final class ProjectBaselineCodec {
    public static final int MAX_PAYLOAD = 8*1024*1024+128*1024, MAX_DESCRIPTOR = 2*1024*1024, MAX_GRAPH = 16*1024*1024;
    public static final int MAX_GENERATED_FILE = 8*1024*1024, MAX_MANIFEST = 8192, MAX_TEXT = 16384, MAX_OUTPUT_PATH = 4096;
    private static final byte[] SOURCE_MAGIC = "KCG-SOURCE-PAYLOAD-V1\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DESCRIPTOR_MAGIC = "KCG-PROJECT-BASELINE-V2\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID_DOMAIN = "KCG-PROJECT-BASELINE-ID-V2\0".getBytes(StandardCharsets.US_ASCII);
    private ProjectBaselineCodec() {}

    public static final class Problem extends IOException {
        public final String code;
        public Problem(String suffix,String message) { super(message);code="SIR-APP-PROJECT-BASELINE-"+suffix; }
    }
    public static Problem problem(String suffix,String message) { return new Problem(suffix,message); }
    public static void require(boolean condition,String message) throws Problem { if (!condition) throw problem("FORMAT-001",message); }
    public static void budget(boolean condition,String message) throws Problem { if (!condition) throw problem("LIMIT-001",message); }
    public static void hex(String value) { if (value==null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid SHA-256 hex"); }

    public record Descriptor(Path outputRoot,SourceId entry,String sourceSetSha,long payloadSize,String payloadSha,
            String graphDigest,long graphSize,String graphSha,String targetId,String loweredIrVersion,
            List<BaselineManifestEntry> manifest,String manifestDigest) {
        public Descriptor {
            Objects.requireNonNull(outputRoot); Objects.requireNonNull(entry);Objects.requireNonNull(targetId);Objects.requireNonNull(loweredIrVersion);
            if (!outputRoot.isAbsolute() || !outputRoot.equals(outputRoot.normalize())) throw new IllegalArgumentException("outputRoot must be absolute normalized");
            if (payloadSize<1 || payloadSize>MAX_PAYLOAD || graphSize<1 || graphSize>MAX_GRAPH) throw new IllegalArgumentException("payload/graph size outside budget");
            for (String hash : List.of(sourceSetSha,payloadSha,graphDigest,graphSha,manifestDigest)) hex(hash);
            if (targetId.isBlank() || loweredIrVersion.isBlank()) throw new IllegalArgumentException("blank target/IR version");
            manifest=List.copyOf(manifest);
            if (manifest.isEmpty() || manifest.size()>MAX_MANIFEST) throw new IllegalArgumentException("manifest count outside budget");
            String previous=null;
            for (var e : manifest) {
                if (PathGuard.validateRelativePath(e.relativePath())!=null || e.relativePath().getBytes(StandardCharsets.UTF_8).length>MAX_OUTPUT_PATH
                    || (previous!=null && previous.compareTo(e.relativePath())>=0) || e.byteCount()>MAX_GENERATED_FILE)
                    throw new IllegalArgumentException("invalid, unsorted or oversized manifest entry: "+e.relativePath());
                previous=e.relativePath();
            }
            if (!BaselineDescriptorCodec.computeManifestDigest(manifest).equals(manifestDigest)) throw new IllegalArgumentException("manifest digest mismatch");
        }
    }
    public record Bundle(Descriptor descriptor,SourceSnapshot sources,ProjectGraph graph,byte[] descriptorBytes,byte[] payloadBytes,byte[] graphBytes) {
        public Bundle { descriptorBytes=descriptorBytes.clone();payloadBytes=payloadBytes.clone();graphBytes=graphBytes.clone(); }
        @Override public byte[] descriptorBytes() { return descriptorBytes.clone(); }
        @Override public byte[] payloadBytes() { return payloadBytes.clone(); }
        @Override public byte[] graphBytes() { return graphBytes.clone(); }
        public String baselineId() { return id(descriptorBytes); }
    }

    public static byte[] encodeSources(SourceSnapshot sources) throws IOException {
        var out=new ByteArrayOutputStream();
        try (var d=new DataOutputStream(out)) {
            d.write(SOURCE_MAGIC);text(d,sources.entry().value(),SourceSetManifest.MAX_PATH_BYTES);d.writeInt(sources.manifest().files().size());
            long total=0;
            for (var e : sources.manifest().files()) {
                total+=e.byteCount();budget(e.byteCount()<=ProjectSourceReader.MAX_FILE_BYTES && total<=ProjectSourceReader.MAX_TOTAL_BYTES,"source byte budget exceeded");
                text(d,e.sourceId().value(),SourceSetManifest.MAX_PATH_BYTES);d.writeLong(e.byteCount());d.write(sources.bytes(e.sourceId()));
            }
        }
        budget(out.size()<=MAX_PAYLOAD,"source payload budget exceeded");return out.toByteArray();
    }
    public static SourceSnapshot decodeSources(byte[] bytes) throws IOException {
        budget(bytes.length<=MAX_PAYLOAD,"source payload budget exceeded");var b=ByteBuffer.wrap(bytes);magic(b,SOURCE_MAGIC);
        try {
            var entry=SourceSetManifest.strictId(text(b,SourceSetManifest.MAX_PATH_BYTES));int count=b.getInt();budget(count>=1 && count<=SourceSetManifest.MAX_FILES,"source count outside budget");
            var values=new LinkedHashMap<SourceId,byte[]>();String previous=null;long total=0;
            for (int i=0;i<count;i++) {
                var name=text(b,SourceSetManifest.MAX_PATH_BYTES);var sid=SourceSetManifest.strictId(name);
                require(previous==null || previous.compareTo(name)<0,"sources must be strictly sorted");previous=name;
                long size=b.getLong();budget(size>=0 && size<=ProjectSourceReader.MAX_FILE_BYTES,"individual source budget exceeded");
                total+=size;budget(total<=ProjectSourceReader.MAX_TOTAL_BYTES,"total source budget exceeded");require(size<=b.remaining(),"truncated source bytes");
                byte[] raw=new byte[(int)size];b.get(raw);values.put(sid,raw);
            }
            require(!b.hasRemaining(),"trailing source payload bytes");var result=new SourceSnapshot(entry,values);
            require(Arrays.equals(bytes,encodeSources(result)),"noncanonical source payload");return result;
        } catch (BufferUnderflowException|IllegalArgumentException e) { throw problem("FORMAT-001","invalid source payload: "+e.getMessage()); }
    }
    public static byte[] encodeDescriptor(Descriptor v) throws IOException {
        var out=new ByteArrayOutputStream();
        try (var d=new DataOutputStream(out)) {
            d.write(DESCRIPTOR_MAGIC);d.writeInt(2);text(d,v.outputRoot().toString(),MAX_OUTPUT_PATH);text(d,v.entry().value(),SourceSetManifest.MAX_PATH_BYTES);
            text(d,v.sourceSetSha());d.writeLong(v.payloadSize());text(d,v.payloadSha());text(d,"V0_2");text(d,v.graphDigest());text(d,"V2");
            d.writeLong(v.graphSize());text(d,v.graphSha());text(d,v.targetId());text(d,v.loweredIrVersion());text(d,v.manifestDigest());d.writeInt(v.manifest().size());
            for (var e : v.manifest()) {
                text(d,e.relativePath(),MAX_OUTPUT_PATH);d.writeLong(e.byteCount());text(d,e.sha256Hex());text(d,e.artifactId().value());
                d.writeByte(e.ownerSymbol().isPresent()?1:0);if (e.ownerSymbol().isPresent()) text(d,e.ownerSymbol().get().value());
                budget(out.size()<=MAX_DESCRIPTOR,"descriptor budget exceeded during encoding");
            }
        }
        budget(out.size()<=MAX_DESCRIPTOR,"descriptor budget exceeded");return out.toByteArray();
    }
    public static Descriptor decodeDescriptor(byte[] bytes) throws IOException {
        budget(bytes.length<=MAX_DESCRIPTOR,"descriptor budget exceeded");var b=ByteBuffer.wrap(bytes);magic(b,DESCRIPTOR_MAGIC);
        try {
            if (b.getInt()!=2) throw problem("VERSION-001","unsupported project baseline version");
            var root=Path.of(text(b,MAX_OUTPUT_PATH));var entry=SourceSetManifest.strictId(text(b,SourceSetManifest.MAX_PATH_BYTES));
            var sourceSha=text(b);long payloadSize=b.getLong();var payloadSha=text(b);
            if (!text(b).equals("V0_2")) throw problem("VERSION-001","project baseline requires graph V0_2");
            var graphDigest=text(b);if (!text(b).equals("V2")) throw problem("VERSION-001","project baseline requires snapshot V2");
            long graphSize=b.getLong();var graphSha=text(b);var target=text(b);var ir=text(b);var manifestSha=text(b);int count=b.getInt();
            budget(payloadSize>0 && payloadSize<=MAX_PAYLOAD && graphSize>0 && graphSize<=MAX_GRAPH,"payload/graph budget exceeded");
            budget(count>=1 && count<=MAX_MANIFEST,"manifest count outside budget");var manifest=new ArrayList<BaselineManifestEntry>();
            for (int i=0;i<count;i++) {
                var path=text(b,MAX_OUTPUT_PATH);long size=b.getLong();budget(size>=0 && size<=MAX_GENERATED_FILE,"generated-file budget exceeded");
                var sha=text(b);var artifact=new LoweredNodeId(text(b));int flag=b.get()&255;require(flag<=1,"invalid owner flag");
                var owner=flag==0?Optional.<SymbolId>empty():Optional.of(new SymbolId(text(b)));manifest.add(new BaselineManifestEntry(path,size,sha,artifact,owner));
            }
            require(!b.hasRemaining(),"trailing descriptor bytes");var result=new Descriptor(root,entry,sourceSha,payloadSize,payloadSha,graphDigest,graphSize,graphSha,target,ir,manifest,manifestSha);
            require(Arrays.equals(bytes,encodeDescriptor(result)),"noncanonical descriptor");return result;
        } catch (BufferUnderflowException|IllegalArgumentException e) { throw problem("FORMAT-001","invalid project descriptor: "+e.getMessage()); }
    }
    public static Bundle build(SourceSnapshot sources,SirCompilation.CompilationSnapshot compilation,ProjectGraph graph,Path outputRoot) throws IOException {
        var serialized=new ProjectGraphSerializer().serialize(graph,ProjectGraphCanonicalFormatVersion.V2);
        if (!(serialized instanceof ProjectGraphSerialization.Success ok)) throw problem("FORMAT-001","cannot serialize V2 graph");
        var payload=encodeSources(sources);var graphBytes=ok.document().bytes();budget(graphBytes.length<=MAX_GRAPH,"graph budget exceeded");
        var manifest=BaselineBuilder.buildManifest(compilation.generatedFiles());budget(manifest.size()<=MAX_MANIFEST,"manifest budget exceeded");
        for (var entry : manifest) budget(entry.byteCount()<=MAX_GENERATED_FILE,"generated-file budget exceeded");
        var descriptor=new Descriptor(outputRoot,sources.entry(),sources.sha256Hex(),payload.length,Sha256.hexDigest(payload),graph.canonicalDigest(),graphBytes.length,
            Sha256.hexDigest(graphBytes),compilation.loweredModel().targetId(),compilation.loweredModel().irVersion().value(),manifest,BaselineDescriptorCodec.computeManifestDigest(manifest));
        return load(encodeDescriptor(descriptor),payload,graphBytes);
    }
    public static Bundle load(byte[] descriptorBytes,byte[] payload,byte[] graphBytes) throws IOException {
        var d=decodeDescriptor(descriptorBytes);budget(payload.length<=MAX_PAYLOAD && graphBytes.length<=MAX_GRAPH,"bundle byte budget exceeded");
        require(d.payloadSize()==payload.length && d.payloadSha().equals(Sha256.hexDigest(payload)),"source payload hash/length mismatch");
        require(d.graphSize()==graphBytes.length && d.graphSha().equals(Sha256.hexDigest(graphBytes)),"graph hash/length mismatch");
        var sources=decodeSources(payload);require(d.entry().equals(sources.entry()) && d.sourceSetSha().equals(sources.sha256Hex()),"source-set evidence mismatch");
        var parsed=new ProjectGraphLoader().load(new CanonicalProjectGraphDocument(graphBytes));
        if (!(parsed instanceof ProjectGraphAnalysis.Success ok)) throw problem("FORMAT-001","graph rejected: "+parsed.diagnostics());
        var graph=ok.graph();if (graph.version()!=GraphVersion.V0_2) throw problem("VERSION-001","project bundle requires a V2 graph");
        require(d.graphDigest().equals(graph.canonicalDigest()),"graph canonical digest mismatch");
        var origins=graph.nodes().stream().filter(n->n instanceof ProjectGraphNode.Project).map(n->((ProjectGraphNode.Project)n).provenance()).toList();
        require(origins.size()==1 && origins.getFirst().sourceId().equals(sources.entry()) && origins.getFirst().sourceSet().equals(Optional.of(sources.manifest())),"graph source provenance mismatch");
        require(Arrays.equals(graphBytes,((ProjectGraphSerialization.Success)new ProjectGraphSerializer().serialize(graph,ProjectGraphCanonicalFormatVersion.V2)).document().bytes()),"noncanonical graph bytes");
        return new Bundle(d,sources,graph,descriptorBytes,payload,graphBytes);
    }
    public static String id(byte[] descriptor) {
        var bytes=new byte[ID_DOMAIN.length+descriptor.length];System.arraycopy(ID_DOMAIN,0,bytes,0,ID_DOMAIN.length);System.arraycopy(descriptor,0,bytes,ID_DOMAIN.length,descriptor.length);return Sha256.hexDigest(bytes);
    }
    private static void magic(ByteBuffer b,byte[] expected) throws Problem {
        if (b.remaining()<expected.length) throw problem("VERSION-001","missing project format magic");
        for (byte v : expected) if (b.get()!=v) throw problem("VERSION-001","unsupported/legacy format magic");
    }
    private static void text(DataOutputStream d,String text) throws IOException { text(d,text,MAX_TEXT); }
    private static void text(DataOutputStream d,String text,int max) throws IOException {
        budget(text.length()<=max,"string budget exceeded before encoding");
        ByteBuffer encoded;
        try { encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text)); }
        catch (CharacterCodingException e) { throw problem("FORMAT-001","field cannot be encoded as strict UTF-8"); }
        var bytes=new byte[encoded.remaining()];encoded.get(bytes);budget(bytes.length<=max,"string budget exceeded");require(!text.contains("\0"),"NUL in field");d.writeInt(bytes.length);d.write(bytes);
    }
    private static String text(ByteBuffer b) throws IOException { return text(b,MAX_TEXT); }
    private static String text(ByteBuffer b,int max) throws IOException {
        int length=b.getInt();budget(length>=0 && length<=max,"string budget exceeded");require(length<=b.remaining(),"truncated string");
        var slice=b.slice();slice.limit(length);String value;
        try { value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(slice).toString(); }
        catch (CharacterCodingException e) { throw problem("FORMAT-001","invalid UTF-8 field"); }
        b.position(b.position()+length);require(!value.contains("\0"),"NUL in field");return value;
    }
}
