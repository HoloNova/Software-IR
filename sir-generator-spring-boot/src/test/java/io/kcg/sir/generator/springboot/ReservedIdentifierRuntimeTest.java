package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.Update;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every place that writes SQL text with a table or column name in it must quote a MySQL reserved name, or the
 * statement is a syntax error on a real database. Each name below is reserved: the table names {@code group},
 * {@code like} and {@code range}, the columns {@code match}, {@code rank}, {@code limit} and {@code condition}.
 * The SQL is read from the real emitted services and mappers, the way MyBatis would send it; no database runs.
 */
class ReservedIdentifierRuntimeTest {
    private static final String SOURCE = """
            sir 0.3
            software Reserved {
              metadata { displayName "Reserved"; namespace "example.reserved"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus; database mysql; build maven; interface rest; }
              sources {} imports {}
              declarations {
                entity Group persistent {
                  identity id: Id generated auto;
                  field match: Text;
                  field rank: Count;
                  field limit: Count;
                  field condition: Optional<Code>;
                }
                entity Like persistent {
                  identity id: Id generated auto;
                  field group: Ref<Group>;
                  field condition: Optional<Code>;
                }
                entity Range persistent {
                  identity id: Id generated auto;
                  field limit: Count;
                  field version: Version;
                }
                use Crud { entity: Range; }
                view GroupView from Group { field id: Id; field match: Text; }
                input Search { field text: Text; field condition: Optional<Code>; field page: PageNo; field size: PageSize; }
                input GroupRef { field id: Id; }
                error BadPage;
                error Full http 409;
                error GroupMissing http 404;
                capability SearchGroups {
                  input Search; output Page<GroupView>; fails BadPage; requires readonly; expose query;
                  workflow {
                    find Group
                      where item.match containsLiteral input.text
                        and (input.condition is none or item.condition == input.condition)
                        and any(Like, group == item and condition == input.condition)
                      order by id ascending
                      Page input.page, input.size else BadPage as rows;
                    return rows;
                  }
                }
                capability JoinGroup {
                  input GroupRef; output Unit; fails Full; fails GroupMissing; requires atomic; expose command;
                  workflow {
                    load Group by input.id as group else GroupMissing;
                    update group when group.rank < group.limit else Full { rank: group.rank + 1; }
                    persist group;
                    return unit;
                  }
                }
              }
            }
            """;

    @TempDir Path work;

    @Test void predicateFragmentsAndCorrelatedSubqueriesQuoteReservedNames() throws Exception {
        try (var loader = GeneratedRuntimeSupport.compile(SOURCE, work)) {
            initTables(loader, new MybatisConfiguration(), "Group", "Like");
            var segments = new ArrayList<String>();
            Object mapper = recordingMapper(loader, segments, "selectCount", 0L, "selectList", List.of());
            invoke(loader, "SearchGroups", mapper, "Search", Map.of("text", "x", "condition", java.util.Optional.of("NEW")));

            assertFalse(segments.isEmpty());
            for (String sql : segments) {
                assertTrue(sql.contains("`match` LIKE CONCAT('%', "), sql);
                assertTrue(sql.contains("COALESCE(`condition` = "), sql);
                assertTrue(sql.contains("EXISTS (SELECT 1 FROM `like` like_rel WHERE like_rel.group_id = `group`.id"), sql);
                assertTrue(sql.contains("COALESCE(like_rel.`condition` = "), sql);
                assertFalse(sql.matches("(?s).*(?<![`.])\\b(match|condition)\\b(?!`).*"), "an unquoted reserved column remains: " + sql);
            }
        }
    }

