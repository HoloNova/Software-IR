package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlogOracleTest {
    private static final Instant TIME = Instant.parse("2026-01-02T00:00:00Z");

    @Test
    void publicProjectionRejectsExtraKeysWrongNestedIdentityAndQuotedNumbers() throws Exception {
        var state = state();
        var valid = BlogAssertions.decode(publicPost());
        assertTrue(BlogAssertions.matchesPost(valid, state, 102, true, Duration.ZERO, null));
        for (String invalid : List.of(
                publicPost().replace("\"likes\":0", "\"likes\":\"0\""),
                publicPost().replace("\"name\":\"alice\"", "\"name\":\"bob\""),
                publicPost().replace("\"title\":\"alpha\"", "\"title\":\"different\""),
                publicPost().replace("\"content\":\"body\"", "\"state\":\"PUBLISHED\",\"content\":\"body\""),
                publicPost().replace("\"id\":102", "\"id\":103"))) {
            assertFalse(BlogAssertions.matchesPost(BlogAssertions.decode(invalid), state, 102, true, Duration.ZERO, null), invalid);
        }
    }

    @Test
    void responseTimeToleranceAppliesOnlyWhenExplicitlyRequestedForANewRow() throws Exception {
        var body = BlogAssertions.decode(publicPost().replace("00:00:00Z", "00:00:00.000500Z"));
        assertFalse(BlogAssertions.matchesPost(body, state(), 102, true, Duration.ZERO, null));
        assertTrue(BlogAssertions.matchesPost(body, state(), 102, true, Duration.ofMillis(1), null));
    }

    @Test
    void pageChecksRequireAllRecordsExactOrderAndFullTotalEvenOnAnEmptyPage() throws Exception {
        var page = BlogAssertions.decode("{\"total\":1,\"page\":1,\"size\":20,\"records\":[" + publicPost() + "]}");
        assertTrue(BlogAssertions.matchesPage(page, state(), List.of(102L), 1, 1, 20));
        assertFalse(BlogAssertions.matchesPage(page, state(), List.of(103L), 1, 1, 20));
        assertFalse(BlogAssertions.matchesPage(page, state(), List.of(), 1, 1, 20));
        assertFalse(BlogAssertions.matchesPage(page, state(), List.of(102L), 2, 1, 20));
        var twoRows = state().add("post", state().row("post", 102).with("id", 103L));
        var twoRecords = BlogAssertions.decode("{\"total\":2,\"page\":1,\"size\":20,\"records\":[" + publicPost()
                + "," + publicPost().replace("\"id\":102", "\"id\":103") + "]}");
        assertTrue(BlogAssertions.matchesPage(twoRecords, twoRows, List.of(102L, 103L), 2, 1, 20));
        assertFalse(BlogAssertions.matchesPage(twoRecords, twoRows, List.of(103L, 102L), 2, 1, 20));
        var empty = BlogAssertions.decode("{\"total\":4,\"page\":3,\"size\":2,\"records\":[]}");
        assertTrue(BlogAssertions.matchesPage(empty, state(), List.of(), 4, 3, 2));
        assertFalse(BlogAssertions.matchesPage(empty, state(), List.of(), 0, 3, 2));
    }

    @Test
    void authorViewUsesNumericReferencesAndCannotSubstituteThePublicShape() throws Exception {
        var body = BlogAssertions.decode("{\"id\":102,\"author\":1,\"category\":10,\"title\":\"alpha\","
                + "\"content\":\"body\",\"state\":\"PUBLISHED\",\"likes\":0,\"createdAt\":\"2026-01-02T00:00:00Z\"}");
        assertTrue(BlogAssertions.matchesPost(body, state(), 102, false, Duration.ZERO, null));
        assertFalse(BlogAssertions.matchesPost(body, state(), 102, true, Duration.ZERO, null));
        assertFalse(BlogAssertions.matchesPost(BlogAssertions.decode(publicPost()), state(), 102, false, Duration.ZERO, null));
    }

    @Test
    void strictJsonRejectsDuplicateFieldsAndTrailingDocuments() {
        assertThrows(IOException.class, () -> BlogAssertions.decode("{\"id\":1,\"id\":2}"));
        assertThrows(IOException.class, () -> BlogAssertions.decode("{} {}"));
    }

    @Test
    void declaredErrorsRequireTheExactEnvelopeAndEmptyFields() throws Exception {
        assertTrue(BlogAssertions.matchesError(BlogAssertions.decode(
                "{\"code\":\"TagAttached\",\"message\":\"TagAttached\",\"fields\":[]}"), "TagAttached"));
        assertFalse(BlogAssertions.matchesError(BlogAssertions.decode(
                "{\"code\":\"TagAttached\",\"message\":\"wrong\",\"fields\":[]}"), "TagAttached"));
        assertFalse(BlogAssertions.matchesError(BlogAssertions.decode(
                "{\"code\":\"TagAttached\",\"message\":\"TagAttached\",\"fields\":[],\"extra\":1}"), "TagAttached"));
    }

    @Test
    void expectedStateChangesNeverMutateTheBeforeSnapshotAndDetectChangesOutsideTheTarget() {
        var before = state();
        var expected = before.withPost(102, "likes", 1L);
        assertEquals(0L, before.row("post", 102).number("likes"));
        assertEquals(1L, expected.row("post", 102).number("likes"));
        assertNotEquals(expected, expected.withPost(102, "title", "changed"));
        assertThrows(UnsupportedOperationException.class, () -> before.tables().clear());
        assertThrows(UnsupportedOperationException.class, () -> before.row("post", 102).cells().clear());
    }

    @Test
    void lockProofFollowsIndirectWaitsAndCountsConnectionsAndTransactionsRatherThanEdges() {
        var edges = List.of(
                new BlogLockWaits.Edge("t1", 11, 99, "app"),
                new BlogLockWaits.Edge("t1", 11, 99, "app"),
                new BlogLockWaits.Edge("t2", 12, 11, "app"),
                new BlogLockWaits.Edge("other", 13, 99, "other-user"));
        var observed = BlogLockWaits.observe(edges, 99, "app");
        assertEquals(2, observed.connections().size());
        assertEquals(2, observed.transactions().size());
        assertFalse(observed.ready(6));
        assertTrue(observed.ready(2));
    }

    @Test
    void sixWaitsNeedBothSixDistinctConnectionsAndSixDistinctTransactions() {
        var actual = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> new BlogLockWaits.Edge("t" + i, i, 99, "app")).toList();
        assertTrue(BlogLockWaits.observe(actual, 99, "app").ready(6));
        var sameConnection = actual.stream().map(e -> new BlogLockWaits.Edge(e.transaction(), 1, 99, "app")).toList();
        var sameTransaction = actual.stream().map(e -> new BlogLockWaits.Edge("same", e.requestingConnection(), 99, "app")).toList();
        assertFalse(BlogLockWaits.observe(sameConnection, 99, "app").ready(6));
        assertFalse(BlogLockWaits.observe(sameTransaction, 99, "app").ready(6));
    }

    @Test
    void unrelatedWaitsAndCyclesCannotPretendToReachTheHeldPost() {
        var edges = List.of(new BlogLockWaits.Edge("a", 1, 2, "app"),
                new BlogLockWaits.Edge("b", 2, 1, "app"), new BlogLockWaits.Edge("c", 3, 4, "app"));
        assertTrue(BlogLockWaits.observe(edges, 99, "app").connections().isEmpty());
    }

    @Test
    void completedResponsesCollectedAfterTheDeadlineCannotQualify(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var environment = BusinessSliceHarnessEvidenceTest.environment(temp.resolve("work"), temp.resolve("evidence"),
                BlogBusinessConformanceIT.FIXTURE.enabledProperty());
        var slice = BlogBusinessConformanceIT.createSlice(environment);
        var calls = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new BlogConcurrentRequests.Call(1, "/api/posts/110/like", "{}")).toList();
        List<java.util.concurrent.Future<BlogConcurrentRequests.Outcome>> futures = calls.stream()
                .<java.util.concurrent.Future<BlogConcurrentRequests.Outcome>>map(c -> java.util.concurrent.CompletableFuture.completedFuture(
                        new BlogConcurrentRequests.Outcome(c, new HttpAssertionClient.Response(201, "{}"), null))).toList();
        var results = BlogConcurrentRequests.collect(slice, "late results", futures, calls,
                System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
        assertTrue(results.stream().allMatch(r -> r.error() != null && r.error().contains("deadline")));
        assertTrue(results.stream().allMatch(r -> r.response() != null && r.response().statusCode() == 201),
                "retain the actual late responses for forensic evidence without accepting them");
        assertFalse(slice.passed());
    }

    private static BlogFixture.State state() {
        return new BlogFixture.State(Map.of(
                "user", List.of(new BlogFixture.Row(Map.of("id", 1L, "name", "alice"))),
                "category", List.of(new BlogFixture.Row(Map.of("id", 10L, "name", "tech"))),
                "post", List.of(new BlogFixture.Row(Map.of("id", 102L, "author_id", 1L, "category_id", 10L,
                        "title", "alpha", "content", "body", "state", "PUBLISHED", "likes", 0L, "created_at", TIME)))));
    }

    private static String publicPost() {
        return "{\"id\":102,\"author\":{\"id\":1,\"name\":\"alice\"},"
                + "\"category\":{\"id\":10,\"name\":\"tech\"},\"title\":\"alpha\",\"content\":\"body\","
                + "\"likes\":0,\"createdAt\":\"2026-01-02T00:00:00Z\"}";
    }
}
