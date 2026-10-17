package io.kcg.sir.lowering.springboot;

import io.kcg.sir.api.*;
import io.kcg.sir.ast.AstSourceUnit;
import io.kcg.sir.lowering.springboot.api.*;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.source.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentPlannerTest {
    @Test void physicalStorageSharesTargetNamesAndBoundIdentityTypes() {
        var plan = plan(bearer().replace("field name: String unique;", "field name: String where length(1, 64) unique;"));
        assertTrue(plan.credentialAuthentication());
        var token = plan.tables().stream().filter(t -> t.name().equals("access_token")).findFirst().orElseThrow();
        assertEquals(DeploymentPlan.Storage.UUID, token.columns().getFirst().storage());
        var owner = token.columns().stream().filter(c -> c.name().equals("owner_id")).findFirst().orElseThrow();
        assertEquals(DeploymentPlan.Storage.BIGINT, owner.storage());
        assertEquals(new DeploymentPlan.ForeignKey("user", "id"), owner.reference().orElseThrow());
        var user = plan.tables().stream().filter(t -> t.name().equals("user")).findFirst().orElseThrow();
        var secret = user.columns().stream().filter(c -> c.name().equals("password_hash")).findFirst().orElseThrow();
        assertEquals(255, secret.length(), "digest capacity is not the input password limit");
        assertTrue(user.columns().stream().filter(c -> c.name().equals("name")).findFirst().orElseThrow().unique());
    }

    @Test void refusesUnboundedUniqueTextInsteadOfCreatingAPrefixUniqueIndex() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> plan(bearer())).getMessage().contains("unique text"));
    }

    @Test void refusesAnAuthenticatedActorWithoutItsCredentialAdapter() {
        String text = bearer().replace("credential subject owner expires expiresAt;", "");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> plan(text)).getMessage().contains("external adapter"));
    }

    @Test void refusesDecimalRatherThanSilentlyChoosingALossyScale() {
        String text = bearer().replace("field name: String unique;", "field name: String where length(1, 64) unique;")
            .replace("entity Note persistent {", "entity Price persistent { identity id: Int64 generated auto; field value: Decimal; } entity Note persistent {");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> plan(text)).getMessage().contains("precision/scale"));
    }

    private static String bearer() { return LoweringTestSupport.source("valid/bearer-credential.sir"); }
    private static DeploymentPlan plan(String source) {
        var parsed = SirParser.create().parseProject(new SirSource(SourceId.of("project.sir"), source));
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var semantic = new SirSemanticAnalyzer().analyzeProject(new ProjectSemanticInput((AstSourceUnit.Root) parsed.unit().orElseThrow(), List.of()));
        assertTrue(semantic.isSuccess(), () -> semantic.diagnostics().toString());
        var model = semantic.model().orElseThrow();
        var lowered = new SpringBootTargetLowering().lower(model);
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        return new SpringBootDeploymentPlanner().plan(model, lowered.model().orElseThrow());
    }
}
