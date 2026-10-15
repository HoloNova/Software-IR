package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated-code contract of a load by a unique field.
 *
 * <p>What makes the lookup trustworthy has to be visible in the generated source: the equality on the
 * named field, the bound of two rows, the declared failure for none and the internal failure for more
 * than one. The identity load and the row lock must stay exactly as they were.
 */
class GeneratorKeyedLoadContractTest {
    private static final String SOURCE = "valid/unique-lookup.sir";
    private static final String APPLICATION = "src/main/java/example/lookup/application/";

    private final List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(SOURCE);

    @Test
    void aKeyedLoadAsksForAtMostTwoRowsByTheNamedField() {
        String service = content(APPLICATION + "FindAccountByNameService.java");

        assertTrue(service.contains("var accountMatches = accountMapper.selectList(new LambdaQueryWrapper<Account>()\n"
                + "                .eq(Account::getName, input.getName())\n"
                + "                .last(\"LIMIT 2\"));"), service);
        assertTrue(service.contains("if (accountMatches.isEmpty()) {\n            throw new AccountNotFoundException();\n        }"), service);
        assertTrue(service.contains("if (accountMatches.size() > 1) {\n"
                + "            throw new IllegalStateException(\"more than one Account row has the same name\");\n        }"), service);
        assertTrue(service.contains("var account = accountMatches.get(0);"), service);
        assertTrue(service.contains("import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;"), service);
    }

    @Test
    void theMessageNeverCarriesTheValueThatWasLookedUp() {
        String service = content(APPLICATION + "FindAccountByNameService.java");

        assertFalse(service.contains("input.getName() +"), service);
        assertFalse(service.contains("+ input.getName()"), service);
    }

    @Test
    void theKeyedLoadUsesThePropertyOfTheFieldItNamesForEveryKeyType() {
        assertTrue(content(APPLICATION + "FindAccountByBadgeService.java").contains(".eq(Account::getBadge, input.getBadge())"));
    }

    @Test
    void aLoadByIdentityIsGeneratedExactlyAsBefore() {
        String service = content(APPLICATION + "GetAccountService.java");

        assertTrue(service.contains("var account = accountMapper.selectById(input.getId());"), service);
        assertFalse(service.contains("LIMIT"), service);
        assertFalse(service.contains("selectList"), service);
    }

    @Test
    void aKeyedLoadFollowedByAnUpdateWritesByIdentityWithoutALock() {
        String service = content(APPLICATION + "RenameAccountService.java");

        assertTrue(service.contains(".eq(Account::getName, input.getName())"), service);
        assertTrue(service.contains("accountMapper.updateById(account);"), service);
        assertFalse(files.stream().anyMatch(file -> file.content().contains("selectByIdForUpdate")),
                "nothing in this project is locked: no entity is versioned");
    }

    private String content(String path) {
        return files.stream()
                .filter(file -> file.relativePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing generated file " + path + " in " + files.stream().map(GeneratedFile::relativePath).toList()))
                .content();
    }
}
