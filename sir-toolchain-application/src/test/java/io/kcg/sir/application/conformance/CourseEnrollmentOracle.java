package io.kcg.sir.application.conformance;

import io.kcg.sir.application.api.LogRef;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.StageOutcome;
import io.kcg.sir.application.api.VerificationPorts;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The original requirement of the course-enrollment exercise as a judge: a course is returned only if at least one
 * of its enrollments is ACTIVE; courses are distinct, ordered by code and paged; the nested projection keeps the
 * other enrollments of a matching course; an invalid page is rejected with 400; and reading changes none of the three
 * tables. The expectations are written down here from the exercise (the Q11 golden data), never derived from a
 * candidate: the oracle is handed an address and nothing else about the candidate.
 */
final class CourseEnrollmentOracle implements VerificationPorts.Oracle {
    static final String REVISION = "Q11_ACTIVE_ENROLLMENT_GOLDEN_V1";
    static final String ROUTE = "/api/search-course-enrollments";
    private static final List<String> EXPECTED_FIRST_PAGE = List.of("CS101", "CS102", "MAT101", "ZOO101");
    private static final List<String> EXPECTED_SECOND_PAGE_OF_TWO = List.of("MAT101", "ZOO101");

    private final MysqlVerificationSandbox sandbox;

    CourseEnrollmentOracle(MysqlVerificationSandbox sandbox) {
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
            HttpAssertionClient http = new HttpAssertionClient();
            String base = baseUrl.toString();

            var first = http.get(base + ROUTE + "?page=1&size=10", Map.of());
            log.add("GET page=1&size=10 -> " + first.statusCode() + " " + first.body());
            check(log, failures, "first page returns exactly the courses with an ACTIVE enrollment, in code order, total 4",
                    first.statusCode() == 200 && BusinessSliceHarness.field(first.body(), "total").equals("4") && codes(first.body()).equals(EXPECTED_FIRST_PAGE),
                    "status=" + first.statusCode() + " total=" + BusinessSliceHarness.field(first.body(), "total") + " codes=" + codes(first.body()));
            check(log, failures, "a course without any enrollment is not returned",
                    first.statusCode() == 200 && !first.body().contains("PHY101"), "codes=" + codes(first.body()));
            check(log, failures, "the nested projection keeps students and the cancelled enrollments of a matching course",
                    first.body().contains("\"enrollments\"") && first.body().contains("\"student\"") && first.body().contains("\"name\":\"Alice\"")
                            && first.body().contains("\"name\":\"Bob\"") && first.body().contains("\"status\":\"CANCELLED\""), "projection missing");

            var second = http.get(base + ROUTE + "?page=2&size=2", Map.of());
            log.add("GET page=2&size=2 -> " + second.statusCode() + " " + second.body());
            check(log, failures, "the second page of two is MAT101 and ZOO101", second.statusCode() == 200 && codes(second.body()).equals(EXPECTED_SECOND_PAGE_OF_TWO),
                    "status=" + second.statusCode() + " codes=" + codes(second.body()));

            var invalid = http.get(base + ROUTE + "?page=0&size=10", Map.of());
            log.add("GET page=0&size=10 -> " + invalid.statusCode() + " " + invalid.body());
            check(log, failures, "an invalid page is rejected with 400 InvalidPage",
                    invalid.statusCode() == 400 && BusinessSliceHarness.field(invalid.body(), "code").equals("InvalidPage"), "status=" + invalid.statusCode());

            Map<String, List<String>> after = rows(observer);
            check(log, failures, "reading changes none of course, enrollment and student", before.equals(after),
                    "before=" + sizes(before) + " after=" + sizes(after));
        } catch (Exception e) {
            // An exception is not a business verdict, and its text may name a host or an account.
            return StageOutcome.failed("BUSINESS_CHECK_ERROR", "the business check could not complete (" + e.getClass().getSimpleName() + ")");
        }

        try {
            LogRef ref = write(evidenceRoot, log);
            if (failures.isEmpty()) {
                return StageOutcome.passed("the requirement holds on every check (" + REVISION + ")", List.of(ref));
            }

            return StageOutcome.failed("BUSINESS_ASSERTION_FAILED", failures.size() + " requirement check(s) failed (" + REVISION + ")", failures, List.of(ref));
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
        for (String table : List.of("course", "enrollment", "student")) {
            rows.put(table, observer.queryRows(sandbox.schema(), table, "id"));
        }

        return rows;
    }

    private static Map<String, Integer> sizes(Map<String, List<String>> rows) {
        Map<String, Integer> sizes = new TreeMap<>();
        rows.forEach((table, list) -> sizes.put(table, list.size()));
        return sizes;
    }

    static List<String> codes(String body) {
        return Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(body).results().map(m -> m.group(1)).toList();
    }

    private static LogRef write(Path evidenceRoot, List<String> lines) throws Exception {
        Path file = evidenceRoot.resolve("logs/business.log");
        Files.createDirectories(file.getParent());
        byte[] bytes = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        return new LogRef(LogRef.Kind.BUSINESS, "logs/business.log", bytes.length,
                java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
