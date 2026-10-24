package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Executes the actual ledger topic, with real MyBatis parameter rendering and no database/application. */
class LedgerCalendarRuntimeTest {
    @TempDir static Path output;
    static URLClassLoader loader;
    record Scenario(String zone,long budgetOwner,long categoryOwner,String limit,String total) {}
    static final Scenario OWN=new Scenario("America/New_York",1,1,"10.00","10.00");
    @BeforeAll static void compile() throws Exception {
        var source=Path.of(System.getProperty("basedir",".")).resolveSibling("sir-toolchain-application").resolve("src/test/resources/topics/ledger/project.sir");
        loader=GeneratedRuntimeSupport.compile(Files.readString(source),output);
        var assistant=new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"ledger-calendar");
        assistant.setCurrentNamespace("calendar.fixture");
        for(String name:List.of("User","Entry","Budget","Category"))com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,type("domain."+name));
    }
    @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}

    @Test void monthlyTotalsUseSavedUserZoneForBothSharedSqlBoundaries() throws Exception {
        var calls=new ArrayList<String>();var parameters=new ArrayList<Object>();
        var result=invoke("TotalsForMonth","MonthQuery",OWN,"2026-03",calls,parameters);
        assertEquals(1L,result.getClass().getMethod("getTotal").invoke(result));
        assertEquals(List.of("UserMapper.selectById","EntryMapper.selectCount","EntryMapper.selectMaps"),calls);
        assertTrue(parameters.contains(Instant.parse("2026-03-01T05:00:00Z")),parameters.toString());
        assertTrue(parameters.contains(Instant.parse("2026-04-01T04:00:00Z")),parameters.toString());
        assertThrows(NoSuchMethodException.class,()->type("api.MonthQuery").getMethod("setZone",String.class));
    }
    @Test void unsetPreferenceAndBadMonthStopBeforeTheAggregate() throws Exception {
        for(var scenario:List.of(new Scenario(null,1,1,"10","0"),new Scenario("bad",1,1,"10","0"))) {
            var calls=new ArrayList<String>();
            var error=assertThrows(InvocationTargetException.class,()->invoke("TotalsForMonth","MonthQuery",scenario,"2026-03",calls,new ArrayList<>()));
            assertEquals("BadPeriod",code(error));assertEquals(List.of("UserMapper.selectById"),calls);
        }
        var calls=new ArrayList<String>();
        assertThrows(InvocationTargetException.class,()->invoke("TotalsForMonth","MonthQuery",OWN,"2026-13",calls,new ArrayList<>()));
        assertEquals(List.of("UserMapper.selectById"),calls);
    }
    @Test void budgetIsTheSpecifiedCurrentRuleAndEqualityIsNotExceeded() throws Exception {
        assertEquals(false,invoke("BudgetExceededForMonth","BudgetMonthQuery",OWN,"2026-03",new ArrayList<>(),new ArrayList<>()));
        assertEquals(true,invoke("BudgetExceededForMonth","BudgetMonthQuery",new Scenario(OWN.zone(),1,1,"9.99","10.00"),"2026-03",new ArrayList<>(),new ArrayList<>()));
        assertEquals(false,invoke("BudgetExceededForMonth","BudgetMonthQuery",new Scenario(OWN.zone(),1,1,"9.99","0"),"2026-03",new ArrayList<>(),new ArrayList<>()));
    }
    @Test void anotherUsersRuleOrCategoryCannotBeUsedAsAnAggregateOracle() throws Exception {
        for(var scenario:List.of(new Scenario(OWN.zone(),2,1,"10","11"),new Scenario(OWN.zone(),1,2,"10","11"))) {
            var calls=new ArrayList<String>();
            var error=assertThrows(InvocationTargetException.class,()->invoke("BudgetExceededForMonth","BudgetMonthQuery",scenario,"2026-03",calls,new ArrayList<>()));
            assertEquals("BudgetNotFound",code(error));assertTrue(calls.stream().noneMatch(s -> s.startsWith("EntryMapper")),calls.toString());
        }
    }
    @Test void settingsWriteTheActorOnlyAndCandidateValidationCannotBeBypassed() throws Exception {
        for(String zone:List.of("Asia/Shanghai","America/New_York","bad")) {
            var calls=new ArrayList<String>();
            var input=type("api.PreferredTimeZone").getConstructor().newInstance();input.getClass().getMethod("setTimeZone",String.class).invoke(input,zone);
            var service=type("application.SetTimeZoneService");var constructor=service.getConstructors()[0];
            var args=Arrays.stream(constructor.getParameterTypes()).map(mapper -> Proxy.newProxyInstance(loader,new Class<?>[]{mapper},(p,m,a)->{
                calls.add(m.getName());
                if(m.getName().equals("selectById")){assertEquals(1L,a[0]);return row("User",new Scenario(null,1,1,"10","0"));}
                assertEquals("updateById",m.getName());assertEquals(1L,a[0].getClass().getMethod("getId").invoke(a[0]));
                assertEquals(zone,a[0].getClass().getMethod("getTimeZone").invoke(a[0]));return 1;
            })).toArray();
            var method=service.getMethod("setTimeZone",Long.class,input.getClass());
            if(zone.equals("bad")) {
                var error=assertThrows(InvocationTargetException.class,()->method.invoke(constructor.newInstance(args),1L,input));
                assertEquals("INVALID_REQUEST",code(error));assertEquals(List.of("selectById"),calls);
            } else { method.invoke(constructor.newInstance(args),1L,input);assertEquals(List.of("selectById","updateById"),calls); }
        }
    }
    private static Object invoke(String capability,String dto,Scenario scenario,String month,List<String> calls,List<Object> values) throws Exception {
        var inputType=type("api."+dto);var input=inputType.getConstructor().newInstance();
        inputType.getMethod("setMonth",Optional.class).invoke(input,Optional.ofNullable(month));
        if(dto.equals("BudgetMonthQuery"))inputType.getMethod("setBudgetId",Long.class).invoke(input,10L);
        var service=type("application."+capability+"Service");var constructor=service.getConstructors()[0];
        var args=Arrays.stream(constructor.getParameterTypes()).map(mapper -> Proxy.newProxyInstance(loader,new Class<?>[]{mapper},(p,m,a)->{
            calls.add(mapper.getSimpleName()+"."+m.getName());
            if(mapper.getSimpleName().equals("EntryMapper")) {
                var query=(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>)a[0];query.getSqlSegment();
                values.addAll(query.getParamNameValuePairs().values());
                if(m.getName().equals("selectCount"))return 1L;
                if(m.getName().equals("selectMaps"))return List.of(Map.of("g_key",10L,"g_total",new BigDecimal(scenario.total())));
                assertEquals("selectObjs",m.getName());return List.of(new BigDecimal(scenario.total()));
            }
            assertEquals("selectById",m.getName());assertInstanceOf(Long.class,a[0]);
            var name=mapper.getSimpleName().replace("Mapper","");
            assertEquals(name.equals("User")?1L:10L,a[0]);return row(name,scenario);
        })).toArray();
        var methodName=Character.toLowerCase(capability.charAt(0))+capability.substring(1);
        return service.getMethod(methodName,Long.class,inputType).invoke(constructor.newInstance(args),1L,input);
    }
    private static Object row(String name,Scenario scenario) throws Exception {
        var row=type("domain."+name).getConstructor().newInstance();row.getClass().getMethod("setId",Long.class).invoke(row,name.equals("User")?1L:10L);
        if(name.equals("User"))row.getClass().getMethod("setTimeZone",String.class).invoke(row,scenario.zone());
        if(name.equals("Category"))row.getClass().getMethod("setOwnerId",Long.class).invoke(row,scenario.categoryOwner());
        if(name.equals("Budget")) {
            row.getClass().getMethod("setOwnerId",Long.class).invoke(row,scenario.budgetOwner());
            row.getClass().getMethod("setCategoryId",Long.class).invoke(row,10L);
            row.getClass().getMethod("setMonthlyLimit",BigDecimal.class).invoke(row,new BigDecimal(scenario.limit()));
        }
        return row;
    }
    private static String code(InvocationTargetException error) throws Exception {return (String)error.getCause().getClass().getMethod("code").invoke(error.getCause());}
    private static Class<?> type(String name) throws ClassNotFoundException {return loader.loadClass("example.ledger."+name);}
}
