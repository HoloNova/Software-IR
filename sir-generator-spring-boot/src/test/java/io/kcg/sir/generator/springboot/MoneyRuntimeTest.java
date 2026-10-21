package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Execute real generated DTOs, validators and services; no rewritten getter types or application startup. */
class MoneyRuntimeTest {
    private static URLClassLoader loader;

    @BeforeAll
    static void compile(@TempDir Path directory) throws Exception {
        var arguments = new ArrayList<String>(List.of("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", directory.toString()));
        for (var file : GeneratorTestSupport.generateSuccess("valid/money.sir")) {
            if (!file.relativePath().endsWith(".java")) continue;
            Path path = directory.resolve(file.relativePath());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.content());
            arguments.add(path.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, MoneyRuntimeTest.class.getClassLoader());
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test
    void beanValidationUsesExactValuesAndActualOptionalContainers() throws Exception {
        Class<?> type = loader.loadClass("example.money.api.Amount");
        Object input = input("1.2300");
        try (var factory = jakarta.validation.Validation.byDefaultProvider().configure()
                .messageInterpolator(new org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator()).buildValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(input).isEmpty());
            type.getMethod("setOptional", Optional.class).invoke(input, Optional.of(new BigDecimal("1.2300")));
            assertTrue(validator.validate(input).isEmpty());
            type.getMethod("setOptional", Optional.class).invoke(input, Optional.of(new BigDecimal("1.234")));
            assertTrue(validator.validate(input).stream().anyMatch(v -> v.getPropertyPath().toString().startsWith("optional")));
            type.getMethod("setOptional", Optional.class).invoke(input, Optional.empty());
            type.getMethod("setAmount", BigDecimal.class).invoke(input, new Object[]{null});
            assertTrue(validator.validate(input).stream().anyMatch(v -> v.getPropertyPath().toString().equals("amount")));
        }
    }

