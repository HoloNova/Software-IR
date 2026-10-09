package io.kcg.sir.application.api;

import io.kcg.sir.generator.springboot.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SirValidationFaultTest {
    static String source() throws Exception {
        try(var in=SirValidationFaultTest.class.getResourceAsStream("/valid/campus-market.sir")) {return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
    }
    @Test void generationFailureIsNotInternalAndOriginalMessageIsPreserved() throws Exception {
        var original=GenerationDiagnostic.of("GEN-TEST-001","original error text");
        var app=new SirValidationApplication(m->new GenerationResult.Failure(List.of(original)));
        var result=app.check(new SirValidationRequest("x",source()));assertFalse(result.ok());assertFalse(result.hasInternalFailure());
        assertEquals(ValidationStopAfter.GENERATION,result.stage());assertEquals(original.code(),result.diagnostics().getLast().code());assertEquals(original.message(),result.diagnostics().getLast().message());
        assertEquals(0,result.fileCount());assertNull(result.digest());
    }
    @Test void unexpectedRuntimeFaultStaysInItsRowAndDoesNotLeakExceptionText() throws Exception {
        var calls=new AtomicInteger();var app=new SirValidationApplication(m->{if(calls.getAndIncrement()==0)throw new IllegalStateException("secret /absolute/path");return new SpringBootGenerator().generate(m);});
        String quoted=source().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
        String row="{\"id\":\"x\",\"sir\":\""+quoted+"\"}\n";var results=new ArrayList<SirValidationResult>();
        var summary=app.checkBatch(new SirValidationBatchRequest(new ByteArrayInputStream((row+row).getBytes(StandardCharsets.UTF_8)),results::add));
        assertEquals(2,summary.lineCount());assertTrue(summary.internalFailure());assertTrue(results.getFirst().hasInternalFailure());
        assertEquals(ValidationStopAfter.GENERATION,results.getFirst().stage());assertTrue(results.getLast().ok());assertEquals(2,calls.get());
        assertFalse(results.getFirst().toString().contains("secret"));assertFalse(results.getFirst().toString().contains("absolute"));
    }
    @Test void batchDoesNotCloseCallerOwnedInputOrSink() throws Exception {
        var closed=new boolean[1];var input=new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {@Override public void close(){closed[0]=true;}};
        assertEquals(1,new SirValidationApplication().checkBatch(new SirValidationBatchRequest(input,r->{})).lineCount());assertFalse(closed[0]);
        assertThrows(IOException.class,()->new SirValidationApplication().checkBatch(new SirValidationBatchRequest(new InputStream(){@Override public int read()throws IOException{throw new IOException("broken");}},r->{})));
    }
}
