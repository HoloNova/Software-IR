package io.kcg.sir.application.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Versioned, length-framed generated-content hash; not a project baseline digest. */
public final class ValidationHashes {
    private ValidationHashes() {}
    public static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String source(String sir) { return HexFormat.of().formatHex(sha256().digest(sir.getBytes(StandardCharsets.UTF_8))); }
    public static String generated(List<GeneratedFile> files) {
        var sha = sha256(); sha.update("KCG-SIR-CHECK-GENERATED-V1\0".getBytes(StandardCharsets.US_ASCII));
        length(sha, files.size());
        for (var file : files.stream().sorted(Comparator.comparing(GeneratedFile::relativePath)).toList()) {
            field(sha, file.relativePath()); field(sha, file.content());
        }
        return HexFormat.of().formatHex(sha.digest());
    }
    private static void field(MessageDigest sha, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8); length(sha, bytes.length); sha.update(bytes);
    }
    private static void length(MessageDigest sha, long n) { sha.update(ByteBuffer.allocate(Long.BYTES).putLong(n).array()); }
    /** Strict scalar-value validation with bounded UTF-8 counting; -1 means an unpaired surrogate. */
    public static int utf8Length(String s, int limit) {
        int bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return -1;
                i++; bytes += 4;
            } else if (Character.isLowSurrogate(c)) return -1;
            else bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            if (bytes > limit) return limit + 1;
        }
        return bytes;
    }
}
