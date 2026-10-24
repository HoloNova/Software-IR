package io.kcg.sir.change.edit;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.change.edit.DraftNodes.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarMonthEditingTest {
    private static final SourceId ENTRY=SourceId.of("project.sir");
    @ParameterizedTest @ValueSource(strings={"\n","\r\n"})
    void inputsAndDeclaredErrorAreRenamedAtTheirActualTokens(String newline) {
        var result=edit(source(newline),List.of(
            new Command.Rename(Selector.root().named("declarations","CalendarInput").named("fields","month"),"period"),
            new Command.Rename(Selector.root().named("declarations","CalendarInput").named("fields","zone"),"region"),
            new Command.Rename(Selector.root().named("declarations","BadPeriod"),"InvalidPeriod")));
        assertTrue(rendered(result).contains("month input.period in input.region as since, until else InvalidPeriod;"),rendered(result));
        assertTrue(rendered(result).contains("return until;"));
    }
    @Test void theExistingEditorCanAddChangeAndRemoveTheStepWithTwoDefinitions() {
        var month=child(node("MonthStep","month",FACT,child(node("StringLiteral","value",FACT,text("2026-01"))),
            "zone",FACT,child(node("StringLiteral","value",FACT,text("UTC"))),
            "sinceVariable",REF,text("extraStart"),"untilVariable",REF,text("extraEnd"),"errorSymbol",REF,text("BadPeriod")));
        var flow=Selector.root().named("declarations","MonthStart").slot("workflow");
        var added=edit(source("\n"),List.of(new Command.Add(flow.slot("steps"),month,OptionalInt.of(0))));
        assertTrue(rendered(added).contains("as extraStart, extraEnd else BadPeriod;"));
        var changed=edit(added.sources(),List.of(new Command.Update(flow.at("steps",0).slot("zone"),
            child(node("StringLiteral","value",FACT,text("Asia/Shanghai"))))));
        assertTrue(rendered(changed).contains("in \"Asia/Shanghai\" as extraStart, extraEnd"));
        var removed=edit(changed.sources(),List.of(new Command.Delete(flow.at("steps",0))));
        assertFalse(rendered(removed).contains("extraStart"));
    }
    private static SourceSnapshot source(String newline) {
        String text=EditFixtures.read("/valid/calendar-month.sir").replace("\r\n","\n").replace("\n",newline);
        return new SourceSnapshot(ENTRY,Map.of(ENTRY,text.getBytes(StandardCharsets.UTF_8)));
    }
    private static String rendered(EditResult.Edited result) {return new String(result.sources().bytes(ENTRY),StandardCharsets.UTF_8);}
    private static EditResult.Edited edit(SourceSnapshot source,List<Command> commands) {
        return assertInstanceOf(EditResult.Edited.class,new SourceEditor().edit(source,
            new CommandBatch(CommandBatch.PROTOCOL,"CalendarMonth",SourceEditor.sha256(source),commands)));
    }
}
