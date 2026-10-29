package io.kcg.sir.application.conformance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/** Spec B01-B06, P01-P07 and Q01-Q06. Each read/refusal compares all seven tables. */
final class BlogPublicationChecks {
    private record Write(String name, String path, String body) { }
    private record InvalidTitle(String name, String body, String constraint) { }
    private record Literal(String encoded, long id) { }
    private BlogPublicationChecks() { }

    static void identityAndCreation(BlogAssertions a) throws Exception {
        a.page("B01 anonymous readiness", "/api/posts?keyword=&page=1&size=10",
                List.of(110L, 109L, 107L, 106L, 103L, 102L, 104L), 7, 1, 10);
        authenticationMatrix(a);
        create(a, "B03 alice creates draft", 1, 10, "flow article", "flow payload");
        create(a, "B04 bob creates distinct draft", 2, 11, "bob article", "bob payload");
        invalidTitles(a);
        create(a, "B06 full Name length and unrestricted Text", 1, 10, "t".repeat(100), "b".repeat(1001));
    }

    private static void authenticationMatrix(BlogAssertions a) throws Exception {
        var writes = List.of(new Write("CreatePost", "/api/myposts", "{\"categoryId\":10,\"title\":\"auth\",\"content\":\"body\"}"),
                new Write("Publish", "/api/my/posts/101/publish", "{}"),
                new Write("AttachTag", "/api/posts/101/tags", "{\"tagId\":21}"),
                new Write("LikePost", "/api/posts/102/like", "{}"));
        List<Map<String, String>> invalid = List.of(Map.of(), Map.of("Authorization", "Bearer not-a-uuid"),
                Map.of("Authorization", "Bearer 99999999-9999-4999-8999-999999999999"), BlogAssertions.actor(99));
        for (Write write : writes) {
            for (int index = 0; index < invalid.size(); index++) {
                a.unauthorized("B02 " + write.name() + " invalid identity " + index, invalid.get(index), write.path(), write.body());
            }
        }
    }

    static long create(BlogAssertions a, String label, int actor, long category, String title, String content) throws Exception {
        var before = a.fixture.snapshot();
        Instant started = Instant.now();
        var response = a.post(label, actor, "/api/myposts", BlogAssertions.json(Map.of("categoryId", category, "title", title, "content", content)));
        Instant finished = Instant.now();
        a.slice.expectStatus(label, response, 201);
        long id = BlogAssertions.identifier(a.body(label, response).path("id"));
        var after = a.fixture.snapshot();
        var added = after.addedSince("post", before);
        boolean newRow = id > 0 && added.size() == 1 && added.getFirst().number("id") == id;
        a.slice.check(label + " returns the one newly inserted numeric id", newRow, "id=" + id + "; inserted=" + added.size());
        if (!newRow) return -1;
        var row = added.getFirst();
        a.slice.check(label + " stores author, inputs and all creation defaults",
                row.number("author_id") == actor && row.number("category_id") == category && title.equals(row.text("title"))
                        && content.equals(row.text("content")) && "DRAFT".equals(row.text("state")) && row.number("likes") == 0,
                row.toString());
        a.slice.check(label + " stores the actual request creation time",
                row.time("created_at") != null && !row.time("created_at").isBefore(started.minusMillis(1))
                        && !row.time("created_at").isAfter(finished.plusMillis(1)),
                "request=" + started + ".." + finished + "; stored=" + row.time("created_at"));
        a.expected(label, before.add("post", row), after);
        a.postView(label, response, id, false, after, Duration.ofMillis(1), null);
        return id;
    }

    private static void invalidTitles(BlogAssertions a) throws Exception {
        var cases = List.of(
                new InvalidTitle("omitted", "{\"categoryId\":10,\"content\":\"body\"}", "NotBlank"),
                new InvalidTitle("null", "{\"categoryId\":10,\"title\":null,\"content\":\"body\"}", "NotBlank"),
                new InvalidTitle("empty", "{\"categoryId\":10,\"title\":\"\",\"content\":\"body\"}", "NotBlank"),
                new InvalidTitle("blank", "{\"categoryId\":10,\"title\":\"   \",\"content\":\"body\"}", "NotBlank"),
                new InvalidTitle("too long", BlogAssertions.json(Map.of("categoryId", 10, "title", "a".repeat(101), "content", "body")), "Size"));
        for (InvalidTitle invalid : cases) {
            String label = "B05 title " + invalid.name();
            var before = a.fixture.snapshot();
            var response = a.post(label, 1, "/api/myposts", invalid.body());
            a.slice.expectStatus(label, response, 400);
            var body = a.body(label, response);
            boolean field = body.path("fields").isArray() && StreamSupport.stream(body.path("fields").spliterator(), false)
                    .anyMatch(f -> "title".equals(f.path("path").asText()) && invalid.constraint().equals(f.path("code").asText()));
            a.slice.check(label + " identifies the title constraint without fixing localized messages",
                    BlogAssertions.keys(body, Set.of("code", "message", "fields")) && "INVALID_REQUEST".equals(body.path("code").asText())
                            && body.path("message").isTextual() && field, response.body());
            a.unchanged(label, before, a.fixture.snapshot());
        }
    }

