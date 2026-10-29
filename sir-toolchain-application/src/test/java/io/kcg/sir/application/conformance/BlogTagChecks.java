package io.kcg.sir.application.conformance;

import java.util.List;

/** Spec T01-T11: chosen tag, publication visibility, authorization and unique relations. */
final class BlogTagChecks {
    private BlogTagChecks() { }

    static void run(BlogAssertions a) throws Exception {
        a.page("T01 first selected-tag page", "/api/tags/20/posts?page=1&size=2", List.of(106L, 103L), 3, 1, 2);
        a.page("T01 second selected-tag page", "/api/tags/20/posts?page=2&size=2", List.of(102L), 3, 2, 2);
        a.page("T02 other tag does not duplicate roots", "/api/tags/21/posts?page=1&size=10", List.of(102L, 104L), 2, 1, 10);
        a.page("T02 existing empty tag", "/api/tags/22/posts?page=1&size=10", List.of(), 0, 1, 10);
        a.missingTag("T03 missing tag differs from empty tag");
        attach(a, "T04 attach tag to draft", 101, 21);
        a.page("T05 tagged draft stays private", "/api/tags/21/posts?page=1&size=10", List.of(102L, 104L), 2, 1, 10);
        a.refusal("T06 missing post", 1, "/api/posts/999999/tags", "{\"tagId\":21}", 404, "PostNotFound");
        a.refusal("T06 missing tag", 1, "/api/posts/101/tags", "{\"tagId\":999999}", 404, "TagNotFound");
        a.refusal("T07 other author cannot attach", 2, "/api/posts/101/tags", "{\"tagId\":22}", 403, "Forbidden");
        a.refusal("T08 repeated attachment", 1, "/api/posts/101/tags", "{\"tagId\":21}", 409, "TagAttached");
        BlogPublicationChecks.publish(a, "T09 publish tagged draft", 101, "{}");
        a.page("T09 published draft enters selected tag", "/api/tags/21/posts?page=1&size=10", List.of(101L, 102L, 104L), 3, 1, 10);
        defaultsAndBounds(a);
        attach(a, "T11 published post may receive a tag", 106, 21);
        a.page("T11 published attachment is visible", "/api/tags/21/posts?page=1&size=10", List.of(101L, 106L, 102L, 104L), 4, 1, 10);
    }

    private static void defaultsAndBounds(BlogAssertions a) throws Exception {
        a.page("T10 tag page defaults", "/api/tags/20/posts", List.of(101L, 106L, 103L, 102L), 4, 1, 20);
        a.page("T10 tag page default alone", "/api/tags/20/posts?size=2", List.of(101L, 106L), 4, 1, 2);
        a.page("T10 tag size default alone", "/api/tags/20/posts?page=2", List.of(), 4, 2, 20);
        a.page("T10 tag page past end retains total", "/api/tags/20/posts?page=3&size=2", List.of(), 4, 3, 2);
        a.page("T10 tag page inclusive maxima", "/api/tags/20/posts?page=10000&size=100", List.of(), 4, 10000, 100);
    }

    private static void attach(BlogAssertions a, String label, long post, long tag) throws Exception {
        var before = a.fixture.snapshot();
        var response = a.post(label, 1, "/api/posts/" + post + "/tags", "{\"tagId\":" + tag + "}");
        a.emptyCreated(label, response);
        var after = a.fixture.snapshot();
        var added = after.addedSince("post_tag", before);
        boolean correct = added.size() == 1 && added.getFirst().number("post_id") == post && added.getFirst().number("tag_id") == tag;
        a.slice.check(label + " inserts exactly the requested relation", correct, added.toString());
        if (correct) a.expected(label, before.add("post_tag", added.getFirst()), after);
    }
}
