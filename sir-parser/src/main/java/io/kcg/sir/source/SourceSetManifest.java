package io.kcg.sir.source;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.*;

/** Versioned, byte-free evidence for a complete compilation source set. No filesystem access. */
public record SourceSetManifest(SourceId entry, List<Entry> files) {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_FILES = 128;
    public static final int MAX_PATH_BYTES = 512;

    public SourceSetManifest {
        Objects.requireNonNull(entry, "entry");
        files = files.stream().sorted(Comparator.comparing(f -> f.sourceId().value())).toList();
        if (files.isEmpty() || files.size() > MAX_FILES) throw new IllegalArgumentException("source count must be 1..128");
        Set<SourceId> ids = new HashSet<>();
        Set<String> folded = new HashSet<>();
        for (Entry file : files) {
            if (!ids.add(file.sourceId())) throw new IllegalArgumentException("duplicate source: " + file.sourceId());
            if (!folded.add(file.sourceId().value().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("case-colliding source: " + file.sourceId());
        }
        if (!ids.contains(entry)) throw new IllegalArgumentException("entry must belong to the source set");
    }

    public record Entry(SourceId sourceId, long byteCount, String sha256Hex) {
        public Entry {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sha256Hex, "sha256Hex");
            validatePathEncoding(sourceId.value());
            if (byteCount < 0 || !sha256Hex.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid source byte evidence");
        }
    }

    public boolean contains(SourceId id) { return files.stream().anyMatch(f -> f.sourceId().equals(id)); }

    /** Big-endian int version, framed UTF-8 entry, int count, then framed path, long size, framed hex digest. */
    public byte[] canonicalBytes() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(FORMAT_VERSION).array());
        frame(out, entry.value());
        out.writeBytes(ByteBuffer.allocate(4).putInt(files.size()).array());
        for (var file : files) {
            frame(out, file.sourceId().value());
            out.writeBytes(ByteBuffer.allocate(8).putLong(file.byteCount()).array());
            frame(out, file.sha256Hex());
        }
        return out.toByteArray();
    }

    public String sha256Hex() { return sha256(canonicalBytes()); }

    public static SourceSetManifest decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > 128 * 1024) throw new IllegalArgumentException("source manifest exceeds 128 KiB");
        try {
            var buffer = ByteBuffer.wrap(bytes);
            if (buffer.getInt() != FORMAT_VERSION) throw new IllegalArgumentException("unsupported source manifest version");
            SourceId entry = strictId(text(buffer, MAX_PATH_BYTES));
            int count = buffer.getInt();
            if (count < 1 || count > MAX_FILES) throw new IllegalArgumentException("invalid source count");
            var files = new ArrayList<Entry>();
            for (int i = 0; i < count; i++) {
                SourceId id = strictId(text(buffer, MAX_PATH_BYTES));
                long size = buffer.getLong();
                files.add(new Entry(id, size, text(buffer, 64)));
            }
            if (buffer.hasRemaining()) throw new IllegalArgumentException("trailing source manifest bytes");
            var manifest = new SourceSetManifest(entry, files);
            if (!Arrays.equals(bytes, manifest.canonicalBytes())) throw new IllegalArgumentException("non-canonical source manifest");
            return manifest;
        } catch (java.nio.BufferUnderflowException | java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("invalid source manifest bytes", e);
        }
    }

    public static SourceId strictId(String path) {
        validatePathEncoding(path);
        SourceId id = SourceId.of(path);
        if (!path.equals(id.value())) throw new IllegalArgumentException("non-canonical source path: " + path);
        return id;
    }

    private static void validatePathEncoding(String path) {
        Objects.requireNonNull(path, "path");
        if (path.length() > MAX_PATH_BYTES) throw new IllegalArgumentException("source path exceeds 512 UTF-8 bytes");
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(path)) throw new IllegalArgumentException("source path contains malformed Unicode");
        if (path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) throw new IllegalArgumentException("source path exceeds 512 UTF-8 bytes");
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void frame(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }

    private static String text(ByteBuffer buffer, int max) throws java.nio.charset.CharacterCodingException {
        int length = buffer.getInt();
        if (length < 0 || length > max || length > buffer.remaining()) throw new IllegalArgumentException("invalid framed string length");
        var slice = buffer.slice();
        slice.limit(length);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(slice).toString();
        buffer.position(buffer.position() + length);
        return text;
    }
}