    @Test void guardComparingTwoReservedColumnsIsQuoted() throws Exception {
        try (var loader = GeneratedRuntimeSupport.compile(SOURCE, work)) {
            initTables(loader, new MybatisConfiguration(), "Group", "Like");
            var group = loader.loadClass("example.reserved.domain.Group").getConstructor().newInstance();
            group.getClass().getMethod("setId", Long.class).invoke(group, 7L);
            group.getClass().getMethod("setRank", Integer.class).invoke(group, 1);
            group.getClass().getMethod("setLimit", Integer.class).invoke(group, 2);
            var writes = new ArrayList<String>();
            var mapperType = loader.loadClass("example.reserved.persistence.GroupMapper");
            Object mapper = Proxy.newProxyInstance(loader, new Class<?>[]{mapperType}, (p, m, a) -> switch (m.getName()) {
                case "selectById" -> group;
                case "update" -> {
                    var wrapper = (AbstractWrapper<?, ?, ?>) a[1];
                    writes.add(((Update<?, ?>) wrapper).getSqlSet() + " WHERE " + wrapper.getSqlSegment());
                    yield 1;
                }
                default -> throw new AssertionError(m);
            });
            invoke(loader, "JoinGroup", mapper, "GroupRef", Map.of("id", 7L));

            assertEquals(1, writes.size());
            String sql = writes.getFirst();
            assertTrue(sql.contains("`rank` < `limit`"), sql);
            assertTrue(sql.startsWith("`rank`=`rank` + "), sql);
        }
    }

    @Test void versionedMapperStatementsQuoteReservedTableAndColumns() throws Exception {
        try (var loader = GeneratedRuntimeSupport.compile(SOURCE, work)) {
            var configuration = new MybatisConfiguration();
            var mapper = loader.loadClass("example.reserved.persistence.RangeMapper");
            configuration.addMapper(mapper);
            String lock = configuration.getMappedStatement(mapper.getName() + ".selectByIdForUpdate").getBoundSql(7L).getSql();
            String update = configuration.getMappedStatement(mapper.getName() + ".updateIfVersionMatches").getBoundSql(Map.of()).getSql();

            assertEquals("SELECT * FROM `range` WHERE id = ? FOR UPDATE", lock);
            assertEquals("UPDATE `range` SET `limit` = ?, version = version + 1 WHERE id = ? AND version = ?", update);
        }
    }

    private static void initTables(ClassLoader loader, MybatisConfiguration configuration, String... entities) throws Exception {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "reserved-identifiers");
        assistant.setCurrentNamespace("reserved.test");
        for (String entity : entities)
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, loader.loadClass("example.reserved.domain." + entity));
    }

    /** A GroupMapper that records the SQL of every wrapper it is given. */
    private static Object recordingMapper(ClassLoader loader, List<String> segments, Object... results) throws Exception {
        var answers = new java.util.HashMap<String, Object>();
        for (int i = 0; i < results.length; i += 2) answers.put((String) results[i], results[i + 1]);
        var type = loader.loadClass("example.reserved.persistence.GroupMapper");
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (p, m, a) -> {
            if (!answers.containsKey(m.getName())) throw new AssertionError(m);
            segments.add(((AbstractWrapper<?, ?, ?>) a[0]).getSqlSegment());
            return answers.get(m.getName());
        });
    }

    private static void invoke(ClassLoader loader, String capability, Object mapper, String inputType, Map<String, Object> fields) throws Exception {
        var service = loader.loadClass("example.reserved.application." + capability + "Service");
        var constructor = service.getConstructors()[0];
        var instance = constructor.newInstance(java.util.Arrays.stream(constructor.getParameterTypes())
                .map(t -> t.isInstance(mapper) ? mapper : null).toArray());
        var type = loader.loadClass("example.reserved.api." + inputType);
        var input = type.getConstructor().newInstance();
        for (var field : fields.entrySet()) {
            String setter = "set" + Character.toUpperCase(field.getKey().charAt(0)) + field.getKey().substring(1);
            java.util.Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(setter) && m.getParameterCount() == 1)
                    .findFirst().orElseThrow().invoke(input, field.getValue());
        }
        String method = Character.toLowerCase(capability.charAt(0)) + capability.substring(1);
        java.util.Arrays.stream(service.getMethods()).filter(m -> m.getName().equals(method)).findFirst().orElseThrow()
                .invoke(instance, input);
    }
}