    @Test
    void ordinaryUpdateChecksTheTargetPrecisionBeforeWriting() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        Object wallet = wallet();
        Object service = service("SetBalanceService", wallet, writes);
        var method = service.getClass().getMethod("setBalance", loader.loadClass("example.money.api.Amount"));
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> method.invoke(service, input("1.234")));
        assertEquals("INVALID_REQUEST", failure.getCause().getClass().getMethod("code").invoke(failure.getCause()));
        assertEquals(0, writes.get());
        method.invoke(service, input("1.2300"));
        assertEquals(1, writes.get());
        assertEquals(0, new BigDecimal("1.23").compareTo((BigDecimal) wallet.getClass().getMethod("getBalance").invoke(wallet)));
    }

    @Test
    void computedOrdinaryUpdateChecksFinalRangeNotJustEachInput() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        Object wallet = wallet(), service = service("DoubleBalanceService", wallet, writes);
        var method = service.getClass().getMethod("doubleBalance", loader.loadClass("example.money.api.Amount"));
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> method.invoke(service, input("99999999999999999.99")));
        assertEquals("INVALID_REQUEST", failure.getCause().getClass().getMethod("code").invoke(failure.getCause()));
        assertEquals(0, writes.get());
        method.invoke(service, input("1.2300"));
        assertEquals(1, writes.get());
        assertEquals(0, new BigDecimal("2.46").compareTo((BigDecimal) wallet.getClass().getMethod("getBalance").invoke(wallet)));
    }

    @Test
    void decimalNegationCompilesAndExecutesWithoutChangingTheAmount() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        Object wallet = wallet(), input = input("1.23");
        Object service = service("NegateBalanceService", wallet, writes);
        service.getClass().getMethod("negateBalance", input.getClass()).invoke(service, input);
        assertEquals(1, writes.get());
        assertEquals(new BigDecimal("-1.23"), wallet.getClass().getMethod("getBalance").invoke(wallet));
        assertEquals(new BigDecimal("1.23"), input.getClass().getMethod("getAmount").invoke(input));
    }

    @Test
    void exactDecimalHandlesZerosExponentsAndBothRangeEdges() throws Exception {
        var accepts = loader.loadClass("example.money.api.ValidationSupport")
                .getMethod("acceptsDecimal", BigDecimal.class, int.class, int.class);
        for (String value : List.of("0", "0.0000", "1.2300", "-1.23", "99999999999999999.99", "-99999999999999999.99"))
            assertEquals(true, accepts.invoke(null, new BigDecimal(value), 19, 2), value);
        for (String value : List.of("1.234", "100000000000000000", "-100000000000000000", "1E+2147483647"))
            assertEquals(false, accepts.invoke(null, new BigDecimal(value), 19, 2), value);
        for (String value : List.of("0", "0E-2147483647", "0.99"))
            assertEquals(true, accepts.invoke(null, new BigDecimal(value), 2, 2), value);
        assertEquals(false, accepts.invoke(null, BigDecimal.ONE, 2, 2));
        var strict = loader.loadClass("example.money.api.ValidationSupport").getMethod("requireStrictDecimalSession", Class.class);
        assertInstanceOf(IllegalStateException.class, assertThrows(InvocationTargetException.class,
                () -> strict.invoke(null, loader.loadClass("example.money.domain.Wallet"))).getCause());
    }

    @Test
    void realPatchPresenceRequiredNullAndOptionalClearUseEntityRules() throws Exception {
        var entity = loader.loadClass("example.money.domain.VersionedWallet");
        Object wallet = entity.getConstructor().newInstance();
        entity.getMethod("setId", Long.class).invoke(wallet, 1L);
        entity.getMethod("setVersion", Long.class).invoke(wallet, 1L);
        entity.getMethod("setBalance", BigDecimal.class).invoke(wallet, new BigDecimal("5.00"));
        entity.getMethod("setRebate", BigDecimal.class).invoke(wallet, new BigDecimal("2.00"));
        AtomicInteger writes = new AtomicInteger();
        Object service = service("PatchWalletService", wallet, writes);
        var payload = loader.loadClass("example.money.api.WalletPatch");
        Object input = payload.getConstructor().newInstance();
        payload.getMethod("setId", Long.class).invoke(input, 1L);
        payload.getMethod("setExpectedVersion", Long.class).invoke(input, 1L);
        var changesType = loader.loadClass("example.money.api.WalletPatch$Changes");
        Object changes = changesType.getConstructor().newInstance();
        payload.getMethod("setChanges", changesType).invoke(input, changes);
        var execute = service.getClass().getMethod("patchWallet", payload);
        changesType.getMethod("balance", BigDecimal.class).invoke(changes, new Object[]{null});
        var requiredFailure = assertThrows(InvocationTargetException.class, () -> execute.invoke(service, input));
        assertInstanceOf(RuntimeException.class, requiredFailure.getCause(), requiredFailure.getCause().toString());
        assertEquals("INVALID_REQUEST", requiredFailure.getCause().getClass().getMethod("code").invoke(requiredFailure.getCause()));
        assertEquals(0, writes.get());
        changesType.getMethod("balance", BigDecimal.class).invoke(changes, new BigDecimal("1.234"));
        assertThrows(InvocationTargetException.class, () -> execute.invoke(service, input));
        assertEquals(0, writes.get());
        changesType.getMethod("balance", BigDecimal.class).invoke(changes, new BigDecimal("1.2300"));
        changesType.getMethod("rebate", Optional.class).invoke(changes, Optional.empty());
        execute.invoke(service, input);
        assertEquals(1, writes.get());
        assertNull(entity.getMethod("getRebate").invoke(wallet));
    }

    @Test
    void strictModeIsCheckedOnTheTransactionBoundMapperConnection() throws Exception {
        DecimalSessionProbe.check(loader, "STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION", true);
        DecimalSessionProbe.check(loader, "strict_all_tables", true);
        DecimalSessionProbe.check(loader, "NO_ENGINE_SUBSTITUTION", false);
        DecimalSessionProbe.check(loader, "NO_STRICT_TRANS_TABLES", false);
    }

    private static Object input(String amount) throws Exception {
        var type = loader.loadClass("example.money.api.Amount");
        Object input = type.getConstructor().newInstance();
        type.getMethod("setId", Long.class).invoke(input, 1L);
        type.getMethod("setAmount", BigDecimal.class).invoke(input, new BigDecimal(amount));
        return input;
    }

    private static Object wallet() throws Exception {
        var type = loader.loadClass("example.money.domain.Wallet");
        Object wallet = type.getConstructor().newInstance();
        type.getMethod("setId", Long.class).invoke(wallet, 1L);
        type.getMethod("setBalance", BigDecimal.class).invoke(wallet, BigDecimal.ZERO);
        return wallet;
    }

    private static Object service(String name, Object wallet, AtomicInteger writes) throws Exception {
        Class<?> mapper = loader.loadClass("example.money.persistence." + wallet.getClass().getSimpleName() + "Mapper");
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{mapper}, (object, method, args) -> switch (method.getName()) {
            case "selectById", "selectByIdForUpdate" -> wallet;
            case "updateById", "updateIfVersionMatches" -> { writes.incrementAndGet(); yield 1; }
            default -> throw new AssertionError("unexpected mapper operation: " + method);
        });
        return loader.loadClass("example.money.application." + name).getConstructor(mapper).newInstance(proxy);
    }
}
