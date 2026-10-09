package io.kcg.sir.application.internal.projectbaseline;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.projectupdate.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.Path;
import java.util.*;

/** Immutable publication relations. CURRENT remains the sole commit point; no mutable history head. */
public final class ProjectPublicationHistory {
    public static final String DIRECTORY="project-history";
    public static final int MAX_BASELINES=8, MAX_EDGES=32, MAX_EDGE_BYTES=65536;
    public static final long MAX_HISTORY_BYTES=64L*1024*1024;
    private static final String MAGIC="KCG-PROJECT-PUBLISH-EDGE-V1\n";
    private ProjectPublicationHistory() {}
    public record Edge(String origin,String b0,String b1,Path outputRoot,String baseSource,String candidateSource,
                       String baseGraph,String candidateGraph,String baseManifest,String candidateManifest,
                       String contextDigest,String planDigest,String subject) {
        public Edge {
            for(String s:List.of(origin,b0,b1,baseSource,candidateSource,baseGraph,candidateGraph,baseManifest,candidateManifest,contextDigest,planDigest))hex(s);
            Objects.requireNonNull(outputRoot);Objects.requireNonNull(subject);
            if(b0.equals(b1) || !outputRoot.isAbsolute() || !outputRoot.equals(outputRoot.normalize()))throw new IllegalArgumentException("invalid publication roots/IDs");
            validText(outputRoot.toString(),MAX_OUTPUT_PATH);validText(subject,MAX_TEXT);if(subject.isBlank())throw new IllegalArgumentException("blank subject");
        }
        public static Edge of(String origin,Bundle base,Bundle candidate,String context,String plan,String subject) {
            if(!base.descriptor().outputRoot().equals(candidate.descriptor().outputRoot()))throw new IllegalArgumentException("cross-root publication");
            return new Edge(origin,base.baselineId(),candidate.baselineId(),base.descriptor().outputRoot(),base.descriptor().sourceSetSha(),candidate.descriptor().sourceSetSha(),
                base.descriptor().graphDigest(),candidate.descriptor().graphDigest(),base.descriptor().manifestDigest(),candidate.descriptor().manifestDigest(),context,plan,subject);
        }
        public byte[] bytes() { return encode(MAGIC,List.of(origin,b0,b1,outputRoot.toString(),baseSource,candidateSource,baseGraph,candidateGraph,baseManifest,candidateManifest,contextDigest,planDigest,subject)); }
        public String id() { return Sha256.hexDigest(bytes()); }
        public static Edge decode(byte[] bytes) throws IOException {
            try {
                var f=decodeFrames(bytes,MAGIC,13);var e=new Edge(f.get(0),f.get(1),f.get(2),Path.of(f.get(3)),f.get(4),f.get(5),f.get(6),f.get(7),f.get(8),f.get(9),f.get(10),f.get(11),f.get(12));
                require(Arrays.equals(e.bytes(),bytes),"noncanonical publication record");return e;
            }catch(IllegalArgumentException e) {throw problem("FORMAT-001","invalid publication record: "+e.getMessage());}
        }
    }
    public record View(String origin,Set<String> baselineIds,Set<String> edgeIds,long byteCount,Set<String> transactionsKeys,int receiptCount) {
        public View {baselineIds=Set.copyOf(baselineIds);edgeIds=Set.copyOf(edgeIds);transactionsKeys=Set.copyOf(transactionsKeys);}
    }
    /** Read-only validation. A valid but disconnected Bundle is not silently accepted as published history. */
    public static View validate(SecureFileAccess files,String current) throws IOException {
        hex(current);var history=files.list(DIRECTORY,MAX_EDGES+2);
        require(history.contains("origin"),"history origin missing");budget(history.size()-1-(history.contains("receipts")?1:0)<=MAX_EDGES,"publication relation count exceeded");String origin=ProjectBaselineStore.pointer(files.read(DIRECTORY+"/origin",65));
        var ids=files.list("baselines",MAX_BASELINES);require(ids.contains(origin) && ids.contains(current),"history lacks origin/current Bundle");
        var descriptors=new HashMap<String,Descriptor>();long total=65;
        for(String id:ids) {
            try {hex(id);}catch(IllegalArgumentException e) {throw problem("STATE-001","unknown baseline directory");}
            String p="baselines/"+id+"/";
            var d=decodeDescriptor(files.read(p+ProjectBaselineStore.DESCRIPTOR,MAX_DESCRIPTOR));
            total+=d.payloadSize()+d.graphSize()+encodeDescriptor(d).length+65;budget(total<=MAX_HISTORY_BYTES,"cumulative history byte budget exceeded");
            var b=new ProjectBaselineStore(files).load(id);descriptors.put(id,b.descriptor());
        }
        Path root=descriptors.get(origin).outputRoot();var edges=new ArrayList<Edge>();var edgeIds=new HashSet<String>();
        for(String name:history)if(!name.equals("origin") && !name.equals("receipts")) {
            try {hex(name);}catch(IllegalArgumentException e) {throw problem("STATE-001","unknown history member: "+name);}
            byte[] bytes=files.read(DIRECTORY+"/"+name,MAX_EDGE_BYTES);total+=bytes.length;budget(total<=MAX_HISTORY_BYTES,"cumulative history byte budget exceeded");
            var e=Edge.decode(bytes);require(name.equals(e.id()) && e.origin().equals(origin) && e.outputRoot().equals(root),"history name/origin/output mismatch");
            Descriptor b=descriptors.get(e.b0()),c=descriptors.get(e.b1());require(b!=null && c!=null,"publication references a missing Bundle");
            require(b.outputRoot().equals(root) && c.outputRoot().equals(root) && b.sourceSetSha().equals(e.baseSource()) && c.sourceSetSha().equals(e.candidateSource())
                && b.graphDigest().equals(e.baseGraph()) && c.graphDigest().equals(e.candidateGraph()) && b.manifestDigest().equals(e.baseManifest()) && c.manifestDigest().equals(e.candidateManifest()),"publication source/graph/manifest evidence mismatch");
            edges.add(e);edgeIds.add(name);
        }
        var reached=new HashSet<String>(Set.of(origin));boolean changed;
        do {changed=false;for(var edge:edges)if(reached.contains(edge.b0()))changed|=reached.add(edge.b1());}while(changed);
        require(reached.equals(ids),"unexplained/unpublished history Bundle");
        require(edges.stream().allMatch(e->reached.contains(e.b0()) && reached.contains(e.b1())),"disconnected publication record");
        var transactionKeys=new HashSet<String>();var completedEdges=new HashSet<String>();int receiptCount=0;
        if(history.contains("receipts"))for(String transaction:files.list(ProjectUpdateReceipt.DIRECTORY,ProjectUpdateReceipt.MAX_RECEIPTS)) {
            var receipt=ProjectUpdateReceipt.read(files,transaction);var binding=receipt.binding();var p=binding.publication();total+=receipt.byteCount();receiptCount++;
            budget(total<=MAX_HISTORY_BYTES,"cumulative history/receipt byte budget exceeded");
            require(p.origin().equals(origin) && p.outputRoot().equals(root) && ids.contains(p.b0()),"receipt is not rooted in published history");
            var base=descriptors.get(p.b0());require(base.sourceSetSha().equals(p.baseSource()) && base.graphDigest().equals(p.baseGraph()) && base.manifestDigest().equals(p.baseManifest()),"receipt baseline evidence mismatch");
            if(receipt.trail().state().phase()==ProjectUpdateTrail.Phase.COMPLETED) {require(edgeIds.contains(p.id()) && ids.contains(p.b1()),"completed receipt lacks publication relation");completedEdges.add(p.id());}
            transactionKeys.add(binding.transactionsKey());
        }
        require(completedEdges.equals(edgeIds),"publication relation lacks a completed transaction receipt");
        return new View(origin,ids,edgeIds,total,transactionKeys,receiptCount);
    }
    public static void validText(String text,int max) {
        Objects.requireNonNull(text);
        if(text.length()>max || text.indexOf('\0')>=0 || !StandardCharsets.UTF_8.newEncoder().canEncode(text) || text.getBytes(StandardCharsets.UTF_8).length>max)throw new IllegalArgumentException("invalid/oversized UTF-8 field");
    }
    /** Strict bounded framing shared by the new publication/transaction family, not an old codec change. */
    public static byte[] encode(String magic,List<String> fields) {
        var out=new ByteArrayOutputStream();out.writeBytes(magic.getBytes(StandardCharsets.US_ASCII));out.writeBytes(ByteBuffer.allocate(4).putInt(fields.size()).array());
        for(String f:fields) {validText(f,MAX_TEXT);byte[] bytes=f.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_EDGE_BYTES-4-out.size())throw new IllegalArgumentException("record budget exceeded");out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());out.writeBytes(bytes);}
        return out.toByteArray();
    }
    public static List<String> decodeFrames(byte[] bytes,String magic,int count) throws IOException {
        budget(bytes.length<=MAX_EDGE_BYTES,"publication/transaction record too large");var b=ByteBuffer.wrap(bytes);byte[] header=magic.getBytes(StandardCharsets.US_ASCII);
        try {
            for(byte c:header)require(b.get()==c,"unknown publication/transaction magic");require(b.getInt()==count,"invalid field count");var fields=new ArrayList<String>();
            for(int i=0;i<count;i++) {
                int size=b.getInt();require(size>=0 && size<=MAX_TEXT && size<=b.remaining(),"invalid field frame");var slice=b.slice();slice.limit(size);
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(slice).toString();validText(text,MAX_TEXT);fields.add(text);b.position(b.position()+size);
            }
            require(!b.hasRemaining(),"trailing record bytes");return List.copyOf(fields);
        }catch(BufferUnderflowException|CharacterCodingException|IllegalArgumentException e) {throw problem("FORMAT-001","truncated/invalid record: "+e.getMessage());}
    }
}
