package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual generated services and result carriers; no application or database is started. */
class RelatedCountRuntimeTest {
    @TempDir static Path work;
    private static java.net.URLClassLoader compiled;
    @org.junit.jupiter.api.BeforeAll static void compile() throws Exception {
        compiled=GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/related-count.sir"),work);
    }
    @org.junit.jupiter.api.AfterAll static void close() throws Exception { if(compiled!=null)compiled.close(); }

    @Test void aCataloguePageReadsChildrenOnceAndPreservesZeroGroupsAndOrder() throws Exception {
        {
            var calls=new ArrayList<String>();
            var bookMapper=compiled.loadClass("example.counts.persistence.BookMapper");
            var copyMapper=compiled.loadClass("example.counts.persistence.CopyMapper");
            Object books=Proxy.newProxyInstance(bookMapper.getClassLoader(),new Class<?>[]{bookMapper},(p,m,a)-> {
                calls.add("book."+m.getName());
                return switch(m.getName()) {
                    case "selectCount" -> 3L;
                    case "selectList" -> List.of(book(compiled,3L),book(compiled,1L),book(compiled,2L));
                    default -> throw new AssertionError(m.getName());
                };
            });
            Object copies=Proxy.newProxyInstance(copyMapper.getClassLoader(),new Class<?>[]{copyMapper},(p,m,a)-> {
                calls.add("copy."+m.getName());assertEquals("selectMaps",m.getName());
                var wrapper=assertInstanceOf(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class,a[0]);
                String sql=wrapper.getSqlSegment();
                assertTrue(sql.contains("GROUP BY `book_id`"),sql);
                assertFalse(sql.contains("book.id"),sql);
                var values=wrapper.getParamNameValuePairs().values();
                assertTrue(values.containsAll(List.of(1L,2L,3L,"AVAILABLE",false)),values.toString());
                return List.of(Map.of("count_key",1L,"count_value",2L),Map.of("count_key",3L,"count_value",1L));
            });
            var service=compiled.loadClass("example.counts.application.SearchCatalogueService");
            Object instance=service.getConstructor(bookMapper,copyMapper).newInstance(books,copies);
            var input=compiled.loadClass("example.counts.api.Search");Object payload=input.getConstructor().newInstance();
            input.getMethod("setKeyword",String.class).invoke(payload,"book");input.getMethod("setPage",Integer.class).invoke(payload,1);
            input.getMethod("setSize",Integer.class).invoke(payload,20);
            Object page=service.getMethod("searchCatalogue",input).invoke(instance,payload);
            var records=(List<?>)page.getClass().getMethod("getRecords").invoke(page);
            assertEquals(List.of(3L,1L,2L),values(records,"getId"));
            assertEquals(List.of(1L,2L,0L),values(records,"getAvailableCopies"));
            assertEquals(List.of("book.selectCount","book.selectList","copy.selectMaps"),calls);
            assertEquals(3L,page.getClass().getMethod("getTotal").invoke(page));
        }
    }

    @Test void anEmptyRootPageNeverQueriesTheChildTable() throws Exception {
        {
            var root=compiled.loadClass("example.counts.persistence.BookMapper");var child=compiled.loadClass("example.counts.persistence.CopyMapper");
            Object books=Proxy.newProxyInstance(root.getClassLoader(),new Class<?>[]{root},(p,m,a)->switch(m.getName()) {
                case "selectCount" -> 0L; case "selectList" -> List.of(); default -> throw new AssertionError(m.getName());
            });
            Object copies=Proxy.newProxyInstance(child.getClassLoader(),new Class<?>[]{child},(p,m,a)->{throw new AssertionError("empty page queried copies: "+m.getName());});
            var type=compiled.loadClass("example.counts.application.ListBookService");Object service=type.getConstructor(root,child).newInstance(books,copies);
            var input=compiled.loadClass("example.counts.api.ListBookInput");Object page=type.getMethod("listBook",input).invoke(service,input.getConstructor().newInstance());
            assertEquals(List.of(),page.getClass().getMethod("getRecords").invoke(page));
        }
    }

