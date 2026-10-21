package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Opt-in real HTTP/MySQL evidence; unavailable reference environments remain NOT_RUN. */
class CrudAccessBusinessConformanceIT {
    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "G9 Crud roles, public reads, protected fields and versioned ownership",
            "/valid/crud-access.sir", "/conformance/mysql/crud-access-ddl.sql", "/conformance/mysql/crud-access-seed.sql",
            "post", "/api/library/books/1", "crud-access-report.txt", "crud-access", "kcg.crud-conformance.enabled");
    private static final Map<String, String> OWNER = bearer(1), READER = bearer(2), OTHER_ADMIN = bearer(3);

    @Test void crudAccessAgainstRealMysql() throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, FIXTURE.enabledProperty());
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[CRUD-CONFORMANCE] NOT_RUN: " + environment.missingPrerequisites());
            return;
        }
        var slice = new BusinessSliceHarness(FIXTURE, environment);
        try {
            execute(slice);
        } catch (SQLException unreachable) {
            slice.setNotRunReason("reference database unreachable: " + BusinessSliceHarness.describe(unreachable));
        } finally {
            slice.teardown();
            slice.writeReport();
        }
        if (slice.notRunReason() != null) {
            System.out.println("[CRUD-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            return;
        }
        System.out.println(slice.report());
        assertTrue(slice.passed(), slice::report);
    }

    private void execute(BusinessSliceHarness slice) throws Exception {
        var url = slice.runtimeUrl();
        slice.initializeRedaction(url);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null || slice.compileAndMaterialize().isEmpty()) return;
        slice.useActorMode("external");
        var jar = slice.buildAndLocateJar(url);
        if (jar == null || !slice.startAndWaitUntilReady(jar)) return;
        slice.setHttpStatus("READY (public Book route)");
        try (var observer = slice.openObserver(url)) {
            slice.setRowsBefore(slice.rowsNow(observer));
            var http = new HttpAssertionClient();
            bookWrites(slice, observer, http);
            publicReads(slice, http);
            ownerPatch(slice, observer, http);
            slice.setRowsAfter(slice.rowsNow(observer));
        }
    }

    private void bookWrites(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        String url = s.baseUrl() + "/api/library/books";
        status(s, "anonymous book read", http.get(url + "/1", Map.of()), 200);
        status(s, "anonymous create refused", http.postJson(url, Map.of(), "{\"title\":\"denied\"}"), 401);
        status(s, "reader create refused", http.postJson(url, READER, "{\"title\":\"denied\"}"), 403);
        // The role guard runs before probing whether a business row exists.
        status(s, "reader update refused before load", http.putJson(url + "/999999", READER,
                "{\"title\":\"denied\"}"), 403);
        status(s, "reader delete refused before load", http.delete(url + "/999999", READER), 403);
        status(s, "anonymous delete refused", http.delete(url + "/1", Map.of()), 401);
        var beforeCreate = sliceBooks(s, observer);
        status(s, "excluded field in create is refused", http.postJson(url, OWNER,
                "{\"title\":\"tampered\",\"stock\":999}"), 400);
        s.check("rejected create wrote nothing", beforeCreate.equals(sliceBooks(s, observer)), sliceBooks(s, observer).toString());
        var created = http.postJson(url, OWNER, "{\"title\":\"published\"}");
        status(s, "admin creates book", created, 201);
        s.check("excluded stock keeps its server default", sliceBooks(s, observer).stream().anyMatch(row -> row.endsWith("|published|5")),
                sliceBooks(s, observer).toString());
        s.check("refused creates wrote nothing", sliceBooks(s, observer).size() == 2, sliceBooks(s, observer).toString());
        String id = BusinessSliceHarness.field(created.body(), "id");
        var beforeUpdate = sliceBooks(s, observer);
        status(s, "excluded field in update is refused", http.putJson(url + "/" + id, OWNER,
                "{\"title\":\"tampered\",\"stock\":999}"), 400);
        s.check("rejected update wrote nothing", beforeUpdate.equals(sliceBooks(s, observer)), sliceBooks(s, observer).toString());
        status(s, "admin updates without writing excluded stock", http.putJson(url + "/" + id, OWNER,
                "{\"title\":\"edited\"}"), 200);
        s.check("update preserved stock", sliceBooks(s, observer).contains(id + "|edited|5"), sliceBooks(s, observer).toString());
        status(s, "admin deletes", http.delete(url + "/" + id, OWNER), 200);
        s.check("only the seed book remains", sliceBooks(s, observer).size() == 1, sliceBooks(s, observer).toString());
    }

    private void publicReads(BusinessSliceHarness s, HttpAssertionClient http) throws Exception {
        String url = s.baseUrl() + "/api/public/posts";
        for (var headers : List.of(Map.<String, String>of(), READER, OTHER_ADMIN)) {
            status(s, "public detail regardless of account", http.get(url + "/1", headers), 200);
            var listed = http.get(url + "?page=1&size=20", headers);
            status(s, "public list", listed, 200);
            s.check("public list keeps entity scope", !listed.body().contains("\"private\""), listed.body());
            status(s, "public read still obeys scope", http.get(url + "/2", headers), 404);
        }
    }

    private void ownerPatch(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        String url = s.baseUrl() + "/api/public/posts/1";
        String change = "{\"expectedVersion\":1,\"changes\":{\"body\":\"edited\"}}";
        var initial = s.rowsOf(observer, "post");
        status(s, "anonymous patch refused", http.patchJson(url, Map.of(), change), 401);
        status(s, "wrong role refused", http.patchJson(url, READER, change), 403);
        status(s, "correct role cannot bypass owner", http.patchJson(url, OTHER_ADMIN, change), 404);
        s.check("denied patches wrote nothing", initial.equals(s.rowsOf(observer, "post")), s.rowsOf(observer, "post").toString());
        // This target rejects unknown request properties before the workflow; do not test authorization with an invalid body.
        status(s, "owner patch with an excluded field is refused", http.patchJson(url, OWNER,
                "{\"expectedVersion\":1,\"changes\":{\"body\":\"edited\",\"state\":\"hidden\"}}"), 400);
        s.check("rejected patch wrote nothing", initial.equals(s.rowsOf(observer, "post")), s.rowsOf(observer, "post").toString());
        status(s, "owner clean patch", http.patchJson(url, OWNER, change), 200);
        s.check("the stored state stays open and the version advances once", s.rowsOf(observer, "post").contains("1|1|edited|open|2"),
                s.rowsOf(observer, "post").toString());
        status(s, "stale patch is a conflict", http.patchJson(url, OWNER, "{\"expectedVersion\":1,\"changes\":{\"body\":\"stale\"}}"), 409);
        s.check("stale patch did not overwrite", s.rowsOf(observer, "post").contains("1|1|edited|open|2"), s.rowsOf(observer, "post").toString());
    }

    private static List<String> sliceBooks(BusinessSliceHarness s, MysqlObserver observer) throws SQLException { return s.rowsOf(observer, "book"); }
    private static void status(BusinessSliceHarness s, String label, HttpAssertionClient.Response response, int expected) {
        s.record(label, response);
        s.expectStatus(label, response, expected);
    }
    private static Map<String, String> bearer(int account) {
        return Map.of("Authorization", "Bearer 00000000-0000-4000-8000-00000000000" + account);
    }
}
