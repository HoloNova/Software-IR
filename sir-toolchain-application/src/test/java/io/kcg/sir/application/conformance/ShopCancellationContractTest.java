package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShopCancellationContractTest {
    static final String RESOURCE="/valid/shop-conformance.sir";
    @Test void ciUsesTheActualShopAndTheUnchangedMultiplicationBase() throws Exception {
        assertEquals(ShopSourceComposition.compose(),ConformanceFixtures.readResource(RESOURCE).replace("\r\n","\n"));
        var compiled=compile(RESOURCE);
        var cancel=capability(compiled,"CancelOrder");
        assertEquals("/api/orders/{id}/cancel",cancel.route());assertTrue(cancel.authenticated());
        assertEquals(SpringBootDeclaration.TransactionMode.READ_COMMITTED,cancel.transactionMode());
        var each=cancel.workflow().steps().stream().filter(SpringBootWorkflow.EachStep.class::isInstance).map(SpringBootWorkflow.EachStep.class::cast).findFirst().orElseThrow();
        assertEquals(100,each.plan().limit());assertEquals(101,each.plan().fetchLimit());
        assertEquals(List.of("productId","id"),each.orderKeys().stream().map(SpringBootWorkflow.OrderKey::targetMemberName).toList());
        assertEquals("/api/products/show",capability(compiled,"ShowProduct").route());assertFalse(capability(compiled,"ShowProduct").authenticated());
        assertTrue(compiled.generatedFiles().stream().anyMatch(f->f.relativePath().endsWith("/ShopProductMapper.java")));
        assertTrue(compiled.generatedFiles().stream().anyMatch(f->f.relativePath().endsWith("/ProductMapper.java")));
    }
    @Test void actualChildEnvironmentAndPublicReadinessStillMatchTheComposedApp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var environment=BusinessSliceHarnessEvidenceTest.environment(root.resolve("work"),root.resolve("evidence"),MultiplicationBusinessConformanceIT.FIXTURE.enabledProperty());
        var slice=MultiplicationBusinessConformanceIT.createSlice(environment);var child=slice.childEnvironment(slice.runtimeUrl());
        var spring=new org.springframework.core.env.StandardEnvironment();spring.getPropertySources().remove("systemProperties");
        spring.getPropertySources().replace("systemEnvironment",new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment",Map.copyOf(child)));
        assertEquals("external",spring.getProperty("kcg.actor-identity.mode"));assertFalse(child.containsKey("KCG_ACTOR_IDENTITY_LOCAL_ID"));assertFalse(child.containsKey("SPRING_PROFILES_ACTIVE"));
        assertEquals("/api/products/show",java.net.URI.create(slice.readinessUrl()).getPath());assertEquals(RESOURCE,MultiplicationBusinessConformanceIT.FIXTURE.sirResource());
        var compiled=compile(RESOURCE);assertFalse(capability(compiled,"ShowProduct").authenticated());
        var cancel=compiled.generatedFiles().stream().filter(f->f.relativePath().endsWith("/CancelOrderController.java")).findFirst().orElseThrow().content();
        assertTrue(cancel.contains("@PostMapping"));assertFalse(cancel.contains("@ResponseStatus"),"cancel preserves default 200");
        var buy=compiled.generatedFiles().stream().filter(f->f.relativePath().endsWith("/BuyNowController.java")).findFirst().orElseThrow().content();assertTrue(buy.contains("HttpStatus.CREATED"));
        for(String error:List.of("NotYourOrder","NotCancellable","OrderTooLarge","InconsistentOrder","StockLimit"))
            assertTrue(compiled.generatedFiles().stream().anyMatch(f->f.relativePath().endsWith("/"+error+"Exception.java")&&f.content().contains("super(\""+error+"\",")),error);
    }
    @Test void nonStrictProbeOwnsItsColdEvidenceDirectory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var harness=BusinessSliceHarnessEvidenceTest.harness(root.resolve("work"),root.resolve("absent"));assertFalse(java.nio.file.Files.exists(harness.evidenceRoot()));
        ShopCancellationChecks.install(harness);
        var path=harness.projectRoot().resolve("src/main/java/example/multiplication/fixture/ShopSessionFixture.java");
        assertArrayEquals(java.nio.file.Files.readAllBytes(path),java.nio.file.Files.readAllBytes(harness.evidenceRoot().resolve("ShopSessionFixture.java.txt")));
        assertEquals(ConformanceFixtures.readResource("/conformance/mysql/ShopSessionFixture.java.txt"),java.nio.file.Files.readString(path));
    }

    static ProjectCompilation.Compiled compile(String resource) throws Exception {
        var entry=SourceId.of("project.sir");var diagnostics=new ArrayList<ExecutionDiagnostic>();
        return ProjectCompilation.compile(new SourceSnapshot(entry,Map.of(entry,ConformanceFixtures.readResource(resource).getBytes(StandardCharsets.UTF_8))),new SpringBootGenerator()::generate,diagnostics)
            .orElseThrow(()->new AssertionError(diagnostics));
    }
    static SpringBootDeclaration.CapabilityDeclaration capability(ProjectCompilation.Compiled model,String name) {
        return model.loweredModel().declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).filter(c->c.javaName().equals(name)).findFirst().orElseThrow();
    }
}
