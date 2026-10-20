package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A reference field stores the identity of the row it refers to, so a row can be loaded from it directly
 * ({@code load Item by line.product}) or through its identity ({@code line.product.id}); both are the key held in the field.
 */
class ReferenceLoadTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private static final String SOURCE = LanguageReferenceExamplesTest.SHELL_HEAD + """
            entity Item persistent {
              identity id: Id generated auto;
              field title: Text;
            }
            entity Line persistent {
              identity id: Id generated auto;
              field product: Ref<Item>;
            }
            view ItemView from Item {
              field id: Id;
              field title: Text;
            }
            input LineRef {
              field id: Id;
            }
            error Missing http 404;
            capability ItemOfLine {
              input LineRef;
              output ItemView;
              fails Missing;
              requires readonly;
              expose query;
              workflow {
                load Line by input.id as line else Missing;
                load Item by line.product as found else Missing;
                return found;
              }
            }
            capability ItemOfLineById {
              input LineRef;
              output ItemView;
              fails Missing;
              requires readonly;
              expose query;
              workflow {
                load Line by input.id as line else Missing;
                load Item by line.product.id as found else Missing;
                return found;
              }
            }
            """ + LanguageReferenceExamplesTest.SHELL_TAIL;

    @Test void aRowIsLoadedFromAReferenceDirectlyOrThroughItsIdentity() {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(ENTRY, Map.of(ENTRY, SOURCE.getBytes(StandardCharsets.UTF_8))),
            model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(compiled.isPresent(), diagnostics::toString);
        for (String service : new String[] {"ItemOfLineService", "ItemOfLineByIdService"}) {
            String java = compiled.get().generatedFiles().stream().filter(f -> f.relativePath().endsWith("/" + service + ".java"))
                .findFirst().orElseThrow().content();
            assertTrue(java.contains("itemMapper.selectById(line.getProductId())"), service + "\n" + java);
            assertEquals(-1, java.indexOf("getProductId().getId()"), service);
        }
    }
}
