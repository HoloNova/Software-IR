package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoundedEachSemanticsTest {
    @Test void boundedSourceAndBodyUseSeparateLexicalBindings() {
        var result=TestSources.analyzeResource("valid/bounded-each.sir");
        assertTrue(result.isSuccess(),result.diagnostics().toString());
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "limit 100|limit 0", "limit 100|limit 101",
        "id ascending limit|quantity ascending limit", "product ascending, id ascending|product ascending, product ascending",
        "requires atomic; expose command;|requires readonly; expose query;",
        "else TooMany as|else Unknown as", "fails TooMany;|",
        "return unit;|return line;", "return unit;|validate product.stock >= 0 else InvalidLine; return unit;",
        "as line {|as batch {", "persist product;|persist batch;",
        "persist product;|return unit;",
        "line.quantity > 0|item.quantity > 0",
        "persist product;|each Line where true order by id ascending limit 1 else TooMany as inner { persist inner; }"
    })
    void unsupportedShapesAndScopeEscapesAreRejectedWithoutThrowing(String from,String to) {
        var result=TestSources.analyze(TestSources.resource("valid/bounded-each.sir").replace(from,to==null?"":to));
        assertFalse(result.isSuccess(),"must reject "+from+" -> "+to);
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "persist product;|persist product; delete product;",
        "persist product;|persist product; create Product as extra { stock: 0; } persist extra;"
    })
    void aPassMayCreateAndDeleteItsOwnRows(String from,String to) {
        var result=TestSources.analyze(TestSources.resource("valid/bounded-each.sir").replace(from,to));
        assertTrue(result.isSuccess(),from+" -> "+to+": "+result.diagnostics());
    }
    @Test void aRequestControlledLimitIsRejectedByTheParserRatherThanReinterpretedAsAnExpression() {
        var parsed=io.kcg.sir.api.SirParser.create().parseProject(new io.kcg.sir.api.SirSource(
            io.kcg.sir.source.SourceId.of("project.sir"),TestSources.resource("valid/bounded-each.sir").replace("limit 100","limit input.id")));
        assertFalse(parsed.isSuccess());
    }
    @Test void theSourceRowIsAnExistingRowForGuardedAtomicUpdates() {
        String source=TestSources.resource("valid/bounded-each.sir");
        int start=source.indexOf("validate line.quantity > 0"),end=source.indexOf("        }\n        return unit;",start);
        source=source.substring(0,start)+"update line when line.quantity > 0 else InvalidLine { quantity: line.quantity + 1; } persist line;\n"+source.substring(end);
        var result=TestSources.analyze(source);assertTrue(result.isSuccess(),result.diagnostics().toString());
    }

    @Test void twoSeparateBodiesMayUseTheSameLocalNameWithoutSharingAnIdentity() {
        String source=TestSources.resource("valid/bounded-each.sir");
        int begin=source.indexOf("each Line"),end=source.indexOf("return unit;");
        var result=TestSources.analyze(source.substring(0,end)+source.substring(begin,end)+source.substring(end));
        assertTrue(result.isSuccess(),result.diagnostics().toString());
    }
}
