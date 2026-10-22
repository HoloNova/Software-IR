package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;

class ExplicitLockRuntimeTest {
    @TempDir Path work;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void actualGeneratedServiceLocksIdentityBeforeCheckingAndWriting(boolean versioned) throws Exception {
        String source = versioned ? source().replace("field name: Text unique;", "field name: Text unique; field version: Serial versioned;") : source();
        var files = GeneratorTestSupport.generateSuccessFromText(source);
        String service = GeneratorTestSupport.contentEndingWith(files, "/BookService.java");
        assertTrue(service.contains("@Transactional(isolation = Isolation.READ_COMMITTED)"), service);
        assertTrue(service.contains(".last(\"FOR UPDATE\")"), service);
        String yaml = GeneratorTestSupport.contentEndingWith(files, "/application.yml");
        var resource = new org.springframework.core.io.ByteArrayResource(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var properties = new org.springframework.boot.env.YamlPropertySourceLoader().load("locking", resource).getFirst();
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(properties);
        var mybatis = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("mybatis-plus", com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties.class).get();
        assertEquals(org.apache.ibatis.session.LocalCacheScope.STATEMENT, mybatis.getConfiguration().getLocalCacheScope());
        String mapper = GeneratorTestSupport.contentEndingWith(files, "/RoomMapper.java");
        var ordinary = GeneratorTestSupport.generateSuccessFromText(source.replace(" for update", ""));
        assertEquals(GeneratorTestSupport.contentEndingWith(ordinary, "/RoomMapper.java"), mapper);
        try (var loader = GeneratedRuntimeSupport.compile(source, work)) {
            var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                    new com.baomidou.mybatisplus.core.MybatisConfiguration(), "locking-test");
            assistant.setCurrentNamespace("locking.test");
            for (String name : List.of("Room", "Booking"))
                com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                        loader.loadClass("example.locking.domain." + name));
            var roomType = loader.loadClass("example.locking.domain.Room");
            var room = roomType.getConstructor().newInstance();
            roomType.getMethod("setId", Long.class).invoke(room, 1L);
            var events = new ArrayList<String>();
            var writes = new AtomicInteger();
            var occupied = new java.util.concurrent.atomic.AtomicBoolean();
            var roomMapper = loader.loadClass("example.locking.persistence.RoomMapper");
            var bookingMapper = loader.loadClass("example.locking.persistence.BookingMapper");
            var rm = Proxy.newProxyInstance(loader, new Class<?>[]{roomMapper}, (p,m,a) -> {
                events.add("room." + m.getName());
                if (m.getName().equals("selectOne")) {
                    var wrapper = (com.baomidou.mybatisplus.core.conditions.Wrapper<?>) a[0];
                    assertTrue(wrapper.getSqlSegment().contains("FOR UPDATE"));
                    assertTrue(wrapper.getSqlSegment().contains("id ="));
                    return room;
                }
                throw new AssertionError(m);
            });
            var bm = Proxy.newProxyInstance(loader, new Class<?>[]{bookingMapper}, (p,m,a) -> {
                events.add("booking." + m.getName());
                if (m.getName().equals("selectObjs")) {
                    var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) a[0];
                    wrapper.getSqlSegment();
                    assertEquals(List.of(1L, java.time.Instant.parse("2030-01-01T11:00:00Z"),
                            java.time.Instant.parse("2030-01-01T10:00:00Z")),
                            wrapper.getParamNameValuePairs().entrySet().stream()
                                    .sorted(java.util.Map.Entry.comparingByKey()).map(java.util.Map.Entry::getValue).toList(),
                            "room_id must bind the loaded row's identity, never its Room object");
                    return occupied.get() ? List.of(new Object()) : List.of();
                }
                if (m.getName().equals("insert")) { writes.incrementAndGet(); return 1; }
                throw new AssertionError(m);
            });
            var type = loader.loadClass("example.locking.application.BookService");
            var constructor = type.getConstructors()[0];
            var instance = constructor.newInstance(java.util.Arrays.stream(constructor.getParameterTypes())
                    .map(t -> t.equals(roomMapper) ? rm : bm).toArray());
            var inputType = loader.loadClass("example.locking.api.NewBooking");
            var input = inputType.getConstructor().newInstance();
            inputType.getMethod("setRoomId", Long.class).invoke(input, 1L);
            inputType.getMethod("setStartsAt", java.time.Instant.class).invoke(input, java.time.Instant.parse("2030-01-01T10:00:00Z"));
            inputType.getMethod("setEndsAt", java.time.Instant.class).invoke(input, java.time.Instant.parse("2030-01-01T11:00:00Z"));
            type.getMethod("book", inputType).invoke(instance, input);
            assertEquals(1, writes.get());
            assertEquals(List.of("room.selectOne", "booking.selectObjs", "booking.insert"), events);
            occupied.set(true);
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> type.getMethod("book", inputType).invoke(instance, input));
            assertEquals("SlotTakenException", failure.getCause().getClass().getSimpleName());
            assertEquals(1, writes.get());
        }
    }
    private static String source() { return GeneratorTestSupport.resourceText("valid/explicit-lock.sir"); }
}