    @Test void aDetailUsesTheSameZeroFillingIndexAsAPage() throws Exception {
        var root=compiled.loadClass("example.counts.persistence.BookMapper");var child=compiled.loadClass("example.counts.persistence.CopyMapper");
        var calls=new ArrayList<String>();
        Object books=Proxy.newProxyInstance(root.getClassLoader(),new Class<?>[]{root},(p,m,a)->{calls.add(m.getName());assertEquals("selectById",m.getName());return book(compiled,1L);});
        Object copies=Proxy.newProxyInstance(child.getClassLoader(),new Class<?>[]{child},(p,m,a)->{calls.add(m.getName());assertEquals("selectMaps",m.getName());return List.of();});
        var type=compiled.loadClass("example.counts.application.GetBookService");
        var method=Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals("getBook")&&m.getParameterCount()==1).findFirst().orElseThrow();
        var input=method.getParameterTypes()[0];
        var service=type.getConstructor(root,child).newInstance(books,copies);Object payload=input.getConstructor().newInstance();input.getMethod("setId",Long.class).invoke(payload,1L);
        Object response=method.invoke(service,payload);
        assertEquals(0L,response.getClass().getMethod("getAvailableCopies").invoke(response));assertEquals(List.of("selectById","selectMaps"),calls);
    }

    @Test void strictResultDecodingCannotTurnCorruptRowsIntoZero() throws Exception {
        var helper=compiled.loadClass("example.counts.persistence.RelatedCounts");
        var read=helper.getMethod("read",List.class,int.class,String.class,String.class,java.util.function.Function.class,java.util.function.Supplier.class);
        java.util.function.Function<Object,Object> decoder=value->{try{return helper.getMethod("longKey",Object.class).invoke(null,value);}catch(Exception e){throw new IllegalStateException(e);}};
        var invalid=new ArrayList<List<Map<String,Object>>>();
        for(Object count:List.of(-1L,1.5d,"3",new java.math.BigDecimal("1.1"),java.math.BigInteger.ONE.shiftLeft(63)))
            invalid.add(List.of(Map.of("count_key",1L,"count_value",count)));
        invalid.add(List.of(Map.of("count_key",2L,"count_value",1L)));
        invalid.add(List.of(Map.of("count_key",1L,"count_value",1L),Map.of("count_key",1L,"count_value",0L)));
        invalid.add(List.of(Map.of("key",1L,"count_value",1L)));
        var nullValue=new HashMap<String,Object>();nullValue.put("count_key",1L);nullValue.put("count_value",null);invalid.add(List.of(nullValue));
        for(var rows:invalid) {
            var error=assertThrows(InvocationTargetException.class,()->read.invoke(null,List.of(1L),100,"count_key","count_value",decoder,(java.util.function.Supplier<List<Map<String,Object>>>)()->rows),rows.toString());
            assertInstanceOf(IllegalStateException.class,error.getCause());
        }
        java.util.function.Supplier<List<Map<String,Object>>> mustNotRead=()->{throw new AssertionError("no query before source validation");};
        assertEquals(Map.of(),read.invoke(null,List.of(),100,"count_key","count_value",decoder,mustNotRead));
        var tooMany=java.util.stream.LongStream.rangeClosed(1,101).boxed().toList();
        assertInstanceOf(IllegalStateException.class,assertThrows(InvocationTargetException.class,()->read.invoke(null,tooMany,100,"count_key","count_value",decoder,mustNotRead)).getCause());
        java.util.function.Supplier<List<Map<String,Object>>> failure=()->{throw new IllegalStateException("database failed");};
        var error=assertThrows(InvocationTargetException.class,()->read.invoke(null,List.of(1L),100,"count_key","count_value",decoder,failure));
        assertEquals("database failed",error.getCause().getMessage());
    }

    @Test void uuidKeysKeepTheirIdentityAndDoNotBecomeNumbers() throws Exception {
        String source=GeneratorTestSupport.resourceText("valid/related-count.sir").replace("entity Book persistent { identity id: Id generated auto;","entity Book persistent { identity id: Token generated uuid;")
            .replace("field id: Id;\n      field title", "field id: Token;\n      field title");
        // The root identity and view member are both explicitly changed; no implicit key coercion.
        source=source.replace("field id: Id;\r\n      field title", "field id: Token;\r\n      field title");
        try(var uuid=GeneratedRuntimeSupport.compile(source,work.resolve("uuid"))) {
            var helper=uuid.loadClass("example.counts.persistence.RelatedCounts");var key=UUID.randomUUID();
            assertEquals(key,helper.getMethod("uuidKey",Object.class).invoke(null,key.toString()));
            assertThrows(InvocationTargetException.class,()->helper.getMethod("uuidKey",Object.class).invoke(null,1L));
            assertThrows(InvocationTargetException.class,()->helper.getMethod("uuidKey",Object.class).invoke(null,"1-1-1-1-1"));
            var root=uuid.loadClass("example.counts.persistence.BookMapper");var child=uuid.loadClass("example.counts.persistence.CopyMapper");
            Object books=Proxy.newProxyInstance(root.getClassLoader(),new Class<?>[]{root},(p,m,a)->book(uuid,key));
            Object copies=Proxy.newProxyInstance(child.getClassLoader(),new Class<?>[]{child},(p,m,a)->List.of(Map.of("count_key",key.toString(),"count_value",java.math.BigInteger.valueOf(4))));
            var type=uuid.loadClass("example.counts.application.GetBookService");
            var method=Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals("getBook")&&m.getParameterCount()==1).findFirst().orElseThrow();
            var input=method.getParameterTypes()[0];
            var service=type.getConstructor(root,child).newInstance(books,copies);Object payload=input.getConstructor().newInstance();input.getMethod("setId",UUID.class).invoke(payload,key);
            Object response=method.invoke(service,payload);assertEquals(4L,response.getClass().getMethod("getAvailableCopies").invoke(response));
        }
    }

    @Test void snapshotAndCacheAreExplicitInTheActualGeneratedArtifacts() throws Exception {
        var type=compiled.loadClass("example.counts.application.SearchCatalogueService");
        var tx=type.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertTrue(tx.readOnly());assertEquals(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,tx.isolation());
        var files=GeneratorTestSupport.generateSuccessFromText(GeneratorTestSupport.resourceText("valid/related-count.sir"));
        assertTrue(files.stream().anyMatch(f->f.relativePath().endsWith("application.yml")&&f.content().contains("local-cache-scope: STATEMENT")));
        assertFalse(files.stream().anyMatch(f->f.relativePath().endsWith("/ExactAggregate.java")));
    }

    private static Object book(java.net.URLClassLoader compiled,Object id) throws Exception {
        var type=compiled.loadClass("example.counts.domain.Book");Object row=type.getConstructor().newInstance();
        type.getMethod("setId",type.getDeclaredField("id").getType()).invoke(row,id);type.getMethod("setTitle",String.class).invoke(row,"book"+id);return row;
    }
    private static List<Object> values(List<?> rows,String getter) throws Exception {
        List<Object> result=new ArrayList<>();for(Object row:rows)result.add(row.getClass().getMethod(getter).invoke(row));return result;
    }
}
