package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A declared failure with status 401 is reported as 401: the status a client sees is the one the SIR declared. */
class GeneratorUnauthorizedStatusContractTest {
    @Test
    void anErrorDeclaredHttp401IsRenderedAsUnauthorized() {
        String source = GeneratorTestSupport.resourceText("valid/guarded-update.sir")
                .replace("error Invalid http 409;", "error Invalid http 401;");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccessFromText(source);

        String exception = GeneratorTestSupport.contentEndingWith(files, "/InvalidException.java");
        assertTrue(exception.contains("super(\"Invalid\", HttpStatus.UNAUTHORIZED);"), exception);
    }
}
