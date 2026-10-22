package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the actual topic, not a separate approximation. SQL/transaction behaviour is checked by its CI IT. */
class LibraryBorrowingRuntimeTest {
    private static URLClassLoader loader;
    private static List<io.kcg.sir.generator.springboot.api.GeneratedFile> files;

    @BeforeAll static void compileActualLibrary(@TempDir Path output) throws Exception {
        Path sample = Path.of(System.getProperty("basedir", ".")).resolveSibling("sir-toolchain-application")
                .resolve("src/test/resources/topics/library/project.sir");
        String source = Files.readString(sample);
        files = GeneratorTestSupport.generateSuccessFromText(source);
        loader = GeneratedRuntimeSupport.compile(source, output);
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "library-sample");
        assistant.setCurrentNamespace("library.test");
        for (String entity : List.of("User", "Copy", "Loan"))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, type("domain." + entity));
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void borrowingHasOneReaderLockAndAConditionalCounterIncrement() {
        String borrow = file("BorrowService.java"), returned = file("GiveBackService.java");
        for (String service : List.of(borrow, returned)) {
            assertTrue(service.contains("Isolation.READ_COMMITTED"), service);
            assertTrue(service.contains(".last(\"FOR UPDATE\")"), service);
        }
        assertTrue(borrow.contains(".setIncrBy(User::getActiveLoans, 1)"), borrow);
        assertTrue(returned.contains(".setDecrBy(User::getActiveLoans, 1)"), returned);
        assertTrue(borrow.contains("User::getActiveLoans, 5"), borrow);
        assertTrue(file("application.yml").contains("local-cache-scope: STATEMENT"));
    }

    @Test void aBorrowFromFourUsesTheLastSlotBeforeClaimingTheCopyAndCreatingTheLoan() throws Exception {
        State state = new State(4, false);
        Object view = state.invoke("Borrow", "barcode", "C21");
        assertEquals(5, state.counter);
        assertEquals(1, state.inserts);
        assertEquals(501L, view.getClass().getMethod("getId").invoke(view));
        assertBefore(state, "User.lock", "User.update", "Copy.update", "Loan.insert");
    }

    @Test void aFullReaderCannotWriteACopyOrInsertALoan() throws Exception {
        State state = new State(5, false);
        assertFailure("BorrowLimitExceededException", () -> state.invoke("Borrow", "barcode", "C21"));
        assertEquals(5, state.counter);
        assertEquals(0, state.inserts);
        assertFalse(state.calls.contains("Copy.update"), state.calls.toString());
    }

    @Test void returningOnceFreesOneSlotAndTheSecondReturnCannotDecrementAgain() throws Exception {
        State state = new State(5, true);
        state.invoke("GiveBack", "id", 31L);
        assertEquals(4, state.counter);
        assertBefore(state, "User.lock", "Loan.lock", "Copy.lock", "User.update", "Loan.update", "Copy.update");
        long writes = state.calls.stream().filter(c -> c.endsWith(".update")).count();
        assertFailure("AlreadyReturnedException", () -> state.invoke("GiveBack", "id", 31L));
        assertEquals(4, state.counter);
        assertEquals(writes, state.calls.stream().filter(c -> c.endsWith(".update")).count());
    }

    @Test void aForeignLoanAndAnInvalidCounterAreRejectedBeforeAnyWrite() throws Exception {
        State foreign = new State(1, true);
        set(foreign.loan, "ReaderId", 99L);
        assertFailure("ForbiddenException", () -> foreign.invoke("GiveBack", "id", 31L));
        assertFalse(foreign.calls.stream().anyMatch(c -> c.endsWith(".update")));
        State drift = new State(0, true);
        assertFailure("LoanCounterMismatchException", () -> drift.invoke("GiveBack", "id", 31L));
        assertEquals(0, drift.counter);
        assertFalse(drift.calls.stream().anyMatch(c -> c.endsWith(".update")));
    }

    @Test void lateCopyFailuresPropagateAfterEarlierWritesSoTheTransactionMustRollBack() throws Exception {
        State borrow = new State(0, false);
        borrow.copyUpdateMatches = false;
        assertFailure("CopyNotAvailableException", () -> borrow.invoke("Borrow", "barcode", "C21"));
        assertBefore(borrow, "User.update", "Copy.update");
        assertEquals(0, borrow.inserts);
        State returned = new State(1, true);
        returned.copyUpdateMatches = false;
        assertFailure("CopyNotAvailableException", () -> returned.invoke("GiveBack", "id", 31L));
        assertBefore(returned, "User.update", "Loan.update", "Copy.update");
        // This is a direct service invocation, not a Spring transaction. Real rollback is asserted in the IT.
    }

    @Test void backfilledLegacyOverLimitCountsCanBeReturnedButCannotBorrowMore() throws Exception {
        State state = new State(8, true);
        state.invoke("GiveBack", "id", 31L);
        assertEquals(7, state.counter);
        assertFailure("BorrowLimitExceededException", () -> state.invoke("Borrow", "barcode", "C21"));
        assertEquals(7, state.counter);
        assertEquals(0, state.inserts);
    }

    @Test void renewingChangesTheLoanButDoesNotConsumeAnotherSlot() throws Exception {
        State state = new State(5, true);
        state.invoke("Renew", "id", 31L);
        assertEquals(5, state.counter);
        assertFalse(state.calls.contains("User.update"), state.calls.toString());
        assertEquals(1, state.loan.getClass().getMethod("getRenewals").invoke(state.loan));
    }

    private static final class State {
        int counter, inserts;
        boolean copyUpdateMatches = true, returned;
        String action;
        final Object copy, loan;
        final List<String> calls = new ArrayList<>();
        State(int counter, boolean borrowed) throws Exception {
            this.counter = counter;
            copy = row("Copy", Map.of("Id", 21L, "Barcode", "C21", "BookId", 11L,
                    "Status", enumValue("CopyStatus", borrowed ? "BORROWED" : "AVAILABLE")));
            loan = row("Loan", Map.of("Id", 31L, "ReaderId", 7L, "CopyId", 21L,
                    "BorrowedAt", Instant.parse("2030-01-01T00:00:00Z"), "DueAt", Instant.parse("2099-01-01T00:00:00Z"),
                    "Renewals", 0, "Returned", false));
        }
        Object invoke(String capability, String property, Object value) throws Exception {
            action = capability;
            Object input = type("api." + (capability.equals("Borrow") ? "BorrowInput" : "LoanRef")).getConstructor().newInstance();
            set(input, Character.toUpperCase(property.charAt(0)) + property.substring(1), value);
            var service = type("application." + capability + "Service");
            var ctor = service.getConstructors()[0];
            Object instance = ctor.newInstance(Arrays.stream(ctor.getParameterTypes()).map(this::mapper).toArray());
            var method = Arrays.stream(service.getMethods()).filter(m -> m.getName().equals(
                    Character.toLowerCase(capability.charAt(0)) + capability.substring(1))).findFirst().orElseThrow();
            return method.invoke(instance, 7L, input);
        }
        private Object mapper(Class<?> mapperType) {
            String entity = mapperType.getSimpleName().replace("Mapper", "");
            return Proxy.newProxyInstance(loader, new Class<?>[]{mapperType}, (p, method, args) -> {
                String name = method.getName();
                if (name.equals("selectOne")) {
                    var wrapper = (com.baomidou.mybatisplus.core.conditions.Wrapper<?>) args[0];
                    assertTrue(wrapper.getSqlSegment().contains("FOR UPDATE"), wrapper.getSqlSegment());
                    calls.add(entity + ".lock");
                    return entity.equals("User") ? user() : entity.equals("Loan") ? loan : copy;
                }
                calls.add(entity + "." + name);
                return switch (name) {
                    case "selectById" -> entity.equals("User") ? user() : entity.equals("Loan") ? loan : copy;
                    case "selectList", "selectByIds", "selectBatchIds" -> List.of(copy);
                    case "insert" -> { inserts++; set(args[0], "Id", 501L); yield 1; }
                    case "update" -> update(entity, args);
                    default -> throw new AssertionError("unexpected mapper call " + method);
                };
            });
        }
        private Object user() throws Exception {
            return row("User", Map.of("Id", 7L, "Name", "reader", "PasswordHash", "not-a-digest",
                    "Role", enumValue("Role", "READER"), "ActiveLoans", counter));
        }
        private int update(String entity, Object[] args) throws Exception {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) args[1];
            assertTrue(wrapper.getSqlSegment().contains("id"));
            if (entity.equals("User")) {
                if (action.equals("Borrow") ? counter >= 5 : counter <= 0) return 0;
                counter += action.equals("Borrow") ? 1 : -1;
            }
            if (entity.equals("Copy") && !copyUpdateMatches) return 0;
            if (entity.equals("Loan") && action.equals("GiveBack")) {
                if (returned) return 0;
                returned = true;
            }
            return 1;
        }
    }
    private static Class<?> type(String name) throws Exception { return loader.loadClass("example.library." + name); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(String name, String value) throws Exception { return Enum.valueOf((Class) type("domain." + name), value); }
    private static Object row(String entity, Map<String, Object> values) throws Exception {
        Object row = type("domain." + entity).getConstructor().newInstance();
        for (var value : values.entrySet()) set(row, value.getKey(), value.getValue());
        return row;
    }
    private static void set(Object target, String property, Object value) throws Exception {
        var method = Arrays.stream(target.getClass().getMethods()).filter(m -> m.getName().equals("set" + property)).findFirst().orElseThrow();
        method.invoke(target, value);
    }
    private static void assertBefore(State state, String... calls) {
        int previous = -1;
        for (String call : calls) {
            int index = state.calls.indexOf(call);
            assertTrue(index > previous, call + " in " + state.calls); previous = index;
        }
    }
    private static String file(String name) { return GeneratorTestSupport.contentEndingWith(files, "/" + name); }
    private static void assertFailure(String name, org.junit.jupiter.api.function.Executable invocation) {
        var failure = assertThrows(InvocationTargetException.class, invocation);
        assertEquals(name, failure.getCause().getClass().getSimpleName(), failure.getCause().toString());
        String code = assertDoesNotThrow(() -> (String) failure.getCause().getClass().getMethod("code").invoke(failure.getCause()));
        assertEquals(name.substring(0, name.length() - "Exception".length()), code,
                "declared failures expose their exact SIR name, not an uppercase framework error code");
    }
}
