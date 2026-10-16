package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated-code contract of a secret field. What is stored is visible in the generated source (a
 * binding to the secret goes through the digest, no other does, a response never carries it) and the
 * digest class itself is compiled and run here, because its behaviour is the whole point: it must
 * never store the value, must accept the right candidate and refuse the others, and must treat a stored
 * text it does not recognise as a mismatch instead of an error.
 */
class GeneratorSecretFieldContractTest {
    private static final String SOURCE = "valid/secret-field.sir";
    private static final String BASE = "src/main/java/example/accounts/";

    private final List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(SOURCE);

    @Test
    void onlyTheBindingToTheSecretFieldIsDigestedAndAMatchIsACheckAgainstTheStoredText() {
        String register = content(BASE + "application/RegisterService.java");
        assertTrue(register.contains("account.setPasswordHash(SecretDigest.digest(input.getPassword()));"), register);
        assertTrue(register.contains("account.setName(input.getName());"), register);
        assertTrue(register.contains("import example.accounts.persistence.SecretDigest;"), register);

        String login = content(BASE + "application/LoginService.java");
        assertTrue(login.contains("SecretDigest.matches(input.getPassword(), account.getPasswordHash())"), login);
    }

    @Test
    void aResponseNeverCarriesTheSecret() {
        String view = content(BASE + "api/AccountView.java");

        assertFalse(view.toLowerCase().contains("password"), view);
    }

    @Test
    void theDigestClassIsNotGeneratedForAProjectWithoutASecret() {
        List<GeneratedFile> plain = GeneratorTestSupport.generateSuccess("valid/unique-lookup.sir");

        assertTrue(plain.stream().noneMatch(file -> file.relativePath().endsWith("SecretDigest.java")));
    }

    @Test
    void theGeneratedDigestStoresASaltedHashAndChecksCandidatesAgainstIt(@TempDir Path directory) throws Exception {
        Class<?> digest = compileDigest(directory);
        Method make = digest.getMethod("digest", String.class);
        Method check = digest.getMethod("matches", String.class, String.class);

        String first = (String) make.invoke(null, "correct horse");
        String second = (String) make.invoke(null, "correct horse");
        assertTrue(first.startsWith("pbkdf2-sha256$600000$"), first);
        assertFalse(first.contains("correct horse"), "the stored text must not contain the secret");
        assertNotEquals(first, second, "each digest has its own salt");

        assertEquals(true, check.invoke(null, "correct horse", first));
        assertEquals(true, check.invoke(null, "correct horse", second));
        assertEquals(false, check.invoke(null, "correct horsf", first));
        assertEquals(false, check.invoke(null, "", first));
        assertEquals(false, check.invoke(null, null, first));
        assertEquals(false, check.invoke(null, "correct horse", null));
        for (String unrecognised : List.of("", "plain", "pbkdf2-sha256$x$y$z", "pbkdf2-sha256$600000$!!$!!", "pbkdf2-sha256$0$AA$AA",
                "other$600000$AA$AA", first + "$extra", "pbkdf2-sha256$600000$AA$")) {
            assertEquals(false, check.invoke(null, "correct horse", unrecognised), unrecognised);
        }
    }

    private Class<?> compileDigest(Path directory) throws IOException, ClassNotFoundException {
        GeneratedFile file = files.stream()
                .filter(value -> value.relativePath().endsWith("persistence/SecretDigest.java"))
                .findFirst()
                .orElseThrow();
        Path source = directory.resolve("src/example/accounts/persistence/SecretDigest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, file.content());
        Path classes = Files.createDirectories(directory.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()), "the generated class must compile");
        URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
        return loader.loadClass("example.accounts.persistence.SecretDigest");
    }

    private String content(String path) {
        return files.stream()
                .filter(value -> value.relativePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing generated file " + path))
                .content();
    }
}
