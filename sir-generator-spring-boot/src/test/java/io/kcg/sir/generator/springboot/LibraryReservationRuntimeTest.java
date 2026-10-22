package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the actual library services and JDBC parameter handlers; CI, not these proxies, proves rollback. */
class LibraryReservationRuntimeTest {
    private static URLClassLoader loader;
    private static org.apache.ibatis.session.Configuration configuration;
    @BeforeAll static void compile(@TempDir Path output) throws Exception {
        var sample=Path.of(System.getProperty("basedir",".")).resolveSibling("sir-toolchain-application")
                .resolve("src/test/resources/topics/library/project.sir");
        loader=GeneratedRuntimeSupport.compile(Files.readString(sample),output);
        configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant=new org.apache.ibatis.builder.MapperBuilderAssistant(configuration,"library-reservations");
        assistant.setCurrentNamespace("library.reservations.test");
        for(String entity:List.of("User","Book","Copy","Loan","Reservation"))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,type("domain."+entity));
    }
    @AfterAll static void close() throws Exception { if(loader!=null) loader.close(); }

    @Test void headClaimUsesOneSlotAndConsumesTheTicketOnlyAfterTheLoanIsPersisted() throws Exception {
        var s=new State(); s.counter=4;
        Object result=s.claim();
        assertEquals(5,s.counter); assertFalse(s.ticketExists); assertEquals(1,s.loans);
        assertEquals(501L,result.getClass().getMethod("getId").invoke(result));
        Object copy=result.getClass().getMethod("getCopy").invoke(result);
        assertEquals("BORROWED",copy.getClass().getMethod("getStatus").invoke(copy).toString());
        order(s,"User.lock","Book.lock","Reservation.lock","Copy.lock","User.update","Copy.update","Loan.insert","Reservation.delete");
        assertEquals(List.of(11L,23L),s.headParameters);
    }
    @Test void anotherReadersEarlierTicketPreventsClaimingBeforeAnyCopyLockOrWrite() throws Exception {
        var s=new State(); s.earlier=true;
        failure("NotQueueHead",s::claim);
        assertFalse(s.calls.contains("Copy.lock")); assertEquals(0,s.counter); assertTrue(s.ticketExists);
    }
    @Test void aFullHeadKeepsItsPlaceAndIsNotSkipped() throws Exception {
        var s=new State(); s.counter=5;
        failure("BorrowLimitExceeded",s::claim);
        assertTrue(s.ticketExists); assertEquals(0,s.loans); assertFalse(s.calls.contains("Copy.update"));
    }
    @Test void anUnavailableCopyDoesNotConsumeThePendingHead() throws Exception {
        var s=new State(); s.copyBorrowed=true;
        failure("CopyNotAvailable",s::claim);
        assertTrue(s.ticketExists); assertEquals(0,s.loans);
        order(s,"User.update","Copy.update");
    }
    @Test void wrongBookIsRefusedBeforeLockingThatBooksCopy() throws Exception {
        var s=new State(); s.copyBook=12;
        failure("WrongBook",s::claim);
        assertFalse(s.calls.contains("Copy.lock")); assertTrue(s.ticketExists); assertEquals(0,s.counter);
    }
    @Test void foreignTicketIsRefusedBeforeTakingItsBookLock() throws Exception {
        var s=new State(); s.owner=99;
        failure("Forbidden",s::claim);
        assertFalse(s.calls.contains("Book.lock")); assertTrue(s.ticketExists);
    }
    @Test void replayingAConsumedTicketCannotBorrowAgain() throws Exception {
        var s=new State(); s.claim();
        failure("ReservationNotFound",s::claim);
        assertEquals(1,s.loans); assertEquals(1,s.counter);
    }
    @Test void ordinaryBarcodeBorrowCannotBypassAnyPendingQueue() throws Exception {
        var s=new State(); s.queued=true;
        failure("QueueHasPriority",()->s.invoke("Borrow","BorrowInput",Map.of("Barcode","C21")));
        assertFalse(s.calls.contains("User.update")); assertEquals(0,s.loans);
        order(s,"User.lock","Book.lock","Copy.lock","Reservation.exists");
    }
    @Test void joiningABorrowedBooksQueueDoesNotReserveAnActiveLoanSlot() throws Exception {
        var s=new State(); s.counter=5; s.ticketExists=false;
        Object result=s.reserve();
        assertEquals(23L,result.getClass().getMethod("getId").invoke(result));
        assertTrue(s.ticketExists); assertEquals(5,s.counter); assertFalse(s.calls.contains("User.update"));
        order(s,"User.lock","Book.lock","Reservation.insert");
    }
    @ParameterizedTest @ValueSource(strings={"available","noCopies"})
    void emptyQueuesAreNotForBooksThatCanBeBorrowedOrHaveNoBorrowedCopy(String scenario) throws Exception {
        var s=new State(); s.ticketExists=false;
        s.available=scenario.equals("available"); s.borrowed=scenario.equals("available");
        failure("NotReservable",s::reserve);
        assertFalse(s.calls.contains("Reservation.insert")); assertEquals(0,s.counter);
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","holding"})
    void duplicateTicketsAndExistingBorrowersCannotJoinAgain(String scenario) throws Exception {
        var s=new State(); s.duplicate=scenario.equals("duplicate"); s.holding=scenario.equals("holding");
        failure(s.duplicate?"AlreadyReserved":"AlreadyBorrowingBook",s::reserve);
        assertFalse(s.calls.contains("Reservation.insert"));
    }
    @Test void anExistingQueueMayAcceptFollowersEvenWhenACopyIsAwaitingTheHead() throws Exception {
        var s=new State(); s.ticketExists=false; s.queued=true; s.available=true; s.borrowed=false;
        assertDoesNotThrow(s::reserve); assertTrue(s.ticketExists); assertEquals(0,s.counter);
    }
    @Test void ownerCancellationUsesTheSameBookMutexAndNeverChangesLoanCounts() throws Exception {
        var s=new State(); s.invoke("CancelReservation","ReservationRef",Map.of("Id",23L));
        order(s,"User.lock","Book.lock","Reservation.lock","Reservation.delete");
        assertFalse(s.ticketExists); assertEquals(0,s.counter);
    }
    @Test void staffCancellationLocksTheTicketsOwnerRatherThanTheStaffAccount() throws Exception {
        var s=new State(); s.owner=99; s.staff=true;
        s.invoke("CancelReservation","ReservationRef",Map.of("Id",23L));
        assertEquals(List.of(99L),s.lockedUsers); assertFalse(s.ticketExists);
    }
    @Test void ordinaryReadersCannotCancelAnotherReadersTicket() throws Exception {
        var s=new State(); s.owner=99;
        failure("Forbidden",()->s.invoke("CancelReservation","ReservationRef",Map.of("Id",23L)));
        assertFalse(s.calls.contains("User.lock")); assertFalse(s.calls.contains("Book.lock")); assertTrue(s.ticketExists);
    }
    @Test void myReservationsFiltersAtTheQueryNotWithAGlobalOwnerScope() throws Exception {
        var s=new State(); Object result=s.invoke("MyReservations","PageOnly",Map.of("Page",1,"Size",10));
        assertEquals("PageResponse",result.getClass().getSimpleName());
        assertTrue(s.calls.contains("Reservation.selectCount")); assertTrue(s.calls.contains("Reservation.selectList"));
    }
    @Test void lateDeleteFailuresEscapeTheServiceAndLeaveTheTicketForTransactionalRollback() throws Exception {
        var s=new State(); s.failDelete=true;
        var failure=assertThrows(InvocationTargetException.class,s::claim).getCause();
        assertInstanceOf(org.springframework.dao.DataIntegrityViolationException.class,failure);
        order(s,"User.update","Copy.update","Loan.insert","Reservation.delete");
        assertTrue(s.ticketExists); // Proxy calls prove ordering and propagation, NOT rollback of previous writes.
    }

    private static final class State {
        int counter,loans;
        long owner=7,copyBook=11;
        boolean ticketExists=true,earlier,duplicate,holding,queued,available,borrowed=true,staff,failDelete,copyBorrowed;
        String action;
        final List<String> calls=new ArrayList<>();
        final List<Long> lockedUsers=new ArrayList<>();
        List<Object> headParameters=List.of();
        Object claim() throws Exception { return invoke("ClaimReservation","ClaimReservationInput",Map.of("Id",23L,"Barcode","C21")); }
        Object reserve() throws Exception { return invoke("ReserveBook","ReserveBookInput",Map.of("BookId",11L)); }
        Object invoke(String capability,String inputName,Map<String,Object> values) throws Exception {
            action=capability;
            Object input=row("api."+inputName,values);
            var service=type("application."+capability+"Service");
            var ctor=service.getConstructors()[0];
            Object instance=ctor.newInstance(Arrays.stream(ctor.getParameterTypes()).map(this::mapper).toArray());
            return service.getMethod(Character.toLowerCase(capability.charAt(0))+capability.substring(1),Long.class,input.getClass())
                    .invoke(instance,7L,input);
        }
        private Object mapper(Class<?> mapper) {
            String entity=mapper.getSimpleName().replace("Mapper","");
            return Proxy.newProxyInstance(loader,new Class<?>[]{mapper},(proxy,method,args)-> {
                String operation=method.getName();
                if(operation.equals("selectOne")) {
                    calls.add(entity+".lock");
                    var wrapper=(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)args[0];
                    assertTrue(wrapper.getSqlSegment().contains("FOR UPDATE"));
                    var values=bind(wrapper); assertEquals(1,values.size());
                    if(entity.equals("User")) { lockedUsers.add((Long)values.getFirst()); return user((Long)values.getFirst()); }
                    return entity.equals("Book")?book():entity.equals("Reservation")?ticket():copy();
                }
                if(operation.equals("selectObjs")) return exists(entity,args[0]);
                calls.add(entity+"."+(operation.equals("deleteById")?"delete":operation));
                return switch(operation) {
                    case "selectById" -> entity.equals("User")?user((Long)args[0]):entity.equals("Book")?book():entity.equals("Reservation")?ticket():copy();
                    case "selectByIds","selectBatchIds" -> entity.equals("Book")?List.of(book()):List.of(copy());
                    case "selectList" -> {
                        if(entity.equals("Reservation")) privateQuery(args[0]);
                        yield entity.equals("Book")?List.of(book()):entity.equals("Reservation")?List.of(ticket()):List.of(copy());
                    }
                    case "selectCount" -> { privateQuery(args[0]); yield 1L; }
                    case "insert" -> {
                        set(args[0],"Id",entity.equals("Loan")?501L:23L);
                        if(entity.equals("Loan")) loans++; else ticketExists=true;
                        yield 1;
                    }
                    case "update" -> {
                        var wrapper=(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)args[1];
                        wrapper.getSqlSegment(); bind(wrapper);
                        if(entity.equals("User")) { if(counter>=5) yield 0; counter++; }
                        if(entity.equals("Copy")) { if(copyBorrowed) yield 0; copyBorrowed=true; }
                        yield 1;
                    }
                    case "deleteById" -> {
                        if(failDelete) throw new org.springframework.dao.DataIntegrityViolationException("fixture late deletion");
                        ticketExists=false; yield 1;
                    }
                    default -> throw new AssertionError("unexpected mapper call "+method);
                };
            });
        }
        private Object exists(String entity,Object value) throws Exception {
            calls.add(entity+".exists");
            var wrapper=(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)value;
            String sql=wrapper.getSqlSegment(); var values=bind(wrapper); boolean found;
            if(entity.equals("Reservation")) {
                if(sql.contains("id <")) {
                    assertFalse(sql.contains("reader_id"),"FIFO must include other readers");
                    assertTrue(sql.contains("book_id =")); headParameters=values; found=earlier;
                } else found=sql.contains("reader_id")?duplicate:queued;
            } else if(sql.contains("loan_rel")) {
                assertTrue(sql.contains("loan_rel.copy_id = copy.id"),sql); found=holding;
            } else found=values.contains("AVAILABLE")?available:borrowed;
            assertTrue(sql.contains("LIMIT 1"),sql);
            return found?List.of(9L):List.of();
        }
        private void privateQuery(Object value) throws Exception {
            var wrapper=(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)value;
            assertTrue(wrapper.getSqlSegment().contains("reader_id =")); assertEquals(List.of(7L),bind(wrapper));
        }
        private Object user(long id) throws Exception {
            return row("domain.User",Map.of("Id",id,"Name","user","PasswordHash","not-a-digest","ActiveLoans",counter,
                    "Role",enumValue("Role",staff?"LIBRARIAN":"READER")));
        }
        private Object book() throws Exception { return row("domain.Book",Map.of("Id",11L,"Isbn","ISBN11","Title","Book","Author","Author")); }
        private Object copy() throws Exception { return row("domain.Copy",Map.of("Id",21L,"BookId",copyBook,"Barcode","C21","Status",enumValue("CopyStatus",copyBorrowed?"BORROWED":"AVAILABLE"))); }
        private Object ticket() throws Exception {
            return ticketExists?row("domain.Reservation",Map.of("Id",23L,"BookId",11L,"ReaderId",owner,"QueuedAt",Instant.parse("2030-01-01T00:00:00Z"))):null;
        }
    }
    private static List<Object> bind(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?> wrapper) throws Exception {
        var parameters=wrapper.getParamNameValuePairs().keySet().stream().sorted()
                .map(key->new ParameterMapping.Builder(configuration,"ew.paramNameValuePairs."+key,Object.class).build()).toList();
        String text="SELECT "+String.join(",",java.util.Collections.nCopies(parameters.size(),"?"));
        var sql=new BoundSql(configuration,text,parameters,Map.of("ew",wrapper));
        var statement=new MappedStatement.Builder(configuration,"library-reservation-binding",
                new org.apache.ibatis.builder.StaticSqlSource(configuration,text),SqlCommandType.SELECT).build();
        var values=new ArrayList<Object>();
        var jdbc=(PreparedStatement)Proxy.newProxyInstance(LibraryReservationRuntimeTest.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},
                (proxy,method,args)-> { assertTrue(args[1] instanceof Number || args[1] instanceof String || args[1] instanceof Boolean,"not a JDBC scalar: "+args[1]); values.add(args[1]); return null; });
        new org.apache.ibatis.scripting.defaults.DefaultParameterHandler(statement,Map.of("ew",wrapper),sql).setParameters(jdbc);
        return List.copyOf(values);
    }
    private static Class<?> type(String name) throws Exception { return loader.loadClass("example.library."+name); }
    private static Object row(String name,Map<String,Object> values) throws Exception {
        Object row=type(name).getConstructor().newInstance();
        for(var value:values.entrySet()) set(row,value.getKey(),value.getValue());
        return row;
    }
    private static void set(Object target,String property,Object value) throws Exception {
        Arrays.stream(target.getClass().getMethods()).filter(m->m.getName().equals("set"+property)).findFirst().orElseThrow().invoke(target,value);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static Object enumValue(String type,String value) throws Exception { return Enum.valueOf((Class)type("domain."+type),value); }
    private static void failure(String code,org.junit.jupiter.api.function.Executable call) {
        var error=assertThrows(InvocationTargetException.class,call).getCause();
        assertEquals(code,assertDoesNotThrow(()->error.getClass().getMethod("code").invoke(error)),error.toString());
    }
    private static void order(State state,String... operations) {
        int index=-1;
        for(String operation:operations) { int next=state.calls.indexOf(operation); assertTrue(next>index,operation+" in "+state.calls); index=next; }
    }
}
