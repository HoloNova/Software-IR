package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated-code contract of a guarded update. What makes a rotation safe under concurrent requests
 * has to be visible in the generated source, not inferred from a passing test: one statement that
 * checks and writes, only the bound columns, and the declared error when no row matched.
 */
class GeneratorGuardedUpdateContractTest {
    private static final String SOURCE = "valid/guarded-update.sir";
    private static final String SERVICE = "src/main/java/example/credentials/application/RefreshService.java";

    private final List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(SOURCE);

    @Test
    void theRowIsCheckedAndWrittenByOneStatementKeyedOnTheIdentityAndTheGuard() {
        String service = service();

        assertTrue(service.contains("int oldUpdated = credentialMapper.update(null, new LambdaUpdateWrapper<Credential>()\n"
                + "                .eq(Credential::getId, old.getId())\n"
                + "                .and(q0 -> q0.and(q1 -> q1.eq(Credential::getUsed, false))"
                + ".and(q2 -> q2.gt(Credential::getExpiresAt, java.time.Instant.now())))\n"), service);
    }

    @Test
    void onlyTheBoundColumnsAreWrittenWithTheValueTheUpdateStepBuilt() {
        String service = service();

        assertTrue(service.contains(".set(Credential::getUsed, old.getUsed()));"), service);
        assertFalse(service.contains("Credential::getOwner, old"), "an unbound column must not be written back: " + service);
        assertFalse(service.contains("updateById(old)"), "the whole row must not be written back: " + service);
    }

    @Test
    void noMatchingRowIsTheDeclaredErrorAndNothingAfterItRuns() {
        String service = service();

        assertTrue(service.contains("if (oldUpdated != 1) {\n            throw new InvalidException();\n        }\n"
                + "        credentialMapper.insert(fresh);"), service);
    }

    @Test
    void theGuardIsNeverEvaluatedInJavaBeforeTheWrite() {
        String service = service();

        assertFalse(service.contains("old.getUsed() == false"), service);
        assertFalse(service.contains("!old.getUsed()"), service);
        assertTrue(service.contains("import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;"), service);
    }

    private String service() {
        return files.stream()
                .filter(file -> file.relativePath().equals(SERVICE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing generated file " + SERVICE))
                .content();
    }
}
