package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compiles and executes the actual generated services; no application or database is started. */
class ComputedViewRuntimeTest {
    private static URLClassLoader loader;
    private static final Instant START = Instant.parse("2026-10-22T12:00:00Z");

    @BeforeAll static void compile(@TempDir Path directory) throws Exception {
        loader = GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/computed-views.sir"), directory);
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "computed-view-test");
        assistant.setCurrentNamespace("computed.test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, loader.loadClass("example.computed.domain.Course"));
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void ordinaryReturnUsesHiddenSourceColumnsAndPreservesExactDecimalAndOptional() throws Exception {
        var calls = new ArrayList<String>();
        Object course = course(1, 3, 1, null);
        Object output = invoke("GetCourse", key(), mapper("Course", course, List.of(course), calls));
        assertEquals(2, get(output, "Remaining"));
        assertEquals(false, get(output, "Full"));
        assertEquals(true, get(output, "ConstantState"));
        assertEquals(new BigDecimal("3.7035"), get(output, "Total"));
        assertEquals(Optional.empty(), get(output, "Note"));
        assertEquals(true, get(output, "Missing"));
        assertEquals(START.minusSeconds(300), get(output, "Reminder"));
        assertEquals(List.of("Course.selectById"), calls);
    }

    @Test void createAndUpdateReturnComputedValuesFromTheirActualCandidateRow() throws Exception {
        var calls = new ArrayList<String>();
        Object created = invoke("CreateCourse", input("NewCourse", "Capacity", Integer.class, 3),
                mapper("Course", null, List.of(), calls));
        assertEquals(3, get(created, "Remaining"));
        assertEquals(0, get(created, "Enrolled"));
        assertEquals(41L, get(created, "Id"));
        assertEquals(new BigDecimal("3.7035"), get(created, "Total"));
        assertEquals(List.of("Course.insert"), calls);
        calls.clear();
        Object course = course(1, 3, 1, "note");
        Object updated = invoke("ChangeCourse", key(), mapper("Course", course, List.of(course), calls));
        assertEquals(3, get(updated, "Remaining"));
        assertEquals(Optional.of("note"), get(updated, "Note"));
        assertEquals(false, get(updated, "Missing"));
        assertEquals(List.of("Course.selectById", "Course.update", "Course.selectById"), calls);
    }

    @Test void pagedProjectionComputesEachRowWithoutMakingPageParametersRequired() throws Exception {
        var calls = new ArrayList<String>();
        List<Object> courses = List.of(course(1, 10, 7, null), course(2, 2, 2, "two"));
        Object page = invoke("Browse", input("Paging"), mapper("Course", null, courses, calls));
        List<?> records = records(page);
        assertEquals(List.of(3, 0), records.stream().map(row -> uncheckedGet(row, "Remaining")).toList());
        assertEquals(List.of(false, true), records.stream().map(row -> uncheckedGet(row, "Full")).toList());
        assertEquals(1, get(page, "Page"));
        assertEquals(List.of("Course.selectCount", "Course.selectList"), calls);
    }

    @Test void nestedToOneBindsTheRelatedCourseAndReferenceIdentityWithoutExtraReads() throws Exception {
        var calls = new ArrayList<String>();
        Object lesson = lesson(7, 1, 11), course = course(1, 5, 2, null);
        Object output = invoke("GetLesson", key(), mapper("Lesson", lesson, List.of(lesson), calls),
                mapper("Course", null, List.of(course), calls));
        assertEquals(22, get(output, "Doubled"));
        assertEquals(1L, get(output, "CourseId"));
        assertEquals(3, get(get(output, "Course"), "Remaining"));
        assertEquals(List.of("Lesson.selectById", "Course.selectList"), calls);
    }

    @Test void nestedToManyAndPagedTreesKeepEachViewRowSeparateAndBatchOnlyOnce() throws Exception {
        var calls = new ArrayList<String>();
        List<Object> courses = List.of(course(1, 10, 3, null), course(2, 4, 2, null));
        List<Object> lessons = List.of(lesson(11, 1, 2), lesson(12, 1, 3), lesson(13, 2, 9));
        Object page = invoke("BrowseTrees", input("Paging"), mapper("Course", null, courses, calls),
                mapper("Lesson", null, lessons, calls));
        List<?> trees = records(page);
        assertEquals(7, get(trees.get(0), "Remaining"));
        assertEquals(2, get(trees.get(1), "Remaining"));
        assertEquals(List.of(4, 6), ((List<?>) get(trees.get(0), "Lessons")).stream()
                .map(row -> uncheckedGet(row, "Doubled")).toList());
        assertEquals(18, get(((List<?>) get(trees.get(1), "Lessons")).getFirst(), "Doubled"));
        assertEquals(List.of("Course.selectCount", "Course.selectList", "Lesson.selectList"), calls);
    }

    @Test void computedViewsNeverChangeThePhysicalSchemaOrItsDefaults() {
        String source = GeneratorTestSupport.resourceText("valid/computed-views.sir");
        String plain = source.lines().filter(line -> !(line.stripLeading().startsWith("field ") && line.contains(" = ")))
                .collect(java.util.stream.Collectors.joining("\n"));
        var planner = new io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner();
        var computed = planner.plan(GeneratorTestSupport.normalizedText(source), GeneratorTestSupport.lowerText(source));
        var baseline = planner.plan(GeneratorTestSupport.normalizedText(plain), GeneratorTestSupport.lowerText(plain));
        assertEquals(io.kcg.sir.lowering.springboot.model.SchemaContract.render(baseline.tables()),
                io.kcg.sir.lowering.springboot.model.SchemaContract.render(computed.tables()));
    }

    @Test void integerProjectionOverflowFailsInsteadOfWrappingOrReturningAnInputError() throws Exception {
        var calls = new ArrayList<String>();
        Object lesson = lesson(1, 1, Integer.MAX_VALUE), course = course(1, 3, 1, null);
        var failure = assertThrows(InvocationTargetException.class, () -> invoke("GetLesson", key(),
                mapper("Lesson", lesson, List.of(lesson), calls), mapper("Course", null, List.of(course), calls)));
        assertInstanceOf(ArithmeticException.class, failure.getCause());
    }

    private static Object mapper(String entity, Object row, List<Object> rows, List<String> calls) throws Exception {
        Class<?> type = loader.loadClass("example.computed.persistence." + entity + "Mapper");
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, arguments) -> {
            calls.add(entity + "." + method.getName());
            return switch (method.getName()) {
                case "selectById" -> row;
                case "selectCount" -> (long) rows.size();
                case "selectList" -> rows;
                case "insert" -> { set(arguments[0], "Id", Long.class, 41L); yield 1; }
                case "updateById", "update" -> 1;
                default -> throw new AssertionError(method);
            };
        });
    }

    private static Object invoke(String capability, Object input, Object... mappers) throws Exception {
        var type = loader.loadClass("example.computed.application." + capability + "Service");
        var constructor = type.getConstructors()[0];
        Object[] ordered = java.util.Arrays.stream(constructor.getParameterTypes()).map(parameter ->
                java.util.Arrays.stream(mappers).filter(parameter::isInstance).findFirst().orElseThrow()).toArray();
        Object service = constructor.newInstance(ordered);
        return type.getMethod(Character.toLowerCase(capability.charAt(0)) + capability.substring(1), input.getClass()).invoke(service, input);
    }
    private static Object course(long id, int capacity, int enrolled, String note) throws Exception {
        Object row = loader.loadClass("example.computed.domain.Course").getConstructor().newInstance();
        set(row, "Id", Long.class, id); set(row, "Capacity", Integer.class, capacity);
        set(row, "Enrolled", Integer.class, enrolled); set(row, "Price", BigDecimal.class, new BigDecimal("1.2345"));
        set(row, "Note", String.class, note); set(row, "Starts", Instant.class, START);
        return row;
    }
    private static Object lesson(long id, long course, int count) throws Exception {
        Object row = loader.loadClass("example.computed.domain.Lesson").getConstructor().newInstance();
        set(row, "Id", Long.class, id); set(row, "CourseId", Long.class, course); set(row, "Count", Integer.class, count);
        return row;
    }
    private static Object input(String name) throws Exception { return loader.loadClass("example.computed.api." + name).getConstructor().newInstance(); }
    private static Object input(String name, String field, Class<?> type, Object value) throws Exception {
        Object input = input(name); set(input, field, type, value); return input;
    }
    private static Object key() throws Exception { return input("Key", "Id", Long.class, 1L); }
    private static void set(Object object, String field, Class<?> type, Object value) throws Exception {
        object.getClass().getMethod("set" + field, type).invoke(object, value);
    }
    private static Object get(Object object, String field) throws Exception { return object.getClass().getMethod("get" + field).invoke(object); }
    private static Object uncheckedGet(Object object, String field) {
        try { return get(object, field); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static List<?> records(Object page) throws Exception { return (List<?>) get(page, "Records"); }
}
