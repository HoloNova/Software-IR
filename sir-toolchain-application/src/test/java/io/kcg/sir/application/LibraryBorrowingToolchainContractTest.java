package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootDeploymentGenerator;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LibraryBorrowingToolchainContractTest {
    private static ProjectCompilation.Compiled compiled;

    @BeforeAll static void compileActualTopic() throws Exception {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var source = ProjectTestSupport.oneFile("project.sir", ProjectTestSupport.resource("topics/library/project.sir"));
        compiled = ProjectCompilation.compile(source, new SpringBootGenerator()::generate, diagnostics).orElseThrow(
                () -> new AssertionError(diagnostics.toString()));
    }

    @Test void theCounterIsServerManagedAndNewAccountsStartAtZero() {
        assertFalse(file("RegisterInput.java").contains("activeLoans"));
        assertTrue(file("RegisterService.java").contains("user.setActiveLoans(0)"));
        assertTrue(file("User.java").contains("getActiveLoans()"));
        assertFalse(file("RegisterService.java").contains("input.getActiveLoans"));
    }

    @Test void responseAndHttpContractsRemainTheExistingBorrowReturnAndRenewRoutes() {
        assertTrue(file("BorrowController.java").contains("HttpStatus.CREATED"));
        for (String controller : java.util.List.of("GiveBackController.java", "RenewController.java")) {
            assertTrue(file(controller).contains("@PostMapping"));
            assertFalse(file(controller).contains("@ResponseStatus"), "ordinary return keeps Spring's default 200");
        }
        assertTrue(file("GiveBackController.java").contains("/loans/{id}/return"));
        assertTrue(file("LoanView.java").contains("getReturnedAt()"));
        assertFalse(file("LoanView.java").contains("activeLoans"));
    }

    @Test void deploymentCarriesARealDefaultedCounterAndTheFixtureHasEveryTargetTable() throws Exception {
        var plan = new SpringBootDeploymentPlanner().plan(compiled.semanticModel(), compiled.loweredModel());
        var user = plan.tables().stream().filter(t -> t.name().equals("user")).findFirst().orElseThrow();
        var counter = user.columns().stream().filter(c -> c.name().equals("active_loans")).findFirst().orElseThrow();
        assertEquals(DeploymentPlan.Storage.INT, counter.storage());
        assertEquals("0", counter.defaultValue().orElseThrow());
        assertFalse(counter.nullable());
        String sql = new SpringBootDeploymentGenerator().generate(plan).stream()
                .filter(f -> f.relativePath().endsWith(".sql")).map(f -> f.content()).reduce("", String::concat);
        assertTrue(sql.contains("`active_loans` INT NOT NULL DEFAULT 0"), sql);
        String fixture = ProjectTestSupport.resource("conformance/mysql/library-ddl.sql");
        for (var table : plan.tables()) assertTrue(fixture.contains("CREATE TABLE " + table.name() + " ("), table.name());
        assertTrue(fixture.contains("active_loans INT NOT NULL DEFAULT 0"));
        assertTrue(fixture.contains("fixture_loan_copy FOREIGN KEY (copy_id) REFERENCES copy(id)"));
        assertTrue(fixture.contains("fixture_loan_reader FOREIGN KEY (reader_id) REFERENCES user(id)"));
    }

    private static String file(String name) {
        return compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/" + name))
                .findFirst().orElseThrow().content();
    }
}
