package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CalendarMonthSemanticsTest {
    @Test void bothBoundariesAreMomentDefinitionsVisibleAfterTheStep() {
        var result=TestSources.analyzeResource("valid/calendar-month.sir");
        assertTrue(result.isSuccess(),result.diagnostics().toString());
    }
    @ParameterizedTest
    @CsvSource(delimiter='|',value={
        "as since, until|as since, since", "return since;|return missing;",
        "month input.month|month since", "in input.zone|in until",
        "else BadPeriod|else NotDeclared", "fails BadPeriod;|",
        "requires readonly; expose query;|requires atomic; expose command;",
        "Optional<Text>; field zone|Count; field zone", "zone: Optional<Text>|zone: Count"
    })
    void malformedDefinitionsTypesAndErrorContractsAreRejected(String from,String to) {
        var result=TestSources.analyze(TestSources.resource("valid/calendar-month.sir").replace(from,to==null?"":to));
        assertFalse(result.isSuccess());
    }
}
