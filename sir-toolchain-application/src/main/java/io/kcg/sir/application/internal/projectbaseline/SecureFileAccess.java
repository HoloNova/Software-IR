package io.kcg.sir.application.internal.projectbaseline;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.PathGuard;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded, anchored NOFOLLOW reads/writes; no fallback to unbounded path-based reads. */
public final class SecureFileAccess implements AutoCloseable {
    private final Path root;
    private final SecureDirectoryStream<Path> directory;
    private final List<SecureDirectoryStream<Path>> handles;
    private final Object rootKey;
    private final Consumer<String> afterRead;

    public SecureFileAccess(Path root) throws IOException { this(root,ignored->{}); }
    public SecureFileAccess(Path root,Consumer<String> afterRead) throws IOException {
        if (!root.isAbsolute() || !root.equals(root.normalize())) throw problem("REQUEST-001","root must be absolute normalized");
        this.root=root;this.afterRead=Objects.requireNonNull(afterRead);handles=new ArrayList<>();
        var stream=Files.newDirectoryStream(root.getRoot());
        if (!(stream instanceof SecureDirectoryStream<Path> start)) { stream.close();throw problem("READ-001","secure directory-relative access unavailable"); }
        var current=start;handles.add(current);
        try {
            for (var part : root) { current=current.newDirectoryStream(part,LinkOption.NOFOLLOW_LINKS);handles.add(current); }
            directory=current;rootKey=directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
            if (rootKey==null) throw problem("READ-001","root has no file identity");
        } catch (IOException|RuntimeException e) { closeHandles(handles);throw e; }
    }
    public Path root() { return root; }
    private record Parent(SecureDirectoryStream<Path> stream,Path name,List<SecureDirectoryStream<Path>> opened) implements AutoCloseable {
        @Override public void close() throws IOException { closeHandles(opened); }
    }
    private Parent parent(String relative) throws IOException {
        if (PathGuard.validateRelativePath(relative)!=null) throw problem("REQUEST-001","unsafe relative path: "+relative);
        var parts=relative.split("/");var current=directory;var opened=new ArrayList<SecureDirectoryStream<Path>>();
        try {
            for (int i=0;i<parts.length-1;i++) { current=current.newDirectoryStream(Path.of(parts[i]),LinkOption.NOFOLLOW_LINKS);opened.add(current); }
            return new Parent(current,Path.of(parts[parts.length-1]),opened);
        } catch (IOException|RuntimeException e) { closeHandles(opened);throw e; }
    }
    public boolean exists(String relative) throws IOException {
        try (var p=parent(relative)) {
            try { p.stream().getFileAttributeView(p.name(),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();return true; }
            catch (NoSuchFileException e) { return false; }
        } catch(NoSuchFileException e) { return false; }
    }
    public Object identity(String relative) throws IOException {
        try (var p=parent(relative)) { return attrs(p).fileKey(); }
    }
    private static BasicFileAttributes attrs(Parent p) throws IOException {
        var v=p.stream().getFileAttributeView(p.name(),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!v.isRegularFile() || v.fileKey()==null) throw problem("READ-001","expected identifiable regular non-link file: "+p.name());return v;
    }
    public byte[] read(String relative,int max) throws IOException {
        try (var p=parent(relative)) {
            var before=attrs(p);budget(before.size()<=max,"file exceeds read budget: "+relative);
            var out=new ByteArrayOutputStream();
            try (var channel=p.stream().newByteChannel(p.name(),Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
                var buffer=ByteBuffer.allocate(8192);
                while (channel.read(buffer)!=-1) {
                    int size=buffer.position();budget(out.size()+size<=max,"file exceeds budget during read: "+relative);
                    out.write(buffer.array(),0,size);buffer.clear();
                }
            }
            afterRead.accept(relative);
            var after=attrs(p);
            require(before.fileKey().equals(after.fileKey()) && before.size()==after.size() && before.size()==out.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime()),"file changed during read: "+relative);
            return out.toByteArray();
        }
    }
    public Set<String> list(String relative) throws IOException { return list(relative,8); }
    /** Explicit per-call budget; the original eight-entry default remains unchanged. */
    public Set<String> list(String relative,int maximum) throws IOException {
        budget(maximum>=1 && maximum<=8192,"directory enumeration budget outside bounds");
        var opened=new ArrayList<SecureDirectoryStream<Path>>();var current=directory;
        try {
            if (!relative.isEmpty()) {
                if (PathGuard.validateRelativePath(relative)!=null) throw problem("REQUEST-001","unsafe directory path");
                for (var part : relative.split("/")) { current=current.newDirectoryStream(Path.of(part),LinkOption.NOFOLLOW_LINKS);opened.add(current); }
            } else { current=current.newDirectoryStream(Path.of("."),LinkOption.NOFOLLOW_LINKS);opened.add(current); }
            var names=new HashSet<String>();
            for (var path : current) {
                budget(names.size()<maximum,"project storage directory has too many entries");
                names.add(path.getFileName().toString());
            }
            return Set.copyOf(names);
        } finally { closeHandles(opened); }
    }
    public void writeNew(String relative,byte[] bytes,Consumer<String> checkpoint) throws IOException {
        assertRootUnchanged();
        try (var p=parent(relative);var channel=p.stream().newByteChannel(p.name(),Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,StandardOpenOption.SYNC,LinkOption.NOFOLLOW_LINKS))) {
            int offset=0;
            while (offset<bytes.length) {
                int length=Math.min(4096,bytes.length-offset);var buffer=ByteBuffer.wrap(bytes,offset,length);
                while (buffer.hasRemaining()) channel.write(buffer);offset+=length;
                checkpoint.accept("partial:"+relative);
            }
        }
        assertRootUnchanged();checkpoint.accept("written:"+relative);
    }
    public void createDirectory(String relative) throws IOException {
        assertRootUnchanged();
        try (var p=parent(relative)) {
            var parent= root.resolve(relative).getParent();var actual=Files.readAttributes(parent,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(actual.isDirectory() && Objects.equals(actual.fileKey(),p.stream().getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()),"directory parent identity changed");
            Files.createDirectory(root.resolve(relative));
        }
        assertRootUnchanged();
    }
    public void createPendingLink(String anchor) throws IOException {
        assertRootUnchanged();var key=identity(anchor);
        try (var p=parent(anchor)) {
            var absoluteParent=Files.readAttributes(root.resolve(anchor).getParent(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(absoluteParent.isDirectory() && Objects.equals(absoluteParent.fileKey(),p.stream().getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()),"pointer-anchor parent identity changed");
            Files.createLink(root.resolve("CURRENT.new"),root.resolve(anchor));
        }
        assertRootUnchanged();require(key.equals(identity("CURRENT.new")),"pending pointer is not anchored to candidate");
    }
    public void publishLink() throws IOException {
        assertRootUnchanged();var key=identity("CURRENT.new");
        Files.createLink(root.resolve("CURRENT"),root.resolve("CURRENT.new"));
        assertRootUnchanged();require(key.equals(identity("CURRENT")) && key.equals(identity("CURRENT.new")),"pointer identity changed during publication");
    }
    public void deleteOwnedPointer(Object key) throws IOException {
        assertRootUnchanged();
        try (var p=parent("CURRENT.new")) {
            require(key.equals(attrs(p).fileKey()) && key.equals(identity("CURRENT")),"pending pointer is not the published physical file");
            p.stream().deleteFile(p.name());
        }
        assertRootUnchanged();
    }
    /** Stable identity for a directory, including the root; never follows links. */
    public Object directoryIdentity(String relative) throws IOException {
        assertRootUnchanged();if(relative.isEmpty())return rootKey;
        try(var p=parent(relative)) {
            var a=p.stream().getFileAttributeView(p.name(),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
            require(a.isDirectory() && a.fileKey()!=null,"expected identifiable directory: "+relative);return a.fileKey();
        }
    }
    private void assertParentBinding(String relative,Parent p) throws IOException {
        assertRootUnchanged();var actual=Files.readAttributes(root.resolve(relative).getParent(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(actual.isDirectory() && Objects.equals(actual.fileKey(),p.stream().getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()),"directory parent identity changed");
    }
    /** Only fixed, physically anchored journal slots may be overwritten; never CREATE or fallback. */
    public void overwriteOwned(String relative,Object key,byte[] bytes,Consumer<String> checkpoint) throws IOException {
        try(var p=parent(relative)) {
            assertParentBinding(relative,p);require(Objects.equals(key,attrs(p).fileKey()),"owned slot replaced: "+relative);
            try(var channel=p.stream().newByteChannel(p.name(),Set.of(StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.SYNC,LinkOption.NOFOLLOW_LINKS))) {
                int offset=0;while(offset<bytes.length) {
                    int size=Math.min(512,bytes.length-offset);var b=ByteBuffer.wrap(bytes,offset,size);while(b.hasRemaining())channel.write(b);offset+=size;checkpoint.accept("partial-owned:"+relative);
                }
            }
            assertParentBinding(relative,p);require(Objects.equals(key,attrs(p).fileKey()),"owned slot identity changed during write");
        }
    }
    /** Same-volume hard-link creation with anchored parents and post-link physical verification. */
    public void linkOwned(String source,Object sourceKey,SecureFileAccess to,String target) throws IOException {
        try(var p=parent(source);var q=to.parent(target)) {
            assertParentBinding(source,p);to.assertParentBinding(target,q);require(Objects.equals(sourceKey,attrs(p).fileKey()),"link source replaced");
            require(Files.getFileStore(root.resolve(source)).equals(Files.getFileStore(to.root.resolve(target).getParent())),"cross-FileStore hard link refused");
            Files.createLink(to.root.resolve(target),root.resolve(source));
            assertParentBinding(source,p);to.assertParentBinding(target,q);require(Objects.equals(sourceKey,to.identity(target)),"hard link physical identity mismatch");
        }
    }
    /** SecureDirectoryStream.move has atomic-move semantics; an existing destination is explicitly bound. */
    public void moveOwned(String source,Object sourceKey,SecureFileAccess to,String target,Object targetKey,Consumer<String> checkpoint) throws IOException {
        checkpoint.accept("before-move:"+source+"->"+target);
        try(var p=parent(source);var q=to.parent(target)) {
            assertParentBinding(source,p);to.assertParentBinding(target,q);require(Objects.equals(sourceKey,attrs(p).fileKey()),"move source replaced");
            if(targetKey==null)require(!to.exists(target),"unexpected move target");else require(Objects.equals(targetKey,to.identity(target)),"move destination replaced");
            require(Files.getFileStore(root.resolve(source)).equals(Files.getFileStore(to.root.resolve(target).getParent())),"cross-FileStore atomic move refused");
            p.stream().move(p.name(),q.stream(),q.name());
            assertParentBinding(source,p);to.assertParentBinding(target,q);require(Objects.equals(sourceKey,to.identity(target)),"moved file identity mismatch");
        }
        checkpoint.accept("moved:"+source+"->"+target);
    }
    /** Atomically relocate a physically verified completed receipt directory, without copying or replacement. */
    public void moveOwnedDirectory(String source,Object sourceKey,String target,Consumer<String> checkpoint) throws IOException {
        checkpoint.accept("before-directory-move:"+source+"->"+target);
        try(var p=parent(source);var q=parent(target)) {
            assertParentBinding(source,p);assertParentBinding(target,q);require(Objects.equals(sourceKey,directoryIdentity(source)),"receipt directory replaced");
            require(!exists(target),"receipt directory target already exists");
            require(Files.getFileStore(root.resolve(source)).equals(Files.getFileStore(root.resolve(target).getParent())),"cross-FileStore directory relocation refused");
            p.stream().move(p.name(),q.stream(),q.name());assertParentBinding(target,q);require(Objects.equals(sourceKey,directoryIdentity(target)),"receipt directory identity changed during relocation");
        }
        checkpoint.accept("directory-moved:"+source+"->"+target);
    }
    public void deleteOwned(String relative,Object key) throws IOException {
        try(var p=parent(relative)) {assertParentBinding(relative,p);require(Objects.equals(key,attrs(p).fileKey()),"cleanup file replaced: "+relative);p.stream().deleteFile(p.name());assertParentBinding(relative,p);}
    }
    public void deleteOwnedDirectory(String relative,Object key) throws IOException {
        try(var p=parent(relative)) {assertParentBinding(relative,p);require(Objects.equals(key,directoryIdentity(relative)),"cleanup directory replaced: "+relative);p.stream().deleteDirectory(p.name());assertParentBinding(relative,p);}
    }
    public void assertRootUnchanged() throws IOException {
        for (var path=root;path!=null;path=path.getParent()) {
            var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(attrs.isDirectory(),"root ancestor replaced by non-directory/link: "+path);
            if (path.equals(root)) require(rootKey.equals(attrs.fileKey()),"root physical identity changed");
        }
    }
    private static void closeHandles(List<SecureDirectoryStream<Path>> handles) throws IOException {
        IOException first=null;
        for (int i=handles.size()-1;i>=0;i--) try { handles.get(i).close(); } catch (IOException e) { if (first==null) first=e;else first.addSuppressed(e); }
        if (first!=null) throw first;
    }
    @Override public void close() throws IOException { closeHandles(handles); }
}
