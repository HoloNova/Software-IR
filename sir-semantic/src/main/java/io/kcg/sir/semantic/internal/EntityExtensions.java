package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstEntityExtensionDecl;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstImport;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstSourceUnit;
import io.kcg.sir.source.SourceId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives every entity the fields its {@code extend} declarations add, before anything resolves a name.
 *
 * <p>From here on an added field is a field of the entity like those it lists itself: it is resolved, typed, checked,
 * stored and changed the same way. What stays different is only where it is written, so the extension declarations
 * and the ids of the fields they add are kept for the model, which shows the author each field where they wrote it.
 *
 * <p>An extension names its entity like an import would: the entity is declared in the same file or imported into it.
 */
final class EntityExtensions {
   record Result(Map<SourceId, List<AstDeclaration>> declarations, List<AstEntityExtensionDecl> extensions, Set<AstNodeId> addedFields) {
   }

   private EntityExtensions() {
   }

   static Result merge(Map<SourceId, AstSourceUnit> units, Map<SourceId, List<AstDeclaration>> effective, List<Diagnostic> diagnostics) {
      Map<String, AstEntityDecl> entities = new LinkedHashMap<>();
      Map<String, SourceId> entitySources = new LinkedHashMap<>();
      List<AstEntityExtensionDecl> extensions = new ArrayList<>();
      Map<SourceId, List<AstEntityExtensionDecl>> extensionsBySource = new LinkedHashMap<>();
      for (var unit : units.values()) {
         SourceId source = unit.span().source();
         for (AstDeclaration declaration : effective.get(source)) {
            if (declaration instanceof AstEntityDecl entity) {
               entities.putIfAbsent(entity.name().text(), entity); // duplicate declarations are diagnosed by resolution
               entitySources.putIfAbsent(entity.name().text(), source);
            } else if (declaration instanceof AstEntityExtensionDecl extension) {
               extensions.add(extension);
               extensionsBySource.computeIfAbsent(source, s -> new ArrayList<>()).add(extension);
            }
         }
      }

      Map<String, List<AstField>> added = new LinkedHashMap<>();
      Set<AstNodeId> addedFields = new HashSet<>();
      for (var entry : extensionsBySource.entrySet()) {
         SourceId source = entry.getKey();
         for (AstEntityExtensionDecl extension : entry.getValue()) {
            String target = extension.entity().text();
            if (!entities.containsKey(target)) {
               diagnostics.add(DiagnosticBuilder.error("SIR-EXTEND-001", "extend 的目标必须是实体: " + target, extension.entity().span()));
               continue;
            }
            boolean imported = units.get(source).imports().stream().map(AstImport::declaration).anyMatch(d -> d.text().equals(target));
            if (!entitySources.get(target).equals(source) && !imported) {
               diagnostics.add(DiagnosticBuilder.error("SIR-SOURCE-VISIBILITY-001",
                  "reference to entity " + target + " requires an explicit import from " + entitySources.get(target).value(), extension.entity().span()));
               continue;
            }
            added.computeIfAbsent(target, t -> new ArrayList<>()).addAll(extension.fields());
            extension.fields().forEach(field -> addedFields.add(field.id()));
         }
      }

      Map<SourceId, List<AstDeclaration>> merged = new LinkedHashMap<>();
      for (var entry : effective.entrySet()) {
         List<AstDeclaration> out = new ArrayList<>();
         for (AstDeclaration declaration : entry.getValue()) {
            if (declaration instanceof AstEntityExtensionDecl) {
               continue;
            }
            if (declaration instanceof AstEntityDecl entity && added.containsKey(entity.name().text()) && entities.get(entity.name().text()) == entity) {
               List<AstField> fields = new ArrayList<>(entity.fields());
               fields.addAll(added.get(entity.name().text()));
               declaration = new AstEntityDecl(entity.id(), entity.span(), entity.name(), entity.identity(), fields, entity.credential(), entity.scope());
            }
            out.add(declaration);
         }
         merged.put(entry.getKey(), List.copyOf(out));
      }
      return new Result(merged, List.copyOf(extensions), Set.copyOf(addedFields));
   }
}
