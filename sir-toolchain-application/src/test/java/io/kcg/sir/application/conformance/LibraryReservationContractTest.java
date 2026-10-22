package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The reservation protocol must be present in the actual library, not only in a feasibility sample. */
class LibraryReservationContractTest {
    private static ProjectCompilation.Compiled compiled;
    @BeforeAll static void compileActualTopic() throws Exception {
        var id = SourceId.of("project.sir");
        byte[] bytes;
        try (var source = LibraryReservationContractTest.class.getResourceAsStream("/topics/library/project.sir")) {
            assertNotNull(source); bytes = source.readAllBytes();
        }
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        compiled = ProjectCompilation.compile(new SourceSnapshot(id, Map.of(id, bytes)),
                new SpringBootGenerator()::generate, diagnostics).orElseThrow(() -> new AssertionError(diagnostics.toString()));
    }
    @Test void theActualTopicProvidesEnqueueClaimCancellationAndPrivateListing() {
        for (String operation : List.of("ReserveBook", "ClaimReservation", "CancelReservation", "MyReservations"))
            assertTrue(compiled.generatedFiles().stream().anyMatch(f -> f.relativePath().endsWith("/" + operation + "Service.java")),
                    "actual library is missing " + operation);
    }
    @Test void everyAvailabilityWriterLocksTheSharedBookBeforeChangingACopy() {
        for (String operation : List.of("Borrow", "GiveBack", "AddCopy")) {
            String service = file(operation + "Service.java");
            assertTrue(service.contains("bookMapper.selectOne"), operation + " must join the same book mutex");
            assertTrue(service.contains("Isolation.READ_COMMITTED"), service);
            assertTrue(service.contains(".last(\"FOR UPDATE\")"), service);
        }
        assertTrue(file("BorrowService.java").contains("QueueHasPriorityException"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(LibraryReservationChecks.Failure.class)
    void itErrorsKeepTheirActualDeclaredNamesAndStatuses(LibraryReservationChecks.Failure expected) {
        var error=compiled.semanticModel().declarations().stream().filter(io.kcg.sir.semantic.model.NormalizedError.class::isInstance)
                .map(io.kcg.sir.semantic.model.NormalizedError.class::cast).filter(e->e.name().equals(expected.code())).findFirst().orElseThrow();
        assertEquals(error.httpStatus(),expected.status());
        assertTrue(file(error.name()+"Exception.java").contains("super(\""+expected.code()+"\", HttpStatus."));
    }
    @Test void realItRoutesAndReadinessBelongToTheGeneratedInterface() {
        var plan=new io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner().plan(compiled.semanticModel(),compiled.loweredModel());
        var expected=Map.of("POST /api"+LibraryReservationChecks.RESERVE_ROUTE,"ReserveBook",
                "POST /api"+LibraryReservationChecks.CLAIM_ROUTE,"ClaimReservation",
                "DELETE /api"+LibraryReservationChecks.CANCEL_ROUTE,"CancelReservation",
                "GET /api"+LibraryReservationChecks.RESERVE_ROUTE,"MyReservations");
        expected.forEach((key,operation)-> {
            var endpoint=plan.api().endpoints().stream().filter(e->(e.method()+" "+e.route()).equals(key)).findFirst().orElseThrow();
            assertTrue(endpoint.authenticated(),key);
            assertTrue(file(operation+"Controller.java").contains(operation.equals("MyReservations")?"@GetMapping":operation.equals("CancelReservation")?"@DeleteMapping":"HttpStatus.CREATED"));
        });
        var readiness=java.net.URI.create(LibraryBorrowingBusinessConformanceIT.FIXTURE.readinessPath());
        assertTrue(plan.api().endpoints().stream().anyMatch(e->e.method().equals("GET") && !e.authenticated()
                && e.sample().equals(readiness.getPath())),readiness.toString());
    }
    @Test void privacyDoesNotHideOtherReadersFromInternalFifoChecks() {
        var entity=compiled.semanticModel().declarations().stream().filter(io.kcg.sir.semantic.model.NormalizedEntity.class::isInstance)
                .map(io.kcg.sir.semantic.model.NormalizedEntity.class::cast).filter(e->e.name().equals("Reservation")).findFirst().orElseThrow();
        assertTrue(entity.scope().isEmpty(),"global owner scope would allow each reader to consider themselves first");
        assertTrue(file("MyReservationsService.java").contains("Reservation::getReaderId"));
        assertTrue(file("ClaimReservationService.java").contains("NotQueueHeadException"));
        assertFalse(file("ReservationView.java").contains("getReader"));
    }
    private static String file(String name) {
        return compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/" + name))
                .findFirst().orElseThrow().content();
    }
}
