package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The real-topic policies must run in the existing library application, not a new approximation. */
class G9TopicContractTest {
    static final String RESOURCE = "/valid/g9-topic-conformance.sir";

    @Test void actualWriteRoutesStatusAndPublicReadinessAreUnchanged() throws Exception {
        var compiled = ShopCancellationContractTest.compile(RESOURCE);
        for (String entity : List.of("Book", "Item")) {
            String route = entity.equals("Book") ? G9TopicChecks.BOOKS : G9TopicChecks.ITEMS;
            for (String operation : List.of("Create", "Update", "Delete")) {
                var capability = ShopCancellationContractTest.capability(compiled, operation + entity);
                assertEquals(operation.equals("Create") ? route : route + "/{id}", capability.route());
                assertEquals(operation.equals("Create") ? "POST" : operation.equals("Update") ? "PUT" : "DELETE", capability.httpMethod().name());
                String controller = compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/" + operation + entity + "Controller.java")).findFirst().orElseThrow().content();
                assertEquals(operation.equals("Create"), controller.contains("HttpStatus.CREATED"), "updates/deletes retain default 200");
            }
        }
        var get = ShopCancellationContractTest.capability(compiled, "GetBook");
        var list = ShopCancellationContractTest.capability(compiled, "ListBook");
        assertFalse(get.authenticated()); assertFalse(list.authenticated());
        assertEquals("/api/books/1", LibraryBorrowingBusinessConformanceIT.FIXTURE.readinessPath());
        assertEquals("/api/books/{id}", get.route());
        assertTrue(compiled.loweredModel().basePackage().startsWith("example.library"));
        var config = compiled.loweredModel().projectArtifacts().stream().filter(io.kcg.sir.lowering.springboot.model.ProjectArtifact.ApplicationConfig.class::isInstance)
                .map(io.kcg.sir.lowering.springboot.model.ProjectArtifact.ApplicationConfig.class::cast).findFirst().orElseThrow();
        assertTrue(config.rejectUnknownRequestProperties(), "the exact runtime profile must reject excluded HTTP properties");
        var item = compiled.loweredModel().declarations().stream().filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast).filter(e -> e.javaName().equals("Item")).findFirst().orElseThrow();
        assertEquals("condition", item.fields().stream().filter(f -> f.javaName().equals("condition")).findFirst().orElseThrow().columnName().orElseThrow(),
                "quote the SQL reference; do not rename physical columns or change the public field");
        assertTrue(compiled.generatedFiles().stream().anyMatch(f -> f.relativePath().endsWith("/Item.java") && f.content().contains("@TableField(\"`condition`\")")));
        for (var expected : java.util.Map.of("BookForbidden", "FORBIDDEN", "ItemNotFound", "NOT_FOUND").entrySet())
            assertTrue(compiled.generatedFiles().stream().anyMatch(f -> f.relativePath().endsWith("/" + expected.getKey() + "Exception.java")
                && f.content().contains("super(\"" + expected.getKey() + "\", HttpStatus." + expected.getValue() + ");")));
    }

    @Test void actualChildEnvironmentKeepsExternalIdentityWithoutStartingAProcess(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var environment = BusinessSliceHarnessEvidenceTest.environment(root.resolve("work"), root.resolve("evidence"), LibraryBorrowingBusinessConformanceIT.FIXTURE.enabledProperty());
        var slice = LibraryBorrowingBusinessConformanceIT.createSlice(environment);
        var child = slice.childEnvironment(slice.runtimeUrl());
        var spring = new org.springframework.core.env.StandardEnvironment(); spring.getPropertySources().remove("systemProperties");
        spring.getPropertySources().replace("systemEnvironment", new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", java.util.Map.copyOf(child)));
        assertEquals("external", spring.getProperty("kcg.actor-identity.mode"));
        assertFalse(child.containsKey("KCG_ACTOR_IDENTITY_LOCAL_ID"));
        assertEquals("/api/books/1", java.net.URI.create(slice.readinessUrl()).getPath());
    }

    @Test void optionalNotBlankSupportIsConditionalOnTheActualInputTypes() throws Exception {
        var market = ShopCancellationContractTest.compile("/topics/market/project.sir");
        var library = ShopCancellationContractTest.compile("/topics/library/project.sir");
        String marketSupport = market.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/ValidationSupport.java")).findFirst().orElseThrow().content();
        String librarySupport = library.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/ValidationSupport.java")).findFirst().orElseThrow().content();
        assertFalse(marketSupport.contains("PresentNotBlankValidator"));
        assertTrue(librarySupport.contains("PresentNotBlankValidator"));
        String create = library.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/CreateBookInput.java")).findFirst().orElseThrow().content();
        assertTrue(create.contains("@example.library.api.ValidationSupport.NotBlank"));
        assertTrue(create.contains("    @NotBlank"), "ordinary required names keep the ordinary mandatory validator");
    }

    @Test void theExistingItExecutesTheActualMarketAndLibraryPolicies() throws Exception {
        assertEquals(RESOURCE, LibraryBorrowingBusinessConformanceIT.FIXTURE.sirResource(),
                "the prior generic G9 fixture does not qualify the two actual topics");
        assertEquals(G9TopicComposition.compose(), ConformanceFixtures.readResource(RESOURCE).replace("\r\n", "\n"),
                "keep every actual library declaration and the canonical market Item slice unchanged");
        var compiled = ShopCancellationContractTest.compile(RESOURCE);
        for (String operation : List.of("CreateItem", "UpdateItem", "DeleteItem", "CreateBook", "UpdateBook", "DeleteBook")) {
            var capability = ShopCancellationContractTest.capability(compiled, operation);
            assertTrue(capability.authenticated(), operation);
            assertEquals(SpringBootDeclaration.TransactionMode.REQUIRED, capability.transactionMode());
        }
        var browse = ShopCancellationContractTest.capability(compiled, "BrowseItems");
        assertEquals(G9TopicChecks.BROWSE, browse.route());
        assertFalse(browse.authenticated(), "the actual market browse stays public");
        String service = compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/BrowseItemsService.java")).findFirst().orElseThrow().content();
        assertTrue(service.contains("COALESCE(`condition` = {0}, FALSE)"), "the reserved condition column is quoted in the browse filter");
    }
}
