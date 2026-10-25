package io.kcg.sir.change.edit;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.change.edit.DraftNodes.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GroupedSumEditingTest {
    static final SourceId ENTRY=SourceId.of("project.sir");
    @ParameterizedTest @ValueSource(strings={"\n","\r\n"})
    void keyMeasurementEntityAndPageRenameFollowTheirExactBindings(String newline) {
        var source=source(newline);
        var result=edit(source,List.of(
            new Command.Rename(Selector.root().named("declarations","Entry").named("fields","category"),"bucket"),
            new Command.Rename(Selector.root().named("declarations","Entry").named("fields","amount"),"cost"),
            new Command.Rename(Selector.root().named("declarations","Category"),"Bucket"),
            new Command.Rename(Selector.root().named("declarations","TotalsInput").named("fields","page"),"index")));
        var text=rendered(result);
        assertTrue(text.contains("sum Entry.cost by bucket"),text);
        assertTrue(text.contains("SumGroup<Ref<Bucket>>"),text);
        assertTrue(text.contains("any(Bucket, id == item.bucket"),text);
        assertTrue(text.contains("Page input.index, input.size else BadPage"),text);
    }

    @Test void addUpdateDeleteUseTheExistingSourceEditorProtocol() {
        var page=child(node("PageSpec","pageField",REF,text("page"),"sizeField",REF,text("size"),"errorSymbol",REF,text("BadPage")));
        var group=child(node("GroupedSumStep","entitySymbol",REF,text("Entry"),"fieldSymbol",REF,text("amount"),
            "keySymbol",REF,text("category"),"predicate",FACT,child(node("BooleanLiteral","value",FACT,text("true"))),
            "page",FACT,page,"average",FACT,text("false"),"resultVariable",REF,text("extra")));
        var flow=Selector.root().named("declarations","TotalsByCategory").slot("workflow");
        var added=edit(source("\n"),List.of(new Command.Add(flow.slot("steps"),group,OptionalInt.of(0))));
        assertTrue(rendered(added).contains("sum Entry.amount by category"));
        var updated=edit(added.sources(),List.of(new Command.Update(flow.at("steps",0).slot("predicate"),
            child(node("BooleanLiteral","value",FACT,text("false"))))));
        assertTrue(rendered(updated).contains("where false"),rendered(updated));
        var removed=edit(updated.sources(),List.of(new Command.Delete(flow.at("steps",0))));
        assertFalse(rendered(removed).contains("as extra;"));
    }
    static SourceSnapshot source(String newline) {
        String source=EditFixtures.read("/valid/decimal-group.sir").replace("\r\n","\n").replace("\n",newline);
        return new SourceSnapshot(ENTRY,Map.of(ENTRY,source.getBytes(StandardCharsets.UTF_8)));
    }
    static String rendered(EditResult.Edited result) { return new String(result.sources().bytes(ENTRY),StandardCharsets.UTF_8); }
    static EditResult.Edited edit(SourceSnapshot source,List<Command> commands) {
        return assertInstanceOf(EditResult.Edited.class,new SourceEditor().edit(source,
            new CommandBatch(CommandBatch.PROTOCOL,"DecimalGroup",SourceEditor.sha256(source),commands)));
    }
}
