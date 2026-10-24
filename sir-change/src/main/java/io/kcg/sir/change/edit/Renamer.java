package io.kcg.sir.change.edit;

import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedBinding;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEntityExtension;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.MemberExpression;
import io.kcg.sir.semantic.model.NormalizedExpression.NameExpression;
import io.kcg.sir.semantic.model.NormalizedFeatureArgument;
import io.kcg.sir.semantic.model.NormalizedFeatureDefinition;
import io.kcg.sir.semantic.model.NormalizedFeatureUse;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.model.NormalizedSemanticTypeDeclaration;
import io.kcg.sir.semantic.model.NormalizedView;
import io.kcg.sir.semantic.model.NormalizedViewField;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.semantic.type.SemanticType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Applies the renames of a batch: gives things of the source new names and refers to them by those names everywhere.
 *
 * <p>A thing is found by identity, never by its old name, so a variable or a field elsewhere that happens to be spelled
 * the same is left alone. The model is rewritten (each name a thing is written by, and each written reference whose
 * resolved identity is a renamed thing) and read back into a draft, so references are named exactly as the source writes
 * them. A field of a view or of a patch payload takes its name from the entity field it shows or changes, so it is
 * renamed with it. What a use declaration expanded into is not written by the author: its use's arguments are renamed
 * instead, and it follows when the use expands again. Whether the result is a valid software is, as for every edit,
 * decided by analysing the source written from it.
 */
final class Renamer {
   private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

   private Renamer() {
   }

   static final class Refused extends RuntimeException {
      Refused(String message) {
         super(message);
      }
   }

   static SoftwareDraft apply(NormalizedSemanticModel model, SoftwareDraft draft, List<Command.Rename> renames, DraftEdit edit) {
      Map<SymbolId, String> names = new LinkedHashMap<>();
      Map<SymbolId, String> oldNames = new LinkedHashMap<>();
      for (Command.Rename rename : renames) {
         if (!NAME.matcher(rename.name()).matches()) {
            throw new Refused(rename.target() + ": '" + rename.name() + "' is not a name");
         }

         StructNode node;
         try {
            node = edit.nodeAt(draft, rename.target());
         } catch (IllegalArgumentException e) {
            throw new Refused(e.getMessage());
         }

         SymbolId target = SoftwareDraft.identityOf(node).map(SymbolId::new)
            .orElseThrow(() -> new Refused(rename.target() + ": only something the source already has can be renamed"));
         if (node.find("name").isEmpty()) {
            throw new Refused(rename.target() + ": " + node.kind() + " has no name");
         }

         NormalizedDeclaration owner = owner(model, target);
         if (!model.isWritten(owner)) {
            throw new Refused(rename.target() + ": '" + owner.name() + "' is expanded from a use declaration; rename what the use names instead");
         }

         if (names.put(target, rename.name()) != null) {
            throw new Refused(rename.target() + ": renamed twice in one batch");
         }

         oldNames.put(target, ((StructValue.Text) node.find("name").get().value()).value());
         // The fields that take their name from this entity field are renamed with it.
         for (SymbolId follower : followers(model, target)) {
            names.put(follower, rename.name());
         }
      }


      List<NormalizedDeclaration> declarations = new ArrayList<>();
      for (NormalizedDeclaration declaration : model.declarations()) {
         declarations.add(declaration instanceof NormalizedFeatureUse use
            ? (NormalizedDeclaration) rename(use, names, oldNames)
            : (NormalizedDeclaration) rewrite(declaration, names, oldNames));
      }

      NormalizedSemanticModel renamed = withDeclarations(model, declarations);
      SoftwareDraft result = SoftwareDraft.of(renamed, id -> Optional.ofNullable(names.get(id)));
      // What a use expanded into stays as it was; it is not written back, and the use expands it again.
      Map<String, StructNode> original = new LinkedHashMap<>();
      draft.declarations().forEach(node -> SoftwareDraft.identityOf(node).ifPresent(id -> original.put(id, node)));
      List<StructValue> kept = new ArrayList<>();
      for (StructNode node : result.declarations()) {
         String id = SoftwareDraft.identityOf(node).orElseThrow();
         boolean expanded = model.expansions().containsKey(new SymbolId(id));
         kept.add(new StructValue.Child(expanded ? original.get(id) : node));
      }

      return draft.withRoot(result.root().withSlot(SoftwareDraft.DECLARATIONS, new StructValue.Seq(kept)));
   }

