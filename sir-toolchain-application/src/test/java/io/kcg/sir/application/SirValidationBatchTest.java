package io.kcg.sir.application;

import io.kcg.sir.application.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SirValidationBatchTest {
    static String q(String s) { return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\""; }
    static String sample(String id, String sir) { return "{\"id\":"+q(id)+",\"sir\":"+q(sir)+"}"; }
    static List<SirValidationResult> batch(byte[] bytes) throws Exception {
        var results = new ArrayList<SirValidationResult>();
        var summary = new SirValidationApplication().checkBatch(new SirValidationBatchRequest(new ByteArrayInputStream(bytes), results::add));
        assertEquals(results.size(),summary.lineCount()); return results;
    }
    static List<SirValidationResult> batch(String text) throws Exception { return batch(text.getBytes(StandardCharsets.UTF_8)); }
    @Test void mixedRowsRemainOrderedAndIsolated() throws Exception {
        String sir = SirValidationApplicationTest.source("campus-market.sir");
        var rows = List.of(sample("good",sir), sample("parse","not valid SIR"), sample("semantic",ValidationFixtureProbeTest.resource("invalid/semantic-unresolved-name.sir")),
            sample("empty",""), sample("over","x".repeat(SirValidationApplication.MAX_SIR_BYTES+1)), "{invalid", "{\"sir\":"+q(sir)+"}", sample("last",sir));
        var result = batch(String.join("\n",rows)); assertEquals(rows.size(),result.size());
        assertTrue(result.getFirst().ok()); assertTrue(result.getLast().ok());
        for (int i=1;i<7;i++) assertFalse(result.get(i).ok(),"row "+i);
        assertEquals(List.of("good","parse","semantic","empty","over"), result.subList(0,5).stream().map(SirValidationResult::id).toList());
        assertNull(result.get(5).id()); assertNull(result.get(6).id());
    }
    @Test void completeBadShapesKeepIdRegardlessOfFieldOrder() throws Exception {
        for (String value : List.of("null","true","12.5e-2","[1,false,{\"a\":null}]","{\"a\":\"b\"}")) {
            for (String line : List.of("{\"sir\":"+value+",\"id\":\"keep\"}","{\"id\":\"keep\",\"sir\":"+value+"}")) {
                var r = batch(line).getFirst(); assertFalse(r.ok()); assertEquals("keep",r.id());
            }
        }
        var unknown = batch("{\"unknown\":[1,{\"a\":true}],\"sir\":\"bad\",\"id\":\"keep\"}").getFirst();
        assertEquals("keep",unknown.id()); assertEquals("KCG-CHECK-INPUT-008",unknown.diagnostics().getFirst().code());
        assertNull(batch("{\"id\":\"a\",\"id\":\"b\",\"sir\":\"bad\"}").getFirst().id());
        var ambiguous = batch("{\"id\":\"keep\",\"sir\":\"first\",\"sir\":\"second\"}").getFirst();
        assertEquals("keep",ambiguous.id()); assertNull(ambiguous.sourceSha256());
    }
    @Test void malformedJsonAndUnicodeCannotPoisonNextRow() throws Exception {
        String good = sample("next",SirValidationApplicationTest.source("campus-market.sir"));
        for (String bad : List.of("", "[]", "{} trailing", "{\"id\":\"x\",}", "{\"sir\":01,\"id\":\"x\"}",
            "{\"sir\":1.,\"id\":\"x\"}", "{\"sir\":trueX,\"id\":\"x\"}", "{\"sir\":[1,],\"id\":\"x\"}",
            "{\"id\":\"\\q\",\"sir\":\"x\"}","{\"id\":\"\\uZZZZ\",\"sir\":\"x\"}",
            "{\"id\":\"\\uD800\",\"sir\":\"x\"}","\ufeff{}")) {
            var rs = batch(bad+"\n"+good); assertEquals(2,rs.size()); assertFalse(rs.getFirst().ok(),bad); assertTrue(rs.getLast().ok(),bad);
        }
        var bytes = new ByteArrayOutputStream(); bytes.write(new byte[]{(byte)0xc3,(byte)0x28,'\n'}); bytes.write(good.getBytes(StandardCharsets.UTF_8));
        var result = batch(bytes.toByteArray()); assertEquals("KCG-CHECK-INPUT-006",result.getFirst().diagnostics().getFirst().code()); assertTrue(result.getLast().ok());
        assertEquals("😀",batch("{\"id\":\"\\uD83D\\uDE00\",\"sir\":\"bad\"}").getFirst().id());
    }
    @Test void lineFramingCrlfBlankEofAndExactBudgets() throws Exception {
        assertEquals(0,batch("").size()); assertEquals(1,batch("\n").size()); assertEquals(2,batch("\n\n").size());
        String row = sample("x","bad"); assertEquals(1,batch(row+"\r\n").size()); assertEquals(1,batch(row).size());
        String at = row+" ".repeat(SirValidationApplication.MAX_LINE_BYTES-row.length());
        assertFalse(batch(at+"\r\n").getFirst().diagnostics().getFirst().code().equals("KCG-CHECK-LIMIT-004"));
        var over = batch(at+" \n"+row); assertEquals(2,over.size()); assertEquals("KCG-CHECK-LIMIT-004",over.getFirst().diagnostics().getFirst().code()); assertEquals("x",over.getLast().id());
    }
    @Test void batchLimitProducesOneErrorForEachExtraRowWithoutCompilingIt() throws Exception {
        var counts = new int[2];
        String lines = "{}\n".repeat(SirValidationApplication.MAX_BATCH_LINES+2);
        var summary = new SirValidationApplication().checkBatch(new SirValidationBatchRequest(new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)), r -> {
            counts[0]++; if (r.diagnostics().getFirst().code().equals("KCG-CHECK-LIMIT-003")) { counts[1]++; assertNull(r.id()); }
        }));
        assertEquals(10002,summary.lineCount()); assertEquals(10002,counts[0]); assertEquals(2,counts[1]); assertFalse(summary.internalFailure());
    }
    @Test void explicitStopAfterIsStrictAndMissingDefaultsToGeneration() throws Exception {
        String sir = SirValidationApplicationTest.source("campus-market.sir");
        for (String stop : List.of("null","\"GRAPH\"","\"semantic\"","0","[]")) {
            var r = batch("{\"id\":\"x\",\"sir\":"+q(sir)+",\"stopAfter\":"+stop+"}").getFirst();
            assertFalse(r.ok()); assertEquals("x",r.id()); assertEquals("KCG-CHECK-INPUT-005",r.diagnostics().getFirst().code());
        }
        for (var stage : ValidationStopAfter.values()) assertEquals(stage,batch("{\"id\":\"x\",\"sir\":"+q(sir)+",\"stopAfter\":\""+stage+"\"}").getFirst().stage());
        assertEquals(ValidationStopAfter.GENERATION,batch(sample("x",sir)).getFirst().stage());
    }
    @Test void deepInvalidJsonIsBoundedAndSinkFailuresRemainTransportFailures() throws Exception {
        var rs = batch("{\"unknown\":"+"[".repeat(5000)+"0"+"]".repeat(5000)+",\"id\":\"x\"}\n{}");
        assertEquals("KCG-CHECK-LIMIT-005",rs.getFirst().diagnostics().getFirst().code()); assertEquals(2,rs.size());
        assertThrows(IOException.class,()->new SirValidationApplication().checkBatch(new SirValidationBatchRequest(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), r -> { throw new IOException("broken sink"); })));
    }
    @Test void pathologicalSirStackOverflowIsIsolatedAndReportedAsInternal() throws Exception {
        String sir = SirValidationApplicationTest.source("campus-market.sir");
        String deep = sir.replace("return goods;", "return " + "(".repeat(6000) + "goods" + ")".repeat(6000) + ";");
        assertNotEquals(sir, deep);
        var rs = batch(sample("deep",deep)+"\n"+sample("good",sir));
        assertFalse(rs.getFirst().ok()); assertTrue(rs.getFirst().hasInternalFailure()); assertTrue(rs.getLast().ok());
    }
}
