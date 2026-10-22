package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Existing primitives can verify a known ticket's place without loading an unknown first row. */
class ReservationHeadCompositionTest {
    private static final String SOURCE = """
            sir 0.3
            software ReservationQueries {
              metadata { displayName "Reservation Queries"; namespace "example.reservation"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus;
                database mysql; build maven; interface rest; }
              sources {} imports {}
              declarations {
                entity User persistent { identity id: Id generated auto; }
                entity Book persistent { identity id: Id generated auto; }
                entity Reservation persistent {
                  identity id: Id generated auto;
                  field reader: Ref<User>; field book: Ref<Book>;
                  unique (reader, book);
                }
                input TicketRef { field id: Id; }
                error BookMissing http 404;
                error Forbidden http 403;
                error NotHead http 409;
                error ReaderMissing http 404;
                error TicketMissing http 404;
                capability VerifyHead {
                  actor Ref<User>; input TicketRef; output Unit;
                  fails BookMissing; fails Forbidden; fails NotHead; fails ReaderMissing; fails TicketMissing;
                  requires authenticated; requires atomic; expose command;
                  workflow {
                    load User by actor as reader for update else ReaderMissing;
                    load Reservation by input.id as observed else TicketMissing;
                    validate observed.reader == actor else Forbidden;
                    load Book by observed.book as book for update else BookMissing;
                    load Reservation by input.id as ticket for update else TicketMissing;
                    validate ticket.reader == actor else Forbidden;
                    exists Reservation where item.book == book and item.id < ticket.id as earlier;
                    validate not earlier else NotHead;
                    return unit;
                  }
                }
              }
            }
            """;
    private static URLClassLoader loader;
    private static org.apache.ibatis.session.Configuration configuration;

    @BeforeAll static void compile(@TempDir Path directory) throws Exception {
        loader = GeneratedRuntimeSupport.compile(SOURCE, directory);
        configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "reservation-head");
        assistant.setCurrentNamespace("reservation.test");
        for (String name : List.of("User", "Book", "Reservation"))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, type("domain." + name));
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aKnownTicketIsHeadExactlyWhenNoEarlierTicketForItsBookExists(boolean earlier) throws Exception {
        var state = new State(42L, earlier);
        if (earlier) assertCode("NotHead", state::invoke);
        else assertDoesNotThrow(state::invoke);
        assertEquals(List.of("User.lock", "Reservation.read", "Book.lock", "Reservation.lock", "Reservation.exists"), state.calls);
        assertEquals(List.of(3L, 23L), state.headParameters);
    }

    @Test void foreignTicketOwnershipIsRejectedBeforeLockingItsBookOrReadingTheQueue() throws Exception {
        var state = new State(99L, false);
        assertCode("Forbidden", state::invoke);
        assertEquals(List.of("User.lock", "Reservation.read"), state.calls);
        assertTrue(state.headParameters.isEmpty());
    }

    private static final class State {
        final long owner;
        final boolean earlier;
        final List<String> calls = new ArrayList<>();
        final List<Long> headParameters = new ArrayList<>();
        State(long owner, boolean earlier) { this.owner = owner; this.earlier = earlier; }
        void invoke() throws Exception {
            Object input = type("api.TicketRef").getConstructor().newInstance();
            set(input, "Id", 23L);
            Class<?> serviceType = type("application.VerifyHeadService");
            var constructor = serviceType.getConstructors()[0];
            var arguments = new ArrayList<Object>();
            for (var mapperType : constructor.getParameterTypes()) arguments.add(mapper(mapperType));
            Object service = constructor.newInstance(arguments.toArray());
            serviceType.getMethod("verifyHead", Long.class, input.getClass()).invoke(service, 42L, input);
        }
        private Object mapper(Class<?> mapperType) {
            String entity = mapperType.getSimpleName().replace("Mapper", "");
            return Proxy.newProxyInstance(loader, new Class<?>[]{mapperType}, (proxy, method, arguments) -> {
                return switch (method.getName()) {
                    case "selectById" -> {
                        assertEquals("Reservation", entity);
                        assertEquals(23L, arguments[0]); calls.add("Reservation.read");
                        yield row("Reservation", Map.of("Id", 23L, "BookId", 3L, "ReaderId", owner));
                    }
                    case "selectOne" -> lock(entity, arguments[0]);
                    case "selectObjs" -> {
                        assertEquals("Reservation", entity);
                        calls.add("Reservation.exists");
                        var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) arguments[0];
                        String sql = wrapper.getSqlSegment();
                        assertTrue(sql.contains("book_id =") && sql.contains("id <") && sql.contains("LIMIT 1"), sql);
                        assertFalse(sql.contains("reader_id"), "head checks must see other readers' earlier tickets: " + sql);
                        headParameters.addAll(bind(wrapper));
                        yield earlier ? List.of(11L) : List.of();
                    }
                    default -> throw new AssertionError("unexpected database operation " + method);
                };
            });
        }
        private Object lock(String entity, Object value) throws Exception {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) value;
            assertTrue(wrapper.getSqlSegment().contains("FOR UPDATE"));
            calls.add(entity + ".lock");
            List<Long> parameters = bind(wrapper);
            return switch (entity) {
                case "User" -> { assertEquals(List.of(42L), parameters); yield row(entity, Map.of("Id", 42L)); }
                case "Book" -> { assertEquals(List.of(3L), parameters); yield row(entity, Map.of("Id", 3L)); }
                case "Reservation" -> {
                    assertEquals(List.of(23L), parameters);
                    yield row(entity, Map.of("Id", 23L, "BookId", 3L, "ReaderId", owner));
                }
                default -> throw new AssertionError("unexpected lock target " + entity);
            };
        }
    }
    private static List<Long> bind(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) throws Exception {
        var values = new ArrayList<Long>();
        var mappings = wrapper.getParamNameValuePairs().keySet().stream().sorted()
                .map(key -> new ParameterMapping.Builder(configuration, "ew.paramNameValuePairs." + key, Object.class).build()).toList();
        String sqlText = "SELECT " + String.join(", ", java.util.Collections.nCopies(mappings.size(), "?"));
        var sql = new BoundSql(configuration, sqlText, mappings, Map.of("ew", wrapper));
        var statement = new MappedStatement.Builder(configuration, "reservation-binding",
                new org.apache.ibatis.builder.StaticSqlSource(configuration, sqlText), SqlCommandType.SELECT).build();
        var jdbc = (PreparedStatement) Proxy.newProxyInstance(ReservationHeadCompositionTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    values.add(assertInstanceOf(Long.class, arguments[1], "entity rows are not JDBC values")); return null;
                });
        new org.apache.ibatis.scripting.defaults.DefaultParameterHandler(statement, Map.of("ew", wrapper), sql).setParameters(jdbc);
        return List.copyOf(values);
    }
    private static Class<?> type(String name) throws Exception { return loader.loadClass("example.reservation." + name); }
    private static Object row(String name, Map<String, Object> fields) throws Exception {
        Object row = type("domain." + name).getConstructor().newInstance();
        for (var field : fields.entrySet()) set(row, field.getKey(), field.getValue());
        return row;
    }
    private static void set(Object target, String property, Object value) throws Exception {
        java.util.Arrays.stream(target.getClass().getMethods()).filter(m -> m.getName().equals("set" + property))
                .findFirst().orElseThrow().invoke(target, value);
    }
    private static void assertCode(String code, org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(InvocationTargetException.class, operation).getCause();
        assertEquals(code, assertDoesNotThrow(() -> failure.getClass().getMethod("code").invoke(failure)), failure.toString());
    }
}
