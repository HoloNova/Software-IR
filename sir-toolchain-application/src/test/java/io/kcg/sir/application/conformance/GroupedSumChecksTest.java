package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupedSumChecksTest {
    @TempDir Path root;
    @Test void actualHttpAssertionKeepsPrecisionBeyondDoubleAndSingleMoneyLimits() throws Exception {
        var harness=BusinessSliceHarnessEvidenceTest.harness(root.resolve("work"),root.resolve("evidence"));
        var response=new HttpAssertionClient.Response(200,"{\"total\":1,\"page\":1,\"size\":20,\"records\":[{\"key\":103,\"total\":199999999999999999.98}]}");
        GroupedSumChecks.checkPage(harness,response,1,List.of(103L),List.of("199999999999999999.98"),"exact HTTP fixture");
        assertTrue(harness.passed(),harness::report);
    }
    @Test void wrongMetadataAndLeakedColumnsCannotProduceFalseGreenEvidence() throws Exception {
        var harness=BusinessSliceHarnessEvidenceTest.harness(root.resolve("work"),root.resolve("evidence"));
        var response=new HttpAssertionClient.Response(200,"{\"total\":6,\"records\":[{\"key\":103,\"total\":1.23,\"owner\":2}]}");
        GroupedSumChecks.checkPage(harness,response,1,List.of(103L),List.of("1.23"),"bad fixture page");
        assertFalse(harness.passed(),harness::report);
    }
}
