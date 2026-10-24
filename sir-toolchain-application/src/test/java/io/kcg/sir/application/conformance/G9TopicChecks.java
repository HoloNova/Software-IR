package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Actual catalogue and market policy HTTP checks, sharing the existing library process. */
final class G9TopicChecks {
    static final String ITEMS = "/api/items", BOOKS = "/api/books", BROWSE = "/api/shop/items";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> READER = bearer(1), OTHER = bearer(2), STAFF = bearer(15);
    private G9TopicChecks() {}

    static void verify(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        catalogue(s, o, h);
        market(s, o, h);
    }

    private static void catalogue(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        String url = s.baseUrl() + BOOKS;
        var before = s.rowsOf(o, "book");
        String create = "{\"isbn\":\"G9-CATALOGUE\",\"title\":\"Policy proof\",\"author\":\"Librarian\",\"publisher\":null}";
        status(s, "actual catalogue is publicly readable", h.get(url + "/1", Map.of()), 200);
        status(s, "actual catalogue list is public", h.get(url + "?page=1&size=20", Map.of()), 200);
        status(s, "anonymous catalogue creation is unauthorized", h.postJson(url, Map.of(), create), 401);
        status(s, "anonymous catalogue update is unauthorized", h.putJson(url + "/999999", Map.of(), create), 401);
        status(s, "anonymous catalogue deletion is unauthorized", h.delete(url + "/999999", Map.of()), 401);
        for (var response : List.of(h.postJson(url, READER, create), h.putJson(url + "/999999", READER, create),
                h.delete(url + "/999999", READER))) error(s, "reader cannot maintain the actual catalogue", response, 403, "BookForbidden");
        status(s, "present blank Optional publisher remains invalid", h.postJson(url, STAFF, create.replace("\"publisher\":null", "\"publisher\":\" \"")), 400);
        s.check("denied catalogue operations preserve all books", before.equals(s.rowsOf(o, "book")), "independent observer snapshot");
        var made = h.postJson(url, STAFF, create); status(s, "librarian creates actual catalogue book", made, 201);
        long id = id(made);
        status(s, "librarian updates actual catalogue book", h.putJson(url + "/" + id, STAFF,
                create.replace("Policy proof", "Edited policy proof")), 200);
        s.check("catalogue edit is persisted", s.rowsOf(o, "book").stream().anyMatch(r -> r.startsWith(id + "|G9-CATALOGUE|Edited policy proof|")), "book id=" + id);
        s.fixtureStatement("UPDATE user SET role='READER' WHERE id=15");
        try {
            error(s, "revoked librarian role is read from the server", h.delete(url + "/" + id, STAFF), 403, "BookForbidden");
            s.check("revocation never deletes the book", s.rowsOf(o, "book").stream().anyMatch(r -> r.startsWith(id + "|")), "book id=" + id);
        } finally { s.fixtureStatement("UPDATE user SET role='LIBRARIAN' WHERE id=15"); }
        status(s, "librarian deletes actual catalogue book", h.delete(url + "/" + id, STAFF), 200);
        s.check("catalogue round trip restores the original book rows", before.equals(s.rowsOf(o, "book")), "original lending books unchanged");
    }

