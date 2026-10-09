package io.kcg.sir.application.internal.projectbaseline;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Initial save only: never overwrites/deletes a Bundle or changes a different CURRENT. */
public final class ProjectBaselineStore {
    public static final String DESCRIPTOR="descriptor.kcg-baseline", SOURCES="sources.kcg-source-set", GRAPH="graph.kcg-psg", POINTER="baseline-id.kcg-pointer";
    public static final Set<String> MEMBERS=Set.of(DESCRIPTOR,SOURCES,GRAPH,POINTER);
    private final SecureFileAccess files;
    public ProjectBaselineStore(SecureFileAccess files) { this.files=files; }
    public Optional<String> current() throws IOException {
        if (!files.exists("CURRENT")) return Optional.empty();return Optional.of(pointer(files.read("CURRENT",65)));
    }
    public void guardState(String id,boolean allowPending) throws IOException {
        hex(id);
        var root=files.list("");
        if (!Set.of("LOCK","baselines","CURRENT","CURRENT.new",ProjectPublicationHistory.DIRECTORY,"project-transactions").containsAll(root)) throw problem("STATE-001","state contains transactions or unknown objects; retained without cleanup");
        if (!allowPending && root.contains("CURRENT.new")) throw problem("STATE-001","pending CURRENT.new requires explicit registration retry; inspection never cleans it");
        if (root.contains(ProjectPublicationHistory.DIRECTORY)) {
            require(current().equals(Optional.of(id)),"history validation requires the actual CURRENT");
            if(root.contains("project-transactions"))require(files.list("project-transactions",1).isEmpty(),"active project transaction requires explicit recovery");
            var history=ProjectPublicationHistory.validate(files,id);
            if(root.contains("project-transactions"))require(history.transactionsKeys().contains(files.directoryIdentity("project-transactions").toString()),"empty transaction root has no terminal ownership proof");
        } else if (root.contains("project-transactions")) {
            throw problem("STATE-001","project transaction root lacks recognized history/terminal evidence");
        } else if (root.contains("baselines")) {
            var ids=files.list("baselines");
            if (!ids.isEmpty() && !ids.equals(Set.of(id))) throw problem("STATE-001","state contains a different/unknown candidate; retained without cleanup");
        }
    }
    public Bundle load(String id) throws IOException {
        hex(id);String prefix="baselines/"+id+"/";
        var names=files.list("baselines/"+id);
        if (names.contains("source.sir")) throw problem("VERSION-001","legacy single-source Bundle is not a project baseline");
        require(names.equals(MEMBERS),"project Bundle has missing/unknown members: "+names);
        var bundle=ProjectBaselineCodec.load(files.read(prefix+DESCRIPTOR,MAX_DESCRIPTOR),files.read(prefix+SOURCES,MAX_PAYLOAD),files.read(prefix+GRAPH,MAX_GRAPH));
        require(id.equals(bundle.baselineId()),"Bundle directory ID does not match canonical descriptor");
        require(pointer(files.read(prefix+POINTER,65)).equals(id),"Bundle pointer-anchor bytes mismatch");return bundle;
    }
    public void verifyPointerIdentity(String id,boolean includePending) throws IOException {
        var anchor=files.identity(anchor(id));
        if (files.exists("CURRENT")) require(anchor.equals(files.identity("CURRENT")),"CURRENT is not the candidate's physical pointer anchor");
        if (includePending && files.exists("CURRENT.new")) {
            require(pointer(files.read("CURRENT.new",65)).equals(id),"pending pointer targets a different baseline");
            require(anchor.equals(files.identity("CURRENT.new")),"pending pointer is an external replacement, even if bytes match");
        }
    }
    @FunctionalInterface public interface Verification { void verify(Bundle bundle) throws IOException; }
    public boolean publish(Bundle candidate,Verification verify,Consumer<String> checkpoint) throws IOException {
        String id=candidate.baselineId();var current=current();
        // Decode the existing family before rejecting different IDs, so V1 cannot be silently treated as V2.
        if (current.isPresent()) {
            var existing=load(current.get());guardState(current.get(),true);
            if (!current.get().equals(id)) throw problem("REQUEST-001","state already bound to a different baseline");
            same(candidate,existing);verify.verify(existing);verifyPointerIdentity(id,true);
            if (files.exists("CURRENT.new")) cleanup(id,checkpoint);
            files.assertRootUnchanged();return false;
        }
        guardState(id,true);checkpoint.accept("before-save");
        if (!files.exists("baselines")) files.createDirectory("baselines");
        String dir="baselines/"+id;
        if (!files.exists(dir)) {
            files.createDirectory(dir);checkpoint.accept("candidate-directory");
            files.writeNew(dir+"/"+SOURCES,candidate.payloadBytes(),checkpoint);
            files.writeNew(dir+"/"+GRAPH,candidate.graphBytes(),checkpoint);
            files.writeNew(dir+"/"+DESCRIPTOR,candidate.descriptorBytes(),checkpoint);
            files.writeNew(dir+"/"+POINTER,(id+"\n").getBytes(StandardCharsets.US_ASCII),checkpoint);
        }
        var saved=load(id);same(candidate,saved);verify.verify(saved);guardState(id,true);
        require(current().isEmpty(),"CURRENT appeared before initial publication");checkpoint.accept("bundle-verified");
        if (!files.exists("CURRENT.new")) files.createPendingLink(anchor(id));
        verifyPointerIdentity(id,true);checkpoint.accept("pending-pointer");
        verify.verify(saved);require(current().isEmpty(),"CURRENT appeared before exclusive publication");
        guardState(id,true);verifyPointerIdentity(id,true);files.publishLink();checkpoint.accept("current-published");
        require(current().equals(Optional.of(id)),"published CURRENT bytes mismatch");verifyPointerIdentity(id,true);
        cleanup(id,checkpoint);files.assertRootUnchanged();return true;
    }
    private void cleanup(String id,Consumer<String> checkpoint) throws IOException {
        checkpoint.accept("before-pointer-cleanup");verifyPointerIdentity(id,true);
        var key=files.identity(anchor(id));files.deleteOwnedPointer(key);checkpoint.accept("pointer-cleaned");
    }
    public static String anchor(String id) { hex(id);return "baselines/"+id+"/"+POINTER; }
    public static String pointer(byte[] bytes) throws IOException {
        require(bytes.length==65 && bytes[64]=='\n',"CURRENT must be exactly 64 lowercase hex and LF");
        String id=new String(bytes,0,64,StandardCharsets.US_ASCII);
        try { hex(id); } catch (IllegalArgumentException e) { throw problem("FORMAT-001","CURRENT is not lowercase SHA-256 hex"); }return id;
    }
    private static void same(Bundle a,Bundle b) throws IOException {
        require(Arrays.equals(a.descriptorBytes(),b.descriptorBytes()) && Arrays.equals(a.payloadBytes(),b.payloadBytes()) && Arrays.equals(a.graphBytes(),b.graphBytes()),"saved Bundle differs from independently compiled candidate");
    }
}
