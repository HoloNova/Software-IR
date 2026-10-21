package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.generator.springboot.api.SpringBootDeploymentGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import io.kcg.sir.lowering.springboot.model.SchemaContract;
import io.kcg.sir.lowering.springboot.model.SchemaEvolution;
import org.junit.jupiter.api.Test;

class MoneyDeliveryTest {
    private static String source() { return GeneratorTestSupport.resourceText("valid/money.sir"); }
    private static DeploymentPlan plan(String source) {
        return new SpringBootDeploymentPlanner().plan(GeneratorTestSupport.normalizedText(source), GeneratorTestSupport.lowerText(source));
    }

    @Test
    void constructedColumnRejectsAnInexactDecimalDefault() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentPlan.Column(
                "wallet.balance", "balance", DeploymentPlan.Storage.DECIMAL, 0, false, false, false, false,
                java.util.Optional.empty(), java.util.Optional.of("1.234"),
                java.util.Optional.of(new io.kcg.sir.lowering.springboot.model.DecimalShape(3, 2))));
    }

    @Test
    void deliveryUsesExplicitDecimalShapeAndCanonicalDefault() {
        var plan = plan(source());
        String schema = SchemaContract.render(plan.tables());
        String row = schema.lines().filter(s -> s.startsWith("wallet\tbalance\t")).findFirst().orElseThrow();
        assertEquals(13, row.split("\t", -1).length);
        assertTrue(row.endsWith("\t=0.00\t19\t2"), row);
        assertTrue(schema.lines().filter(s -> s.startsWith("wallet\tid\t")).allMatch(s -> s.split("\t", -1).length == 11));
        var files = new SpringBootDeploymentGenerator().generate(plan);
        String ddl = files.stream().filter(f -> f.relativePath().endsWith(".sql")).map(f -> f.content()).reduce("", String::concat);
        assertTrue(ddl.contains("`balance` DECIMAL(19,2) NOT NULL DEFAULT 0.00"), ddl);
        assertTrue(ddl.contains("`rebate` DECIMAL(19,2) NULL"), ddl);
        String bootstrap = GeneratorTestSupport.contentEndingWith(files, "/Migrate.java");
        assertTrue(bootstrap.contains("NUMERIC_PRECISION,NUMERIC_SCALE"), bootstrap);
    }

    @Test
    void refusesHugePhysicalShapeBeforeMaterializingItsDefault() {
        String text = source().replace("field balance: Money default 0.0;",
                "field balance: Money where precision(1000000000, 1000000000) default 0.0;");
        var failure = assertThrows(IllegalArgumentException.class, () -> plan(text));
        assertTrue(failure.getMessage().contains("exceeds MySQL"), failure.getMessage());
    }

    @Test
    void targetLimitsAreNotCoreLimitsAndNeverSilentlyNarrow() {
        for (String rule : java.util.List.of("precision(66, 2)", "precision(40, 31)")) {
            String text = source().replace("field balance: Money default 0.0;", "field balance: Money where " + rule + ";");
            assertNotNull(GeneratorTestSupport.normalizedText(text));
            assertThrows(IllegalArgumentException.class, () -> plan(text));
        }
    }

    @Test
    void changingScaleIsNotAColumnWideningEvenWhenItAlsoBecomesNullable() {
        String before = source().replace("field balance: Money default 0.0;", "field balance: Money;");
        var oldColumn = plan(before).tables().getFirst().columns().stream().filter(c -> c.name().equals("balance")).findFirst().orElseThrow();
        var newColumn = new DeploymentPlan.Column(oldColumn.symbol(), oldColumn.name(), oldColumn.storage(), oldColumn.length(),
                true, false, false, oldColumn.unique(), oldColumn.reference(), oldColumn.defaultValue(),
                java.util.Optional.of(new io.kcg.sir.lowering.springboot.model.DecimalShape(19, 4)));
        assertFalse(SchemaEvolution.WidenColumn.widens(oldColumn, newColumn));
        assertThrows(IllegalArgumentException.class, () -> new SchemaEvolution.WidenColumn(2, "wallet", oldColumn, newColumn));
    }
}