    private static void market(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        String url = s.baseUrl() + ITEMS;
        String create = "{\"title\":\"Seller policy\",\"description\":null,\"price\":12.50,\"condition\":null}";
        String edit = create.replace("Seller policy", "Edited seller policy");
        var before = s.rowsOf(o, "item");
        status(s, "anonymous actual market create is unauthorized", h.postJson(url, Map.of(), create), 401);
        for (String injected : List.of("\"status\":\"SOLD\"", "\"createdAt\":\"2000-01-01T00:00:00Z\"", "\"sellerId\":2", "\"id\":777"))
            status(s, "actual market create refuses system field " + injected.split(":")[0], h.postJson(url, READER, inject(create, injected)), 400);
        s.check("malformed market creates write nothing", before.equals(s.rowsOf(o, "item")), "independent observer snapshot");
        var made = h.postJson(url, READER, create); status(s, "owner creates actual market item", made, 201);
        long id = id(made);
        var reloaded = h.get(url + "/" + id, READER); status(s, "owner reloads persisted actual item", reloaded, 200);
        // Compare persisted DATETIME(6), not the POST candidate's nanoseconds before its database round trip.
        String createdAt = BusinessSliceHarness.field(reloaded.body(), "createdAt");
        s.expectField("actual market uses the server status default", made, "status", "ON_SALE");
        s.check("actual market supplies its creation timestamp", createdAt != null && !createdAt.equals("null"), "present server timestamp");
        s.check("actual market owner comes from authentication", s.rowsOf(o, "item").stream().anyMatch(r -> r.startsWith(id + "|1|")), "owner=1");
        var stored = s.rowsOf(o, "item");
        for (String injected : List.of("\"status\":\"ON_SALE\"", "\"createdAt\":\"2000-01-01T00:00:00Z\"", "\"sellerId\":2"))
            status(s, "actual market update refuses system field " + injected.split(":")[0], h.putJson(url + "/" + id, READER, inject(edit, injected)), 400);
        error(s, "other seller cannot update actual item", h.putJson(url + "/" + id, OTHER, edit), 404, "ItemNotFound");
        error(s, "other seller cannot delete actual item", h.delete(url + "/" + id, OTHER), 404, "ItemNotFound");
        s.check("rejected edits preserve all actual item columns", stored.equals(s.rowsOf(o, "item")), "independent observer snapshot");
        browse(s, h, id);
        // Isolated fixture transition, not a new production endpoint; prove editing never restores ON_SALE by default.
        s.fixtureStatement("UPDATE item SET status='SOLD' WHERE id=" + id);
        var edited = h.putJson(url + "/" + id, READER, edit); status(s, "owner edits permitted actual item fields", edited, 200);
        s.expectField("clean seller edit preserves SOLD", edited, "status", "SOLD");
        s.expectField("clean seller edit preserves createdAt", edited, "createdAt", createdAt);
        s.check("allowed seller edit reaches storage without transferring ownership", s.rowsOf(o, "item").stream().anyMatch(r -> r.startsWith(id + "|1|Edited seller policy|")), "title changed, owner=1");
        status(s, "owner deletes own actual item", h.delete(url + "/" + id, READER), 200);
        s.check("market round trip restores original rows", before.equals(s.rowsOf(o, "item")), "item cleanup complete");
    }

    /**
     * The actual public browse query filters on {@code condition}, a MySQL reserved word: the generated predicate must
     * quote it, or every call is a SQL syntax error whether or not the filter is given.
     */
    private static void browse(BusinessSliceHarness s, HttpAssertionClient h, long id) throws Exception {
        String url = s.baseUrl() + BROWSE + "?keyword=Seller%20policy&page=1&size=20";
        browsed(s, h, "browse without a condition runs and lists the item", url, id, true);
        browsed(s, h, "a missing condition does not match a requested one", url + "&condition=used", id, false);
        s.fixtureStatement("UPDATE item SET `condition`='used' WHERE id=" + id);
        browsed(s, h, "browse by the reserved condition column finds the item", url + "&condition=used", id, true);
        browsed(s, h, "browse by another condition leaves the item out", url + "&condition=new", id, false);
    }

    private static void browsed(BusinessSliceHarness s, HttpAssertionClient h, String label, String url, long id, boolean listed) throws Exception {
        var response = h.get(url, Map.of());
        status(s, label, response, 200);
        if (response.statusCode() != 200) return;
        boolean found = false;
        for (var row : JSON.readTree(response.body()).path("records")) found |= row.path("id").asLong() == id;
        s.check(label + " (listed=" + listed + ")", found == listed, response.body());
    }

    private static String inject(String json, String property) { return json.substring(0, json.length() - 1) + "," + property + "}"; }
    private static long id(HttpAssertionClient.Response response) { return Long.parseLong(BusinessSliceHarness.field(response.body(), "id")); }
    private static Map<String, String> bearer(int actor) { return Map.of("Authorization", "Bearer 00000000-0000-4000-8000-" + String.format(Locale.ROOT, "%012d", actor)); }
    private static void status(BusinessSliceHarness s, String label, HttpAssertionClient.Response r, int expected) { s.record(label, r); s.expectStatus(label, r, expected); }
    private static void error(BusinessSliceHarness s, String label, HttpAssertionClient.Response r, int expected, String code) { status(s, label, r, expected); s.expectField(label + " code", r, "code", code); }
}
