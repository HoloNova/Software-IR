package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.ast.*;
import io.kcg.sir.source.*;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class CalendarLedgerContractTest {
    private static ProjectCompilation.Compiled ledger;
    private static ProjectCompilation.Compiled money;
    @BeforeAll static void compile() throws Exception {
        ledger=compile("/topics/ledger/project.sir");money=compile("/valid/money.sir");
    }
    @Test void theExistingMoneyItEmbedsTheActualLedgerDeclarationsRatherThanALookalike() throws Exception {
        var a=declarations(ConformanceFixtures.readResource("/topics/ledger/project.sir"));
        var b=declarations(ConformanceFixtures.readResource("/valid/money.sir"));
        for(String name:List.of("Direction","ZoneName","User","Category","Entry","Budget","TimeZoneView","PreferredTimeZone",
            "ReadTimeZone","SetTimeZone","MonthQuery","TotalsForMonth","BudgetMonthQuery","BudgetExceededForMonth","BadPeriod","UserNotFound")) {
            assertNotNull(a.get(name),"the canonical declaration must actually exist: "+name);
            assertEquals(a.get(name),b.get(name),name+" must come from the actual topic");
        }
    }
    @Test void routesErrorCodesTrustedActorAndTheSnapshotAreCheckedAgainstTheActualPlan() {
        for(var compiled:List.of(ledger,money)) {
            var month=capability(compiled,"TotalsForMonth");
            assertTrue(month.authenticated());assertEquals(month.route()+"?month=",CalendarLedgerChecks.MONTH);
            assertEquals(SpringBootDeclaration.TransactionMode.REPEATABLE_READ,month.transactionMode());
            assertTrue(month.workflow().steps().stream().anyMatch(SpringBootWorkflow.MonthStep.class::isInstance));
            var setting=capability(compiled,"SetTimeZone");assertTrue(setting.authenticated());assertEquals("/api/me/time-zone",setting.route());
            assertTrue(compiled.generatedFiles().stream().anyMatch(f -> f.relativePath().endsWith("/SetTimeZoneController.java")
                && !f.content().contains("@ResponseStatus")),"settings keep their default 200 status");
            assertTrue(compiled.loweredModel().projectArtifacts().stream().filter(ProjectArtifact.ValidationSupport.class::isInstance)
                .map(ProjectArtifact.ValidationSupport.class::cast).anyMatch(ProjectArtifact.ValidationSupport::utcInstantBinding));
            assertEquals("/api/budgets/{budgetId}/exceeded",capability(compiled,"BudgetExceededForMonth").route());
            for(String name:List.of("BadPeriod","BudgetNotFound","UserNotFound"))
                assertTrue(compiled.generatedFiles().stream().anyMatch(f -> f.relativePath().endsWith("/"+name+"Exception.java")
                    && f.content().contains("super(\""+name+"\", HttpStatus."+(name.equals("BadPeriod")?"BAD_REQUEST":"NOT_FOUND")+");")),
                    name+" is the public SIR code, not the Java exception class name");
        }
    }
    @Test void newHttpWritesUseTheExistingCreateStatus() {
        var create=money.loweredModel().declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
            .filter(c -> c.route().equals("/api/entries") && c.httpMethod()==SpringBootDeclaration.HttpMethod.POST).findFirst().orElseThrow();
        assertTrue(money.generatedFiles().stream().anyMatch(f -> f.relativePath().endsWith("/"+create.controllerName()+".java")
            && f.content().contains("@ResponseStatus(HttpStatus.CREATED)")),"the actual Crud entry POST returns 201");
        assertTrue(money.loweredModel().declarations().stream().anyMatch(d -> d instanceof SpringBootDeclaration.CapabilityDeclaration c
            && c.route().equals("/api/entries/{id}") && c.httpMethod()==SpringBootDeclaration.HttpMethod.GET),"the fixture reads the created row using the actual Crud route");
    }
    @Test void nonUtcConformanceSetupUsesTheRealPoolInitializationKeyWithoutChangingOrdinaryRuns() {
        assertEquals(Map.of(),BusinessSliceHarness.sessionInitializationEnvironment(false));
        assertEquals(Map.of("SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL","SET SESSION sql_mode=''"),BusinessSliceHarness.sessionInitializationEnvironment(true));
        assertEquals(Map.of("SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL","SET SESSION sql_mode='', time_zone='+05:30'"),BusinessSliceHarness.sessionInitializationEnvironment(true,true));
    }
    private static SpringBootDeclaration.CapabilityDeclaration capability(ProjectCompilation.Compiled compiled,String name) {
        return compiled.loweredModel().declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).filter(c -> c.javaName().equals(name)).findFirst().orElseThrow();
    }
    private static ProjectCompilation.Compiled compile(String path) throws Exception {
        var id=SourceId.of("project.sir");var diagnostics=new ArrayList<ExecutionDiagnostic>();
        var result=ProjectCompilation.compile(new SourceSnapshot(id,Map.of(id,ConformanceFixtures.readResource(path).getBytes(StandardCharsets.UTF_8))),new SpringBootGenerator()::generate,diagnostics);
        assertTrue(result.isPresent()&&diagnostics.isEmpty(),diagnostics.toString());return result.orElseThrow();
    }
    private static Map<String,String> declarations(String source) {
        source=source.replace("\r\n","\n");var parsed=SirParser.create().parseProject(new SirSource(SourceId.of("project.sir"),source));
        assertTrue(parsed.isSuccess(),parsed.diagnostics().toString());var unit=(AstSourceUnit.Root)parsed.unit().orElseThrow();
        var result=new HashMap<String,String>();
        for(var d:unit.declarations()) {
            String name=switch(d) {
                case AstEntityDecl x -> x.name().text();case AstInputDecl x -> x.name().text();case AstViewDecl x -> x.name().text();
                case AstCapabilityDecl x -> x.name().text();case AstEnumDecl x -> x.name().text();case AstErrorDecl x -> x.name().text();
                case AstSemanticTypeDecl x -> x.name().text();default -> null;
            };
            if(name!=null)result.put(name,source.substring(source.offsetByCodePoints(0,d.span().start().codePointOffset()),source.offsetByCodePoints(0,d.span().end().codePointOffset())).strip());
        }
        return Map.copyOf(result);
    }
}
