package io.kcg.sir.change.edit;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.change.edit.DraftNodes.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedEachEditingTest {
    static final SourceId ENTRY=SourceId.of("project.sir");
    static final Selector FLOW=Selector.root().named("declarations","Restore").slot("workflow");
    @ParameterizedTest @ValueSource(strings={"\n","\r\n"})
    void sourceBodyAndOrderNamesFollowFormalReferences(String newline) {
        var source=source(newline);
        var edited=edit(source,List.of(new Command.Rename(Selector.root().named("declarations","Product"),"CatalogItem"),
            new Command.Rename(Selector.root().named("declarations","Line").named("fields","product"),"goods")));
        String text=render(edited);
        assertTrue(text.contains("order by goods ascending, id ascending"),text);
        assertTrue(text.contains("load CatalogItem by line.goods"),text);
        assertTrue(text.contains("persist product;"),"local names are not inferred from entity names");
    }
    @Test void addUpdateDeleteKeepTheWholeNestedBody() {
        var extra=child(node("EachStep","entitySymbol",REF,text("Line"),"predicate",FACT,child(node("BooleanLiteral","value",FACT,text("true"))),
            "orderKeys",FACT,seq(child(node("OrderKey","fieldSymbol",REF,text("id"),"descending",FACT,text("false")))),
            "limit",FACT,text("1"),"errorSymbol",REF,text("TooMany"),"rowVariable",REF,text("extra"),"body",FACT,seq()));
        var added=edit(source("\n"),List.of(new Command.Add(FLOW.slot("steps"),extra,OptionalInt.of(1))));
        assertTrue(render(added).contains("as extra {"),render(added));
        var updated=edit(added.sources(),List.of(new Command.Update(FLOW.at("steps",2).slot("limit"),text("2"))));
        assertTrue(render(updated).contains("limit 2 else TooMany as line"),render(updated));
        assertTrue(render(updated).contains("load Product by line.product"));
        var removed=edit(updated.sources(),List.of(new Command.Delete(FLOW.at("steps",1))));
        assertFalse(render(removed).contains("as extra {"));
    }
    @Test void aBodyMemberAndSourceOrderCanBeRenamedAcrossAnImport() {
        String source=EditFixtures.read("/valid/bounded-each.sir").replace("\r\n","\n");
        int start=source.indexOf("    entity Product"),end=source.indexOf("    input BatchInput");
        var module=SourceId.of("entities.sir");
        String root=(source.substring(0,start)+source.substring(end)).replace("imports {}",
            "imports { import Product from \"entities.sir\"; import Batch from \"entities.sir\"; import Line from \"entities.sir\"; }");
        root=root.replace("sources {}","sources { source \"entities.sir\"; }");
        var snapshot=new SourceSnapshot(ENTRY,Map.of(ENTRY,root.getBytes(StandardCharsets.UTF_8),module,
            ("sir 0.3 imports {} declarations {\n"+source.substring(start,end)+"}\n").getBytes(StandardCharsets.UTF_8)));
        var edited=edit(snapshot,List.of(new Command.Rename(Selector.root().named("declarations","Line").named("fields","product"),"goods")));
        assertTrue(render(edited).contains("line.goods"));assertTrue(render(edited).contains("order by goods ascending"));
        assertTrue(new String(edited.sources().bytes(module),StandardCharsets.UTF_8).contains("field goods: Ref<Product>"));
    }
    static SourceSnapshot source(String newline) {
        return new SourceSnapshot(ENTRY,Map.of(ENTRY,EditFixtures.read("/valid/bounded-each.sir").replace("\r\n","\n").replace("\n",newline).getBytes(StandardCharsets.UTF_8)));
    }
    static String render(EditResult.Edited result) {return new String(result.sources().bytes(ENTRY),StandardCharsets.UTF_8);}
    static EditResult.Edited edit(SourceSnapshot source,List<Command> commands) {
        var result=new SourceEditor().edit(source,new CommandBatch(CommandBatch.PROTOCOL,"BoundedEach",SourceEditor.sha256(source),commands));
        return assertInstanceOf(EditResult.Edited.class,result,result.toString());
    }
}
