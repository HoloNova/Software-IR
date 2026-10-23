package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.*;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class GroupedSumContractTest {
    @Test void actualMoneyRoutesSnapshotPlanAndProbeResourcesAgree() throws Exception {
        var compiled=compile(snapshot(resource("valid/money.sir")));
        var group=compiled.loweredModel().declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).filter(c -> c.javaName().equals("GroupMeasured")).findFirst().orElseThrow();
        assertEquals(GroupedSumChecks.ROUTE,group.route());
        assertEquals(SpringBootDeclaration.HttpMethod.GET,group.httpMethod());
        assertEquals(SpringBootDeclaration.TransactionMode.REPEATABLE_READ,group.transactionMode());
        String fixture=resource("conformance/mysql/GroupedSumFixture.java.txt");
        assertTrue(fixture.contains(GroupedSumChecks.BARRIER+"/status"));
        assertTrue(fixture.indexOf("Object result=invocation.proceed();")<fixture.indexOf("gate.await("));
        assertTrue(compiled.generatedFiles().stream().noneMatch(f -> f.relativePath().contains("GroupedSumFixture")));
        String ddl=resource("conformance/mysql/money-ddl.sql");
        for(var d:compiled.loweredModel().declarations()) if(d instanceof SpringBootDeclaration.EntityDeclaration e&&e.javaName().startsWith("Summary")) {
            assertTrue(ddl.contains(e.javaName().equals("SummaryEntry")?"summary_entry":"summary_category"));
            for(var field:e.fields()) assertTrue(field.columnName().isEmpty()||ddl.contains(field.columnName().orElseThrow()),field.toString());
        }
    }

    @Test void ledgerGroupsBothOwnerAndCategoryAndHasTypedPublicRecords() throws Exception {
        var result=compile(snapshot(resource("topics/ledger/project.sir")));
        var service=result.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/TotalsByCategoryService.java")).findFirst().orElseThrow().content();
        assertTrue(service.contains("PageResponse<example.ledger.persistence.ExactAggregate.Group<Long>>"),service);
        assertTrue(service.contains("category_rel.owner_id")&&service.contains("category_rel.id = entry.category_id"),service);
        assertTrue(service.contains("Isolation.REPEATABLE_READ"),service);
    }

    @Test void importedGroupKeyIsAFormalVisibleFieldReference() throws Exception {
        var source=multi();
        var result=compile(source);
        var binding=result.semanticModel().referenceSiteBindings().all().stream()
            .filter(b -> b.site().role()==ReferenceRole.GROUP_KEY_FIELD).findFirst().orElseThrow();
        assertEquals("category",binding.site().text());
        String root=new String(source.bytes(source.entry()),StandardCharsets.UTF_8);
        var removed=new SourceSnapshot(source.entry(),Map.of(source.entry(),root.replace("import Entry from \"entities.sir\";","").getBytes(StandardCharsets.UTF_8),
            SourceId.of("entities.sir"),source.bytes(SourceId.of("entities.sir"))));
        var diagnostics=new ArrayList<ExecutionDiagnostic>();
        assertTrue(ProjectCompilation.compile(removed,new SpringBootGenerator()::generate,diagnostics).isEmpty());
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("SIR-SOURCE-VISIBILITY-001")),diagnostics.toString());
    }

    private static SourceSnapshot multi() throws Exception {
        String root=resource("valid/decimal-group.sir").replace("\r\n","\n");
        int from=root.indexOf("    entity Account"); int until=root.indexOf("    input TotalsInput");
        String entities=root.substring(from,until);
        String module="sir 0.3\nimports {}\ndeclarations {\n"+entities+"\n}\n";
        root=root.substring(0,from)+root.substring(until);
        root=root.replace("sources {} imports {}","sources { source \"entities.sir\"; } imports { import Account from \"entities.sir\"; import Category from \"entities.sir\"; import Entry from \"entities.sir\"; }");
        return new SourceSnapshot(SourceId.of("project.sir"),Map.of(SourceId.of("project.sir"),root.getBytes(StandardCharsets.UTF_8),SourceId.of("entities.sir"),module.getBytes(StandardCharsets.UTF_8)));
    }
    private static SourceSnapshot snapshot(String source) { return new SourceSnapshot(SourceId.of("project.sir"),Map.of(SourceId.of("project.sir"),source.getBytes(StandardCharsets.UTF_8))); }
    private static String resource(String path) throws Exception {
        try(var stream=GroupedSumContractTest.class.getResourceAsStream("/"+path)) { assertNotNull(stream,path);return new String(stream.readAllBytes(),StandardCharsets.UTF_8); }
    }
    private static ProjectCompilation.Compiled compile(SourceSnapshot source) {
        var diagnostics=new ArrayList<ExecutionDiagnostic>();
        var result=ProjectCompilation.compile(source,new SpringBootGenerator()::generate,diagnostics);
        assertTrue(result.isPresent(),diagnostics.toString());return result.orElseThrow();
    }
}