    static void publicationAndReading(BlogAssertions a) throws Exception {
        long id = create(a, "P00 create article for publication flow", 1, 10, "flow article", "flow payload");
        if (id < 1) return;
        a.missingDetail("P01 draft detail stays hidden", id);
        a.page("P01 draft does not enter search", "/api/posts?keyword=flow&page=1&size=10", List.of(), 0, 1, 10);
        a.refusal("P02 non-author publish", 2, "/api/my/posts/" + id + "/publish", "{}", 403, "Forbidden");
        a.refusal("P03 unknown article publish", 1, "/api/my/posts/999999/publish", "{}", 404, "PostNotFound");
        publish(a, "P04 author publishes and URL overrides conflicting body id", id, "{\"id\":105}");
        a.detail("P05 published detail matches created article", id);
        a.page("P05 published article appears in search", "/api/posts?keyword=flow&page=1&size=10", List.of(id), 1, 1, 10);
        a.refusal("P06 publishing again", 1, "/api/my/posts/" + id + "/publish", "{}", 409, "NotPublishable");
        a.missingDetail("P07 nonexistent detail", 999999);
        a.missingDetail("P07 other draft detail", 105);
    }

    static void publish(BlogAssertions a, String label, long id, String body) throws Exception {
        var before = a.fixture.snapshot();
        var response = a.post(label, 1, "/api/my/posts/" + id + "/publish", body);
        a.slice.expectStatus(label, response, 200);
        var after = a.fixture.snapshot();
        a.expected(label, before.withPost(id, "state", "PUBLISHED"), after);
        a.postView(label, response, id, false, after, Duration.ZERO, null);
    }

    static void searchAndPagination(BlogAssertions a) throws Exception {
        a.page("Q01 first alpha page", "/api/posts?keyword=alpha&page=1&size=2", List.of(107L, 103L), 4, 1, 2);
        a.page("Q01 second alpha page includes body-only match", "/api/posts?keyword=alpha&page=2&size=2", List.of(102L, 104L), 4, 2, 2);
        a.page("Q01 past last page retains total", "/api/posts?keyword=alpha&page=3&size=2", List.of(), 4, 3, 2);
        for (var literal : List.of(new Literal("%25", 102), new Literal("%5F", 103), new Literal("%5C", 107))) {
            a.page("Q02 literal " + literal.encoded(), "/api/posts?keyword=" + literal.encoded() + "&page=1&size=10",
                    List.of(literal.id()), 1, 1, 10);
        }
        a.page("Q03 empty keyword", "/api/posts?keyword=&page=1&size=10", List.of(110L, 109L, 107L, 106L, 103L, 102L, 104L), 7, 1, 10);
        a.page("Q03 no match", "/api/posts?keyword=zzz-no-match&page=1&size=10", List.of(), 0, 1, 10);
        a.page("Q04 both defaults", "/api/posts?keyword=alpha", List.of(107L, 103L, 102L, 104L), 4, 1, 20);
        a.page("Q04 page default alone", "/api/posts?keyword=alpha&size=2", List.of(107L, 103L), 4, 1, 2);
        a.page("Q04 size default alone", "/api/posts?keyword=alpha&page=2", List.of(), 4, 2, 20);
        badPagination(a);
        a.page("Q06 inclusive upper page", "/api/posts?keyword=alpha&page=10000&size=100", List.of(), 4, 10000, 100);
        a.page("Q06 inclusive upper size", "/api/posts?keyword=alpha&page=1&size=100", List.of(107L, 103L, 102L, 104L), 4, 1, 100);
    }

    private static void badPagination(BlogAssertions a) throws Exception {
        for (String path : List.of("/api/posts?keyword=alpha&", "/api/tags/20/posts?")) {
            for (int page : List.of(0, -1, 10001)) a.badPage("Q05 rejected page " + page + " on " + path, path + "page=" + page + "&size=2");
            for (int size : List.of(0, -1, 101)) a.badPage("Q05 rejected size " + size + " on " + path, path + "page=1&size=" + size);
        }
    }
}
