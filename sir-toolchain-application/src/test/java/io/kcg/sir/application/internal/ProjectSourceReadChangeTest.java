package io.kcg.sir.application.internal;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSourceReadChangeTest {
    @TempDir Path temp;

    @Test void sizeTimestampAndInodeChangesAfterReadingAreRejectedRatherThanCertifiedAsASnapshot() throws Exception {
        // Interpose only the real directory's second stat. No production observer and no timing-based thread race.
        var method = ProjectSourceReader.class.getDeclaredMethod("readFile", SecureDirectoryStream.class, SourceId.class);
        method.setAccessible(true);
        List<String> changes = List.of("size", "timestamp", "inode"); assertEquals(3, changes.size());
        for (String change : changes) {
            Path directory = Files.createDirectory(temp.resolve(change)), file = directory.resolve("input.sir"); Files.writeString(file, "original source bytes");
            FileTime originalTime = Files.getLastModifiedTime(file);
            try (var stream = Files.newDirectoryStream(directory)) {
                var real = assertInstanceOf(SecureDirectoryStream.class, stream);
                @SuppressWarnings("unchecked") var typed = (SecureDirectoryStream<Path>) real;
                var interposed = new StatChangeDirectory(typed, () -> {
                    switch (change) {
                        case "size" -> Files.writeString(file, "different source bytes with a different size");
                        case "timestamp" -> Files.setLastModifiedTime(file, FileTime.fromMillis(originalTime.toMillis() + 2000));
                        case "inode" -> {
                            Path replacement = directory.resolve("replacement.sir"); Files.writeString(replacement, "original source bytes");
                            Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                });
                var thrown = assertThrows(InvocationTargetException.class, () -> method.invoke(null, interposed, SourceId.of("input.sir")));
                var refusal = assertInstanceOf(IOException.class, thrown.getCause());
                assertTrue(refusal.getMessage().contains("source changed during read"));
                var code = refusal.getClass().getDeclaredField("code"); code.setAccessible(true);
                assertEquals("SIR-APP-SOURCES-READ-002", code.get(refusal));
                assertEquals(2, interposed.stats);
            }
        }
    }

    @FunctionalInterface private interface Change { void apply() throws IOException; }
    private static final class StatChangeDirectory implements SecureDirectoryStream<Path> {
        private final SecureDirectoryStream<Path> real;
        private final Change change;
        int stats;
        StatChangeDirectory(SecureDirectoryStream<Path> real, Change change) { this.real = real; this.change = change; }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            V view = real.getFileAttributeView(path, type, options);
            if (type != BasicFileAttributeView.class) return view;
            var basic = (BasicFileAttributeView)view;
            return type.cast(new BasicFileAttributeView() {
                @Override public String name() { return basic.name(); }
                @Override public BasicFileAttributes readAttributes() throws IOException {
                    if (++stats == 2) change.apply();
                    return basic.readAttributes();
                }
                @Override public void setTimes(FileTime modified, FileTime access, FileTime creation) throws IOException { basic.setTimes(modified, access, creation); }
            });
        }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Class<V> type) { return real.getFileAttributeView(type); }
        @Override public Iterator<Path> iterator() { return real.iterator(); }
        @Override public void close() throws IOException { real.close(); }
        @Override public SecureDirectoryStream<Path> newDirectoryStream(Path path, LinkOption... options) throws IOException { return real.newDirectoryStream(path, options); }
        @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException { return real.newByteChannel(path, options, attrs); }
        @Override public void deleteFile(Path path) throws IOException { real.deleteFile(path); }
        @Override public void deleteDirectory(Path path) throws IOException { real.deleteDirectory(path); }
        @Override public void move(Path src, SecureDirectoryStream<Path> target, Path dst) throws IOException { real.move(src, target, dst); }
    }
}
