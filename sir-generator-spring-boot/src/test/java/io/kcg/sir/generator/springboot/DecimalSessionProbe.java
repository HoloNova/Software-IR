package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionUtils;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real Spring/MyBatis session binding around a fake JDBC connection; never starts a server. */
final class DecimalSessionProbe {
    private DecimalSessionProbe() {}

    static void check(ClassLoader loader, String mode, boolean accepted) throws Exception {
        Class<?> wallet = loader.loadClass("example.money.domain.Wallet");
        AtomicInteger opens = new AtomicInteger(), statements = new AtomicInteger();
        Connection connection = connection(mode, statements);
        DataSource dataSource = proxy(DataSource.class, (object, method, args) -> connection);
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("decimal-test", new SpringManagedTransactionFactory(), dataSource));
        SqlSession session = proxy(SqlSession.class, (object, method, args) -> switch (method.getName()) {
            case "getConnection" -> connection;
            case "getConfiguration" -> configuration;
            default -> null;
        });
        SqlSessionFactory factory = proxy(SqlSessionFactory.class, (object, method, args) -> switch (method.getName()) {
            case "getConfiguration" -> configuration;
            case "openSession" -> { opens.incrementAndGet(); yield session; }
            default -> null;
        });
        var assistant = new MapperBuilderAssistant(configuration, "decimal-test");
        assistant.setCurrentNamespace("decimal.test");
        TableInfoHelper.initTableInfo(assistant, wallet);
        GlobalConfigUtils.getGlobalConfig(configuration).setSqlSessionFactory(factory);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var check = loader.loadClass("example.money.api.ValidationSupport").getMethod("requireStrictDecimalSession", Class.class);
            if (accepted) {
                check.invoke(null, wallet);
                check.invoke(null, wallet);
                SqlSession mapperSession = SqlSessionUtils.getSqlSession(factory);
                try { assertSame(connection, mapperSession.getConnection()); }
                finally { SqlSessionUtils.closeSqlSession(mapperSession, factory); }
                assertEquals(1, opens.get(), "precheck and mapper must share the bound session");
                assertEquals(2, statements.get());
            } else {
                var failure = assertThrows(InvocationTargetException.class, () -> check.invoke(null, wallet));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertEquals("atomic decimal writes require strict SQL mode", failure.getCause().getMessage());
            }
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(factory);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * Binds a strict-mode session to an active transaction for the given configuration, the way a request's
     * transaction would, so generated atomic decimal writes can run against stand-in mappers. Close it to unbind.
     */
    static AutoCloseable strictTransaction(MybatisConfiguration configuration) {
        Connection connection = connection("STRICT_TRANS_TABLES", new AtomicInteger());
        DataSource dataSource = proxy(DataSource.class, (object, method, args) -> connection);
        configuration.setEnvironment(new Environment("decimal-test", new SpringManagedTransactionFactory(), dataSource));
        SqlSession session = proxy(SqlSession.class, (object, method, args) -> switch (method.getName()) {
            case "getConnection" -> connection;
            case "getConfiguration" -> configuration;
            default -> null;
        });
        SqlSessionFactory factory = proxy(SqlSessionFactory.class, (object, method, args) -> switch (method.getName()) {
            case "getConfiguration" -> configuration;
            case "openSession" -> session;
            default -> null;
        });
        GlobalConfigUtils.getGlobalConfig(configuration).setSqlSessionFactory(factory);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        return () -> {
            TransactionSynchronizationManager.unbindResourceIfPossible(factory);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        };
    }

    private static Connection connection(String mode, AtomicInteger statements) {
        return proxy(Connection.class, (object, method, args) -> {
            if (!method.getName().equals("createStatement")) return null;
            return proxy(Statement.class, (statement, operation, values) -> {
                if (!operation.getName().equals("executeQuery")) return null;
                assertEquals("SELECT @@SESSION.sql_mode", values[0]);
                statements.incrementAndGet();
                AtomicInteger row = new AtomicInteger();
                return proxy(ResultSet.class, (result, access, arguments) -> switch (access.getName()) {
                    case "next" -> row.getAndIncrement() == 0;
                    case "getString" -> mode;
                    default -> null;
                });
            });
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(object);
            case "equals" -> object == args[0];
            case "toString" -> type.getSimpleName() + "Probe";
            default -> handler.invoke(object, method, args);
        }));
    }
}
