package io.kcg.sir.generator.springboot.api;

import io.kcg.sir.generator.springboot.internal.DeploymentEngine;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import java.util.List;

/** Pure rendering of a delivery plan. No Docker, source interpretation or disk access. */
public final class SpringBootDeploymentGenerator {
    public List<GeneratedFile> generate(DeploymentPlan plan) { return new DeploymentEngine().render(plan); }
}
