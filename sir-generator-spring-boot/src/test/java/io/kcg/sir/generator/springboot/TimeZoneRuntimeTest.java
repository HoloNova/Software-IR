package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimeZoneRuntimeTest {
    @TempDir Path work;
    @Test void realDtoAndCandidateUseTheSameRegionRules() throws Exception {
        try (var loader=GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/time-zone.sir"),work);
             var factory=Validation.buildDefaultValidatorFactory()) {
            var type=loader.loadClass("example.zone.api.ZoneInput");
            var dto=type.getConstructor().newInstance();
            var support=loader.loadClass("example.zone.api.ValidationSupport");
            var candidate=support.getMethod("timeZone",List.class,String.class,String.class);
            for(String zone:List.of("UTC","Asia/Shanghai","America/New_York","Australia/Lord_Howe")) {
                type.getMethod("setZone",String.class).invoke(dto,zone);
                assertTrue(factory.getValidator().validate(dto).isEmpty(),zone);
                var errors=new ArrayList<>();candidate.invoke(null,errors,"zone",zone);assertTrue(errors.isEmpty(),zone);
            }
            for(String zone:List.of("Mars/Base","CST","EST","+08:00","UTC+08:00",""," UTC","Asia/Shanghai ")) {
                type.getMethod("setZone",String.class).invoke(dto,zone);
                assertFalse(factory.getValidator().validate(dto).isEmpty(),zone);
                var errors=new ArrayList<>();candidate.invoke(null,errors,"zone",zone);assertEquals(1,errors.size(),zone);
            }
            type.getMethod("setZone",String.class).invoke(dto,new Object[]{null});
            assertFalse(factory.getValidator().validate(dto).isEmpty(),"mandatory settings input");
            var optionalType=loader.loadClass("example.zone.api.OptionalZoneInput");
            var optional=optionalType.getConstructor().newInstance();
            assertTrue(factory.getValidator().validate(optional).isEmpty(),"unset preference is legal storage, not an implicit UTC");
            optionalType.getMethod("setZone",Optional.class).invoke(optional,Optional.of("bad"));
            assertFalse(factory.getValidator().validate(optional).isEmpty());
            var errors=new ArrayList<>();candidate.invoke(null,errors,"zone",null);assertTrue(errors.isEmpty(),"null is handled by a separate mandatory rule");
        }
    }
    @Test void unchangedProjectsDoNotGetTheNewValidator() {
        var files=GeneratorTestSupport.generateSuccess("valid/money.sir");
        assertFalse(files.stream().anyMatch(f -> f.content().contains("public @interface RegionTimeZone")));
    }
}
