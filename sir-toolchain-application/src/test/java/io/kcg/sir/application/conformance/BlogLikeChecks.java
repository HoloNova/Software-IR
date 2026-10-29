package io.kcg.sir.application.conformance;

import java.time.Duration;
import java.util.List;

/** Spec L01-L05: relation and count commit or roll back together; all three reads see the result. */
final class BlogLikeChecks {
    private BlogLikeChecks() { }

    static void run(BlogAssertions a) throws Exception {
        like(a, "L01 bob likes published post", 2, 102);
        a.refusal("L02 duplicate like rolls back everything", 2, "/api/posts/102/like", "{}", 409, "AlreadyLiked");
        like(a, "L03 carol independently likes post", 3, 102);
        like(a, "L03 author may like own post", 1, 102);
        a.refusal("L04 draft cannot receive likes", 2, "/api/posts/105/like", "{}", 404, "PostNotFound");
        a.refusal("L04 missing post cannot receive likes", 2, "/api/posts/999999/like", "{}", 404, "PostNotFound");
        a.detail("L05 detail reads committed count", 102);
        a.page("L05 literal search reads committed count", "/api/posts?keyword=%25&page=1&size=10", List.of(102L), 1, 1, 10);
        a.page("L05 tag page reads committed count", "/api/tags/20/posts?page=1&size=10", List.of(106L, 103L, 102L), 3, 1, 10);
    }

    private static void like(BlogAssertions a, String label, int actor, long post) throws Exception {
        var before = a.fixture.snapshot();
        var response = a.post(label, actor, "/api/posts/" + post + "/like", "{}");
        a.slice.expectStatus(label, response, 201);
        var after = a.fixture.snapshot();
        var added = after.addedSince("like", before);
        boolean correct = added.size() == 1 && added.getFirst().number("post_id") == post && added.getFirst().number("user_id") == actor;
        a.slice.check(label + " inserts exactly the authenticated user's relation", correct, added.toString());
        if (correct) a.expected(label, before.withPost(post, "likes", before.row("post", post).number("likes") + 1)
                .add("like", added.getFirst()), after);
        a.postView(label, response, post, true, after, Duration.ZERO, null);
        long relations = after.table("like").stream().filter(r -> r.number("post_id") == post).count();
        a.slice.check(label + " count equals stored like rows", after.row("post", post).number("likes") == relations,
                "likes=" + after.row("post", post).number("likes") + "; relations=" + relations);
    }
}
