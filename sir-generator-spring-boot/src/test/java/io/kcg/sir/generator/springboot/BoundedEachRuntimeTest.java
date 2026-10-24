package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedEachRuntimeTest {
    @TempDir static Path work;
    static URLClassLoader loader;
    @BeforeAll static void compile() throws Exception {
        loader=GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/bounded-each.sir"),work);
        var assistant=new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"each-runtime");
        assistant.setCurrentNamespace("each.runtime");
        for(String type:List.of("Line","Batch","Product"))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,loader.loadClass("example.each.domain."+type));
    }
    @AfterAll static void close() throws Exception { if(loader!=null)loader.close(); }

    @ParameterizedTest @ValueSource(ints={0,1,100,101})
    void onlyAcceptedBoundedRowsExecuteAndOverflowExecutesNoBody(int count) throws Exception {
        var writes=new AtomicInteger();var loads=new AtomicInteger();var events=new ArrayList<String>();
        var batch=bean("Batch",Map.of("Id",7L,"Active",true));
        var product=bean("Product",Map.of("Id",3L,"Stock",4));
        var lines=new ArrayList<Object>();for(int i=0;i<count;i++)lines.add(bean("Line",Map.of("Id",(long)i+1,"BatchId",7L,"ProductId",3L,"Quantity",2,"Removed",false)));
        var service=service((name,m,args) -> {
            events.add(name+"."+m);
            if(name.equals("Batch")&&m.equals("selectOne"))return batch;
            if(name.equals("Line")&&m.equals("selectList")) {
                var wrapper=(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)args[0];
                assertTrue(wrapper.getSqlSelect().contains("id"));
                assertTrue(wrapper.getSqlSegment().contains("ORDER BY product_id ASC,id ASC"),wrapper.getSqlSegment());
                assertTrue(wrapper.getSqlSegment().endsWith("LIMIT 101"),wrapper.getSqlSegment());
                var values=wrapper.getParamNameValuePairs().values();assertTrue(values.contains(7L));assertTrue(values.contains(false));
                return lines;
            }
            if(name.equals("Product")&&m.equals("selectOne")){loads.incrementAndGet();return product;}
            if(name.equals("Product")&&m.equals("update")) {
                var wrapper=(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>)args[1];
                assertEquals("stock=stock+2",wrapper.getSqlSet().replaceAll("\\s+",""));writes.incrementAndGet();return 1;
            }
            if(name.equals("Product")&&m.equals("selectById"))return product;
            throw new AssertionError(name+"."+m);
        });
        if(count>100) {
            var error=assertThrows(InvocationTargetException.class,()->invoke(service));
            assertEquals("TooManyException",error.getCause().getClass().getSimpleName());
            assertEquals(0,loads.get());assertEquals(0,writes.get());
        } else {invoke(service);assertEquals(count,loads.get());assertEquals(count,writes.get());}
        assertEquals(List.of("Batch.selectOne","Line.selectList"),events.subList(0,2));
    }

    @Test void generatedServiceRetainsTheExistingAtomicWriteAndReadCommittedProtocols() {
        var files=GeneratorTestSupport.generateSuccessFromText(GeneratorTestSupport.resourceText("valid/bounded-each.sir"));
        var service=GeneratorTestSupport.contentEndingWith(files,"/RestoreService.java");
        assertTrue(service.contains("@Transactional(isolation = Isolation.READ_COMMITTED)"),service);
        assertTrue(service.contains(".setIncrBy("),service);
        assertTrue(GeneratorTestSupport.contentEndingWith(files,"/application.yml").contains("STATEMENT"));
    }

    interface Call { Object invoke(String entity,String method,Object[] args) throws Exception; }
    static Object service(Call call) throws Exception {
        var type=loader.loadClass("example.each.application.RestoreService");var constructor=type.getConstructors()[0];
        var mappers=new ArrayList<Object>();
        for(var mapper:constructor.getParameterTypes()) {
            String name=mapper.getSimpleName().replace("Mapper","");
            mappers.add(Proxy.newProxyInstance(loader,new Class<?>[]{mapper},(p,m,a)->call.invoke(name,m.getName(),a)));
        }
        return constructor.newInstance(mappers.toArray());
    }
    static Object bean(String name,Map<String,Object> properties) throws Exception {
        var type=loader.loadClass("example.each.domain."+name);var value=type.getConstructor().newInstance();
        for(var p:properties.entrySet())type.getMethod("set"+p.getKey(),p.getValue().getClass()).invoke(value,p.getValue());
        return value;
    }
    static void invoke(Object service) throws Exception {
        var type=loader.loadClass("example.each.api.BatchInput");var input=type.getConstructor().newInstance();type.getMethod("setId",Long.class).invoke(input,7L);
        service.getClass().getMethod("restore",type).invoke(service,input);
    }
}
