package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the actual reference service without Spring or JDBC; mapper doubles expose read/write order. */
class PatchAuthorizationRuntimeTest {
    @TempDir static Path directory;
    private static URLClassLoader loader;
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new Jdk8Module());
    private static final String PREFIX = "example.maintenance.";

    @BeforeAll static void compileReference() throws Exception {
        Path reference = Path.of(System.getProperty("basedir", ".")).resolveSibling("sir-toolchain-application")
                .resolve("src/test/resources/valid/maintenance-work-order.sir");
        loader = GeneratedRuntimeSupport.compile(Files.readString(reference), directory);
    }

    @AfterAll static void closeLoader() throws Exception {
        if (loader != null) loader.close();
    }

    @Test void permissionPrecedesStaleVersionAndDoesNotMergeOrWrite() throws Exception {
        var state = new State();
        state.failure(2L, 4L, "{\"title\":\"changed\"}", "Forbidden", 403);
        assertEquals(List.of("lock", "facility"), state.calls);
        state.assertOriginal();
    }

    @Test void scopedOutRowRemainsNotFoundEvenWhenVersionIsStale() throws Exception {
        var state = new State();
        set(state.row, "Removed", true);
        state.failure(1L, 4L, "{\"title\":\"changed\"}", "WorkOrderNotFound", 404);
        assertEquals(List.of("lock"), state.calls);
    }

    @Test void missingFacilityPrecedesVersionConflict() throws Exception {
        var state = new State();
        state.facilityMissing = true;
        state.failure(1L, 4L, "{\"title\":\"changed\"}", "FacilityNotFound", 404);
        assertEquals(List.of("lock", "facility"), state.calls);
        state.assertOriginal();
    }

    @Test void authorizedStaleRequestStillFailsBeforeMergeAndCandidateValidation() throws Exception {
        var state = new State();
        state.failure(1L, 4L, "{\"title\":\" \"}", "StaleVersion", 409);
        assertEquals(List.of("lock", "facility"), state.calls);
        state.assertOriginal();
    }

    @Test void compareAndSwapMissStillReportsConflictWithoutAdvancingVersion() throws Exception {
        var state = new State();
        state.affected = 0;
        state.failure(1L, 5L, "{\"title\":\"changed\"}", "StaleVersion", 409);
        assertEquals(List.of("lock", "facility", "persist"), state.calls);
        assertEquals(5L, get(state.row, "Version"));
    }

    @Test void matchingVersionMergesOnlyPresentFieldsAndReportsCommittedVersion() throws Exception {
        var state = new State();
        Object view = state.invoke(1L, 5L, "{\"note\":null}");
        assertEquals(List.of("lock", "facility", "persist"), state.calls);
        assertEquals("original", get(state.row, "Title"));
        assertEquals(2L, get(state.row, "Assignee"));
        assertNull(get(state.row, "Note"));
        assertEquals(6L, get(state.row, "Version"));
        assertEquals(6L, get(view, "Version"));
    }

    private static final class State {
        final Object row;
        final Object facility;
        final Object service;
        final List<String> calls = new ArrayList<>();
        boolean facilityMissing;
        int affected = 1;

        State() throws Exception {
            row = type("domain.WorkOrder").getConstructor().newInstance();
            facility = type("domain.Facility").getConstructor().newInstance();
            set(row, "Id", 7L);
            set(row, "FacilityId", 10L);
            set(row, "ReporterId", 1L);
            set(row, "Title", "original");
            set(row, "Assignee", 2L);
            set(row, "Note", "before");
            set(row, "Removed", false);
            set(row, "Version", 5L);
            set(facility, "Id", 10L);
            set(facility, "ManagerId", 1L);
            Class<?> rowMapper = type("persistence.WorkOrderMapper");
            Class<?> facilityMapper = type("persistence.FacilityMapper");
            Object rows = Proxy.newProxyInstance(loader, new Class<?>[]{rowMapper}, (p, method, args) -> switch (method.getName()) {
                case "selectByIdForUpdate" -> { calls.add("lock"); assertEquals(7L, args[0]); yield row; }
                case "updateIfVersionMatches" -> {
                    calls.add("persist"); assertSame(row, args[0]); assertEquals(5L, args[1]); yield affected;
                }
                default -> throw new AssertionError(method);
            });
            Object facilities = Proxy.newProxyInstance(loader, new Class<?>[]{facilityMapper}, (p, method, args) -> {
                assertEquals("selectById", method.getName());
                calls.add("facility"); assertEquals(10L, args[0]);
                return facilityMissing ? null : facility;
            });
            service = type("application.EditWorkOrderService").getConstructor(rowMapper, facilityMapper)
                    .newInstance(rows, facilities);
        }

        Object invoke(long actor, long expectedVersion, String changes) throws Exception {
            Class<?> inputType = type("api.WorkOrderPatch");
            Object input = JSON.readValue("{\"id\":7,\"expectedVersion\":" + expectedVersion + ",\"changes\":" + changes + "}", inputType);
            return service.getClass().getMethod("editWorkOrder", Long.class, inputType).invoke(service, actor, input);
        }

        void failure(long actor, long expectedVersion, String changes, String code, int status) {
            var wrapped = assertThrows(InvocationTargetException.class, () -> invoke(actor, expectedVersion, changes));
            Throwable failure = wrapped.getCause();
            assertEquals(code, assertDoesNotThrow(() -> failure.getClass().getMethod("code").invoke(failure)));
            assertEquals(status, assertDoesNotThrow(() -> ((org.springframework.http.HttpStatus)
                    failure.getClass().getMethod("status").invoke(failure)).value()));
        }

        void assertOriginal() throws Exception {
            assertEquals("original", get(row, "Title"));
            assertEquals("before", get(row, "Note"));
            assertEquals(5L, get(row, "Version"));
        }
    }

    private static Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass(PREFIX + name); }
    private static Object get(Object row, String property) throws Exception { return row.getClass().getMethod("get" + property).invoke(row); }
    private static void set(Object row, String property, Object value) throws Exception {
        Arrays.stream(row.getClass().getMethods()).filter(m -> m.getName().equals("set" + property))
                .findFirst().orElseThrow().invoke(row, value);
    }
}