   /** The declaration that is or holds the thing. */
   private static NormalizedDeclaration owner(NormalizedSemanticModel model, SymbolId thing) {
      for (NormalizedDeclaration declaration : model.declarations()) {
         if (holds(declaration, thing)) {
            return declaration;
         }
      }

      throw new Refused("nothing in the software has the identity " + thing.value());
   }

   private static boolean holds(Object value, SymbolId thing) {
      return switch (value) {
         case Record record -> {
            if (thing.equals(component(record, "id"))) {
               yield true;
            }

            for (RecordComponent component : record.getClass().getRecordComponents()) {
               Object part = read(component, record);
               if (part instanceof List<?> || part instanceof Record) {
                  if (holds(part, thing)) {
                     yield true;
                  }
               }
            }

            yield false;
         }
         case List<?> list -> list.stream().anyMatch(element -> holds(element, thing));
         default -> false;
      };
   }

    /** View fields that show an entity field, and patch payload fields that change it. */
    private static List<SymbolId> followers(NormalizedSemanticModel model, SymbolId field) {
       List<SymbolId> followers = new ArrayList<>();
       for (NormalizedDeclaration declaration : model.declarations()) {
          if (declaration instanceof NormalizedView view) {
             view.fields().stream().filter(Renamer::showsAColumn)
                    .filter(f -> f.sourceField().equals(field)).map(NormalizedViewField::id).forEach(followers::add);
          } else if (declaration instanceof NormalizedInput input) {
             input.fields().stream().filter(f -> f.patchSourceField().equals(Optional.of(field))).map(NormalizedField::id).forEach(followers::add);
          }
       }

       return followers;
    }

    /** A projection that reads one column of the source row; a computed or counted value is its own output name. */
    private static boolean showsAColumn(NormalizedViewField field) {
       return switch (field.value()) {
          case NormalizedViewField.Column ignored -> true;
          case NormalizedViewField.Related ignored -> true;
          case NormalizedViewField.Computed ignored -> false;
          case NormalizedViewField.CountRelated ignored -> false;
       };
    }

   /** Rename parameter reference ranges by their resolved identities, never by matching another argument's text. */
   private static NormalizedFeatureUse rename(NormalizedFeatureUse use, Map<SymbolId, String> names, Map<SymbolId, String> oldNames) {
      String name = use.name();
      for (var entry : oldNames.entrySet()) {
         if (entry.getValue().equals(use.name()) && isFeatureDefinition(entry.getKey())) {
            name = names.get(entry.getKey());
         }
      }

       List<NormalizedFeatureArgument> arguments = new ArrayList<>();
       for (var argument : use.arguments()) arguments.add(renameArgument(argument, names));
       for (var argument : use.defaults()) {
          if (argument.references().stream().anyMatch(r -> names.containsKey(r.target()))) arguments.add(renameArgument(argument, names));
       }
       return new NormalizedFeatureUse(use.id(), name, use.span(), use.sourceNodeId(), arguments, use.defaults());
    }

    private static NormalizedFeatureArgument renameArgument(NormalizedFeatureArgument argument, Map<SymbolId, String> names) {
       String value = argument.value();
       var references = argument.references().stream().sorted(java.util.Comparator.comparingInt(
               io.kcg.sir.semantic.model.FeatureArgumentReference::start).reversed()).toList();
       for (var reference : references) {
          String replacement = names.get(reference.target());
          if (replacement != null) value = value.substring(0, reference.start()) + replacement + value.substring(reference.end());
       }
       return value.equals(argument.value()) ? argument : new NormalizedFeatureArgument(argument.name(), value);
    }

   private static boolean isFeatureDefinition(SymbolId id) {
      return id.value().contains("/feature-definition/");
   }

