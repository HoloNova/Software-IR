package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.SecretDigest;
import java.util.Optional;

/**
 * Renders the class that turns a secret into the text the database keeps, and checks a candidate against
 * that text.
 *
 * <p>The scheme is PBKDF2 with HMAC-SHA-256 from the JDK, so the generated project needs no extra
 * dependency. Each digest carries its own random salt and its own iteration count, so the count can be
 * raised later without invalidating what is stored. The comparison runs in constant time and a stored
 * text that is not in this format never matches, instead of failing the request.
 */
final class SecretDigestRenderer {

    private SecretDigestRenderer() {
    }

    static GeneratedFile render(SecretDigest digest) {
        ImportSorter imports = new ImportSorter();
        imports.add("java.nio.charset.StandardCharsets");
        imports.add("java.security.GeneralSecurityException");
        imports.add("java.security.MessageDigest");
        imports.add("java.security.SecureRandom");
        imports.add("java.util.Base64");
        imports.add("javax.crypto.SecretKeyFactory");
        imports.add("javax.crypto.spec.PBEKeySpec");
        String content = GenerationContext.assembleSource(digest.packageName(), imports, renderBody(digest));
        return new GeneratedFile(digest.path(), content, digest.id(), Optional.empty());
    }

    private static String renderBody(SecretDigest digest) {
        StringBuilder out = new StringBuilder();
        out.append("public final class ").append(digest.simpleName()).append(" {\n\n");
        out.append("    private static final String ALGORITHM = \"PBKDF2WithHmacSHA256\";\n");
        out.append("    private static final String PREFIX = \"pbkdf2-sha256\";\n");
        out.append("    private static final int ITERATIONS = 600_000;\n");
        out.append("    private static final int MAX_ITERATIONS = 10_000_000;\n");
        out.append("    private static final int SALT_BYTES = 16;\n");
        out.append("    private static final int HASH_BITS = 256;\n");
        out.append("    private static final SecureRandom RANDOM = new SecureRandom();\n\n");
        out.append("    private ").append(digest.simpleName()).append("() {\n");
        out.append("    }\n\n");
        out.append("    /** The text to store for a secret: the scheme, the iteration count, the salt and the hash. */\n");
        out.append("    public static String digest(String secret) {\n");
        out.append("        if (secret == null) {\n");
        out.append("            throw new IllegalArgumentException(\"a secret must not be null\");\n");
        out.append("        }\n");
        out.append("        byte[] salt = new byte[SALT_BYTES];\n");
        out.append("        RANDOM.nextBytes(salt);\n");
        out.append("        byte[] hash = derive(secret, salt, ITERATIONS, HASH_BITS);\n");
        out.append("        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();\n");
        out.append("        return PREFIX + \"$\" + ITERATIONS + \"$\" + encoder.encodeToString(salt) + \"$\" + encoder.encodeToString(hash);\n");
        out.append("    }\n\n");
        out.append("    /** Whether the candidate produces the stored text. A stored text in any other format never matches. */\n");
        out.append("    public static boolean matches(String candidate, String stored) {\n");
        out.append("        if (candidate == null || stored == null) {\n");
        out.append("            return false;\n");
        out.append("        }\n");
        out.append("        String[] parts = stored.split(\"\\\\$\", -1);\n");
        out.append("        if (parts.length != 4 || !PREFIX.equals(parts[0])) {\n");
        out.append("            return false;\n");
        out.append("        }\n");
        out.append("        try {\n");
        out.append("            int iterations = Integer.parseInt(parts[1]);\n");
        out.append("            if (iterations < 1 || iterations > MAX_ITERATIONS) {\n");
        out.append("                return false;\n");
        out.append("            }\n");
        out.append("            byte[] salt = Base64.getDecoder().decode(parts[2]);\n");
        out.append("            byte[] expected = Base64.getDecoder().decode(parts[3]);\n");
        out.append("            byte[] actual = derive(candidate, salt, iterations, expected.length * 8);\n");
        out.append("            return MessageDigest.isEqual(expected, actual);\n");
        out.append("        } catch (IllegalArgumentException malformed) {\n");
        out.append("            return false;\n");
        out.append("        }\n");
        out.append("    }\n\n");
        out.append("    private static byte[] derive(String secret, byte[] salt, int iterations, int bits) {\n");
        out.append("        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, iterations, bits);\n");
        out.append("        try {\n");
        out.append("            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();\n");
        out.append("        } catch (GeneralSecurityException failure) {\n");
        out.append("            throw new IllegalStateException(\"the secret digest scheme is unavailable\", failure);\n");
        out.append("        } finally {\n");
        out.append("            spec.clearPassword();\n");
        out.append("        }\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }
}
