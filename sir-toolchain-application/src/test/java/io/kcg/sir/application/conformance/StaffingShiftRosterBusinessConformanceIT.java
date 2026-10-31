package io.kcg.sir.application.conformance;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StaffingShiftRosterBusinessConformanceIT {
    static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Empty weekly shifts and multi-employee store rosters", "/valid/staffing-shift-roster.sir",
            "/conformance/mysql/staffing-shift-roster-ddl.sql", "/conformance/mysql/staffing-shift-roster-seed.sql", "assignment",
            "/api/stores?page=1&size=10", "staffing-slice-report.txt", "staffing-slice", "kcg.staffing-conformance.enabled");
    private static final BoundedProblemFixture.Definition DATABASE = new BoundedProblemFixture.Definition(
            List.of("user", "store", "access_token", "employee", "shift", "assignment"),
            Map.of("user", 3, "access_token", 4, "store", 3, "employee", 4, "shift", 6, "assignment", 4), StaffingRosterChecks::validSeed);

    @Test
    void shiftsAssignmentsCountsAndRostersMatchHttpAndIndependentDatabaseState() throws Exception {
        BoundedProblemConformance.verify(FIXTURE, DATABASE, "50000000-0000-4000-8000-", List.of(
                new BoundedProblemConformance.Section("B identity", StaffingRosterChecks::identity),
                new BoundedProblemConformance.Section("Q lists permissions and seeded headcounts", StaffingRosterChecks::listings),
                new BoundedProblemConformance.Section("G01-G02 generation and both bands", StaffingWeeklyChecks::generation),
                new BoundedProblemConformance.Section("G03-G04 bounds and late rollback", StaffingWeeklyChecks::boundsAndLateRollback),
                new BoundedProblemConformance.Section("G05-G06 validation and path identity", StaffingWeeklyChecks::invalidPlans),
                new BoundedProblemConformance.Section("G07 leap year", StaffingWeeklyChecks::leapYear),
                new BoundedProblemConformance.Section("A01-A05 assignments and refusal ordering", StaffingAssignmentChecks::assignmentsAndRefusals),
                new BoundedProblemConformance.Section("A06 same employee both bands", StaffingAssignmentChecks::sameDayBands),
                new BoundedProblemConformance.Section("A07 path identity", StaffingAssignmentChecks::pathIdentity),
                new BoundedProblemConformance.Section("A08 relation order", StaffingAssignmentChecks::relationOrder),
                new BoundedProblemConformance.Section("X01 same employee", StaffingConcurrencyChecks::sameEmployee),
                new BoundedProblemConformance.Section("X02 different employees", StaffingConcurrencyChecks::differentEmployees),
                new BoundedProblemConformance.Section("X03 same weekly plan", StaffingConcurrencyChecks::sameWeeklyPlan)));
    }
}
