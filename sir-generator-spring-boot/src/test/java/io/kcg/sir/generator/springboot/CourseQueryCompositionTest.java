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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Proves that arbitrary direct prerequisites need two sibling correlations, not nested any or new syntax. */
class CourseQueryCompositionTest {
    private static final String SOURCE = """
            sir 0.3
            software CourseQueries {
              metadata { displayName "Course Queries"; namespace "example.coursequeries"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus;
                database mysql; build maven; interface rest; }
              sources {} imports {}
              declarations {
                entity User persistent { identity id: Id generated auto; }
                entity Course persistent { identity id: Id generated auto; }
                entity Prerequisite persistent {
                  identity id: Id generated auto;
                  field targetCourse: Ref<Course>; field required: Ref<Course>;
                  unique (targetCourse, required);
                }
                entity CompletedCourse persistent {
                  identity id: Id generated auto;
                  field student: Ref<User>; field course: Ref<Course>;
                  unique (student, course);
                }
                input CheckInput { field id: Id; }
                error CourseMissing http 404;
                error MissingPrerequisites http 409;
                capability VerifyPrerequisites {
                  actor Ref<User>; input CheckInput; output Unit;
                  fails CourseMissing; fails MissingPrerequisites;
                  requires authenticated; requires readonly; expose query;
                  workflow {
                    load Course by input.id as selected else CourseMissing;
                    exists Course where
                      any(Prerequisite, required == item and targetCourse == selected)
                      and not any(CompletedCourse, course == item and student == actor)
                    as missing;
                    validate not missing else MissingPrerequisites;
                    return unit;
                  }
                }
              }
            }
            """;
    private static URLClassLoader loader;
    @BeforeAll static void compile(@TempDir Path output) throws Exception { loader = GeneratedRuntimeSupport.compile(SOURCE, output); }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoSiblingSubqueriesShareTheRequiredCourseAndBindOnlyPhysicalIdentities(boolean missing) throws Exception {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(config, "course-correlations");
        assistant.setCurrentNamespace("coursequeries.test");
        Class<?> courseType = loader.loadClass("example.coursequeries.domain.Course");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, courseType);
        Object course = courseType.getConstructor().newInstance(); courseType.getMethod("setId", Long.class).invoke(course, 7L);
        var bound = new ArrayList<Object>();
        Class<?> mapperType = loader.loadClass("example.coursequeries.persistence.CourseMapper");
        Object mapper = Proxy.newProxyInstance(loader, new Class<?>[]{mapperType}, (p,m,a) -> {
            if (m.getName().equals("selectById")) { assertEquals(7L, a[0]); return course; }
            assertEquals("selectObjs", m.getName());
            var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) a[0];
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("prerequisite_rel.required_id = course.id"), sql);
            assertTrue(sql.contains("completed_course_rel.course_id = course.id"), sql);
            assertTrue(sql.contains("NOT (EXISTS (SELECT 1 FROM completed_course"), sql);
            bind(wrapper, config, bound);
            return missing ? List.of(11L) : List.of();
        });
        Class<?> inputType = loader.loadClass("example.coursequeries.api.CheckInput");
        Object input = inputType.getConstructor().newInstance(); inputType.getMethod("setId", Long.class).invoke(input, 7L);
        Class<?> serviceType = loader.loadClass("example.coursequeries.application.VerifyPrerequisitesService");
        Object service = serviceType.getConstructors()[0].newInstance(mapper);
        var method = serviceType.getMethod("verifyPrerequisites", Long.class, inputType);
        if (missing) {
            var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(service, 42L, input));
            assertEquals("MissingPrerequisitesException", failure.getCause().getClass().getSimpleName());
        } else assertDoesNotThrow(() -> method.invoke(service, 42L, input));
        assertEquals(2, bound.size());
        assertTrue(bound.containsAll(List.of(7L, 42L)), bound.toString());
    }

    private static void bind(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper,
            org.apache.ibatis.session.Configuration config, List<Object> values) throws Exception {
        var mappings = wrapper.getParamNameValuePairs().keySet().stream().sorted()
                .map(key -> new ParameterMapping.Builder(config, "ew.paramNameValuePairs." + key, Object.class).build()).toList();
        var sql = new BoundSql(config, "SELECT ?, ?", mappings, Map.of("ew", wrapper));
        var statement = new MappedStatement.Builder(config, "prerequisites",
                new org.apache.ibatis.builder.StaticSqlSource(config, sql.getSql()), SqlCommandType.SELECT).build();
        var jdbc = (PreparedStatement) Proxy.newProxyInstance(CourseQueryCompositionTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (p,m,a) -> {
                    assertTrue(a[1] instanceof Long, "a loaded Course is not a JDBC parameter: " + a[1].getClass());
                    values.add(a[1]); return null;
                });
        new org.apache.ibatis.scripting.defaults.DefaultParameterHandler(statement, Map.of("ew", wrapper), sql).setParameters(jdbc);
    }
}
