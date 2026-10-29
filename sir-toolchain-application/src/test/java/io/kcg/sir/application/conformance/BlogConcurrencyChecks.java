package io.kcg.sir.application.conformance;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Spec X01-X04: independent seed resets, real contention and full final-state comparisons. */
final class BlogConcurrencyChecks {
    private BlogConcurrencyChecks() { }

    static void run(BlogAssertions a) throws Exception {
        if (!a.fixture.reset()) return;
        publish(a);
        if (!a.fixture.reset()) return;
        attach(a);
        if (!a.fixture.reset()) return;
        sameLiker(a);
        if (!a.fixture.reset()) return;
        differentLikers(a);
    }

    private static List<BlogConcurrentRequests.Call> repeated(int actor, String path, String body) {
        return IntStream.range(0, BlogConcurrentRequests.CLIENTS)
                .mapToObj(i -> new BlogConcurrentRequests.Call(actor, path, body)).toList();
    }

    private static void publish(BlogAssertions a) throws Exception {
        var before = a.fixture.snapshot();
        var results = BlogConcurrentRequests.run(a, "X01", 108, repeated(1, "/api/my/posts/108/publish", "{}"));
        statuses(a, "X01 publish", results, 200, 1, 409, 5);
        var after = a.fixture.snapshot();
        a.expected("X01 publish competition", before.withPost(108, "state", "PUBLISHED"), after);
        for (var result : results) {
            if (result.response() == null) continue;
            if (result.response().statusCode() == 200) a.postView("X01 winning publish", result.response(), 108, false, after, Duration.ZERO, null);
            else a.error("X01 losing publish", result.response(), 409, "NotPublishable");
        }
        invariant(a, "X01", after);
        a.detail("X01 anonymous read after competition", 108);
    }

    private static void attach(BlogAssertions a) throws Exception {
        var before = a.fixture.snapshot();
        var results = BlogConcurrentRequests.run(a, "X02", 111, repeated(1, "/api/posts/111/tags", "{\"tagId\":22}"));
        statuses(a, "X02 attach", results, 201, 1, 409, 5);
        var after = a.fixture.snapshot();
        var added = after.addedSince("post_tag", before);
        boolean correct = added.size() == 1 && added.getFirst().number("post_id") == 111 && added.getFirst().number("tag_id") == 22;
        a.slice.check("X02 only one selected-tag relation is inserted", correct, added.toString());
        if (correct) a.expected("X02 attachment competition", before.add("post_tag", added.getFirst()), after);
        for (var result : results) {
            if (result.response() == null) continue;
            if (result.response().statusCode() == 201) a.emptyCreated("X02 winning attachment", result.response());
            else a.error("X02 losing attachment", result.response(), 409, "TagAttached");
        }
        invariant(a, "X02", after);
        a.page("X02 tagged draft remains hidden after competition", "/api/tags/22/posts?page=1&size=10", List.of(), 0, 1, 10);
    }

    private static void sameLiker(BlogAssertions a) throws Exception {
        var before = a.fixture.snapshot();
        var results = BlogConcurrentRequests.run(a, "X03", 109, repeated(2, "/api/posts/109/like", "{}"));
        statuses(a, "X03 same user", results, 201, 1, 409, 5);
        var after = a.fixture.snapshot();
        var added = after.addedSince("like", before);
        boolean correct = added.size() == 1 && added.getFirst().number("post_id") == 109 && added.getFirst().number("user_id") == 2;
        a.slice.check("X03 exactly one current-user like survives", correct, added.toString());
        if (correct) a.expected("X03 repeated-like competition", before.withPost(109, "likes", 1L).add("like", added.getFirst()), after);
        for (var result : results) {
            if (result.response() == null) continue;
            if (result.response().statusCode() == 201) a.postView("X03 winning like", result.response(), 109, true, after, Duration.ZERO, null);
            else a.error("X03 losing like", result.response(), 409, "AlreadyLiked");
        }
        invariant(a, "X03", after);
        a.detail("X03 committed count is visible anonymously", 109);
    }

    private static void differentLikers(BlogAssertions a) throws Exception {
        var before = a.fixture.snapshot();
        var calls = IntStream.rangeClosed(3, 8).mapToObj(actor -> new BlogConcurrentRequests.Call(actor, "/api/posts/110/like", "{}")).toList();
        var results = BlogConcurrentRequests.run(a, "X04", 110, calls);
        statuses(a, "X04 different users", results, 201, 6, 409, 0);
        var after = a.fixture.snapshot();
        var added = after.addedSince("like", before);
        Set<Long> users = added.stream().map(r -> r.number("user_id")).collect(Collectors.toUnmodifiableSet());
        boolean correct = added.size() == 6 && users.equals(Set.of(3L, 4L, 5L, 6L, 7L, 8L))
                && added.stream().allMatch(r -> r.number("post_id") == 110);
        a.slice.check("X04 six distinct requested-user relations survive", correct, added.toString());
        if (correct) {
            var expected = before.withPost(110, "likes", 6L);
            for (var row : added) expected = expected.add("like", row);
            a.expected("X04 different-user competition", expected, after);
        }
        for (var result : results) {
            if (result.response() == null || result.response().statusCode() != 201) continue;
            long likes = BlogAssertions.identifier(a.body("X04 response", result.response()).path("likes"));
            a.slice.check("X04 each successful response has an in-range numeric count", likes >= 1 && likes <= 6, "likes=" + likes);
            a.postView("X04 successful like", result.response(), 110, true, after, Duration.ZERO, likes);
        }
        invariant(a, "X04", after);
        a.detail("X04 final count is six on the public read path", 110);
    }

    private static void statuses(BlogAssertions a, String label, List<BlogConcurrentRequests.Outcome> results,
                                 int success, int winners, int refused, int losers) {
        long good = results.stream().filter(r -> r.response() != null && r.response().statusCode() == success).count();
        long rejected = results.stream().filter(r -> r.response() != null && r.response().statusCode() == refused).count();
        a.slice.check(label + " has exactly the required success/conflict counts", good == winners && rejected == losers
                        && results.size() == BlogConcurrentRequests.CLIENTS && good + rejected == results.size(),
                results.stream().map(r -> r.response() == null ? "ERROR " + r.error() : Integer.toString(r.response().statusCode())).toList().toString());
    }

    private static void invariant(BlogAssertions a, String label, BlogFixture.State state) {
        boolean valid = state.table("post").stream().allMatch(p -> p.number("likes") == state.table("like").stream()
                .filter(l -> l.number("post_id") == p.number("id")).count());
        a.slice.check(label + " every post count equals independently stored like rows", valid, "checked all post and like rows");
    }
}
