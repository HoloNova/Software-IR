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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Executes the actual topic. Mapper responses are probes, not evidence of database concurrency or rollback. */
class CourseSelectionRuntimeTest {
    private static URLClassLoader loader;
    private static List<io.kcg.sir.generator.springboot.api.GeneratedFile> files;
    @BeforeAll static void compile(@TempDir Path output) throws Exception {
        Path sample = Path.of(System.getProperty("basedir", ".")).resolveSibling("sir-toolchain-application")
                .resolve("src/test/resources/topics/course/project.sir");
        String source = Files.readString(sample);
        files = GeneratorTestSupport.generateSuccessFromText(source);
        loader = GeneratedRuntimeSupport.compile(source, output);
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "course-sample");
        assistant.setCurrentNamespace("course.test");
        for (String entity : List.of("User", "Course", "Enrollment", "Prerequisite", "CompletedCourse"))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, type("domain." + entity));
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void enrollmentAndDropPlanTheSameStudentLockBeforeQueryingOrChangingCourseSeats() {
        String enroll = file("EnrollService.java"), drop = file("DropService.java");
        for (String service : List.of(enroll, drop)) {
            assertTrue(service.contains("Isolation.READ_COMMITTED"), service);
            assertTrue(service.contains("userMapper.selectOne("), service);
            assertTrue(service.indexOf("userMapper.selectOne(") < service.indexOf("courseMapper.selectOne("), service);
        }
        assertTrue(enroll.contains("prerequisite_rel.required_id = course.id"), enroll);
        assertTrue(enroll.contains("completed_course_rel.course_id = course.id"), enroll);
        assertTrue(enroll.contains("q2.not("), enroll);
        assertTrue(enroll.contains(".setIncrBy(Course::getEnrolled, 1)"), enroll);
        assertTrue(drop.contains(".setDecrBy(Course::getEnrolled, 1)"), drop);
        assertTrue(file("application.yml").contains("local-cache-scope: STATEMENT"));
    }

    @Test void successfulSelectionChecksBothRelationsBeforeReservingASeat() throws Exception {
        State state = new State(1, 3);
        Object response = state.invoke("Enroll", 23L);
        assertEquals(2, state.count);
        assertEquals(1, state.inserts);
        assertEquals(501L, response.getClass().getMethod("getId").invoke(response));
        assertOrder(state, "User.lock", "Course.lock", "Enrollment.exists", "Course.conflicts", "Course.prerequisites", "Course.update", "Enrollment.insert");
    }

    @ParameterizedTest @ValueSource(strings = {"unpublished", "duplicate", "clash", "missing", "full"})
    void everySelectionRefusalPreventsAnEnrollmentInsert(String reason) throws Exception {
        State state = new State(reason.equals("full") ? 3 : 1, 3);
        switch (reason) {
            case "unpublished" -> state.published = false;
            case "duplicate" -> state.already = true;
            case "clash" -> state.clash = true;
            case "missing" -> state.missing = true;
            default -> { }
        }
        String code = switch (reason) {
            case "unpublished" -> "CourseNotOpen";
            case "duplicate" -> "AlreadyEnrolled";
            case "clash" -> "TimeConflict";
            case "missing" -> "MissingPrerequisites";
            default -> "CourseFull";
        };
        assertCode(code, () -> state.invoke("Enroll", 23L));
        assertEquals(0, state.inserts);
        if (!reason.equals("full")) assertFalse(state.calls.contains("Course.update"), state.calls.toString());
    }

    @Test void duplicateDropCannotReleaseAnotherSeat() throws Exception {
        State state = new State(2, 3);
        state.invoke("Drop", 11L);
        assertEquals(1, state.count);
        assertOrder(state, "User.lock", "Enrollment.lock", "Course.lock", "Course.update", "Enrollment.delete");
        assertCode("EnrollmentNotFound", () -> state.invoke("Drop", 11L));
        assertEquals(1, state.count);
    }

    @Test void foreignEnrollmentAndZeroCounterCannotDeleteOrDecrement() throws Exception {
        State foreign = new State(2, 3); foreign.owner = 88L;
        assertCode("Forbidden", () -> foreign.invoke("Drop", 11L));
        assertFalse(foreign.calls.contains("Course.update"));
        State empty = new State(0, 3);
        assertCode("EnrollmentCounterMismatch", () -> empty.invoke("Drop", 11L));
        assertFalse(empty.removed);
        assertEquals(0, empty.count);
    }

    @Test void aLateUniqueFailurePropagatesAfterTheSeatWriteForTransactionRollback() throws Exception {
        State state = new State(1, 3); state.duplicateInsert = true;
        assertCode("AlreadyEnrolled", () -> state.invoke("Enroll", 23L));
        assertOrder(state, "Course.update", "Enrollment.insert");
        // No transaction proxy is installed here: only the real MySQL IT can prove rollback.
    }

    @ParameterizedTest @ValueSource(strings = {"Weekday", "StartPeriod", "EndPeriod", "Capacity"})
    void missingScheduleNumbersAreFieldErrorsBeforeAnyMapperAccess(String missing) throws Exception {
        State state = new State(0, 3);
        var failure = assertCode("INVALID_REQUEST", () -> state.create(missing));
        var fields = (List<?>) failure.getClass().getMethod("fields").invoke(failure);
        assertEquals(1, fields.size());
        assertEquals(Character.toLowerCase(missing.charAt(0)) + missing.substring(1),
                fields.getFirst().getClass().getMethod("path").invoke(fields.getFirst()));
        assertTrue(state.calls.isEmpty(), state.calls.toString());
    }

    private static final class State {
        int count, inserts;
        final int capacity;
        long owner = 7L;
        boolean published = true, already, clash, missing, removed, duplicateInsert;
        String action;
        final List<String> calls = new ArrayList<>();
        State(int count, int capacity) { this.count = count; this.capacity = capacity; }
        Object invoke(String action, Long id) throws Exception {
            this.action = action;
            Object input = type("api." + (action.equals("Drop") ? "EnrollmentRef" : "CourseRef")).getConstructor().newInstance();
            set(input, "Id", id);
            return call(action, input);
        }
        Object create(String absent) throws Exception {
            action = "CreateCourse";
            Object input = type("api.NewCourse").getConstructor().newInstance();
            for (var property : Map.<String, Object>of("Code", "CNEW", "Name", "Course", "TeacherId", 9L,
                    "Capacity", 3, "Weekday", 1, "StartPeriod", 1, "EndPeriod", 3).entrySet())
                if (!property.getKey().equals(absent)) set(input, property.getKey(), property.getValue());
            return call(action, input);
        }
        Object call(String capability, Object input) throws Exception {
            var service = type("application." + capability + "Service");
            var ctor = service.getConstructors()[0];
            Object instance = ctor.newInstance(Arrays.stream(ctor.getParameterTypes()).map(this::mapper).toArray());
            var method = Arrays.stream(service.getMethods()).filter(m -> m.getName().equals(
                    Character.toLowerCase(capability.charAt(0)) + capability.substring(1))).findFirst().orElseThrow();
            return method.invoke(instance, 7L, input);
        }
        Object mapper(Class<?> mapperType) {
            String entity = mapperType.getSimpleName().replace("Mapper", "");
            return Proxy.newProxyInstance(loader, new Class<?>[]{mapperType}, (p, method, args) -> {
                String name = method.getName();
                if (name.equals("selectOne")) {
                    var wrapper = (com.baomidou.mybatisplus.core.conditions.Wrapper<?>) args[0];
                    assertTrue(wrapper.getSqlSegment().contains("FOR UPDATE")); calls.add(entity + ".lock");
                    return entity.equals("User") ? user(7L) : entity.equals("Course") ? course() : removed ? null : enrollment();
                }
                if (name.equals("selectObjs")) return exists(entity, args[0]);
                calls.add(entity + "." + (name.equals("deleteById") ? "delete" : name));
                return switch (name) {
                    case "selectById" -> entity.equals("Course") ? course() : user((Long) args[0]);
                    case "selectList" -> entity.equals("Course") ? List.of(course()) : List.of(user(9L));
                    case "insert" -> {
                        if (duplicateInsert) throw new org.springframework.dao.DuplicateKeyException("fixture uniqueness");
                        inserts++; set(args[0], "Id", 501L); yield 1;
                    }
                    case "update" -> {
                        if (action.equals("Enroll") ? count >= capacity : count <= 0) yield 0;
                        count += action.equals("Enroll") ? 1 : -1; yield 1;
                    }
                    case "deleteById" -> { removed = true; yield 1; }
                    default -> throw new AssertionError("unexpected mapper call " + method);
                };
            });
        }
        Object exists(String entity, Object wrapperValue) {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapperValue;
            String sql = wrapper.getSqlSegment();
            assertTrue(wrapper.getParamNameValuePairs().values().stream().allMatch(v -> v instanceof Long || v instanceof Integer),
                    "every relation and selected-course parameter must be a physical scalar: " + wrapper.getParamNameValuePairs());
            boolean found;
            if (entity.equals("Enrollment")) { calls.add("Enrollment.exists"); found = already; }
            else if (sql.contains("prerequisite_rel")) {
                calls.add("Course.prerequisites"); found = missing;
                assertTrue(sql.contains("NOT (EXISTS (SELECT 1 FROM completed_course"), sql);
                assertTrue(wrapper.getParamNameValuePairs().containsValue(23L));
                assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
            } else {
                calls.add("Course.conflicts"); found = clash;
                assertTrue(sql.contains("enrollment_rel.course_id = course.id"), sql);
            }
            return found ? List.of(99L) : List.of();
        }
        Object course() throws Exception {
            return row("Course", Map.of("Id", 23L, "Code", "C23", "Name", "Course", "TeacherId", 9L,
                    "Capacity", capacity, "Enrolled", count, "Weekday", 1, "StartPeriod", 1, "EndPeriod", 3, "Published", published));
        }
        Object user(Long id) throws Exception {
            return row("User", Map.of("Id", id, "Name", "User", "PasswordHash", "not-a-digest", "Role",
                    enumValue("Role", action.equals("CreateCourse") ? "ADMIN" : id == 9L ? "TEACHER" : "STUDENT")));
        }
        Object enrollment() throws Exception {
            return row("Enrollment", Map.of("Id", 11L, "StudentId", owner, "CourseId", 23L,
                    "CreatedAt", Instant.parse("2030-01-01T00:00:00Z")));
        }
    }
    private static Class<?> type(String name) throws Exception { return loader.loadClass("example.course." + name); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(String type, String value) throws Exception { return Enum.valueOf((Class) type("domain." + type), value); }
    private static Object row(String entity, Map<String, Object> values) throws Exception {
        Object row = type("domain." + entity).getConstructor().newInstance();
        for (var value : values.entrySet()) set(row, value.getKey(), value.getValue());
        return row;
    }
    private static void set(Object target, String property, Object value) throws Exception {
        Arrays.stream(target.getClass().getMethods()).filter(m -> m.getName().equals("set" + property)).findFirst().orElseThrow().invoke(target, value);
    }
    private static void assertOrder(State state, String... calls) {
        int previous = -1;
        for (String call : calls) {
            int index = state.calls.indexOf(call); assertTrue(index > previous, call + " in " + state.calls); previous = index;
        }
    }
    private static String file(String name) { return GeneratorTestSupport.contentEndingWith(files, "/" + name); }
    private static Throwable assertCode(String code, org.junit.jupiter.api.function.Executable invocation) {
        var failure = assertThrows(InvocationTargetException.class, invocation).getCause();
        assertEquals(code, assertDoesNotThrow(() -> failure.getClass().getMethod("code").invoke(failure)), failure.toString());
        return failure;
    }
}
