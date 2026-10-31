package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual MyBatis-Plus statements and JDBC parameter handlers, without a database or application. */
class OgnlPropertyRuntimeTest {
    private static final List<String> WORDS = List.of("bor", "xor", "shl", "shr", "ushr", "eq", "neq", "lt", "lte", "gt", "gte", "div", "mod");
    private static final String SOURCE = """
            sir 0.3
            software OgnlProperties {
              metadata { displayName "OGNL properties"; namespace "example.ognl"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus; database mysql; build maven; interface rest; }
              sources {} imports {}
              declarations {
                enum Band { OPENING, CLOSING }
                entity Slot persistent {
                  identity id: Id generated auto;
                  field band: Band;
                  field bor: Optional<Text>;
                  field xor: Optional<Text>;
                  field shl: Optional<Text>;
                  field shr: Optional<Text>;
                  field ushr: Optional<Text>;
                  field eq: Optional<Text>;
                  field neq: Optional<Text>;
                  field lt: Optional<Text>;
                  field lte: Optional<Text>;
                  field gt: Optional<Text>;
                  field gte: Optional<Text>;
                  field div: Code default "DEFAULT";
                  field mod: Optional<Text>;
                }
                entity Keyed persistent {
                  identity band: Id generated auto;
                  field label: Text;
                  field version: Version;
                }
                use Crud { entity: Keyed; }
                input NewSlot { field band: Band; field bor: Optional<Text>; }
                view SlotView from Slot { field id: Id; field band: Band; field bor: Optional<Text>; field div: Code; }
                capability CreateSlot {
                  input NewSlot; output SlotView; requires atomic; expose command;
                  workflow {
                    create Slot as row { band: input.band; bor: input.bor; }
                    persist row;
                    return row;
                  }
                }
              }
            }
            """;
    @TempDir static Path directory;
    private static URLClassLoader loader;
    private static MybatisConfiguration configuration;
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new Jdk8Module());

    @BeforeAll static void compileAndRegisterMappers() throws Exception {
        loader = GeneratedRuntimeSupport.compile(SOURCE, directory);
        configuration = new MybatisConfiguration();
        configuration.addMapper(type("persistence.SlotMapper"));
        configuration.addMapper(type("persistence.KeyedMapper"));
    }

    @AfterAll static void closeLoader() throws Exception {
        if (loader != null) loader.close();
    }

    @Test void safePropertyNamesTrackTheActualOgnlLexer() {
        var tokens = Arrays.stream(org.apache.ibatis.ognl.OgnlParserConstants.tokenImage)
                .filter(token -> token.matches("\"[A-Za-z_][A-Za-z0-9_]*\""))
                .map(token -> token.substring(1, token.length() - 1)).collect(java.util.stream.Collectors.toSet());
        for (String word : tokens) {
            String property = io.kcg.sir.lowering.springboot.profile.OgnlPropertyNames.persistent(word);
            assertNotEquals(word, property);
            assertEquals(true, org.apache.ibatis.scripting.xmltags.OgnlCache.getValue(property + " != null", Map.of(property, "present")));
            assertEquals(false, org.apache.ibatis.scripting.xmltags.OgnlCache.getValue(property + " != null", java.util.Collections.singletonMap(property, null)));
        }
        for (String word : List.of("Band", "bandId", "bandwidth", "version", "id", "_band", "div", "mod")) {
            assertFalse(tokens.contains(word));
            assertEquals(word, io.kcg.sir.lowering.springboot.profile.OgnlPropertyNames.persistent(word));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"insert", "updateById"})
    void keywordPropertiesParseAndBindTheirActualValues(String operation) throws Exception {
        Object row = row("Slot", "id");
        setColumn(row, "band", opening());
        for (String word : WORDS) setColumn(row, word, "value_" + word);
        var expected = new ArrayList<Object>();
        if (operation.equals("insert")) expected.add(7L);
        expected.add("OPENING");
        WORDS.forEach(word -> expected.add("value_" + word));
        if (operation.equals("updateById")) expected.add(7L);
        assertEquals(expected, bindings("Slot", operation, parameter(operation, row)));
    }

    @ParameterizedTest @ValueSource(strings = {"insert", "updateById"})
    void nullKeywordFieldsKeepTheExistingOmissionStrategy(String operation) throws Exception {
        Object row = row("Slot", "id");
        setColumn(row, "band", opening());
        Object parameter = parameter(operation, row);
        String sql = statement("Slot", operation).getBoundSql(parameter).getSql();
        for (String word : WORDS) assertFalse(sql.contains(word), "null property must not enter SET/INSERT: " + sql);
        assertEquals(operation.equals("insert") ? List.of(7L, "OPENING") : List.of("OPENING", 7L),
                bindings("Slot", operation, parameter));
    }

    @Test void defaultsAndJsonNamesRemainUnchangedThroughTheGeneratedService() throws Exception {
        Class<?> mapperType = type("persistence.SlotMapper");
        Object mapper = Proxy.newProxyInstance(loader, new Class<?>[]{mapperType}, (p, method, args) -> {
            assertEquals("insert", method.getName());
            setIdentity(args[0], 7L);
            assertEquals(List.of(7L, "OPENING", "SIDE", "DEFAULT"), bindings("Slot", "insert", args[0]));
            return 1;
        });
        Class<?> serviceType = type("application.CreateSlotService");
        Object service = serviceType.getConstructor(mapperType).newInstance(mapper);
        Class<?> inputType = type("api.NewSlot");
        Object input = JSON.readValue("{\"band\":\"OPENING\",\"bor\":\"SIDE\"}", inputType);
        Object result = serviceType.getMethod("createSlot", inputType).invoke(service, input);
        var json = JSON.readTree(JSON.writeValueAsString(result));
        assertEquals(JSON.readTree("{\"id\":7,\"band\":\"OPENING\",\"bor\":\"SIDE\",\"div\":\"DEFAULT\"}"), json);
    }

    @Test void keywordIdentityRetainsPhysicalNameAndVersionedCompareAndSwap() throws Exception {
        Object row = row("Keyed", "band");
        setColumn(row, "label", "original");
        setColumn(row, "version", 5L);
        assertEquals(List.of(7L, "original", 5L), bindings("Keyed", "insert", row));
        var conditional = statement("Keyed", "updateIfVersionMatches");
        String sql = conditional.getBoundSql(Map.of("candidate", row, "expectedVersion", 5L)).getSql();
        assertTrue(sql.endsWith("WHERE band = ? AND version = ?"), sql);
        assertEquals(List.of("original", 7L, 5L), bindings("Keyed", "updateIfVersionMatches",
                Map.of("candidate", row, "expectedVersion", 5L)));
    }

    @Test void actualStaffingReferenceBindsBandWithoutChangingSqlColumns() throws Exception {
        Path reference = Path.of(System.getProperty("basedir", ".")).resolveSibling("sir-toolchain-application")
                .resolve("src/test/resources/valid/staffing-shift-roster.sir");
        try (var staffing = GeneratedRuntimeSupport.compile(java.nio.file.Files.readString(reference), directory.resolve("staffing"))) {
            var actual = new MybatisConfiguration();
            Class<?> mapper = staffing.loadClass("example.staffing.persistence.ShiftMapper");
            actual.addMapper(mapper);
            Object row = staffing.loadClass("example.staffing.domain.Shift").getConstructor().newInstance();
            Object band = Arrays.stream(staffing.loadClass("example.staffing.domain.ShiftBand").getEnumConstants())
                    .filter(value -> value.toString().equals("OPENING")).findFirst().orElseThrow();
            var day = java.time.LocalDate.of(2026, 2, 2);
            setColumn(row, "store_id", 12L);
            setColumn(row, "day", day);
            setColumn(row, "band", band);
            var insert = actual.getMappedStatement(mapper.getName() + ".insert");
            assertEquals("INSERTINTOshift(store_id,day,band)VALUES(?,?,?)",
                    insert.getBoundSql(row).getSql().replaceAll("\\s+", ""));
            assertEquals(List.of(12L, day, "OPENING"), bindings(insert, row));
        }
    }

    private static List<Object> bindings(String entity, String operation, Object parameter) throws Exception {
        return bindings(statement(entity, operation), parameter);
    }

    private static List<Object> bindings(org.apache.ibatis.mapping.MappedStatement statement, Object parameter) throws Exception {
        var sql = statement.getBoundSql(parameter);
        var values = new TreeMap<Integer, Object>();
        PreparedStatement sink = (PreparedStatement) Proxy.newProxyInstance(OgnlPropertyRuntimeTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (p, method, args) -> {
                    if (method.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                        values.put(index, args[1]);
                        return null;
                    }
                    throw new AssertionError(method);
                });
        new DefaultParameterHandler(statement, parameter, sql).setParameters(sink);
        return List.copyOf(values.values());
    }

    private static org.apache.ibatis.mapping.MappedStatement statement(String entity, String operation) throws Exception {
        return configuration.getMappedStatement(type("persistence." + entity + "Mapper").getName() + "." + operation);
    }
    private static Object parameter(String operation, Object row) { return operation.equals("insert") ? row : Map.of("et", row); }
    private static Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass("example.ognl." + name); }
    private static Object opening() throws Exception {
        return Arrays.stream(type("domain.Band").getEnumConstants()).filter(value -> value.toString().equals("OPENING")).findFirst().orElseThrow();
    }
    private static Object row(String entity, String identityColumn) throws Exception {
        Object row = type("domain." + entity).getConstructor().newInstance();
        assertEquals(identityColumn, TableInfoHelper.getTableInfo(row.getClass()).getKeyColumn());
        setIdentity(row, 7L);
        return row;
    }
    private static void setIdentity(Object row, long value) throws Exception {
        set(row, TableInfoHelper.getTableInfo(row.getClass()).getKeyProperty(), value);
    }
    private static void setColumn(Object row, String column, Object value) throws Exception {
        String property = TableInfoHelper.getTableInfo(row.getClass()).getFieldList().stream()
                .filter(field -> field.getColumn().replace("`", "").equals(column)).findFirst().orElseThrow().getProperty();
        set(row, property, value);
    }
    private static void set(Object row, String property, Object value) throws Exception {
        String name = "set" + property.substring(0, 1).toUpperCase(Locale.ROOT) + property.substring(1);
        Arrays.stream(row.getClass().getMethods()).filter(method -> method.getName().equals(name))
                .findFirst().orElseThrow().invoke(row, value);
    }
}
