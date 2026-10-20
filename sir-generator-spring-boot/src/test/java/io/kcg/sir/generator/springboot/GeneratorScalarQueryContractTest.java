package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GeneratorScalarQueryContractTest {
    @Test
    void negatedLiteralMatchesKeepEscapingPlan() {
        String source = GeneratorTestSupport.resourceText("valid/scalar-queries.sir")
            .replace("field score: Optional<Count>;", "field label: Text; field score: Optional<Count>;")
            .replace("field page: PageNo;", "field label: Text; field page: PageNo;")
            .replace("item.owner == input.owner and (input.minimum is none or item.score >= input.minimum)", "not (item.label containsLiteral input.label)");
        String service = GeneratorTestSupport.contentEndingWith(GeneratorTestSupport.generateSuccessFromText(source), "/CheckService.java");
        assertTrue(service.contains(".not(") && service.contains("LIKE CONCAT"), service);
        assertTrue(service.contains("escapeLikeLiteral(input.getLabel())"), service);
    }

    @Test
    void existsReadsAtMostOneIdentityCountUsesSqlCountAndBothKeepScope() {
        var files = GeneratorTestSupport.generateSuccess("valid/scalar-queries.sir");
        String service = files.stream().filter(f -> f.relativePath().endsWith("/CheckService.java")).findFirst().orElseThrow().content();
        assertTrue(service.contains(".select(Entry::getId).last(\"LIMIT 1\")"), service);
        assertTrue(service.contains(".selectObjs(") && service.contains(".isEmpty()"), service);
        assertTrue(service.contains("long total = ") && service.contains(".selectCount("), service);
        assertTrue(service.contains("Entry::getRemoved, false"), service);
        assertTrue(service.contains("input.getTagId()"), service);
        assertFalse(service.contains(".selectList("), service);
    }
}
