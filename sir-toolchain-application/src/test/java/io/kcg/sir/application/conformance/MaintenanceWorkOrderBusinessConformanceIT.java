package io.kcg.sir.application.conformance;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaintenanceWorkOrderBusinessConformanceIT {
    static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Versioned work orders, optional assignees and bounded inspection batches", "/valid/maintenance-work-order.sir",
            "/conformance/mysql/maintenance-work-order-ddl.sql", "/conformance/mysql/maintenance-work-order-seed.sql",
            "work_order", "/api/facilities?page=1&size=10", "maintenance-slice-report.txt", "maintenance-slice", "kcg.maintenance-conformance.enabled");
    private static final BoundedProblemFixture.Definition DATABASE = new BoundedProblemFixture.Definition(
            List.of("user", "facility", "access_token", "work_order", "inspection_order"),
            Map.of("user", 4, "access_token", 5, "facility", 3, "work_order", 6, "inspection_order", 6), MaintenanceWorkOrderChecks::validSeed);

    @Test
    void workOrdersAndInspectionBatchesMatchHttpAndIndependentDatabaseState() throws Exception {
        BoundedProblemConformance.verify(FIXTURE, DATABASE, "40000000-0000-4000-8000-", List.of(
                new BoundedProblemConformance.Section("B/W identity and creation", MaintenanceWorkOrderChecks::identityAndCreation),
                new BoundedProblemConformance.Section("D details", MaintenanceWorkOrderChecks::details),
                new BoundedProblemConformance.Section("P edits and soft deletion", MaintenanceWorkOrderChecks::edits),
                new BoundedProblemConformance.Section("L optional filters and pagination", MaintenanceWorkOrderChecks::lists),
                new BoundedProblemConformance.Section("R generation", MaintenanceInspectionChecks::generation),
                new BoundedProblemConformance.Section("R rejection and validation", MaintenanceInspectionChecks::rejectedGeneration),
                new BoundedProblemConformance.Section("E closure", MaintenanceInspectionChecks::closure),
                new BoundedProblemConformance.Section("E closure boundaries", MaintenanceInspectionChecks::closureBoundaries),
                new BoundedProblemConformance.Section("X01 same version", MaintenanceConcurrencyChecks::sameVersion),
                new BoundedProblemConformance.Section("X02 concurrent closures", MaintenanceConcurrencyChecks::concurrentClosures),
                new BoundedProblemConformance.Section("X03 generation versus closure", MaintenanceConcurrencyChecks::generationVersusClosure)));
    }
}
