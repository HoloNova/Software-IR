package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What it takes to store a {@code java.util.UUID} through MyBatis. Against a real MySQL a project with a
 * Uuid identity failed on its first request: MyBatis treats a UUID as a bean and looks for its
 * properties, and the identity strategy it was given yields a String. The two contracts are the handler
 * that binds a UUID as text and an identity that the service assigns itself.
 */
class GeneratorUuidStorageContractTest {
    private static final String HANDLER = "src/main/java/example/credentials/persistence/UuidTypeHandler.java";

    @Test
    void aProjectThatStoresAUuidGetsAHandlerThatBindsItAsText() {
        GeneratedFile handler = file(GeneratorTestSupport.generateSuccess("valid/guarded-update.sir"), HANDLER);

        assertTrue(handler.content().contains("@MappedTypes(UUID.class)"), handler.content());
        assertTrue(handler.content().contains("@Component"), handler.content());
        assertTrue(handler.content().contains("statement.setString(index, parameter.toString());"), handler.content());
        assertTrue(handler.content().contains("UUID.fromString(text)"), handler.content());
    }

    @Test
    void aUuidIdentityIsAssignedByTheServiceBeforeTheFirstInsertUnlessTheCallerSetOne() {
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/guarded-update.sir");

        String entity = file(files, "src/main/java/example/credentials/domain/Credential.java").content();
        assertTrue(entity.contains("type = IdType.INPUT"), entity);
        assertFalse(entity.contains("ASSIGN_UUID"), entity);
        String service = file(files, "src/main/java/example/credentials/application/RefreshService.java").content();
        assertTrue(service.contains("if (fresh.getId() == null) {\n            fresh.setId(java.util.UUID.randomUUID());\n        }"), service);
    }

    @Test
    void aProjectThatStoresNoUuidGetsNoHandler() {
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/time-arithmetic.sir");

        assertTrue(files.stream().noneMatch(value -> value.relativePath().endsWith("UuidTypeHandler.java")), files.toString());
    }

    private static GeneratedFile file(List<GeneratedFile> files, String path) {
        return files.stream()
                .filter(value -> value.relativePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing generated file " + path + " among " + files.stream().map(GeneratedFile::relativePath).toList()));
    }
}
