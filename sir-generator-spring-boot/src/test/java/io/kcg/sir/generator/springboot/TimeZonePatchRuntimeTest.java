package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimeZonePatchRuntimeTest {
    @Test void targetRegionRulesApplyOnlyToAnActualPatchChange(@TempDir Path work) throws Exception {
        String source=GeneratorTestSupport.resourceText("valid/time-zone.sir").replace("    error AccountNotFound", """
                entity PatchAccount persistent { identity id: Id generated auto; field version: Version; field zone: Optional<ZoneName>; }
                input ZonePatch patch of PatchAccount { field id: Id; field zone: Optional<Text>; }
                error Conflict http 409;
                capability PatchZone {
                  input ZonePatch; output Unit; fails AccountNotFound; fails Conflict; requires atomic; expose command;
                  workflow {
                    load PatchAccount by input.id as account else AccountNotFound;
                    update account { zone: input.zone; }
                    persist account else Conflict; return unit;
                  }
                }
                error AccountNotFound""");
        try(var loader=GeneratedRuntimeSupport.compile(source,work)) {
            for(String change:List.of("absent","clear","good","bad")) {
                var entity=loader.loadClass("example.zone.domain.PatchAccount");var account=entity.getConstructor().newInstance();
                entity.getMethod("setId",Long.class).invoke(account,1L);entity.getMethod("setVersion",Long.class).invoke(account,1L);entity.getMethod("setZone",String.class).invoke(account,"America/New_York");
                var writes=new AtomicInteger();var mapper=loader.loadClass("example.zone.persistence.PatchAccountMapper");
                var proxy=Proxy.newProxyInstance(loader,new Class<?>[]{mapper},(p,m,a)->switch(m.getName()) {
                    case "selectById", "selectByIdForUpdate" -> account;case "updateById", "updateIfVersionMatches" -> {writes.incrementAndGet();yield 1;}
                    default -> throw new AssertionError(m.getName());
                });
                var service=loader.loadClass("example.zone.application.PatchZoneService");var instance=service.getConstructor(mapper).newInstance(proxy);
                var inputType=loader.loadClass("example.zone.api.ZonePatch");var input=inputType.getConstructor().newInstance();inputType.getMethod("setId",Long.class).invoke(input,1L);inputType.getMethod("setExpectedVersion",Long.class).invoke(input,1L);
                var changesType=loader.loadClass("example.zone.api.ZonePatch$Changes");var changes=changesType.getConstructor().newInstance();inputType.getMethod("setChanges",changesType).invoke(input,changes);
                if(!change.equals("absent"))changesType.getMethod("zone",Optional.class).invoke(changes,
                    change.equals("clear")?Optional.empty():Optional.of(change.equals("good")?"UTC":"CST"));
                var execute=service.getMethod("patchZone",inputType);
                if(change.equals("bad")) {
                    var error=assertThrows(InvocationTargetException.class,()->execute.invoke(instance,input));
                    assertEquals("INVALID_REQUEST",error.getCause().getClass().getMethod("code").invoke(error.getCause()));assertEquals(0,writes.get());
                } else {
                    execute.invoke(instance,input);assertEquals(1,writes.get(),"persist still performs its existing version-conditional write even with empty changes");
                    assertEquals(change.equals("absent")?"America/New_York":change.equals("clear")?null:"UTC",entity.getMethod("getZone").invoke(account));
                }
            }
        }
    }
}
