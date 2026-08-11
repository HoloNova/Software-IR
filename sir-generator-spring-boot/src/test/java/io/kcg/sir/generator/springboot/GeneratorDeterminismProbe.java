package io.kcg.sir.generator.springboot;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class GeneratorDeterminismProbe {

    private GeneratorDeterminismProbe() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one fixture resource argument");
        }
        System.out.println(canonicalDigest(GeneratorTestSupport.generateSuccess(args[0])));
    }

    static String canonicalDigest(List<GeneratedFile> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateInt(digest, files.size());
            for (GeneratedFile file : files) {
                update(digest, file.relativePath());
                update(digest, file.content());
                update(digest, file.artifactId().value());
                digest.update((byte) (file.symbolId().isPresent() ? 1 : 0));
                file.symbolId().ifPresent(symbol -> update(digest, symbol.value()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
