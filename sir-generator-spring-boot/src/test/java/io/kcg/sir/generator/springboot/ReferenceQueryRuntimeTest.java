package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Invokes real emitted services and the actual MyBatis parameter handler, without a database. */
class ReferenceQueryRuntimeTest {
    @TempDir Path work;

    @Test void loadedRowsPayloadReferencesAndNullableReferencesBindOnlyPhysicalIdentities() throws Exception {
        String source = GeneratorTestSupport.resourceText("valid/explicit-lock.sir")
                .replace("field room: Ref<Room>;", "field room: Ref<Room>; field backup: Optional<Ref<Room>>;")
                .replace("    capability Book {", """
                    input References { field anchor: Id; field selected: Ref<Room>; field backup: Optional<Ref<Room>>;
                        field page: PageNo; field size: PageSize; }
                    capability Queries {
                      input References; output Page<BookingView>; fails InvalidInterval; fails RoomMissing;
                      requires readonly; expose query;
                      workflow {
                        load Room by input.anchor as room else RoomMissing;
                        load Room by input.anchor as chosen else RoomMissing;
                        exists Room where any(Booking, room == item and room == chosen) as correlated;
                        exists Booking where item.room == room as found;
                        count Booking where item.room == room as total;
                        find Booking where item.room == room order by id ascending
                          Page input.page, input.size else InvalidInterval as rows;
                        exists Booking where item.backup == room as maybe;
                        exists Booking where item.room == input.selected as selected;
                        exists Booking where item.backup == input.backup as optionalPayload;
                        validate room == room else RoomMissing;
                        load Room by room as same else RoomMissing;
                        return rows;
                      }
                    }
                    """ + "    capability Book {");
        try (var loader = GeneratedRuntimeSupport.compile(source, work)) {
            var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
            var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(config, "reference-parameters");
            assistant.setCurrentNamespace("reference.test");
            var roomType = loader.loadClass("example.locking.domain.Room");
            for (String entity : List.of("Room", "Booking"))
                com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, loader.loadClass("example.locking.domain." + entity));
            var room = roomType.getConstructor().newInstance(); roomType.getMethod("setId", Long.class).invoke(room, 7L);
            var roomMapper = loader.loadClass("example.locking.persistence.RoomMapper");
            var bookingMapper = loader.loadClass("example.locking.persistence.BookingMapper");
            var reads = new ArrayList<Object>(); var calls = new ArrayList<String>();
            var rm = Proxy.newProxyInstance(loader, new Class<?>[]{roomMapper}, (p,m,a) -> {
                if (m.getName().equals("selectById")) {
                    assertEquals(7L, a[0], "load by a row also means load by its identity"); return room;
                }
                assertEquals("selectObjs", m.getName());
                bind((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) a[0], config, reads);
                return List.of();
            });
            var bm = Proxy.newProxyInstance(loader, new Class<?>[]{bookingMapper}, (p,m,a) -> {
                calls.add(m.getName());
                bind((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) a[0], config, reads);
                return switch (m.getName()) {
                    case "selectObjs", "selectList" -> List.of();
                    case "selectCount" -> 0L;
                    default -> throw new AssertionError(m);
                };
            });
            var service = loader.loadClass("example.locking.application.QueriesService");
            var constructor = service.getConstructors()[0];
            var instance = constructor.newInstance(java.util.Arrays.stream(constructor.getParameterTypes()).map(t -> t == roomMapper ? rm : bm).toArray());
            var inputType = loader.loadClass("example.locking.api.References");
            var input = inputType.getConstructor().newInstance();
            inputType.getMethod("setAnchor", Long.class).invoke(input, 7L);
            inputType.getMethod("setSelectedId", Long.class).invoke(input, 7L);
            var method = service.getMethod("queries", inputType);
            method.invoke(instance, input);
            assertTrue(reads.contains(null), "absent Optional reference still binds SQL NULL, not Optional.empty");
            reads.clear();
            inputType.getMethod("setBackup", java.util.Optional.class).invoke(input, java.util.Optional.of(7L));
            method.invoke(instance, input);
            assertFalse(reads.isEmpty()); assertTrue(reads.stream().allMatch(v -> Long.valueOf(7L).equals(v)), reads.toString());
            assertTrue(calls.containsAll(List.of("selectCount", "selectList", "selectObjs")), calls.toString());
        }
    }

    private static void bind(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper,
            org.apache.ibatis.session.Configuration config, List<Object> values) throws Exception {
        wrapper.getSqlSegment();
        var mappings = wrapper.getParamNameValuePairs().keySet().stream().sorted()
                .map(key -> new ParameterMapping.Builder(config, "ew.paramNameValuePairs." + key, Object.class).build()).toList();
        var parameters = Map.of("ew", wrapper);
        var sql = new BoundSql(config, "SELECT " + String.join(",", java.util.Collections.nCopies(mappings.size(), "?")), mappings, parameters);
        var statement = new MappedStatement.Builder(config, "physical-identities",
                new org.apache.ibatis.builder.StaticSqlSource(config, sql.getSql()), SqlCommandType.SELECT).build();
        var jdbc = (PreparedStatement) Proxy.newProxyInstance(ReferenceQueryRuntimeTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p,m,a) -> {
            Object value = m.getName().equals("setNull") ? null : a[1];
            assertTrue(value == null || value instanceof Long, "SQL reference argument must be a physical Long identity, not " + (value == null ? "null" : value.getClass()));
            values.add(value); return null;
        });
        new org.apache.ibatis.scripting.defaults.DefaultParameterHandler(statement, parameters, sql).setParameters(jdbc);
    }
}
