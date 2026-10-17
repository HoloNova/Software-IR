package io.kcg.sir.generator.springboot.api;

import io.kcg.sir.generator.springboot.internal.DeploymentEngine;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import io.kcg.sir.lowering.springboot.model.SchemaEvolution;
import java.util.List;

/** Pure rendering of a delivery plan. No Docker, source interpretation or disk access. */
public final class SpringBootDeploymentGenerator {
    public List<GeneratedFile> generate(DeploymentPlan plan) { return new DeploymentEngine().render(plan); }

    /** The migration files and intermediate contracts a delivery appends to an inherited chain; see SchemaEvolution. */
    public List<GeneratedFile> generateEvolution(DeploymentPlan plan, SchemaEvolution evolution, String baseContract) {
        return new DeploymentEngine().renderEvolution(plan, evolution, baseContract);
    }
}
