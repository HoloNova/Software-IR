package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.change.edit.*;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecimalSumToolchainContractTest {
    @Test void ledgerTotalsParticipateInTheProjectGraphAsTheirOwnSupportRole() throws Exception {
        var source = ProjectTestSupport.oneFile("project.sir", ProjectTestSupport.resource("topics/ledger/project.sir"));
        var result = compile(source);
        var input = new SpringBootProjectGraphInputFactory().build(result.semanticModel(),result.loweredModel(),result.generatedFiles(),source);
        assertEquals(1,input.artifacts().stream().filter(a -> a.role()==ArtifactRole.ProjectRole.EXACT_AGGREGATE).count());
        var graph=new io.kcg.sir.projectgraph.api.ProjectGraphBuilder().build(input);
        assertInstanceOf(io.kcg.sir.projectgraph.api.ProjectGraphAnalysis.Success.class,graph,()->String.valueOf(graph));
        assertTrue(result.generatedFiles().stream().anyMatch(f -> f.relativePath().endsWith("/ExactAggregate.java")));
        assertTrue(file(result,"TotalBetweenController.java").contains("/entries/total"));
        assertTrue(file(result,"TotalOfCategoryController.java").contains("/categories/{categoryId}/total"));
        assertTrue(file(result,"TotalOfCategoryService.java").contains("getId()"),"loaded Category binds its identity");
    }
    @Test void theActualMoneyItContainsAllThreeSumPlansAndTheSameExistingWalletRoutes() throws Exception {
        var result=compile(ProjectTestSupport.oneFile("project.sir",ProjectTestSupport.resource("valid/money.sir")));
        assertTrue(file(result,"SumMeasuredController.java").contains("/measurements/total"));
        assertTrue(file(result,"TaggedSumController.java").contains("/measurements/tagged"));
        assertTrue(file(result,"LiteralSumController.java").contains("/measurements/literal"));
        assertTrue(file(result,"ShowWalletController.java").contains("/wallet/show"));
    }
    @Test void sumEntityAndFieldHaveFormalCrossFileBindingsAndRequireTheirImport() throws Exception {
        var source=multi();var model=ProjectTestSupport.model(source);
        var bindings=model.referenceSiteBindings().all().stream().filter(b -> b.site().role()==ReferenceRole.SUM_ENTITY||b.site().role()==ReferenceRole.SUM_FIELD).toList();
        assertEquals(2,bindings.size());
        var bytes=new LinkedHashMap<SourceId,byte[]>();for(var file:source.manifest().files())bytes.put(file.sourceId(),source.bytes(file.sourceId()));
        String root=new String(bytes.get(source.entry()),StandardCharsets.UTF_8);
        String imported="import Course from \"modules/course.sir\";";
        bytes.put(source.entry(),root.replace(imported," ".repeat(imported.length())).getBytes(StandardCharsets.UTF_8));
        var negative=new SirSemanticAnalyzer().analyzeProject(ProjectTestSupport.parse(new SourceSnapshot(source.entry(),bytes)));
        assertFalse(negative.isSuccess());
        for(var binding:bindings)assertTrue(negative.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-SOURCE-VISIBILITY-001")
                && d.primarySpan().equals(binding.site().span())),negative.diagnostics().toString());
    }
    @Test void importedMeasurementRenamesRewriteTheBoundSumTokenAndStillCompile() throws Exception {
        var source=multi();var result=new SourceEditor().edit(source,new CommandBatch(CommandBatch.PROTOCOL,"CourseAdmin",SourceEditor.sha256(source),List.of(
                new Command.Rename(Selector.root().named("declarations","Course").named("fields","measurement"),"cost"))));
        var edited=assertInstanceOf(EditResult.Edited.class,result,()->String.valueOf(result));
        assertTrue(new String(edited.sources().bytes(source.entry()),StandardCharsets.UTF_8).contains("sum Course.cost where true as total;"));
        compile(edited.sources());
    }
    private static SourceSnapshot multi() throws Exception {
        var files=new LinkedHashMap<SourceId,byte[]>();
        for(String file:ProjectTestSupport.FILES){
            String source=ProjectTestSupport.resource("valid/multi-course/"+file).replace("\r\n","\n");
            if(file.equals("modules/course.sir"))source=source.replace("field code: Code;","field code: Code; field measurement: Money default 0.0;");
            if(file.equals("project.sir"))source=source.replace("    capability CreateCourse {","""
                        capability ImportedTotal {
                          output Money; requires readonly; expose query;
                          workflow { sum Course.measurement where true as total; return total; }
                        }
                    """+"    capability CreateCourse {");
            files.put(SourceId.of(file),source.getBytes(StandardCharsets.UTF_8));
        }
        return new SourceSnapshot(SourceId.of("project.sir"),files);
    }
    private static ProjectCompilation.Compiled compile(SourceSnapshot source){
        var diagnostics=new ArrayList<ExecutionDiagnostic>();var result=ProjectCompilation.compile(source,new SpringBootGenerator()::generate,diagnostics);
        assertTrue(result.isPresent(),diagnostics.toString());return result.orElseThrow();
    }
    private static String file(ProjectCompilation.Compiled result,String name){
        return result.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/"+name)).findFirst().orElseThrow().content();
    }
}
