package io.kcg.sir.change.edit;

import io.kcg.sir.semantic.schema.StructNode;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of asking what declarations a piece of SIR text would add to a software: the declarations,
 * ready to be sent as {@code add} commands, or a refusal with the reasons.
 */
public sealed interface ScaffoldResult permits ScaffoldResult.Scaffolded, ScaffoldResult.Refused {
   /**
    * @param softwareName   the software the declarations are for
    * @param baselineSha256 the digest of the source set they were checked against; a batch must carry it
    * @param declarations   the declarations that are new, in the order the text wrote them
    */
   record Scaffolded(String softwareName, String baselineSha256, List<StructNode> declarations) implements ScaffoldResult {
      public Scaffolded {
         Objects.requireNonNull(softwareName, "softwareName");
         Objects.requireNonNull(baselineSha256, "baselineSha256");
         declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
      }
   }

   /** Nothing was produced; the sources are untouched. {@code details} are the analysis' own findings. */
   record Refused(String message, List<String> details) implements ScaffoldResult {
      public Refused {
         Objects.requireNonNull(message, "message");
         details = List.copyOf(Objects.requireNonNull(details, "details"));
      }
   }
}
