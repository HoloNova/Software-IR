package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MoneyAtomicContractTest {
    @Test
    void atomicMoneyKeepsSqlDeltaAndChecksTheTransactionConnectionAndTargetScale() {
        var files = GeneratorTestSupport.generateSuccess("valid/money.sir");
        String service = GeneratorTestSupport.contentEndingWith(files, "/CreditBalanceService.java");
        assertTrue(service.contains(".setIncrBy(Wallet::getBalance, input.getAmount())"), service);
        assertTrue(service.contains(".decimalScale("), service);
        assertTrue(service.contains(".requireStrictDecimalSession(Wallet.class)"), service);
        assertTrue(service.indexOf(".requireStrictDecimalSession(") < service.indexOf(".update(null,"), service);
        assertTrue(service.contains("walletStored"), service);
        String support = GeneratorTestSupport.contentEndingWith(files, "/ValidationSupport.java");
        assertTrue(support.contains("isSqlSessionTransactional(session, factory)"), support);
        assertTrue(support.contains("session.getConnection().createStatement()"), support);
        assertTrue(support.contains("SELECT @@SESSION.sql_mode"), support);
        assertFalse(support.contains("SET GLOBAL"));
    }
}
