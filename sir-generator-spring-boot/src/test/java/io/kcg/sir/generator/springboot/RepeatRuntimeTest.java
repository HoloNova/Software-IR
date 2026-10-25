package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated weekly booking: {@code repeat input.weeks times} books the same hours on consecutive weeks with
 * {@code addDays}, checks each week for an overlap, and counts the bookings on the locked room. The mappers below stand
 * in for the stored rows; no database is started.
 */
class RepeatRuntimeTest {
    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");
    @TempDir Path work;

    @Test void everyWeekGetsItsOwnBookingSevenDaysApart() throws Exception {
        try (var loader = compile()) {
            var desk = new Desk(loader);
            Object room = desk.book(3);
            assertEquals(List.of(MONDAY, MONDAY.plus(Duration.ofDays(7)), MONDAY.plus(Duration.ofDays(14))), desk.starts());
            assertEquals(3, desk.booked, "the room counts one booking per week");
            assertEquals(3, room.getClass().getMethod("getBooked").invoke(room));
        }
    }

    @Test void aTakenWeekStopsTheSeriesWithItsDeclaredError() throws Exception {
        try (var loader = compile()) {
            var desk = new Desk(loader);
            desk.takenWeek = 1;
            var error = assertThrows(InvocationTargetException.class, () -> desk.book(3)).getCause();
            assertEquals("SlotTakenException", error.getClass().getSimpleName());
        }
    }

    @Test void aCountOverTheBoundIsRefusedBeforeAnyPass() throws Exception {
        try (var loader = compile()) {
            var desk = new Desk(loader);
            var error = assertThrows(InvocationTargetException.class, () -> desk.book(13)).getCause();
            assertEquals("TooManyWeeksException", error.getClass().getSimpleName());
            assertTrue(desk.bookings.isEmpty());
        }
    }

    private URLClassLoader compile() throws Exception {
        return GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/weekly-booking.sir"), work);
    }

    private static final class Desk {
        final ClassLoader loader;
        final List<Object> bookings = new ArrayList<>();
        final com.baomidou.mybatisplus.core.MybatisConfiguration configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        int booked;
        int takenWeek = -1;
        int checks;

        Desk(ClassLoader loader) throws Exception {
            this.loader = loader;
            var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "weekly");
            assistant.setCurrentNamespace("weekly.test");
            for (String entity : List.of("Room", "Booking"))
                com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, type("domain." + entity));
        }

        Object book(int weeks) throws Exception {
            var inputType = type("api.WeeklyBooking");
            Object input = inputType.getConstructor().newInstance();
            set(input, "RoomId", 7L); set(input, "StartsAt", MONDAY); set(input, "EndsAt", MONDAY.plus(Duration.ofHours(1)));
            set(input, "Weeks", weeks);
            var service = type("application.BookWeeklyService");
            var constructor = service.getConstructors()[0];
            Object instance = constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
                    .map(t -> mapper(t.getSimpleName().replace("Mapper", ""))).toArray());
            try (var transaction = DecimalSessionProbe.strictTransaction(configuration)) {
                return service.getMethod("bookWeekly", inputType).invoke(instance, input);
            }
        }

        List<Object> starts() throws Exception {
            var result = new ArrayList<Object>();
            for (Object booking : bookings) result.add(booking.getClass().getMethod("getStartsAt").invoke(booking));
            return result;
        }

        Object mapper(String entity) {
            try {
                var type = type("persistence." + entity + "Mapper");
                return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> call(entity, method.getName(), args));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private Object call(String entity, String method, Object[] args) throws Exception {
            switch (entity + "." + method) {
                case "Room.selectOne", "Room.selectById", "Room.selectByIdForUpdate": return room();
                case "Room.update": booked++; return 1;
                case "Booking.selectObjs", "Booking.selectList": {
                    ((AbstractWrapper<?, ?, ?>) args[0]).getSqlSegment();
                    return checks++ == takenWeek ? List.of(1L) : List.of();
                }
                case "Booking.selectCount": return checks++ == takenWeek ? 1L : 0L;
                case "Booking.insert": bookings.add(args[0]); return 1;
                default: throw new AssertionError("unexpected mapper call " + entity + "." + method);
            }
        }

        private Object room() throws Exception {
            Object row = type("domain.Room").getConstructor().newInstance();
            set(row, "Id", 7L); set(row, "Booked", booked);
            return row;
        }

        Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass("example.weekly." + name); }
    }

    private static void set(Object bean, String property, Object value) throws Exception {
        Arrays.stream(bean.getClass().getMethods()).filter(m -> m.getName().equals("set" + property) && m.getParameterCount() == 1)
                .findFirst().orElseThrow().invoke(bean, value);
    }
}
