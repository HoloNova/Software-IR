package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Low-cost checks for the actual HTTP/SQL fixture, rather than a different illustrative SIR program. */
class CourseSelectionContractTest {
    private static ProjectCompilation.Compiled compiled;
    @BeforeAll static void compileActualTopic() throws Exception {
        var id=SourceId.of("project.sir");
        var diagnostics=new ArrayList<ExecutionDiagnostic>();
        var source=new SourceSnapshot(id,Map.of(id,resource("/topics/course/project.sir").getBytes(StandardCharsets.UTF_8)));
        compiled=ProjectCompilation.compile(source,new SpringBootGenerator()::generate,diagnostics)
                .orElseThrow(()->new AssertionError(diagnostics.toString()));
    }
    @ParameterizedTest @EnumSource(CourseSelectionChecks.Failure.class)
    void fixedItErrorsMatchTheRealDeclarationsAndGeneratedExceptions(CourseSelectionChecks.Failure expected) {
        var error=compiled.semanticModel().declarations().stream().filter(NormalizedError.class::isInstance)
                .map(NormalizedError.class::cast).filter(e->e.name().equals(expected.code())).findFirst().orElseThrow();
        assertEquals(error.httpStatus(),expected.status());
        assertTrue(file(error.name()+"Exception.java").contains("super(\""+expected.code()+"\", HttpStatus."));
    }
    @Test void readinessProbeTargetsAnExistingAnonymousGetOperation() {
        var path=java.net.URI.create(CourseSelectionBusinessConformanceIT.FIXTURE.readinessPath());
        var plan=new SpringBootDeploymentPlanner().plan(compiled.semanticModel(),compiled.loweredModel());
        var endpoint=plan.api().endpoints().stream().filter(e->e.method().equals("GET") && e.route().equals(path.getPath()))
                .findFirst().orElseThrow(()->new AssertionError("readiness probe has no generated GET route: "+path));
        assertFalse(endpoint.authenticated(),"the readiness client sends no credentials");
        assertEquals(io.kcg.sir.lowering.springboot.model.DeploymentPlan.Binding.QUERY,endpoint.binding());
    }

    @Test void readinessProbeSuppliesTheActualSearchInputWithValidPagination() {
        var path=java.net.URI.create(CourseSelectionBusinessConformanceIT.FIXTURE.readinessPath());
        assertNotNull(path.getRawQuery(),"readiness must pass the search keyword and pagination");
        var parameters=java.util.Arrays.stream(path.getRawQuery().split("&")).map(p->p.split("=",2))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(p->p[0],p->p[1]));
        var capability=compiled.loweredModel().declarations().stream()
                .filter(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(c->c.javaName().equals("SearchCourses")).findFirst().orElseThrow();
        assertEquals(capability.route(),path.getPath());
        var inputType=assertInstanceOf(io.kcg.sir.lowering.springboot.model.LoweredJavaType.Declared.class,
                capability.input().orElseThrow().type());
        var input=compiled.loweredModel().declarations().stream()
                .filter(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.InputDeclaration.class::isInstance)
                .map(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.InputDeclaration.class::cast)
                .filter(i->i.sourceSymbol().equals(inputType.symbolId())).findFirst().orElseThrow();
        assertEquals(input.fields().stream().map(f->f.javaName()).collect(java.util.stream.Collectors.toSet()),parameters.keySet());
        assertFalse(parameters.get("keyword").isBlank());
        assertEquals("1",parameters.get("page"));
        assertEquals("1",parameters.get("size"));
    }

    @Test void actualRoutesAndCreationStatusesMatchTheIt() {
        for(String name:java.util.List.of("Enroll", "CreateCourse", "AddPrerequisite", "RecordCompletion"))
            assertTrue(file(name+"Controller.java").contains("HttpStatus.CREATED"),file(name+"Controller.java"));
        assertTrue(file("EnrollController.java").contains(CourseSelectionChecks.ENROLL_ROUTE));
        assertTrue(file("DropController.java").contains("@DeleteMapping"));
        assertTrue(file("DropController.java").contains("/enrollments/{id}"));
        assertTrue(file("PublishCourseController.java").contains("/courses/{id}/publish"));
        assertTrue(file("RemovePrerequisiteController.java").contains("/prerequisites/{id}"));
        assertFalse(file("PublishCourseController.java").contains("HttpStatus.CREATED"));
    }
    @Test void fixtureTablesMatchTheActualDeploymentAndColumnOrderingUsedByObservers() throws Exception {
        var plan=new SpringBootDeploymentPlanner().plan(compiled.semanticModel(),compiled.loweredModel());
        String ddl=resource("/conformance/mysql/course-ddl.sql");
        for(var table:plan.tables()) assertTrue(ddl.contains("CREATE TABLE "+table.name()+" ("),table.name());
        var course=plan.tables().stream().filter(t->t.name().equals("course")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("id","code","name","teacher_id","capacity","enrolled","weekday","start_period","end_period","published"),
                course.columns().stream().map(c->c.name()).toList());
        assertTrue(ddl.contains("UNIQUE (student_id,course_id)"));
        assertTrue(ddl.contains("UNIQUE (target_course_id,required_id)"));
        assertFalse(ddl.contains("REFERENCES enrollment("),"late-delete failure table must be a temporary fixture, not product DDL");
    }
    @Test void publishedCurriculumCannotBeRewrittenByAnyExposedCapability() {
        for(String name:java.util.List.of("AddPrerequisite","RemovePrerequisite","PublishCourse")) {
            String service=file(name+"Service.java");
            assertTrue(service.indexOf("ForbiddenException")>=0,service);
            assertTrue(service.contains("CoursePublishedException"),service);
            assertTrue(service.contains(".last(\"FOR UPDATE\")"),service);
        }
        assertFalse(file("CompletionInput.java").contains("passed"));
        assertFalse(file("NewCourse.java").contains("published"));
        String select=file("SearchCoursesService.java");
        assertTrue(select.contains("Course::getPublished, true"),select);
        assertTrue(select.contains("input.getPage() == null"),select);
    }
    private static String file(String name) {
        return compiled.generatedFiles().stream().filter(f->f.relativePath().endsWith("/"+name)).findFirst().orElseThrow().content();
    }
    private static String resource(String name) throws Exception {
        try(var input=CourseSelectionContractTest.class.getResourceAsStream(name)) { assertNotNull(input,name); return new String(input.readAllBytes(),StandardCharsets.UTF_8); }
    }
}
