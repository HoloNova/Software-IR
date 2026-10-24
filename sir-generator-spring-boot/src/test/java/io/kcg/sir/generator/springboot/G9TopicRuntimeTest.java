package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual topic Java, not a synthetic Crud feature sample. No HTTP process or transaction is started. */
class G9TopicRuntimeTest {
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final String ITEM = "{\"title\":\"edited\",\"description\":null,\"price\":12.50,\"condition\":null}";
    private static final String BOOK = "{\"isbn\":\"ISBN-new\",\"title\":\"edited\",\"author\":\"author\",\"publisher\":null}";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static Session market, library;

    @BeforeAll static void compileActualTopics(@TempDir Path root) throws Exception {
        market = compile("market", root.resolve("market"));
        library = compile("library", root.resolve("library"));
    }
    @AfterAll static void close() throws Exception {
        if (market != null) market.loader().close();
        if (library != null) library.loader().close();
    }

    @ParameterizedTest @ValueSource(strings = {"status", "createdAt", "sellerId"})
    void actualMarketPayloadNeverAcceptsProtectedUpdateFields(String property) {
        String value = property.equals("createdAt") ? "\"2000-01-01T00:00:00Z\"" : property.equals("status") ? "\"ON_SALE\"" : "99";
        assertThrows(UnrecognizedPropertyException.class, () -> market.input("UpdateItemInput", inject(ITEM, property, value)));
    }
    @ParameterizedTest @ValueSource(strings = {"id", "status", "createdAt", "sellerId"})
    void actualMarketCreateCannotChooseSystemValues(String property) {
        String value = property.equals("createdAt") ? "\"2000-01-01T00:00:00Z\"" : property.equals("status") ? "\"SOLD\"" : "99";
        assertThrows(UnrecognizedPropertyException.class, () -> market.input("CreateItemInput", inject(ITEM, property, value)));
    }
    @Test void actualOwnerEditsKeepSoldStateCreationTimeAndOwnership() throws Exception {
        var state = new State(market, "Item");
        Object result = state.invoke("UpdateItem", 7L, withId(market.input("UpdateItemInput", ITEM)));
        assertEquals(1, state.writes.size());
        assertEquals("edited", get(state.row, "Title"));
        assertEquals("SOLD", get(state.row, "Status").toString());
        assertEquals(CREATED, get(state.row, "CreatedAt"));
        assertEquals(7L, get(state.row, "SellerId"));
        assertEquals("SOLD", get(result, "Status").toString());
    }
    @Test void actualCreateGetsItsOwnerAndDefaultsFromTheServer() throws Exception {
        var state = new State(market, "Item");
        Object result = state.invoke("CreateItem", 7L, market.input("CreateItemInput", ITEM));
        assertEquals(7L, get(state.row, "SellerId"));
        assertEquals("ON_SALE", get(result, "Status").toString());
        assertNotNull(get(result, "CreatedAt"));
        assertEquals(1, state.writes.size());
    }
    @ParameterizedTest @ValueSource(strings = {"UpdateItem", "DeleteItem"})
    void otherSellersCannotWriteAnExistingActualItem(String operation) throws Exception {
        var state = new State(market, "Item");
        Object input = withId(market.input(operation.equals("UpdateItem") ? "UpdateItemInput" : "ItemIdInput", operation.equals("UpdateItem") ? ITEM : "{}"));
        failure("ItemNotFoundException", () -> state.invoke(operation, 8L, input));
        assertTrue(state.writes.isEmpty(), state.writes.toString());
    }
    @ParameterizedTest @ValueSource(strings = {"CreateBook", "UpdateBook", "DeleteBook"})
    void actualLibraryRejectsReadersBeforeLoadingAnyBook(String operation) throws Exception {
        var state = new State(library, "Book");
        failure("BookForbiddenException", () -> state.invoke(operation, 7L, bookInput(operation)));
        assertTrue(state.calls.stream().noneMatch(c -> c.startsWith("Book.")), state.calls.toString());
        assertTrue(state.writes.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"CreateBook", "UpdateBook", "DeleteBook"})
    void actualLibraryAllowsAllThreeLibrarianWrites(String operation) throws Exception {
        var state = new State(library, "Book"); state.role = "LIBRARIAN";
        state.invoke(operation, 7L, bookInput(operation));
        assertEquals(1, state.writes.size(), state.calls.toString());
        if (!operation.equals("DeleteBook")) assertEquals("edited", get(state.row, "Title"));
    }
    @ParameterizedTest @ValueSource(strings = {"GetBook", "ListBook", "SearchByTitle"})
    void actualCatalogueReadsRemainPublic(String operation) throws Exception {
        var state = new State(library, "Book");
        Object input = switch (operation) {
            case "GetBook" -> withId(library.input("BookIdInput", "{}"));
            case "ListBook" -> library.input("ListBookInput", "{\"page\":1,\"size\":20}");
            case "SearchByTitle" -> library.input("SearchBooks", "{\"keyword\":\"original\",\"page\":1,\"size\":20}");
            default -> throw new AssertionError(operation);
        };
        Object response = state.invoke(operation, null, input);
        var records = operation.equals("GetBook") ? List.of(response) : (List<?>) get(response, "Records");
        assertEquals(1, records.size());
        assertEquals(2L, get(records.getFirst(), "AvailableCopies"));
        assertEquals(operation.equals("GetBook")
                ? List.of("Book.selectById", "Copy.selectMaps")
                : List.of("Book.selectCount", "Book.selectList", "Copy.selectMaps"), state.calls);
        assertTrue(state.calls.stream().noneMatch(c -> c.startsWith("User.")), state.calls.toString());
        assertTrue(state.writes.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"omitted", "null", "present"})
    void optionalPublisherIsValidatedOnlyWhenPresentInTheRealDtoAndCandidate(String mode) throws Exception {
        String json = mode.equals("omitted") ? BOOK.replace(",\"publisher\":null", "")
                : mode.equals("null") ? BOOK : BOOK.replace("\"publisher\":null", "\"publisher\":\"Press\"");
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            for (String operation : List.of("CreateBook", "UpdateBook")) {
                Object input = library.input(operation + "Input", json);
                if (operation.equals("UpdateBook")) withId(input);
                assertTrue(factory.getValidator().validate(input).isEmpty(), "Optional<Name> " + mode + " must pass DTO validation");
                var state = new State(library, "Book"); state.role = "LIBRARIAN";
                state.invoke(operation, 7L, input);
                assertEquals(mode.equals("present") ? "Press" : null, get(state.row, "Publisher"));
                assertEquals(1, state.writes.size());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "\t", "\u2003"})
    void optionalPublisherStillRejectsPresentBlankValues(String value) throws Exception {
        String json = BOOK.replace("\"publisher\":null", "\"publisher\":" + JSON.writeValueAsString(value));
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            Object input = withId(library.input("UpdateBookInput", json));
            var blank = factory.getValidator().validate(input).stream()
                    .filter(v -> v.getPropertyPath().toString().startsWith("publisher")
                            && v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName().equals("NotBlank"))
                    .findFirst().orElseThrow();
            assertEquals("{jakarta.validation.constraints.NotBlank.message}", blank.getMessageTemplate(), "keep the standard message bundle and public constraint code");
            var state = new State(library, "Book"); state.role = "LIBRARIAN";
            failure("ApiException", () -> state.invoke("UpdateBook", 7L, input));
            assertTrue(state.writes.isEmpty());
        }
    }
    @Test void requiredNamesRemainRequiredInBothValidationPaths() throws Exception {
        for (String json : List.of(BOOK.replace("\"title\":\"edited\"", "\"title\":null"), BOOK.replace("\"title\":\"edited\"", "\"title\":\" \""))) {
            Object input = withId(library.input("UpdateBookInput", json));
            try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
                assertTrue(factory.getValidator().validate(input).stream().anyMatch(v -> v.getPropertyPath().toString().equals("title")));
            }
            var state = new State(library, "Book"); state.role = "LIBRARIAN";
            failure("ApiException", () -> state.invoke("UpdateBook", 7L, input)); assertTrue(state.writes.isEmpty());
        }
    }

    @Test void nullableNamePatchDistinguishesOmissionClearAndInvalidBlank(@TempDir Path directory) throws Exception {
        String source = library.source().replace("field publisher: Optional<Name>;", "field publisher: Optional<Name>; field version: Version;");
        assertNotEquals(library.source(), source);
        try (var loader = GeneratedRuntimeSupport.compile(source, directory)) {
            var patch = session("library", source, loader);
            for (String changes : List.of("{}", "{\"publisher\":null}", "{\"publisher\":\"Press\"}")) {
                var state = new State(patch, "Book"); state.role = "LIBRARIAN";
                set(state.row, "Version", 1L); set(state.row, "Publisher", "previous");
                Object input = patch.input("UpdateBookInput", "{\"id\":9,\"expectedVersion\":1,\"changes\":" + changes + "}");
                state.invoke("UpdateBook", 7L, input);
                assertEquals(changes.equals("{}") ? "previous" : changes.contains("null") ? null : "Press", get(state.row, "Publisher"));
                assertEquals(1, state.writes.size());
            }
            var state = new State(patch, "Book"); state.role = "LIBRARIAN"; set(state.row, "Version", 1L);
            Object input = patch.input("UpdateBookInput", "{\"id\":9,\"expectedVersion\":1,\"changes\":{\"publisher\":\" \"}}");
            failure("ApiException", () -> state.invoke("UpdateBook", 7L, input)); assertTrue(state.writes.isEmpty());
        }
    }

    @Test void removingEachCanonicalPolicyWouldInvalidateTheContract() throws Exception {
        String noExclusion = market.source().replace("writeExclude: [status, createdAt];", "");
        String noRole = library.source().replace("writeRole: LIBRARIAN;", "");
        assertNotEquals(market.source(), noExclusion); assertNotEquals(library.source(), noRole);
        var item = GeneratorTestSupport.contentEndingWith(GeneratorTestSupport.generateSuccessFromText(noExclusion), "/UpdateItemInput.java");
        var book = GeneratorTestSupport.contentEndingWith(GeneratorTestSupport.generateSuccessFromText(noRole), "/CreateBookService.java");
        assertTrue(item.contains("setStatus("), "a policy-removal mutant must expose the forbidden setter");
        assertFalse(book.contains("BookForbiddenException"), "a policy-removal mutant must lose the role guard");
    }

    @Test void actualMapperQuotesMysqlReservedConditionForReadsAndWrites() throws Exception {
        String namespace = market.type("persistence.ItemMapper").getName();
        Object row = new State(market, "Item").row;
        set(row, "Condition", "used");
        for (String operation : List.of("selectById", "insert", "updateById")) {
            Object parameter = operation.equals("selectById") ? 9L
                    : operation.equals("insert") ? row : Map.of("et", row);
            String sql = market.configuration().getMappedStatement(namespace + "." + operation).getBoundSql(parameter).getSql();
            assertTrue(sql.contains("`condition`"), "MySQL 8.4 reserved identifier must be quoted in actual " + operation + ": " + sql);
            assertFalse(sql.contains("`seller_id`"), "ordinary SQL identifiers keep their existing mapping");
        }
    }

    @Test void actualCataloguePageMapsTotalRowsAndOneChildAggregateNotTwoReads() throws Exception {
        var configuration = library.configuration();
        var copyType = library.type("persistence.CopyMapper");
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "catalogue-budget");
        assistant.setCurrentNamespace(copyType.getName());
        new com.baomidou.mybatisplus.core.injector.DefaultSqlInjector().inspectInject(assistant, copyType);
        var calls = new ArrayList<String>();
        var sql = new ArrayList<String>();
        Object book = new State(library, "Book").row;
        var rootType = library.type("persistence.BookMapper");
        Object root = Proxy.newProxyInstance(library.loader(), new Class<?>[]{rootType}, (p, m, a) -> {
            calls.add("book." + m.getName());
            sql.add(configuration.getMappedStatement(rootType.getName() + "." + m.getName()).getBoundSql(Map.of("ew", a[0])).getSql());
            return switch (m.getName()) {
                case "selectCount" -> 1L;
                case "selectList" -> List.of(book);
                default -> throw new AssertionError(m);
            };
        });
        Object child = Proxy.newProxyInstance(library.loader(), new Class<?>[]{copyType}, (p, m, a) -> {
            assertEquals("selectMaps", m.getName()); calls.add("copy." + m.getName());
            sql.add(configuration.getMappedStatement(copyType.getName() + ".selectMaps").getBoundSql(Map.of("ew", a[0])).getSql());
            return List.of();
        });
        var type = library.type("application.ListBookService");
        Object service = type.getConstructors()[0].newInstance(Arrays.stream(type.getConstructors()[0].getParameterTypes())
                .map(t -> t == rootType ? root : child).toArray());
        type.getMethod("listBook", library.type("api.ListBookInput")).invoke(service,
                library.input("ListBookInput", "{\"page\":1,\"size\":50}"));
        assertEquals(List.of("book.selectCount", "book.selectList", "copy.selectMaps"), calls);
        var normalized = sql.stream().map(q -> q.replace("`", "").strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")).toList();
        var fixture = Path.of(System.getProperty("basedir", ".")).resolveSibling("sir-toolchain-application")
                .resolve("src/test/resources/conformance/mysql/library-count-reads.json");
        var expected = JSON.readTree(Files.readString(fixture));
        var expectedSql = List.of("rootTotal", "rootPage", "children").stream().map(k -> expected.path(k).asText())
                .map(q -> q.replace("`", "").strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")).toList();
        assertEquals(expectedSql, normalized, "the budget regression must use the actual mapped SQL, not guessed formatting");
    }

    private record Session(String topic, String source, URLClassLoader loader, com.baomidou.mybatisplus.core.MybatisConfiguration configuration) {
        Class<?> type(String name) throws Exception { return loader.loadClass("example." + topic + "." + name); }
        Object input(String type, String json) throws Exception { return JSON.readValue(json, type("api." + type)); }
    }
    private static Session compile(String topic, Path directory) throws Exception {
        var path = Path.of(System.getProperty("basedir", ".")).resolveSibling("sir-toolchain-application")
                .resolve("src/test/resources/topics/" + topic + "/project.sir");
        String source = Files.readString(path);
        var loader = GeneratedRuntimeSupport.compile(source, directory);
        return session(topic, source, loader);
    }
    private static Session session(String topic, String source, URLClassLoader loader) throws Exception {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (String entity : List.of("User", topic.equals("market") ? "Item" : "Book")) {
            var mapper = loader.loadClass("example." + topic + ".persistence." + entity + "Mapper");
            var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "g9-actual-topics");
            assistant.setCurrentNamespace(mapper.getName());
            new com.baomidou.mybatisplus.core.injector.DefaultSqlInjector().inspectInject(assistant, mapper);
        }
        return new Session(topic, source, loader, configuration);
    }
    private static Object bookInput(String operation) throws Exception {
        String type = operation.equals("DeleteBook") ? "BookIdInput" : operation + "Input";
        Object input = library.input(type, operation.equals("DeleteBook") ? "{}" : BOOK);
        return operation.equals("CreateBook") ? input : withId(input);
    }
    private static final class State {
        final Session session; final String entity; final List<String> calls = new ArrayList<>(), writes = new ArrayList<>();
        String role = "READER"; Object row;
        State(Session session, String entity) throws Exception {
            this.session = session; this.entity = entity;
            row = session.type("domain." + entity).getConstructor().newInstance(); set(row, "Id", 9L);
            set(row, "Title", "original");
            if (entity.equals("Item")) {
                set(row, "SellerId", 7L); set(row, "Price", new BigDecimal("1.00")); set(row, "CreatedAt", CREATED);
                set(row, "Status", enumValue(session, "ItemStatus", "SOLD"));
            } else { set(row, "Isbn", "ISBN-old"); set(row, "Author", "author"); }
        }
        Object invoke(String operation, Long actor, Object input) throws Exception {
            var type = session.type("application." + operation + "Service"); var ctor = type.getConstructors()[0];
            Object instance = ctor.newInstance(Arrays.stream(ctor.getParameterTypes()).map(this::mapper).toArray());
            var method = Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(Character.toLowerCase(operation.charAt(0)) + operation.substring(1))).findFirst().orElseThrow();
            return method.invoke(instance, method.getParameterCount() == 1 ? new Object[]{input} : new Object[]{actor, input});
        }
        Object mapper(Class<?> type) {
            String name = type.getSimpleName().replace("Mapper", "");
            return Proxy.newProxyInstance(session.loader(), new Class<?>[]{type}, (proxy, method, args) -> {
                calls.add(name + "." + method.getName());
                return switch (method.getName()) {
                    case "selectById", "selectByIdForUpdate" -> name.equals("User") ? account() : row;
                    case "selectCount" -> 1L;
                    case "selectList" -> List.of(row);
                    case "selectMaps" -> {
                        assertEquals("Copy", name, "only the catalogue's child-count mapper may issue selectMaps");
                        var query = assertInstanceOf(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class, args[0]);
                        assertTrue(query.getSqlSelect().contains("COUNT(*) AS count_value"), query.getSqlSelect());
                        assertTrue(query.getSqlSegment().contains("GROUP BY `book_id`"), query.getSqlSegment());
                        assertEquals(Set.of(9L, "AVAILABLE"), new HashSet<>(query.getParamNameValuePairs().values()));
                        yield List.of(Map.of("count_key", 9L, "count_value", 2L));
                    }
                    case "insert" -> { set(args[0], "Id", 10L); row = args[0]; writes.add(name + ".insert"); yield 1; }
                    case "updateById", "updateIfVersionMatches" -> { row = args[0]; writes.add(name + ".update"); yield 1; }
                    case "deleteById" -> { writes.add(name + ".delete"); yield 1; }
                    default -> throw new AssertionError("unexpected mapper call " + method);
                };
            });
        }
        Object account() throws Exception {
            Object user = session.type("domain.User").getConstructor().newInstance(); set(user, "Id", 7L);
            set(user, "Role", enumValue(session, "Role", role)); return user;
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static Object enumValue(Session s, String type, String value) throws Exception {
        return Enum.valueOf((Class) s.type("domain." + type), value);
    }
    private static Object get(Object bean, String field) throws Exception { return bean.getClass().getMethod("get" + field).invoke(bean); }
    private static void set(Object bean, String field, Object value) throws Exception {
        Arrays.stream(bean.getClass().getMethods()).filter(m -> m.getName().equals("set" + field) && m.getParameterCount() == 1).findFirst().orElseThrow().invoke(bean, value);
    }
    private static Object withId(Object input) throws Exception { set(input, "Id", 9L); return input; }
    private static String inject(String json, String field, String value) { return json.substring(0, json.length() - 1) + ",\"" + field + "\":" + value + "}"; }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void failure(String name, Action action) {
        var error = assertThrows(InvocationTargetException.class, action::run).getCause();
        assertEquals(name, error.getClass().getSimpleName(), error.toString());
    }
}
