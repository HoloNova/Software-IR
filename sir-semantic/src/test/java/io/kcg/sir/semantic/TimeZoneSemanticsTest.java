package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TimeZoneSemanticsTest {
    @Test void patchUsesTargetRulesButStillRefusesItsOwnRepeatedBusinessRule() {
        String source=TestSources.resource("valid/time-zone.sir");
        String plain=source.replace("    error AccountNotFound", "    entity PatchAccount persistent { identity id: Id generated auto; field version: Version; field zone: Optional<ZoneName>; } input ZonePatch patch of PatchAccount { field id: Id; field zone: Optional<Text>; }\n    error AccountNotFound");
        var valid=TestSources.analyze(plain);assertTrue(valid.isSuccess(),valid.diagnostics().toString());
        assertFalse(TestSources.analyze(plain.replace("field zone: Optional<Text>;", "field zone: Optional<ZoneName>;")).isSuccess(),
            "timeZone is a business rule, not a new precision-only PATCH exception");
        assertFalse(TestSources.analyze(plain.replace("field zone: Optional<Text>;","field zone: Optional<Text> where timeZone;")).isSuccess());
    }
    @Test void aTextRuleExpandsThroughAnAliasAndOptionalStorage() {
        var result = TestSources.analyzeResource("valid/time-zone.sir");
        assertTrue(result.isSuccess(), result.diagnostics().toString());
    }
    @ParameterizedTest
    @ValueSource(strings={"field zone: Count where timeZone;", "field zone: Text where timeZone(1);"})
    void malformedRulesAreRejected(String field) {
        var result = TestSources.analyze(TestSources.sir("input Bad { " + field + " }"));
        assertFalse(result.isSuccess());
    }
    @ParameterizedTest
    @ValueSource(strings={"UTC", "Asia/Shanghai", "America/New_York", "Australia/Lord_Howe"})
    void aValidRegionCanBeADefault(String zone) {
        var result = TestSources.analyze(TestSources.sir("entity Account persistent { identity id: Id generated auto; field zone: Text where timeZone default \""+zone+"\"; }"));
        assertTrue(result.isSuccess(), result.diagnostics().toString());
    }
    @ParameterizedTest
    @ValueSource(strings={"Mars/Base", "CST", "EST", "+08:00", "UTC+08:00", "", " UTC", "Asia/Shanghai "})
    void anInvalidDefaultCannotBypassTheWriteConstraint(String zone) {
        var result = TestSources.analyze(TestSources.sir("entity Account persistent { identity id: Id generated auto; field zone: Text where timeZone default \""+zone+"\"; }"));
        assertFalse(result.isSuccess(), "illegal default: " + zone);
    }
}