   /** The value with each name of a renamed thing, and each written reference to one, replaced by the new name. */
   private static Object rewrite(Object value, Map<SymbolId, String> names, Map<SymbolId, String> oldNames) {
      return switch (value) {
         case SymbolId id -> id;
         case NameExpression name when names.containsKey(name.resolvedSymbol()) ->
            new NameExpression(name.sourceNodeId(), name.span(), name.type(), names.get(name.resolvedSymbol()), name.resolvedSymbol());
         case MemberExpression member when names.containsKey(member.resolvedMember()) ->
            new MemberExpression(member.sourceNodeId(), member.span(), member.type(), (NormalizedExpression) rewrite(member.receiver(), names, oldNames),
               names.get(member.resolvedMember()), member.resolvedMember());
         case NormalizedBinding binding when names.containsKey(binding.fieldSymbol()) ->
            new NormalizedBinding(names.get(binding.fieldSymbol()), binding.fieldSymbol(), (NormalizedExpression) rewrite(binding.value(), names, oldNames));
         case RefType ref when names.containsKey(ref.entityId()) -> new RefType(names.get(ref.entityId()), ref.entityId());
         case DeclaredType declared when names.containsKey(declared.symbolId()) ->
            new DeclaredType(declared.kind(), names.get(declared.symbolId()), declared.symbolId());
         case SemanticType semantic -> renamedType(semantic.name(), names, oldNames).map(n -> new SemanticType(n, semantic.kind())).orElse(semantic);
         case NormalizedSemanticTypeDeclaration type -> {
            NormalizedSemanticTypeDeclaration rewritten = (NormalizedSemanticTypeDeclaration) rebuild(type, names, oldNames);
            yield renamedType(type.base(), names, oldNames)
               .map(base -> new NormalizedSemanticTypeDeclaration(rewritten.id(), rewritten.name(), rewritten.span(), rewritten.sourceNodeId(), base, rewritten.constraints()))
               .orElse(rewritten);
         }
         case NormalizedEntityExtension extension when names.containsKey(extension.entity()) -> {
            NormalizedEntityExtension rewritten = (NormalizedEntityExtension) rebuild(extension, names, oldNames);
            yield new NormalizedEntityExtension(rewritten.id(), names.get(extension.entity()), rewritten.span(), rewritten.sourceNodeId(),
               rewritten.entity(), rewritten.fields());
         }
         case NormalizedFeatureDefinition definition -> rebuild(definition, names, oldNames);
         case Record record -> rebuild(record, names, oldNames);
         case List<?> list -> list.stream().map(element -> rewrite(element, names, oldNames)).toList();
         case Optional<?> optional -> optional.map(element -> rewrite(element, names, oldNames));
         default -> value;
      };
   }

   /** A semantic type is named, not resolved: a renamed type declaration is found by its old name. */
   private static Optional<String> renamedType(String name, Map<SymbolId, String> names, Map<SymbolId, String> oldNames) {
      for (var entry : oldNames.entrySet()) {
         if (entry.getValue().equals(name) && entry.getKey().value().contains("/type/")) {
            return Optional.of(names.get(entry.getKey()));
         }
      }

      return Optional.empty();
   }

   /** The record with its parts rewritten, and its own name replaced when it is a renamed thing. */
   private static Object rebuild(Record record, Map<SymbolId, String> names, Map<SymbolId, String> oldNames) {
      RecordComponent[] components = record.getClass().getRecordComponents();
      Object[] values = new Object[components.length];
      Class<?>[] types = new Class<?>[components.length];
      Object id = component(record, "id");
      for (int i = 0; i < components.length; i++) {
         types[i] = components[i].getType();
         Object part = read(components[i], record);
         values[i] = components[i].getName().equals("name") && id instanceof SymbolId symbol && names.containsKey(symbol)
            ? names.get(symbol)
            : rewrite(part, names, oldNames);
      }

      try {
         var constructor = record.getClass().getDeclaredConstructor(types);
         constructor.setAccessible(true);
         return constructor.newInstance(values);
      } catch (ReflectiveOperationException e) {
         throw new IllegalStateException("cannot rebuild " + record.getClass().getName(), e);
      }
   }

   private static NormalizedSemanticModel withDeclarations(NormalizedSemanticModel model, List<NormalizedDeclaration> declarations) {
      RecordComponent[] components = NormalizedSemanticModel.class.getRecordComponents();
      Object[] values = new Object[components.length];
      Class<?>[] types = new Class<?>[components.length];
      for (int i = 0; i < components.length; i++) {
         types[i] = components[i].getType();
         values[i] = components[i].getName().equals("declarations") ? declarations : read(components[i], model);
      }

      try {
         return NormalizedSemanticModel.class.getDeclaredConstructor(types).newInstance(values);
      } catch (ReflectiveOperationException e) {
         throw new IllegalStateException("cannot rebuild the model", e);
      }
   }

   private static Object component(Record record, String name) {
      for (RecordComponent component : record.getClass().getRecordComponents()) {
         if (component.getName().equals(name)) {
            return read(component, record);
         }
      }

      return null;
   }

   private static Object read(RecordComponent component, Record record) {
      try {
         var accessor = component.getAccessor();
         accessor.setAccessible(true);
         return accessor.invoke(record);
      } catch (ReflectiveOperationException e) {
         throw new IllegalStateException("cannot read " + record.getClass().getName() + "." + component.getName(), e);
      }
   }

}
