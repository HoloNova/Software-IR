package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ExplicitLockToolchainContractTest {
    @Test void bookingTopicUsesRoomLockBeforeOverlapQueryAndOnlyPersistsRealFields() throws Exception {
        var model = compile("topics/booking/project.sir");
        var service = file(model, "BookService.java");
        assertTrue(service.contains("Isolation.READ_COMMITTED"), service);
        assertTrue(service.indexOf(".last(\"FOR UPDATE\")") < service.indexOf("bookingMapper.selectObjs"), service);
        assertTrue(service.indexOf("bookingMapper.selectObjs") < service.indexOf("bookingMapper.insert"), service);
        assertTrue(service.contains("Booking::getStartsAt"), service);
        assertTrue(service.contains("Booking::getEndsAt"), service);
        assertTrue(file(model, "application.yml").contains("local-cache-scope: STATEMENT"));
    }

    @Test void theRealItFixtureReallyPerformsTheSameQueryBeforeAndAfterTakingALock() throws Exception {
        var model = compile("valid/multiplication.sir");
        var service = file(model, "BookAfterReadService.java");
        int first = service.indexOf("bookingMapper.selectObjs"), lock = service.indexOf(".last(\"FOR UPDATE\")");
        int second = service.indexOf("bookingMapper.selectObjs", first + 1);
        assertTrue(first >= 0 && first < lock && lock < second, service);
        assertTrue(second < service.indexOf("bookingMapper.insert"), service);
        assertTrue(file(model, "BookController.java").contains("HttpStatus.CREATED"));
    }
    private static ProjectCompilation.Compiled compile(String resource) throws Exception {
        var source = ProjectTestSupport.oneFile("project.sir", ProjectTestSupport.resource(resource));
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(source, new SpringBootGenerator()::generate, diagnostics);
        assertTrue(compiled.isPresent(), diagnostics.toString()); return compiled.orElseThrow();
    }
    private static String file(ProjectCompilation.Compiled compiled, String name) {
        return compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/" + name)).findFirst().orElseThrow().content();
    }
}
