package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.generator.springboot.api.SpringBootDeploymentGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import org.junit.jupiter.api.Test;

class DeploymentDependencyPrimingTest {
    private static String script(String name) {
        String source = GeneratorTestSupport.resourceText("valid/money.sir");
        var plan = new SpringBootDeploymentPlanner().plan(
                GeneratorTestSupport.normalizedText(source), GeneratorTestSupport.lowerText(source));
        return GeneratorTestSupport.contentEndingWith(new SpringBootDeploymentGenerator().generate(plan), "deploy/" + name);
    }

    @Test
    void applicationBuildResolvesTheRealContractRunnerWithoutCallingTheApplication() {
        String build = script("build.sh");
        String application = build.substring(build.indexOf("cd /tmp/kcg-build/app"), build.indexOf("APP=$("));
        assertTrue(application.contains("-Dtest=HttpContractIT"), application);
        assertTrue(application.contains("-Djunit.platform.execution.dryRun.enabled=true"), application);
        assertTrue(application.contains("-Dmaven.repo.local=/m2"), application);
        assertFalse(application.contains("-Dmaven.test.skip=true"), application);
        assertFalse(application.contains("-Dkcg.baseUrl="), application);
    }

    @Test
    void externalAcceptanceSuiteRemainsAnExplicitOperationNotABuildSideEffect() {
        assertFalse(script("build.sh").contains("/package/acceptance"));
        String acceptance = script("acceptance-test.sh");
        assertTrue(acceptance.contains("-Dkcg.baseUrl=http://app:8080"), acceptance);
        assertTrue(acceptance.contains("-Dkcg.jdbcPasswordFile=/run/secrets/root-password"), acceptance);
        assertFalse(acceptance.contains("junit.platform.execution.dryRun"), acceptance);
    }

    @Test
    void realContractVerificationRunsOfflineAndExplicitlyDisablesDryRun() {
        String runtime = script("contract-test.sh");
        assertTrue(runtime.contains("mvn -B -o -ntp -Dmaven.repo.local=/m2"), runtime);
        assertTrue(runtime.contains("-Djunit.platform.execution.dryRun.enabled=false"), runtime);
        assertFalse(runtime.contains("-Djunit.platform.execution.dryRun.enabled=true"), runtime);
        assertTrue(runtime.contains("-Dkcg.baseUrl=http://app:8080"), runtime);
    }
}
