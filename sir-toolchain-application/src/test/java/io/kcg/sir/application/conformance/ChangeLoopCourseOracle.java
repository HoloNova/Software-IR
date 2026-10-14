package io.kcg.sir.application.conformance;

import io.kcg.sir.application.api.LogRef;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.StageOutcome;
import io.kcg.sir.application.api.VerificationPorts;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The original requirement of the multi-source course search as a judge: a course is returned only if at least one of
 * its enrollments is ACTIVE (so CS101, CS102 and MAT101 qualify, ART101 has only a cancelled enrollment and PHY101 has
 * none), and reading changes none of the three tables. Written from the requirement and the seed data, never from a
 * candidate: the oracle is handed an address and nothing else.
 */
final class ChangeLoopCourseOracle implements VerificationPorts.Oracle {
    static final String REVISION = "CHANGE_LOOP_ACTIVE_ENROLLMENT_GOLDEN_V1";
    static final String ROUTE = "/api/search-course-enrollments?page=1&size=10";
    private static final List<String> EXPECTED = List.of("CS101", "CS102", "MAT101");

    private final MysqlVerificationSandbox sandbox;

    ChangeLoopCourseOracle(MysqlVerificationSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String revision() {
        return REVISION;
    }

    @Override
    public StageOutcome check(URI baseUrl, Path evidenceRoot, ResourceLimits limits) {
        List<String> log = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try (MysqlObserver observer = sandbox.openObserver()) {
            Map<String, List<String>> before = rows(observer);
            var page = new HttpAssertionClient().get(baseUrl + ROUTE, Map.of());
            log.add("GET " + ROUTE + " -> " + page.statusCode() + " " + page.body());
            List<String> codes = Pattern.compile("\"code\"\\s*:\\s*\"((?:ART|CS|MAT|PHY)[0-9]+)\"").matcher(page.body()).results().map(m -> m.group(1)).toList();
            check(log, failures, "exactly the courses with an ACTIVE enrollment, in code order, total 3",
                    page.statusCode() == 200 && BusinessSliceHarness.field(page.body(), "total").equals("3") && codes.equals(EXPECTED),
                    "status=" + page.statusCode() + " total=" + BusinessSliceHarness.field(page.body(), "total") + " codes=" + codes);
            check(log, failures, "a course with only a cancelled enrollment (ART101) and a course without enrollments (PHY101) are not returned",
                    !codes.contains("ART101") && !codes.contains("PHY101"), "codes=" + codes);
            Map<String, List<String>> after = rows(observer);
            check(log, failures, "reading changes none of course, student and enrollment", before.equals(after), "tables differ after the read");
        } catch (Exception e) {
            return StageOutcome.failed("BUSINESS_CHECK_ERROR", "the business check could not complete (" + e.getClass().getSimpleName() + ")");
        }

        try {
            LogRef ref = write(evidenceRoot, log);
            return failures.isEmpty()
                    ? StageOutcome.passed("the requirement holds on every check (" + REVISION + ")", List.of(ref))
                    : StageOutcome.failed("BUSINESS_ASSERTION_FAILED", failures.size() + " requirement check(s) failed (" + REVISION + ")", failures, List.of(ref));
        } catch (Exception e) {
            return StageOutcome.failed("BUSINESS_CHECK_ERROR", "the business log could not be written (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void check(List<String> log, List<String> failures, String what, boolean ok, String observed) {
        log.add((ok ? "PASS " : "FAIL ") + what + (ok ? "" : " [" + observed + "]"));
        if (!ok) {
            String line = what + " [" + observed + "]";
            failures.add(line.length() > 900 ? line.substring(0, 900) : line);
        }
    }

    private Map<String, List<String>> rows(MysqlObserver observer) throws SQLException {
        Map<String, List<String>> rows = new TreeMap<>();
        for (String table : List.of("course", "student", "enrollment")) {
            rows.put(table, observer.queryRows(sandbox.schema(), table, "id"));
        }

        return rows;
    }

    private static LogRef write(Path evidenceRoot, List<String> lines) throws Exception {
        Path file = evidenceRoot.resolve("logs/business.log");
        Files.createDirectories(file.getParent());
        byte[] bytes = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        return new LogRef(LogRef.Kind.BUSINESS, "logs/business.log", bytes.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
