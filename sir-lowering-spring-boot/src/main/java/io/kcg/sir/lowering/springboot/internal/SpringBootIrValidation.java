package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.lowering.api.LoweredIrVersion;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.model.ActorBinding;
import io.kcg.sir.lowering.springboot.model.ActorIdentityRequirement;
import io.kcg.sir.lowering.springboot.model.ActorIdentityTransportPlan;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.lowering.springboot.model.TransportPlan;
import io.kcg.sir.lowering.springboot.profile.SpringBootQueryPolicy;
import io.kcg.sir.lowering.springboot.profile.SpringBootQueryPolicy;
import io.kcg.sir.lowering.springboot.profile.SpringBootTargetProfile;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SpringBootIrValidation {
   private final SpringBootLoweredModel model;
   private final List<LoweringDiagnostic> diagnostics = new ArrayList<>();
   private final Set<LoweredNodeId> nodeIds = new HashSet<>();
   private final Map<SymbolId, SpringBootDeclaration> declarations = new LinkedHashMap<>();
   private final Set<SymbolId> knownSymbols = new LinkedHashSet<>();

   public SpringBootIrValidation(SpringBootLoweredModel model) {
      this.model = model;
   }

   public List<LoweringDiagnostic> validate() {
      SpringBootIrValidation.VersionProfileStatus rootStatus = this.validateRoot();
      if (rootStatus == SpringBootIrValidation.VersionProfileStatus.UNKNOWN) {
         return LoweringDiagnostics.sorted(this.diagnostics);
      }

      if (rootStatus == SpringBootIrValidation.VersionProfileStatus.OK) {
         this.validateTransportPlanMatrix();
      }

      this.validateProjectArtifacts();
      this.indexDeclarations();

      for (SpringBootDeclaration declaration : this.model.declarations()) {
         this.validateDeclaration(declaration);
      }

      this.validateArtifacts();
      return LoweringDiagnostics.sorted(this.diagnostics);
   }

   private void validateProjectArtifacts() {
      ProjectArtifact.MavenProject pom = this.model.mavenProject();
      this.addNode(pom.id(), pom.origin(), "maven-project");
      if (!pom.path().equals("pom.xml")) {
         this.error(pom.origin(), "MavenProject path must be 'pom.xml': " + pom.path());
      }

      if (pom.javaVersion() != SpringBootTargetProfile.V0_2.javaVersion()) {
         this.error(pom.origin(), "MavenProject javaVersion must be " + SpringBootTargetProfile.V0_2.javaVersion() + ": " + pom.javaVersion());
      }

      if (!pom.springBootParentVersion().equals(SpringBootTargetProfile.V0_2.springBootVersion())) {
         this.error(
            pom.origin(),
            "MavenProject springBootParentVersion must be " + SpringBootTargetProfile.V0_2.springBootVersion() + ": " + pom.springBootParentVersion()
         );
      }

      if (pom.dependencies().isEmpty()) {
         this.error(pom.origin(), "MavenProject must declare at least one dependency");
      }

      if (pom.plugins().isEmpty()) {
         this.error(pom.origin(), "MavenProject must declare at least one plugin");
      }

      ProjectArtifact.ApplicationMain app = this.model.applicationMain();
      this.addNode(app.id(), app.origin(), "application-main");
      if (!app.path().startsWith("src/main/java/") || !app.path().endsWith(".java")) {
         this.error(app.origin(), "ApplicationMain path must be a Java source path: " + app.path());
      }

      if (!app.packageName().equals(this.model.basePackage())) {
         this.error(app.origin(), "ApplicationMain packageName must match basePackage: " + app.packageName() + " vs " + this.model.basePackage());
      }

      if (!app.simpleName().equals("Application")) {
         this.error(app.origin(), "ApplicationMain simpleName must be 'Application': " + app.simpleName());
      }

      if (!app.mapperScanPackage().equals(this.model.basePackage() + ".persistence")) {
         this.error(app.origin(), "ApplicationMain mapperScanPackage must be '<basePackage>.persistence': " + app.mapperScanPackage());
      }

      this.validatePaginationSupport(app);
   }

   /**
   * The page envelope has to appear exactly when a capability pages, and its identity must match the
   * package the generator writes it to.
   */
   private void validatePaginationSupport(ProjectArtifact.ApplicationMain app) {
      List<ProjectArtifact.PageResponse> pages = this.model.projectArtifacts().stream()
         .filter(ProjectArtifact.PageResponse.class::isInstance)
         .map(ProjectArtifact.PageResponse.class::cast)
         .toList();
      boolean pagesQuery = this.model.declarations().stream()
         .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
         .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
         .flatMap(capability -> SpringBootWorkflow.walk(capability.workflow().steps()))
         .anyMatch(step -> step instanceof SpringBootWorkflow.GroupedSumStep
                || step instanceof SpringBootWorkflow.FindStep find && find.page().isPresent());

      for (ProjectArtifact artifact : this.model.projectArtifacts()) {
         this.addNode(artifact.id(), artifact.origin(), "project artifact");
      }

      for (ProjectArtifact.PageResponse page : pages) {
         if (!page.path().startsWith("src/main/java/") || !page.path().endsWith(".java")) {
         this.error(page.origin(), "PageResponse path must be a Java source path: " + page.path());
         }

         String expectedPackage = this.model.basePackage() + ".api";
         if (!page.packageName().equals(expectedPackage)) {
         this.error(page.origin(), "PageResponse packageName must be '" + expectedPackage + "': " + page.packageName());
         }

         String expectedPath = "src/main/java/" + expectedPackage.replace('.', '/') + "/" + page.simpleName() + ".java";
         if (!page.path().equals(expectedPath)) {
         this.error(page.origin(), "PageResponse path must be '" + expectedPath + "': " + page.path());
         }
      }

      if (pagesQuery && pages.size() != 1) {
         this.error(app.origin(), "pagination requires exactly one PageResponse project artifact: " + pages.size());
      }

      if (!pagesQuery && !pages.isEmpty()) {
         this.error(app.origin(), "PageResponse is present but no capability declares a paged find");
      }
   }

   private SpringBootQueryPolicy queryPolicy() {
      return SpringBootQueryPolicy.V0_1;
   }

   private SpringBootIrValidation.VersionProfileStatus validateRoot() {
      LoweredIrVersion ir = this.model.irVersion();
      SpringBootTargetProfile profile = this.model.profile();
      boolean irKnown = LoweredIrVersion.V0_2.equals(ir);
      boolean profileKnown = SpringBootTargetProfile.V0_2.equals(profile);
      if (!irKnown) {
         this.error(this.model.applicationMain().origin(), "unsupported Lowered IR version: " + ir.value());
         return SpringBootIrValidation.VersionProfileStatus.UNKNOWN;
      } else if (!profileKnown) {
         this.error(this.model.applicationMain().origin(), "unsupported Spring Boot target profile: " + profile.id());
         return SpringBootIrValidation.VersionProfileStatus.UNKNOWN;
      } else {
         return SpringBootIrValidation.VersionProfileStatus.OK;
      }
   }

   private void validateTransportPlanMatrix() {
      Optional<ActorIdentityTransportPlan> planOpt = this.model.applicationMain().actorIdentityTransportPlan();
      long actorCount = this.model
         .declarations()
         .stream()
         .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
         .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
         .filter(cap -> cap.actor().isPresent())
         .count();
      if (actorCount == 0L) {
         if (planOpt.isPresent()) {
            this.error(this.model.applicationMain().origin(), "V0_2 Lowered IR with no actor capability must not carry an ActorIdentityTransportPlan");
         }
      } else if (planOpt.isEmpty()) {
         this.error(
            this.model.applicationMain().origin(), "V0_2 Lowered IR with " + actorCount + " actor capability(ies) must carry an ActorIdentityTransportPlan"
         );
      } else {
         this.validateTransportPlanExactness(planOpt.get(), actorCount);
      }
   }

   private void validateTransportPlanExactness(ActorIdentityTransportPlan plan, long expectedActorCount) {
      if (plan.policy() != ActorIdentityTransportPlan.Policy.EXPLICIT_EXTERNAL_OR_LOCAL_FIXED) {
         this.error(this.model.applicationMain().origin(), "ActorIdentityTransportPlan policy must be EXPLICIT_EXTERNAL_OR_LOCAL_FIXED: " + plan.policy());
      } else if (plan.modeProperty().equals("kcg.actor-identity.mode")
         && plan.externalAdapterBeanName().equals("kcgActorIdentityTransport")
         && plan.localProfile().equals("kcg-actor-local")
         && plan.localIdentityProperty().equals("kcg.actor-identity.local.id")) {
         List<ActorIdentityRequirement> requirements = plan.requirements();
         if (requirements.size() != expectedActorCount) {
            this.error(
               this.model.applicationMain().origin(),
               "ActorIdentityTransportPlan requirement count " + requirements.size() + " does not match actor capability count " + expectedActorCount
            );
         } else {
            Map<SymbolId, SpringBootDeclaration.EntityDeclaration> entitiesBySymbol = new LinkedHashMap<>();

            for (SpringBootDeclaration declaration : this.model.declarations()) {
               if (declaration instanceof SpringBootDeclaration.EntityDeclaration entity) {
                  entitiesBySymbol.put(entity.sourceSymbol(), entity);
               }
            }

            int requirementIndex = 0;
            Set<String> seenMethodRoute = new HashSet<>();

            for (SpringBootDeclaration declaration : this.model.declarations()) {
               if (declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability && !capability.actor().isEmpty()) {
                  if (requirementIndex >= requirements.size()) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan is missing a requirement for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  ActorIdentityRequirement requirement = requirements.get(requirementIndex);
                  String methodRoute = requirement.httpMethod().name() + " " + requirement.route();
                  if (!seenMethodRoute.add(methodRoute)) {
                     this.error(this.model.applicationMain().origin(), "ActorIdentityTransportPlan contains a duplicate (httpMethod, route): " + methodRoute);
                     return;
                  }

                  if (!requirement.capabilitySymbol().equals(capability.sourceSymbol())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement at index " + requirementIndex + " does not reference capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (capability.actorBinding().isEmpty()) {
                     this.error(this.model.applicationMain().origin(), "actor capability is missing its actorBinding: " + capability.sourceSymbol());
                     return;
                  }

                  ActorBinding binding = capability.actorBinding().get();
                  if (!requirement.attributeName().equals(binding.attributeName())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement attributeName does not match actorBinding for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (!requirement.identityStorageType().equals(binding.identityStorageType())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement identityStorageType does not match actorBinding for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (!requirement.httpMethod().equals(capability.httpMethod())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement httpMethod does not match capability for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (!requirement.route().equals(capability.route())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement route does not match capability for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  SpringBootWorkflow.Variable actorVar = capability.actor().get();

                  SymbolId expectedEntitySymbol = switch (actorVar.type()) {
                     case LoweredJavaType.Declared declared when declared.kind() == LoweredJavaType.DeclaredKind.ENTITY -> declared.symbolId();
                     case LoweredJavaType.EntityReference reference -> reference.entitySymbol();
                     default -> null;
                  };
                  if (expectedEntitySymbol == null || !requirement.actorEntitySymbol().equals(expectedEntitySymbol)) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement actorEntitySymbol does not match actor variable for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  SpringBootDeclaration.EntityDeclaration actorEntity = entitiesBySymbol.get(requirement.actorEntitySymbol());
                  if (actorEntity == null) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement actorEntitySymbol does not reference a lowered EntityDeclaration for capability "
                           + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (!actorEntity.javaName().equals(requirement.actorEntityJavaName())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement actorEntityJavaName '"
                           + requirement.actorEntityJavaName()
                           + "' does not match the typed EntityDeclaration.javaName() '"
                           + actorEntity.javaName()
                           + "' for actor entity "
                           + requirement.actorEntitySymbol()
                           + " on capability "
                           + capability.sourceSymbol()
                     );
                     return;
                  }

                  requirementIndex++;
               }
            }

            if (requirementIndex != requirements.size()) {
               this.error(
                  this.model.applicationMain().origin(),
                  "ActorIdentityTransportPlan contains " + (requirements.size() - requirementIndex) + " extra requirement(s) beyond the actor capability count"
               );
            }
         }
      } else {
         this.error(this.model.applicationMain().origin(), "ActorIdentityTransportPlan carries a frozen constant that does not match the v0.2 contract");
      }
   }

   private void indexDeclarations() {
      Set<String> javaNames = new HashSet<>();

      for (SpringBootDeclaration declaration : this.model.declarations()) {
         this.addNode(declaration.id(), declaration.origin(), "declaration");
         if (this.declarations.putIfAbsent(declaration.sourceSymbol(), declaration) != null) {
            this.error(declaration, "duplicate declaration source SymbolId: " + declaration.sourceSymbol());
         }

         if (!javaNames.add(declaration.javaName())) {
            this.error(declaration, "duplicate declaration Java name: " + declaration.javaName());
         }

         this.knownSymbols.add(declaration.sourceSymbol());
         this.indexNestedSymbols(declaration);
      }
   }

   private void indexNestedSymbols(SpringBootDeclaration declaration) {
      switch (declaration) {
         case SpringBootDeclaration.EnumDeclaration value:
            value.members().forEach(member -> this.knownSymbols.add(member.sourceSymbol()));
            break;
         case SpringBootDeclaration.EntityDeclaration value:
            this.knownSymbols.add(value.identity().sourceSymbol());
            value.fields().forEach(field -> this.knownSymbols.add(field.sourceSymbol()));
            break;
         case SpringBootDeclaration.InputDeclaration value:
            value.fields().forEach(field -> this.knownSymbols.add(field.sourceSymbol()));
            break;
         case SpringBootDeclaration.ErrorDeclaration ignored:
            break;
         case SpringBootDeclaration.ViewDeclaration value:
         value.sourceRow().ifPresent(row -> this.knownSymbols.add(row.symbol()));
         value.fields().forEach(field -> {
               this.knownSymbols.add(field.sourceSymbol());
               if (!(field.value() instanceof SpringBootDeclaration.ComputedViewValue)
                    && !(field.value() instanceof SpringBootDeclaration.CountViewValue))
                   this.knownSymbols.add(field.sourceFieldSymbol());
         });
         break;
         case SpringBootDeclaration.CapabilityDeclaration value:
            value.actor().ifPresent(variable -> this.knownSymbols.add(variable.symbol()));
            value.input().ifPresent(variable -> this.knownSymbols.add(variable.symbol()));
            value.workflow().variables().forEach(variable -> this.knownSymbols.add(variable.symbol()));
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateDeclaration(SpringBootDeclaration declaration) {
      switch (declaration) {
         case SpringBootDeclaration.EnumDeclaration value:
            for (SpringBootDeclaration.EnumMember member : value.members()) {
               this.addNode(member.id(), member.origin(), "enum member");
               this.knownSymbols.add(member.sourceSymbol());
            }
            break;
         case SpringBootDeclaration.EntityDeclaration value:
            this.addNode(value.identity().id(), value.identity().origin(), "identity");
            this.knownSymbols.add(value.identity().sourceSymbol());
            this.validateType(value.identity().type(), value);

            for (SpringBootDeclaration.Property field : value.fields()) {
               this.validateProperty(field, value);
            }

            this.validateEntityVersion(value);
            break;
         case SpringBootDeclaration.InputDeclaration value:
            for (SpringBootDeclaration.Property field : value.fields()) {
               this.validateProperty(field, value);
            }

            this.validatePatchSpec(value);
            break;
         case SpringBootDeclaration.ViewDeclaration value:
         this.validateView(value);
         break;
         case SpringBootDeclaration.ErrorDeclaration ignored:
            break;
         case SpringBootDeclaration.CapabilityDeclaration value:
            this.validateCapability(value);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   /**
   * The version spec must point at a declared field of its own entity and carry an integer type.
   *
   * <p>The version column is written by the target's conditional update, so an entity that declares a
   * version but no matching field would leave that update with nothing to compare against.
   */
   private void validateEntityVersion(SpringBootDeclaration.EntityDeclaration entity) {
      if (entity.version().isEmpty()) {
         return;
      }

      SpringBootDeclaration.VersionSpec version = entity.version().orElseThrow();
      this.addNode(version.id(), version.origin(), "version");
      this.knownSymbols.add(version.fieldSymbol());
      this.validateType(version.type(), entity);
      SpringBootDeclaration.Property field = entity.fields().stream()
         .filter(property -> property.sourceSymbol().equals(version.fieldSymbol()))
         .findFirst()
         .orElse(null);
      if (field == null) {
         this.error(entity, "the version field must be a declared field of " + entity.javaName());
         return;
      }

      if (!version.javaName().equals(field.javaName()) || !version.columnName().equals(field.columnName().orElse(null))) {
         this.error(entity, "the version spec must name its own field and column: " + version.fieldSymbol());
      }
   }

   /**
   * A patch payload's envelope must match the entity: the identity, and a change set that covers
   * exactly the payload fields outside it.
   */
   private void validatePatchSpec(SpringBootDeclaration.InputDeclaration input) {
      if (input.patch().isEmpty()) {
         return;
      }

      SpringBootDeclaration.PatchSpec patch = input.patch().orElseThrow();
      this.requireDeclaration(patch.sourceEntitySymbol(), SpringBootDeclaration.EntityDeclaration.class, input);
      SpringBootDeclaration.EntityDeclaration entity = this.declarations.get(patch.sourceEntitySymbol())
         instanceof SpringBootDeclaration.EntityDeclaration value ? value : null;

      if (input.fields().stream().noneMatch(field -> field.sourceSymbol().equals(patch.identityFieldSymbol()))) {
         this.error(input, "the patch identity field must be a declared payload field: " + patch.identityFieldSymbol());
      }

      if (entity != null && !entity.identity().sourceSymbol().equals(
            entity.fields().stream()
               .filter(field -> field.javaName().equals(patch.identityPropertyName()))
               .map(SpringBootDeclaration.Property::sourceSymbol)
               .findFirst()
               .orElse(entity.identity().sourceSymbol()))) {
         this.error(input, "the patch identity must name the entity's own identity: " + patch.identityPropertyName());
      }

      List<SymbolId> payloadFields = input.fields().stream().map(SpringBootDeclaration.Property::sourceSymbol).toList();
      List<SymbolId> changeFields = patch.changes().stream().map(SpringBootDeclaration.PatchChange::payloadFieldSymbol).toList();
      if (changeFields.stream().anyMatch(field -> !payloadFields.contains(field))) {
         this.error(input, "every patch change must be a declared payload field");
      }

      if (new java.util.LinkedHashSet<>(changeFields).size() != changeFields.size()) {
         this.error(input, "a patch change must appear once per payload field");
      }

      if (payloadFields.size() != changeFields.size() + 1) {
         this.error(input, "the change set must be exactly the payload fields outside its identity");
      }

      for (SpringBootDeclaration.PatchChange change : patch.changes()) {
         this.knownSymbols.add(change.payloadFieldSymbol());
         this.knownSymbols.add(change.entityFieldSymbol());
         if (entity != null && entity.fields().stream().noneMatch(field -> field.sourceSymbol().equals(change.entityFieldSymbol()))) {
            this.error(input, "a patch change must name a field of its entity: " + change.entityFieldSymbol());
         }
      }
   }

   /**
   * A view must project an entity, and each projected field must carry the very type of the entity
   * field it reads.
   */
   private void validateView(SpringBootDeclaration.ViewDeclaration view) {
      this.requireDeclaration(view.sourceEntitySymbol(), SpringBootDeclaration.EntityDeclaration.class, view);
      SpringBootDeclaration.EntityDeclaration entity = this.declarations.get(view.sourceEntitySymbol()) instanceof SpringBootDeclaration.EntityDeclaration value
         ? value
         : null;
      if (entity != null && !entity.javaName().equals(view.sourceEntityJavaName())) {
         this.error(view, "view source entity Java name must match its entity: " + view.sourceEntityJavaName());
      }

      for (SpringBootDeclaration.ViewField field : view.fields()) {
         this.addNode(field.id(), field.origin(), "view field");
         this.knownSymbols.add(field.sourceSymbol());
         if (!(field.value() instanceof SpringBootDeclaration.ComputedViewValue)
                    && !(field.value() instanceof SpringBootDeclaration.CountViewValue))
                   this.knownSymbols.add(field.sourceFieldSymbol());
         this.validateType(field.type(), view);
          if (field.value() instanceof SpringBootDeclaration.CountViewValue counted) {
             this.addNode(counted.plan().id(),counted.plan().origin(),"related count plan");
             for(String problem:RelatedCountValidation.target(view,field,counted.plan(),this.model))this.error(view,problem);
             continue;
          }
          if (field.value() instanceof SpringBootDeclaration.ComputedViewValue computed) {
            this.validateExpression(computed.expression(), view);
            for (String problem : ViewExpressionValidation.validate(view, field, computed.expression(), this.declarations))
               this.error(view, problem);
            continue;
         }
         if (field.relation().isPresent()) {
            this.validateViewRelation(view, field, entity);
            continue;
         }

         if (entity == null) {
         continue;
         }

         LoweredJavaType sourceType = this.entityFieldType(entity, field.sourceFieldSymbol());
         if (sourceType == null) {
         this.error(view, "view field does not project a field of " + entity.javaName() + ": " + field.sourceFieldSymbol());
         } else if (!sourceType.equals(field.type())) {
         this.error(view, "view field type must equal its source entity field type: " + field.javaName());
         }
      }
   }

   /**
   * A relation projection must name the two declarations it joins and the properties the batch read
   * uses, and those properties must exist on the sides the plan says they do.
   *
   * <p>The type rule of a column projection deliberately does not apply here: a relation reads a
   * reference, and the view it produces is a different type entirely.
   */
   private void validateViewRelation(
      SpringBootDeclaration.ViewDeclaration view, SpringBootDeclaration.ViewField field, SpringBootDeclaration.EntityDeclaration entity) {
      SpringBootDeclaration.ViewRelationPlan plan = field.relation().orElseThrow();
      this.addNode(plan.id(), plan.origin(), "view relation plan");
      LoweredJavaType element = field.type() instanceof LoweredJavaType.ListValue list ? list.elementType() : field.type();
      boolean projectsThePlannedView = element instanceof LoweredJavaType.Declared declared
         && declared.kind() == LoweredJavaType.DeclaredKind.VIEW
         && declared.symbolId().equals(plan.targetViewSymbol());
      if (!projectsThePlannedView) {
         this.error(view, "a relation must project the view its plan reads: " + field.javaName() + " -> " + field.type());
      }
      SpringBootDeclaration.ViewDeclaration target = this.declarations.get(plan.targetViewSymbol()) instanceof SpringBootDeclaration.ViewDeclaration value
         ? value
         : null;
      this.requireDeclaration(plan.targetViewSymbol(), SpringBootDeclaration.ViewDeclaration.class, view);
      SpringBootDeclaration.EntityDeclaration targetEntity = this.declarations.get(plan.targetEntitySymbol()) instanceof SpringBootDeclaration.EntityDeclaration value
         ? value
         : null;
      this.requireDeclaration(plan.targetEntitySymbol(), SpringBootDeclaration.EntityDeclaration.class, view);
      if (target != null && target.sourceEntitySymbol() != plan.targetEntitySymbol()) {
         this.error(view, "a relation must read the entity its target view projects: " + field.javaName());
      }

      if (entity == null || targetEntity == null) {
         return;
      }

      if (plan.cardinality() == SpringBootDeclaration.RelationCardinality.TO_MANY) {
         if (!this.hasProperty(targetEntity, plan.targetLookupPropertyName())) {
            this.error(view, "a collection relation must key on a property of the related entity: " + plan.targetLookupPropertyName());
         }

         if (!this.hasProperty(entity, plan.sourceKeyPropertyName())) {
            this.error(view, "a collection relation must collect its keys from the projecting entity: " + plan.sourceKeyPropertyName());
         }

         if (plan.orderPropertyName().isEmpty() || !this.hasProperty(targetEntity, plan.orderPropertyName().get())) {
            this.error(view, "a collection relation must state the identity it is ordered by: " + field.javaName());
         }
      } else {
         if (!this.hasProperty(targetEntity, plan.targetLookupPropertyName())) {
            this.error(view, "a single-row relation must look the related row up by its own property: " + plan.targetLookupPropertyName());
         }

         if (!this.hasProperty(entity, plan.sourceKeyPropertyName())) {
            this.error(view, "a single-row relation must read its reference from the projecting entity: " + plan.sourceKeyPropertyName());
         }

         if (plan.orderPropertyName().isPresent()) {
            this.error(view, "a single-row relation is not ordered: " + field.javaName());
         }
      }

      if (!plan.indexKeyPropertyName().equals(plan.targetLookupPropertyName())) {
         this.error(view, "a relation is indexed by the property it was read with: " + field.javaName());
      }
   }

   private boolean hasProperty(SpringBootDeclaration.EntityDeclaration entity, String javaName) {
      if (entity.identity().javaName().equals(javaName)) {
         return true;
      }

      return entity.fields().stream().anyMatch(property -> property.javaName().equals(javaName));
   }

   /**
   * A load by a field must name a stored field of the very entity it loads, and cannot also lock the
   * row: the lock reads by identity, so asking for both would generate a query that locks nothing.
   */
   private void validateLoadKey(SpringBootWorkflow.LoadStep load, SymbolId key, SpringBootDeclaration.CapabilityDeclaration owner) {
      this.requireKnown(key, owner, "load key");
      if (load.forUpdate()) {
         this.error(owner, "a load by a field cannot be a load for update: " + load.result().symbol());
      }

      if (this.declarations.get(load.entitySymbol()) instanceof SpringBootDeclaration.EntityDeclaration entity
         && entity.fields().stream().noneMatch(property -> property.sourceSymbol().equals(key))) {
         this.error(owner, "load key is not a stored field of the loaded entity: " + key);
      }
   }

   private LoweredJavaType entityFieldType(SpringBootDeclaration.EntityDeclaration entity, SymbolId fieldSymbol) {
      if (entity.identity().sourceSymbol().equals(fieldSymbol)) {
         return entity.identity().type();
      }

      for (SpringBootDeclaration.Property property : entity.fields()) {
         if (property.sourceSymbol().equals(fieldSymbol)) {
         return property.type();
         }
      }

      return null;
   }

   private void validateProperty(SpringBootDeclaration.Property property, SpringBootDeclaration owner) {
      this.addNode(property.id(), property.origin(), "property");
      this.knownSymbols.add(property.sourceSymbol());
      this.validateType(property.type(), owner);

      for (SpringBootDeclaration.Constraint constraint : property.constraints()) {
         this.addNode(constraint.id(), constraint.origin(), "constraint");
      }
   }

    private void validateCapability(SpringBootDeclaration.CapabilityDeclaration capability) {
       for(String problem:RelatedCountValidation.targetOutput(capability,this.model))this.error(capability,problem);
      capability.actor().ifPresent(variable -> this.knownSymbols.add(variable.symbol()));
      capability.input().ifPresent(variable -> this.knownSymbols.add(variable.symbol()));

      for (SpringBootWorkflow.Variable variable : capability.workflow().variables()) {
         this.knownSymbols.add(variable.symbol());
         this.validateType(variable.type(), capability);
      }

      this.validateType(capability.outputType(), capability);

      for (SymbolId failure : capability.failures()) {
         if (!(this.declarations.get(failure) instanceof SpringBootDeclaration.ErrorDeclaration)) {
            this.error(capability, "capability failure does not reference a lowered Error: " + failure);
         }
      }

      if (capability.kind() == SpringBootDeclaration.CapabilityKind.QUERY
         && (capability.httpMethod() != SpringBootDeclaration.HttpMethod.GET || capability.transactionMode() != SpringBootDeclaration.TransactionMode.READ_ONLY
              && capability.transactionMode() != SpringBootDeclaration.TransactionMode.REPEATABLE_READ)
         )
       {
         this.error(capability, "query capability must use GET and a read-only transaction");
      }

      if (capability.kind() == SpringBootDeclaration.CapabilityKind.COMMAND
         && capability.httpMethod() == SpringBootDeclaration.HttpMethod.GET) {
         this.error(capability, "command capability must not use GET");
      }

      // A path's {name} segments are fields of the input the request carries.
      java.util.regex.Matcher variable = java.util.regex.Pattern.compile("\\{([^}]*)}").matcher(capability.route());
      while (variable.find()) {
         String name = variable.group(1);
         boolean known = this.payloadOf(capability).map(input -> input.fields().stream().anyMatch(f -> f.javaName().equals(name))).orElse(false);
         if (!known) {
            this.error(capability, "route variable is not a field of the input: " + name);
         }
      }

      boolean patchPayload = this.payloadOf(capability).flatMap(SpringBootDeclaration.InputDeclaration::patch).isPresent();
      if (patchPayload && capability.httpMethod() != SpringBootDeclaration.HttpMethod.PATCH) {
         this.error(capability, "a capability whose payload patches an entity must use PATCH");
      }

      this.validateActorBinding(capability);
      this.validateTransportPlan(capability);
      this.validateWorkflow(capability);
      this.validateArithmeticInputs(capability);
      this.validatePageDemand(capability);
      this.validateWriteWorkflow(capability);
   }

   /** Required arithmetic inputs are validated against the formal payload and workflow dependencies. */
   private void validateArithmeticInputs(SpringBootDeclaration.CapabilityDeclaration capability) {
      var expected = capability.input().map(input -> ArithmeticInputs.members(capability.workflow(), input.symbol())).orElse(List.of());
      var actual = capability.arithmeticInputs();
      if (expected.size() != actual.size()) this.error(capability, "arithmetic input checks must cover exactly the bound numeric dependencies");
      var payload = this.payloadOf(capability);
      for (int index = 0; index < actual.size(); index++) {
         var plan = actual.get(index);
         this.addNode(plan.id(), plan.origin(), "arithmetic input check");
         this.requireKnown(plan.fieldSymbol(), capability, "arithmetic input field");
         boolean matches = index < expected.size() && capability.input().isPresent()
            && plan.inputSymbol().equals(capability.input().orElseThrow().symbol())
            && plan.fieldSymbol().equals(expected.get(index).resolvedMember())
            && plan.propertyName().equals(expected.get(index).targetMember())
            && plan.origin().equals(expected.get(index).origin())
            && payload.isPresent() && payload.orElseThrow().fields().stream().anyMatch(field ->
               field.sourceSymbol().equals(plan.fieldSymbol()) && field.javaName().equals(plan.fieldPath())
                  && !(field.type() instanceof LoweredJavaType.OptionalValue));
         if (!matches) this.error(capability, "arithmetic input plan does not match its formal INPUT field and origin");
         if (payload.isPresent() && payload.orElseThrow().patch().isPresent()
               && !payload.orElseThrow().patch().orElseThrow().identityFieldSymbol().equals(plan.fieldSymbol())) {
            this.error(capability, "PATCH changes preserve absence and cannot supply required arithmetic operands");
         }
      }
   }

   /**
   * The write slice's invariants: a projection answers with a declared view, and a conditional update
   * only appears for a versioned entity whose payload supplies every assigned column.
   */
   private void validateWriteWorkflow(SpringBootDeclaration.CapabilityDeclaration capability) {
      if (capability.transportPlan().responseRepresentation() == TransportPlan.ResponseRepresentation.PROJECTION
         && !(this.declarations.get(this.declaredTypeSymbol(capability.outputType())) instanceof SpringBootDeclaration.ViewDeclaration)) {
         this.error(capability, "a projected response must declare a view output");
      }

      SpringBootDeclaration.PatchSpec patch = this.payloadOf(capability)
         .flatMap(SpringBootDeclaration.InputDeclaration::patch)
         .orElse(null);
      long conditionalUpdates = 0L;
      for (SpringBootWorkflow.Step step : capability.workflow().steps()) {
         if (!(step instanceof SpringBootWorkflow.UpdateStep update)) {
            if (step instanceof SpringBootWorkflow.LoadStep load && load.explicitLock()) {
                if (!load.forUpdate() || load.keyField().isPresent()
                      || capability.transactionMode() != SpringBootDeclaration.TransactionMode.READ_COMMITTED
                      || capability.httpMethod() == SpringBootDeclaration.HttpMethod.GET)
                   this.error(capability, "explicit lock requires atomic READ_COMMITTED identity load");
                long cachePlans = this.model.projectArtifacts().stream()
                   .filter(ProjectArtifact.ApplicationConfig.class::isInstance)
                   .map(ProjectArtifact.ApplicationConfig.class::cast)
                   .filter(ProjectArtifact.ApplicationConfig::statementScopedQueryCache).count();
                if (cachePlans != 1)
                   this.error(capability, "explicit lock requires statement-scoped query cache");
             } else if (step instanceof SpringBootWorkflow.LoadStep load && load.forUpdate()) {
               if (this.versionedEntity(load.entitySymbol()) == null) {
                  this.error(capability, "only a versioned entity is loaded for update: " + load.entitySymbol());
               }

               // A lock without a version-bounded write would hold a row for no reason, which is the
               // shape the target must never generate by accident.
               boolean underConditionalUpdate = SpringBootWorkflow.walk(capability.workflow().steps())
                  .anyMatch(candidate -> candidate instanceof SpringBootWorkflow.UpdateStep update
                     && update.conditional().isPresent()
                     && update.targetVariable().equals(load.result().symbol()));
               if (!underConditionalUpdate) {
                  this.error(capability,
                     "a load for update must precede a conditional update on the same variable: " + load.result().symbol());
               }
            }

            if (step instanceof SpringBootWorkflow.ValidateStep validate) {
               this.validatePresence(capability, validate.condition(), patch);
            }

            if (step instanceof SpringBootWorkflow.PersistStep persist) {
               persist.conditional().ifPresent(conditional -> this.validateConditionalUpdate(capability, conditional, patch));
            }

            continue;
         }

         if (update.conditional().isEmpty()) {
            continue;
         }

         conditionalUpdates++;
         this.validateConditionalUpdate(capability, update.conditional().orElseThrow(), patch);
      }

      if (conditionalUpdates > 1L) {
         this.error(capability, "a capability performs at most one conditional update");
      }
   }

   /**
   * A presence test must ask about a property of this capability's change set.
   *
   * <p>Presence is a fact of the decoded payload, so a test naming anything else would compile into a
   * question the request cannot answer.
   */
   private void validatePresence(
      SpringBootDeclaration.CapabilityDeclaration capability,
      SpringExpression expression,
      SpringBootDeclaration.PatchSpec patch
   ) {
      switch (expression) {
         case SpringExpression.PayloadPresence presence -> {
            if (patch == null) {
               this.error(capability, "a presence test requires its entity's patch payload");
               return;
            }

            boolean known = patch.changes().stream()
               .anyMatch(change -> change.payloadPropertyName().equals(presence.sourcePropertyName()));
            if (!known) {
               this.error(capability, "a presence test must name a change set property, got an unknown change set property: "
                  + presence.sourcePropertyName());
            }
         }
         case SpringExpression.UnaryExpression unary -> this.validatePresence(capability, unary.operand(), patch);
         case SpringExpression.BinaryExpression binary -> {
            this.validatePresence(capability, binary.left(), patch);
            this.validatePresence(capability, binary.right(), patch);
         }
         default -> {
         }
      }
   }

   private void validateConditionalUpdate(
      SpringBootDeclaration.CapabilityDeclaration capability,
      SpringBootWorkflow.ConditionalUpdate conditional,
      SpringBootDeclaration.PatchSpec patch
   ) {
      SpringBootDeclaration.EntityDeclaration entity = this.versionedEntity(conditional.entitySymbol());
      if (entity == null) {
         this.error(capability, "a conditional update must target a versioned entity: " + conditional.entitySymbol());
         return;
      }

      SpringBootDeclaration.VersionSpec version = entity.version().orElseThrow();
      if (!version.fieldSymbol().equals(conditional.versionFieldSymbol())
         || !version.columnName().equals(conditional.versionColumnName())) {
         this.error(capability, "a conditional update must bound on its own entity's version column");
      }

      if (!entity.identity().sourceSymbol().equals(conditional.identityFieldSymbol())
         || !entity.identity().javaName().equals(conditional.identityPropertyName())) {
         this.error(capability, "a conditional update must bound on its own entity's identity");
      }

      if (patch == null) {
         this.error(capability, "a conditional update requires its entity's patch payload");
         return;
      }

      List<SymbolId> assigned = conditional.assignments().stream()
         .map(SpringBootWorkflow.ColumnAssignment::entityFieldSymbol)
         .toList();
      List<SymbolId> changed = patch.changes().stream()
         .map(SpringBootDeclaration.PatchChange::entityFieldSymbol)
         .toList();
      if (!assigned.equals(changed)) {
         this.error(capability, "a conditional update must assign exactly the payload's change set, in order");
      }
   }

   /**
   * The payload declaration a capability is given.
   *
   * <p>The capability's input is its input variable, whose declared type names the payload, so the
   * lookup follows the resolved type rather than guessing from a name.
   */
   private java.util.Optional<SpringBootDeclaration.InputDeclaration> payloadOf(
      SpringBootDeclaration.CapabilityDeclaration capability
   ) {
      return capability.input()
         .map(SpringBootWorkflow.Variable::type)
         .filter(LoweredJavaType.Declared.class::isInstance)
         .map(LoweredJavaType.Declared.class::cast)
         .map(LoweredJavaType.Declared::symbolId)
         .map(this.declarations::get)
         .filter(SpringBootDeclaration.InputDeclaration.class::isInstance)
         .map(SpringBootDeclaration.InputDeclaration.class::cast);
   }

   private SpringBootDeclaration.EntityDeclaration versionedEntity(SymbolId entitySymbol) {
      return this.declarations.get(entitySymbol) instanceof SpringBootDeclaration.EntityDeclaration entity
         && entity.version().isPresent() ? entity : null;
   }

   private SymbolId declaredTypeSymbol(LoweredJavaType type) {
      return type instanceof LoweredJavaType.Declared declared ? declared.symbolId() : null;
   }

   private void validateActorBinding(SpringBootDeclaration.CapabilityDeclaration capability) {
      if (capability.actor().isPresent() && capability.actorBinding().isEmpty()) {
         this.error(capability, "capability has actor but missing actorBinding");
      }

      if (capability.actor().isEmpty() && capability.actorBinding().isPresent()) {
         this.error(capability, "capability has actorBinding but no actor");
      }

      capability.actorBinding().ifPresent(binding -> {
         if (binding.kind() != ActorBinding.ActorKind.REQUEST_ATTRIBUTE) {
            this.error(capability, "actorBinding kind must be REQUEST_ATTRIBUTE: " + binding.kind());
         }

         if (!binding.attributeName().equals("actorId")) {
            this.error(capability, "actorBinding attributeName must be 'actorId': " + binding.attributeName());
         }

         capability.actor().ifPresent(actor -> {
            LoweredJavaType.Scalar expectedIdentity = this.actorIdentityType(actor.type());
            if (expectedIdentity == null || !binding.identityStorageType().equals(expectedIdentity)) {
               this.error(capability, "actorBinding identityStorageType does not match actor Entity identity: " + binding.identityStorageType());
            }
         });
      });
   }

   private LoweredJavaType.Scalar actorIdentityType(LoweredJavaType actorType) {
      SymbolId entitySymbol = switch (actorType) {
         case LoweredJavaType.Declared declared when declared.kind() == LoweredJavaType.DeclaredKind.ENTITY -> declared.symbolId();
         case LoweredJavaType.EntityReference reference -> reference.entitySymbol();
         default -> null;
      };
      return (entitySymbol == null ? null : (SpringBootDeclaration)this.declarations.get(entitySymbol)) instanceof SpringBootDeclaration.EntityDeclaration entity
         ? entity.identity().type()
         : null;
   }

   private void validateTransportPlan(SpringBootDeclaration.CapabilityDeclaration capability) {
      TransportPlan plan = capability.transportPlan();
      TransportPlan.InputBinding expectedInput;
      if (capability.input().isEmpty()) {
         expectedInput = TransportPlan.InputBinding.NONE;
      } else if (capability.httpMethod() == SpringBootDeclaration.HttpMethod.POST
         || capability.httpMethod() == SpringBootDeclaration.HttpMethod.PUT
         || capability.httpMethod() == SpringBootDeclaration.HttpMethod.PATCH) {
         expectedInput = TransportPlan.InputBinding.REQUEST_BODY;
      } else {
         expectedInput = TransportPlan.InputBinding.MODEL_ATTRIBUTE;
      }

      if (plan.inputBinding() != expectedInput) {
         this.error(
            capability, "transportPlan inputBinding must be " + expectedInput + " for httpMethod " + capability.httpMethod() + ": " + plan.inputBinding()
         );
      }

      TransportPlan.ResponseRepresentation expectedResponse = this.expectedResponse(capability.outputType());
      if (plan.responseRepresentation() == TransportPlan.ResponseRepresentation.PROJECTION
         && capability.outputType() instanceof LoweredJavaType.Declared declared
         && declared.kind() == LoweredJavaType.DeclaredKind.VIEW) {
         return;
      }

      if (plan.responseRepresentation() != expectedResponse) {
         this.error(
            capability,
            "transportPlan responseRepresentation must be "
               + expectedResponse
               + " for outputType "
               + capability.outputType()
               + ": "
               + plan.responseRepresentation()
         );
      }
   }

   private TransportPlan.ResponseRepresentation expectedResponse(LoweredJavaType outputType) {
      return switch (outputType) {
         case LoweredJavaType.Scalar scalar -> scalar.kind() == LoweredJavaType.ScalarKind.VOID
            ? TransportPlan.ResponseRepresentation.VOID
            : TransportPlan.ResponseRepresentation.VALUE;
         case LoweredJavaType.EntityReference ignored -> TransportPlan.ResponseRepresentation.ENTITY_BODY;
         case LoweredJavaType.Declared declared -> declared.kind() == LoweredJavaType.DeclaredKind.ENTITY
            ? TransportPlan.ResponseRepresentation.ENTITY_BODY
            : TransportPlan.ResponseRepresentation.VALUE;
         case LoweredJavaType.ListValue ignored -> TransportPlan.ResponseRepresentation.LIST;
         case LoweredJavaType.OptionalValue ignored -> TransportPlan.ResponseRepresentation.OPTIONAL;
         case LoweredJavaType.PageValue ignored -> TransportPlan.ResponseRepresentation.PAGE;
         default -> throw new MatchException(null, null);
      };
   }

   /** A page output and a paged find step only make sense together. */
   private void validatePageDemand(SpringBootDeclaration.CapabilityDeclaration capability) {
      long paged = SpringBootWorkflow.walk(capability.workflow().steps())
         .filter(SpringBootWorkflow.FindStep.class::isInstance)
         .map(SpringBootWorkflow.FindStep.class::cast)
         .filter(find -> find.page().isPresent())
         .count();
      paged += SpringBootWorkflow.walk(capability.workflow().steps()).filter(SpringBootWorkflow.GroupedSumStep.class::isInstance).count();
       boolean pageOutput = capability.outputType() instanceof LoweredJavaType.PageValue;
      if (pageOutput && paged != 1L) {
         this.error(capability, "Page output requires exactly one paged find step, found " + paged);
      }

      if (!pageOutput && paged > 0L) {
         this.error(capability, "a paged find step requires a Page output");
      }
   }

    private void validateWorkflow(SpringBootDeclaration.CapabilityDeclaration capability) {
       for(String problem:EachLexicalScopes.target(capability))this.error(capability,problem);
      SpringBootWorkflow workflow = capability.workflow();
      this.addNode(workflow.id(), workflow.origin(), "workflow");
      long returnCount = workflow.steps().stream().filter(SpringBootWorkflow.ReturnStep.class::isInstance).count();
      if (returnCount != 1L || workflow.steps().isEmpty() || !(workflow.steps().get(workflow.steps().size() - 1) instanceof SpringBootWorkflow.ReturnStep)) {
         this.error(capability, "workflow must contain exactly one final Return step");
      }

      for (SpringBootWorkflow.Step step : workflow.steps()) {
         this.addNode(step.id(), step.origin(), "workflow step");
         this.validateStep(step, capability);
      }
   }

   private void validateStep(SpringBootWorkflow.Step step, SpringBootDeclaration.CapabilityDeclaration owner) {
      switch (step) {
         case SpringBootWorkflow.ValidateStep value:
            this.validateExpression(value.condition(), owner);
            this.requireDeclaration(value.errorSymbol(), SpringBootDeclaration.ErrorDeclaration.class, owner);
            break;
         case SpringBootWorkflow.LoadStep value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            this.validateExpression(value.idExpression(), owner);
            this.requireKnown(value.result().symbol(), owner, "load result variable");
            this.requireDeclaration(value.errorSymbol(), SpringBootDeclaration.ErrorDeclaration.class, owner);
            value.keyField().ifPresent(key -> this.validateLoadKey(value, key, owner));
            break;
         case SpringBootWorkflow.LoadActorStep value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            this.validateExpression(value.idExpression(), owner);
            this.requireKnown(value.result().symbol(), owner, "actor row variable");
            if (owner.actor().isEmpty()) {
               this.error(owner, "only a capability with an actor can read the actor's row");
            }
            break;
         case SpringBootWorkflow.MonthStep value:
              this.validateExpression(value.month(),owner);this.validateExpression(value.zone(),owner);
              this.requireKnown(value.since().symbol(),owner,"month start");this.requireKnown(value.until().symbol(),owner,"month end");
              this.requireDeclaration(value.errorSymbol(),SpringBootDeclaration.ErrorDeclaration.class,owner);
              for(String problem:CalendarMonthValidation.validate(this.model,owner,value))this.error(owner,problem);
              break;
          case SpringBootWorkflow.GroupedSumStep value:
             this.validateExpression(value.predicate(), owner);
             this.validateStringMatches(value.stringMatches(), value.predicate(), owner);
             this.validatePageSpec(owner, value.page());
             for (String problem : GroupedSumValidation.validate(this.model, owner, value)) this.error(owner, problem);
             break;
          case SpringBootWorkflow.SumStep value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            this.validateExpression(value.predicate(), owner);
            this.requireKnown(value.result().symbol(), owner, "sum result");
            this.requireKnown(value.itemVariable().symbol(), owner, "sum item");
            this.validateStringMatches(value.stringMatches(), value.predicate(), owner);
            this.validateSum(value, owner);
            break;
         case SpringBootWorkflow.ScalarQueryStep value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            this.validateExpression(value.predicate(), owner);
            this.requireKnown(value.result().symbol(), owner, "scalar query result");
            this.requireKnown(value.itemVariable().symbol(), owner, "scalar query item");
            this.validateScalarQuery(value, owner);
            this.validateStringMatches(value.stringMatches(), value.predicate(), owner);
            break;
                   case SpringBootWorkflow.EachStep value:
             this.requireDeclaration(value.entitySymbol(),SpringBootDeclaration.EntityDeclaration.class,owner);
             this.validateExpression(value.predicate(),owner);
             this.validateStringMatches(value.stringMatches(),value.predicate(),owner);
             this.requireDeclaration(value.errorSymbol(),SpringBootDeclaration.ErrorDeclaration.class,owner);
             for(String problem:EachValidation.validate(this.model,owner,value))this.error(owner,problem);
             for(var child:value.body()) {
                this.addNode(child.id(),child.origin(),"each body step");
                if(!(child instanceof SpringBootWorkflow.EachStep))this.validateStep(child,owner);
             }
             break;
          case SpringBootWorkflow.RepeatStep value:
             this.validateExpression(value.count(),owner);
             this.requireDeclaration(value.errorSymbol(),SpringBootDeclaration.ErrorDeclaration.class,owner);
             for(String problem:EachValidation.validate(owner,value))this.error(owner,problem);
             for(var child:value.body()) {
                this.addNode(child.id(),child.origin(),"repeat body step");
                if(!(child instanceof SpringBootWorkflow.EachStep || child instanceof SpringBootWorkflow.RepeatStep))this.validateStep(child,owner);
             }
             break;
case SpringBootWorkflow.FindStep value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            this.validateExpression(value.predicate(), owner);
            this.requireKnown(value.result().symbol(), owner, "find result variable");
            this.requireKnown(value.itemVariable().symbol(), owner, "find item variable");
             this.validateFindQuery(value, owner);
            break;
         case SpringBootWorkflow.CreateStep value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            this.requireKnown(value.result().symbol(), owner, "create result variable");
            value.bindings().forEach(binding -> this.validateBinding(binding, owner));
            break;
         case SpringBootWorkflow.UpdateStep value:
            this.requireKnown(value.targetVariable(), owner, "update target variable");
            value.bindings().forEach(binding -> this.validateBinding(binding, owner));
            value.guard().ifPresent(guard -> this.validateUpdateGuard(value, guard, owner));
            if (value.bindings().stream().anyMatch(binding -> binding.relative().isPresent())) {
               this.validateRelativeWrite(value, owner);
            }
            break;
         case SpringBootWorkflow.PersistStep value:
            this.requireKnown(value.targetVariable(), owner, "persist target variable");
            if (value.action() == io.kcg.sir.lowering.springboot.model.PersistenceAction.INSERT && value.failure().isPresent()) {
               // The error a new row's collision with an existing row is answered with must be one the capability declares.
               this.requireDeclaration(value.failure().orElseThrow(), SpringBootDeclaration.ErrorDeclaration.class, owner);
               if (!owner.failures().contains(value.failure().orElseThrow())) {
                  this.error(owner, "an insert's duplicate failure must be declared in the capability failures: " + value.failure().orElseThrow());
               }
            }
            break;
         case SpringBootWorkflow.ReturnStep value:
            this.validateExpression(value.value(), owner);
            this.validateReturnReads(value, owner);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateSum(SpringBootWorkflow.SumStep query, SpringBootDeclaration.CapabilityDeclaration owner) {
      var expected = new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BIG_DECIMAL);
      var entity = this.declarations.get(query.entitySymbol());
      var field = entity instanceof SpringBootDeclaration.EntityDeclaration e ? e.fields().stream()
            .filter(f -> f.sourceSymbol().equals(query.plan().fieldSymbol())).findFirst().orElse(null) : null;
      var type = field == null ? null : field.type();
      if (type instanceof LoweredJavaType.OptionalValue optional) type = optional.elementType();
       if (field == null || !expected.equals(type) || field.persistenceShape() != SpringBootDeclaration.PersistenceShape.SCALAR
             || field.columnName().isEmpty()
            || !field.columnName().get().equals(query.plan().columnName())
            || !query.plan().projectionSql().equals(AggregateSql.measurement(
                  "`" + query.plan().columnName().replace("`", "``") + "`", query.plan().average(), query.plan().scale())))
         this.error(owner, "sum must project a declared Decimal column of its entity");
      if (!(query.itemVariable().type() instanceof LoweredJavaType.Declared row
              && row.kind() == LoweredJavaType.DeclaredKind.ENTITY && row.symbolId().equals(query.entitySymbol()))
            || !owner.workflow().variables().contains(query.itemVariable())
            || !owner.workflow().variables().contains(query.result()))
         this.error(owner, "sum row and result must belong to this capability with their declared types");
      if (!query.result().type().equals(expected)
             || !query.predicate().type().equals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BOOLEAN))
             || !query.plan().emptyAsZero() || !query.plan().rejectWarnings()
            || !query.plan().mapperMethod().equals("selectObjs") || query.plan().statements() != 1
             || (owner.transactionMode() != SpringBootDeclaration.TransactionMode.READ_ONLY
                  && owner.transactionMode() != SpringBootDeclaration.TransactionMode.REPEATABLE_READ)
             || owner.kind() != SpringBootDeclaration.CapabilityKind.QUERY)
         this.error(owner, "sum requires exact Decimal, empty zero, current statement warnings and one readonly SELECT");
      var support = this.model.projectArtifacts().stream().filter(ProjectArtifact.ExactAggregate.class::isInstance)
            .map(ProjectArtifact.ExactAggregate.class::cast).toList();
       var groupPage = this.model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
             .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).flatMap(c -> c.workflow().steps().stream())
             .anyMatch(SpringBootWorkflow.GroupedSumStep.class::isInstance)
             ? Optional.of(this.model.basePackage()+".api.PageResponse") : Optional.<String>empty();
       if (support.size() != 1 || !support.getFirst().groupPageType().equals(groupPage)
             || !support.getFirst().packageName().equals(this.model.basePackage() + ".persistence")
            || !support.getFirst().simpleName().equals("ExactAggregate")
            || !support.getFirst().path().equals("src/main/java/" + (this.model.basePackage() + ".persistence").replace('.', '/') + "/ExactAggregate.java"))
         this.error(owner, "sum requires its exact aggregate support artifact");
      if (this.model.projectArtifacts().stream().filter(ProjectArtifact.ApplicationConfig.class::isInstance)
            .map(ProjectArtifact.ApplicationConfig.class::cast).filter(ProjectArtifact.ApplicationConfig::statementScopedQueryCache).count() != 1)
         this.error(owner, "sum requires STATEMENT query cache to avoid bypassing warning checks");
   }

   private void validateScalarQuery(SpringBootWorkflow.ScalarQueryStep query, SpringBootDeclaration.CapabilityDeclaration owner) {
      SpringBootWorkflow.ScalarQueryPlan plan = query.plan();
      boolean exists = plan.kind() == SpringBootWorkflow.ScalarResult.EXISTS;
      LoweredJavaType expected = new LoweredJavaType.Scalar(exists ? LoweredJavaType.ScalarKind.BOOLEAN : LoweredJavaType.ScalarKind.LONG);
      if (!query.result().type().equals(expected) || plan.statements() != 1
            || !plan.mapperMethod().equals(exists ? "selectObjs" : "selectCount")) {
         this.error(owner, "scalar query result, method and one-statement budget must agree");
      }
      if (this.declarations.get(query.entitySymbol()) instanceof SpringBootDeclaration.EntityDeclaration entity) {
         java.util.Optional<String> projection = exists ? java.util.Optional.of(entity.identity().javaName()) : java.util.Optional.empty();
         java.util.Optional<String> tail = exists ? java.util.Optional.of("LIMIT 1") : java.util.Optional.empty();
         if (!plan.projectionProperty().equals(projection) || !plan.tailSql().equals(tail)) {
            this.error(owner, "existence reads one identity with LIMIT 1; count has neither projection nor limit");
         }
      }
   }

   /**
   * Ordering, pagination and literal matches must all point at declared entity fields, and the
   * literal-match plan must describe exactly the predicate's literal matches — in both directions.
   */
   private void validateFindQuery(SpringBootWorkflow.FindStep find, SpringBootDeclaration.CapabilityDeclaration owner) {
      SpringBootDeclaration.EntityDeclaration entity = this.declarations.get(find.entitySymbol()) instanceof SpringBootDeclaration.EntityDeclaration value
         ? value
         : null;
      for (SpringBootWorkflow.OrderKey key : find.orderKeys()) {
         this.requireKnown(key.fieldSymbol(), owner, "order key");
         if (entity != null && this.entityFieldType(entity, key.fieldSymbol()) == null) {
         this.error(owner, "order key is not a field of the find entity: " + key.fieldSymbol());
         }
      }

      find.page().ifPresent(page -> this.validatePageSpec(owner, page));
      this.validateStringMatches(find, owner);
      this.validateStatementBudget(find, owner);

      if (find.page().isPresent() && !(find.result().type() instanceof LoweredJavaType.PageValue)) {
         this.error(owner, "a paged find must produce a Page value: " + find.result().type());
      }
   }

   /**
   * The declared read budget must match what the statement promises: the page reads, and one batch
   * read for every association the response projection nests.
   *
   * <p>The budget is what the target is held to on a real database, so it is recomputed here from
   * the projection rather than trusted; a projection that grows an association without the budget
   * following it is a contradiction inside the IR.
   */
   private void validateStatementBudget(SpringBootWorkflow.FindStep find, SpringBootDeclaration.CapabilityDeclaration owner) {
      int pageStatements = find.page().isPresent() ? 2 : 1;
      int associations = this.associationReads(find.result().type());
      SpringBootWorkflow.StatementBudget budget = find.statementBudget();
      if (budget.pageStatements() != pageStatements) {
         this.error(owner, "the page reads of a find must be " + pageStatements + " but the budget says " + budget.pageStatements());
      }

      if (budget.associationStatements() != associations) {
         this.error(owner, "the response projection nests " + associations + " batch reads but the budget says "
            + budget.associationStatements());
      }
   }

   /**
   * Answering with one row that the response view nests other views into costs one batch read per association, and the
   * step must say so; answering with a find result (counted by the find) or a row with nothing nested costs none.
   */
   private void validateReturnReads(SpringBootWorkflow.ReturnStep step, SpringBootDeclaration.CapabilityDeclaration owner) {
      boolean aFindResult = step.value().type() instanceof LoweredJavaType.PageValue || step.value().type() instanceof LoweredJavaType.ListValue;
      int expected = aFindResult ? 0 : this.associationReads(owner.outputType());
      if (step.associationStatements() != expected) {
         this.error(owner, "answering with a row nests " + expected + " batch reads but the return says " + step.associationStatements());
      }
   }

   /** The batch reads the rows a find returns need, counted through the projection it answers with. */
   private int associationReads(LoweredJavaType type) {
      LoweredJavaType element = switch (type) {
         case LoweredJavaType.PageValue value -> value.elementType();
         case LoweredJavaType.ListValue value -> value.elementType();
         default -> type;
      };
      if (!(element instanceof LoweredJavaType.Declared declared)
         || declared.kind() != LoweredJavaType.DeclaredKind.VIEW
         || !(this.declarations.get(declared.symbolId()) instanceof SpringBootDeclaration.ViewDeclaration view)) {
         return 0;
      }

      int associations = 0;
      for (SpringBootDeclaration.ViewField field : view.fields()) {
          if (field.value() instanceof SpringBootDeclaration.CountViewValue) associations++;
          if (field.relation().isPresent()) {
             associations += 1 + this.associationReads(field.type());
         }
      }

      return associations;
   }

   private void validatePageSpec(SpringBootDeclaration.CapabilityDeclaration owner, SpringBootWorkflow.PageSpec page) {
      this.requireDeclaration(page.errorSymbol(), SpringBootDeclaration.ErrorDeclaration.class, owner);
      this.requireKnown(page.pageFieldSymbol(), owner, "pagination page field");
      this.requireKnown(page.sizeFieldSymbol(), owner, "pagination size field");
      if (page.pageFieldSymbol().equals(page.sizeFieldSymbol())) {
         this.error(owner, "pagination requires two distinct input fields");
      }

      if (!owner.failures().contains(page.errorSymbol())) {
         this.error(owner, "pagination error must be declared in the capability failures: " + page.errorSymbol());
      }

      if (page.defaultSize() != this.queryPolicy().pageDefaultSize()
         || page.maxSize() != this.queryPolicy().pageMaxSize()
         || page.maxPageNumber() != this.queryPolicy().pageMaxNumber()) {
         this.error(owner, "pagination bounds must match the target query policy: default=" + page.defaultSize()
         + " maxSize=" + page.maxSize() + " maxPageNumber=" + page.maxPageNumber());
      }
   }

   private void validateStringMatches(SpringBootWorkflow.FindStep find, SpringBootDeclaration.CapabilityDeclaration owner) {
      this.validateStringMatches(find.stringMatches(), find.predicate(), owner);
   }

   private void validateStringMatches(List<SpringExpression.StringMatch> matches, SpringExpression predicate, SpringBootDeclaration.CapabilityDeclaration owner) {
      Set<LoweredNodeId> planned = new LinkedHashSet<>();
      for (SpringExpression.StringMatch match : matches) {
         this.addNode(match.id(), match.origin(), "string match plan");
         if (!planned.add(match.expressionId())) {
         this.error(owner, "duplicate literal-match plan entry: " + match.expressionId());
         }

         if (match.escapeCharacter() != this.queryPolicy().likeEscapeCharacter()
         || !match.escapedLiterals().equals(this.queryPolicy().likeEscapedLiterals())) {
         this.error(owner, "literal-match escaping must match the target query policy: " + match.escapedLiterals());
         }
      }

      Set<LoweredNodeId> actual = new LinkedHashSet<>();
      this.collectLiteralMatches(predicate, actual);
      for (LoweredNodeId expressionId : actual) {
         if (!planned.contains(expressionId)) {
         this.error(owner, "literal match in the predicate has no plan entry: " + expressionId);
         }
      }

      for (LoweredNodeId expressionId : planned) {
         if (!actual.contains(expressionId)) {
         this.error(owner, "literal-match plan entry has no predicate node: " + expressionId);
         }
      }
   }

   private void collectLiteralMatches(SpringExpression expression, Set<LoweredNodeId> sink) {
      switch (expression) {
         case SpringExpression.BinaryExpression binary:
         if (binary.operator() == SpringExpression.BinaryOperator.CONTAINS_LITERAL) {
               sink.add(binary.id());
         }

         this.collectLiteralMatches(binary.left(), sink);
         this.collectLiteralMatches(binary.right(), sink);
         break;
         case SpringExpression.UnaryExpression unary:
         this.collectLiteralMatches(unary.operand(), sink);
         break;
         default:
      }
   }

   private void validateBinding(SpringBootWorkflow.Binding binding, SpringBootDeclaration.CapabilityDeclaration owner) {
      this.requireKnown(binding.fieldSymbol(), owner, "binding field");
      this.validateExpression(binding.value(), owner);
   }

   private void validateExpression(SpringExpression expression, SpringBootDeclaration owner) {
      this.addNode(expression.id(), expression.origin(), "expression");
      this.validateType(expression.type(), owner);
      switch (expression) {
         case SpringExpression.NameExpression value:
            this.requireKnown(value.resolvedSymbol(), owner, "name expression");
            break;
         case SpringExpression.MemberExpression value:
            this.validateExpression(value.receiver(), owner);
            this.requireKnown(value.resolvedMember(), owner, "member expression");
            break;
          case SpringExpression.IntegralToDecimal value:
             this.validateExpression(value.operand(), owner);
             if (!ArithmeticInputs.integral(value.operand().type())
                   || !(value.type() instanceof LoweredJavaType.Scalar scalar)
                   || scalar.kind() != LoweredJavaType.ScalarKind.BIG_DECIMAL) {
                this.error(owner, "integral-to-decimal requires a non-optional integer and a decimal result");
             }
             break;
          case SpringExpression.OptionalPayloadValue value:
             this.validateExpression(value.payloadMember(), owner);
            if (!this.isRawOptionalPayload(value.payloadMember()) || !value.type().equals(value.payloadMember().type())) {
               this.error(owner, "an optional payload read must unwrap its typed INPUT member");
            }
            break;
         case SpringExpression.UnaryExpression value:
             this.validateExpression(value.operand(), owner);
             this.requireNullablePayloadValue(value.operand(), owner);
             if ((value.operator() == SpringExpression.UnaryOperator.IS_NONE || value.operator() == SpringExpression.UnaryOperator.IS_PRESENT)
                   && !(value.operand().type() instanceof LoweredJavaType.OptionalValue)) {
                this.error(owner, "a null test requires an optional operand");
             }
             break;
          case SpringExpression.BinaryExpression value:
            this.validateExpression(value.left(), owner);
            this.validateExpression(value.right(), owner);
             this.validatePresentComparison(value, owner);
             this.validateArithmetic(value, owner);
             this.validateFunction(value, owner);
            this.validateMatches(value, owner);
            break;
         case SpringExpression.DigestExpression value:
            this.validateExpression(value.operand(), owner);
            this.validateDigest(value, owner);
            break;
         case SpringExpression.TimeShiftExpression value:
            this.validateExpression(value.base(), owner);
            this.validateTimeShift(value, owner);
            break;
         case SpringExpression.ExistsPredicate value:
            this.validateExistsPredicate(value, owner);
            break;
         default:
      }
   }

    private boolean isRawOptionalPayload(SpringExpression expression) {
       return expression instanceof SpringExpression.MemberExpression member
          && member.type() instanceof LoweredJavaType.OptionalValue
          && member.receiver().type() instanceof LoweredJavaType.Declared receiver
          && receiver.kind() == LoweredJavaType.DeclaredKind.INPUT;
    }

    private void requireNullablePayloadValue(SpringExpression expression, SpringBootDeclaration owner) {
       if (this.isRawOptionalPayload(expression)) {
          this.error(owner, "an optional payload operand requires an explicit nullable read plan");
       }
    }

    /** The null policy is mandatory for optional comparisons and must match their physical operands. */
    private void validatePresentComparison(SpringExpression.BinaryExpression value, SpringBootDeclaration owner) {
       boolean comparison = switch (value.operator()) {
          case EQ, NE, LT, LE, GT, GE -> true;
          default -> false;
       };
       boolean left = value.left().type() instanceof LoweredJavaType.OptionalValue;
       boolean right = value.right().type() instanceof LoweredJavaType.OptionalValue;
       boolean needsPlan = comparison && (left || right);
       if (comparison) {
          this.requireNullablePayloadValue(value.left(), owner);
          this.requireNullablePayloadValue(value.right(), owner);
       }
       if (needsPlan != value.presentComparison().isPresent()) {
          this.error(owner, "optional comparisons require exactly one present-only comparison plan");
       }
       value.presentComparison().ifPresent(plan -> {
          LoweredJavaType operand = left ? ((LoweredJavaType.OptionalValue) value.left().type()).elementType() : value.left().type();
          LoweredJavaType other = right ? ((LoweredJavaType.OptionalValue) value.right().type()).elementType() : value.right().type();
          if (!plan.operandType().equals(operand) || !operand.equals(other) || plan.checkLeft() != left || plan.checkRight() != right) {
             this.error(owner, "present-only comparison plan does not match its operands");
          }
       });
    }

    /**
    * A guard is written into the statement that persists the row, so the update must be persisted and
   * that persist must be a plain one: a version check and a guard are two different protections and a
   * lowered model that asks for both (or for a guard on nothing) would generate a statement that
   * checks one of them by accident.
   */
   private void validateUpdateGuard(SpringBootWorkflow.UpdateStep update, SpringBootWorkflow.UpdateGuard guard, SpringBootDeclaration owner) {
      this.validateExpression(guard.predicate(), owner);
      this.requireDeclaration(guard.failure(), SpringBootDeclaration.ErrorDeclaration.class, owner);
      if (update.conditional().isPresent()) {
         this.error(owner, "an update cannot carry both a version check and a guard: " + update.targetVariable());
      }

      this.requirePlainPersist(update, owner, "a guarded update");
   }

   /** A column changed by an amount is one statement too, so the same holds: no version check, and a plain persist. */
   private void validateRelativeWrite(SpringBootWorkflow.UpdateStep update, SpringBootDeclaration owner) {
      if (update.conditional().isPresent()) {
         this.error(owner, "an update cannot carry both a version check and a change by an amount: " + update.targetVariable());
      }

      this.requirePlainPersist(update, owner, "an update that changes a column by an amount");
   }

   private void requirePlainPersist(SpringBootWorkflow.UpdateStep update, SpringBootDeclaration owner, String what) {
      if (owner instanceof SpringBootDeclaration.CapabilityDeclaration capability) {
         boolean persistedPlainly = SpringBootWorkflow.walk(capability.workflow().steps())
            .anyMatch(step -> step instanceof SpringBootWorkflow.PersistStep persist
               && persist.targetVariable().equals(update.targetVariable())
               && persist.action() == io.kcg.sir.lowering.springboot.model.PersistenceAction.UPDATE
               && persist.conditional().isEmpty());
         if (!persistedPlainly) {
            this.error(owner, what + " must be persisted by a plain persist of the same row: " + update.targetVariable());
         }
      }
   }

   private static boolean isText(LoweredJavaType type) {
      return type instanceof LoweredJavaType.Scalar scalar && scalar.kind() == LoweredJavaType.ScalarKind.STRING;
   }

   /** A match asks whether a candidate text produces a stored text: two texts, a truth value. */
   private void validateMatches(SpringExpression.BinaryExpression value, SpringBootDeclaration owner) {
      if (value.operator() != SpringExpression.BinaryOperator.MATCHES) {
         return;
      }

      boolean truth = value.type() instanceof LoweredJavaType.Scalar scalar && scalar.kind() == LoweredJavaType.ScalarKind.BOOLEAN;
      if (!truth || !isText(value.left().type()) || !isText(value.right().type())) {
         this.error(owner, "a match needs two texts and answers a boolean: " + value.id());
      }
   }

   /** A digest is the digest of a text and is itself a text. */
   private void validateDigest(SpringExpression.DigestExpression value, SpringBootDeclaration owner) {
      if (!isText(value.operand().type()) || !isText(value.type())) {
         this.error(owner, "a digest is taken of a text and is a text: " + value.id());
      }
   }

   /** After explicit conversions, arithmetic takes two operands of one numeric Java type and answers in that type. */
   private void validateArithmetic(SpringExpression.BinaryExpression value, SpringBootDeclaration owner) {
      if (value.operator() != SpringExpression.BinaryOperator.ADD && value.operator() != SpringExpression.BinaryOperator.SUB
             && value.operator() != SpringExpression.BinaryOperator.MUL) {
         return;
      }

      boolean numeric = value.type() instanceof LoweredJavaType.Scalar scalar
         && (scalar.kind() == LoweredJavaType.ScalarKind.INTEGER || scalar.kind() == LoweredJavaType.ScalarKind.LONG
            || scalar.kind() == LoweredJavaType.ScalarKind.BIG_DECIMAL);
      if (!numeric || !value.left().type().equals(value.type()) || !value.right().type().equals(value.type())) {
         this.error(owner, "arithmetic needs two operands of the result's numeric type: " + value.id());
      }
   }

   /**
   * A built-in function takes two operands of one type: daysBetween two dates or two date-times and answers a
   * long; max and min two numbers or two points in time and answer in that type. Neither takes an optional value.
   */
   private void validateFunction(SpringExpression.BinaryExpression value, SpringBootDeclaration owner) {
      var operator = value.operator();
      if (operator != SpringExpression.BinaryOperator.DAYS_BETWEEN && operator != SpringExpression.BinaryOperator.MAX
            && operator != SpringExpression.BinaryOperator.MIN && operator != SpringExpression.BinaryOperator.ADD_DAYS) {
         return;
      }
      LoweredJavaType.ScalarKind kind = value.left().type() instanceof LoweredJavaType.Scalar scalar ? scalar.kind() : null;
      boolean temporal = kind == LoweredJavaType.ScalarKind.INSTANT || kind == LoweredJavaType.ScalarKind.LOCAL_DATE;
      boolean numeric = kind == LoweredJavaType.ScalarKind.INTEGER || kind == LoweredJavaType.ScalarKind.LONG
         || kind == LoweredJavaType.ScalarKind.BIG_DECIMAL;
      if (operator == SpringExpression.BinaryOperator.ADD_DAYS) {
         LoweredJavaType.ScalarKind days = value.right().type() instanceof LoweredJavaType.Scalar scalar ? scalar.kind() : null;
         if (!temporal || value.presentComparison().isPresent() || !value.type().equals(value.left().type())
               || (days != LoweredJavaType.ScalarKind.INTEGER && days != LoweredJavaType.ScalarKind.LONG)) {
            this.error(owner, "addDays needs a point in time and a whole number of days and answers the point's type: " + value.id());
         }
         return;
      }
      boolean valid = value.left().type().equals(value.right().type()) && value.presentComparison().isEmpty()
         && (operator == SpringExpression.BinaryOperator.DAYS_BETWEEN
            ? temporal && value.type().equals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LONG))
            : (temporal || numeric) && value.type().equals(value.left().type()));
      if (!valid) {
         this.error(owner, "a built-in function needs two operands of one supported type and answers its declared type: " + value.id());
      }
   }

   /**
   * A time shift answers in the type of what it moves, and that type must be one Java can move: an
   * instant by any unit, a local date by days only (it has no time of day to move by hours).
   */
   private void validateTimeShift(SpringExpression.TimeShiftExpression value, SpringBootDeclaration owner) {
      LoweredJavaType.ScalarKind kind = value.base().type() instanceof LoweredJavaType.Scalar scalar ? scalar.kind() : null;
      boolean movable = kind == LoweredJavaType.ScalarKind.INSTANT || kind == LoweredJavaType.ScalarKind.LOCAL_DATE;
      if (!movable || !value.type().equals(value.base().type())) {
         this.error(owner, "a time shift moves a date or a date-time and answers in the same type: " + value.id());
      } else if (kind == LoweredJavaType.ScalarKind.LOCAL_DATE && value.unit() != SpringExpression.ShiftUnit.DAYS) {
         this.error(owner, "a date can only be shifted by days: " + value.id());
      }
   }

   /**
   * A correlated subquery must carry one value for every placeholder it uses, in order.
   *
   * <p>The renderer binds the values positionally, so a placeholder without a value (or a value
   * nobody uses) would silently shift every parameter that follows it.
   */
   private void validateExistsPredicate(SpringExpression.ExistsPredicate exists, SpringBootDeclaration owner) {
      for (SpringExpression.ExistsArgument argument : exists.arguments()) {
         if (argument instanceof SpringExpression.ExistsArgument.Value value) {
            this.validateExpression(value.expression(), owner);
            this.requireNullablePayloadValue(value.expression(), owner);
         }
      }
      int placeholders = 0;
      while (exists.subquerySql().contains("{" + placeholders + "}")) {
         placeholders++;
      }

      if (placeholders != exists.arguments().size()) {
         this.error(owner, "a correlated subquery must bind exactly the values it declares: " + placeholders
            + " placeholders for " + exists.arguments().size() + " values");
      }

      for (int index = 0; index < placeholders; index++) {
         if (!exists.subquerySql().contains("{" + index + "}")) {
            this.error(owner, "a correlated subquery must number its placeholders without gaps: " + index);
         }
      }
   }

   private void validateType(LoweredJavaType type, SpringBootDeclaration owner) {
      switch (type) {
          case LoweredJavaType.SumGroupValue group:
             if (!(owner instanceof SpringBootDeclaration.CapabilityDeclaration capability
                   && capability.outputType() instanceof LoweredJavaType.PageValue page && page.elementType().equals(group)))
                this.error(owner, "group carriers are direct Page outputs, never storage or request members");
             this.validateType(group.keyType(), owner);
             break;
          case LoweredJavaType.Scalar ignored:
            break;
         case LoweredJavaType.OptionalValue value:
            this.validateType(value.elementType(), owner);
            break;
         case LoweredJavaType.ListValue value:
            this.validateType(value.elementType(), owner);
            break;
         case LoweredJavaType.PageValue value:
         this.validateType(value.elementType(), owner);
         break;
         case LoweredJavaType.EntityReference value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            break;
         case LoweredJavaType.Declared value:
            Class<? extends SpringBootDeclaration> expected = switch (value.kind()) {
               case ENUM -> SpringBootDeclaration.EnumDeclaration.class;
               case ENTITY -> SpringBootDeclaration.EntityDeclaration.class;
               case INPUT -> SpringBootDeclaration.InputDeclaration.class;
               case VIEW -> SpringBootDeclaration.ViewDeclaration.class;
            };
            this.requireDeclaration(value.symbolId(), expected, owner);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateArtifacts() {
      Set<String> qualifiedNames = new HashSet<>();
      Map<SymbolId, Set<SpringArtifact.Role>> actualRoles = new LinkedHashMap<>();

      for (SpringArtifact artifact : this.model.artifacts()) {
         this.addNode(artifact.id(), artifact.origin(), "artifact");
         if (!this.declarations.containsKey(artifact.ownerSymbol())) {
            this.error(artifact.origin(), "artifact owner is not a lowered declaration: " + artifact.ownerSymbol());
         }

         if (!qualifiedNames.add(artifact.qualifiedName())) {
            this.error(artifact.origin(), "duplicate artifact qualified name: " + artifact.qualifiedName());
         }

         actualRoles.computeIfAbsent(artifact.ownerSymbol(), ignored -> EnumSet.noneOf(SpringArtifact.Role.class)).add(artifact.role());
      }

      for (SpringBootDeclaration declaration : this.model.declarations()) {
         Set<SpringArtifact.Role> expected = this.expectedRoles(declaration);
         Set<SpringArtifact.Role> actual = actualRoles.getOrDefault(declaration.sourceSymbol(), Set.of());
         if (!actual.equals(expected)) {
            this.error(declaration, "artifact ownership mismatch; expected " + expected + " but found " + actual);
         }
      }
   }

   private Set<SpringArtifact.Role> expectedRoles(SpringBootDeclaration declaration) {
      return switch (declaration) {
         case SpringBootDeclaration.EnumDeclaration ignored -> EnumSet.of(SpringArtifact.Role.ENUM);
         case SpringBootDeclaration.EntityDeclaration ignored -> EnumSet.of(SpringArtifact.Role.ENTITY_MODEL, SpringArtifact.Role.MAPPER);
         case SpringBootDeclaration.InputDeclaration ignored -> EnumSet.of(SpringArtifact.Role.REQUEST_DTO);
          case SpringBootDeclaration.ViewDeclaration ignored -> EnumSet.of(SpringArtifact.Role.VIEW_DTO);
         case SpringBootDeclaration.ErrorDeclaration ignored -> EnumSet.of(SpringArtifact.Role.EXCEPTION);
         case SpringBootDeclaration.CapabilityDeclaration ignored -> EnumSet.of(SpringArtifact.Role.SERVICE, SpringArtifact.Role.CONTROLLER);
         default -> throw new MatchException(null, null);
      };
   }

   private void requireDeclaration(SymbolId id, Class<? extends SpringBootDeclaration> expected, SpringBootDeclaration owner) {
      if (!expected.isInstance(this.declarations.get(id))) {
         this.error(owner, "SymbolId does not reference " + expected.getSimpleName() + ": " + id);
      }
   }

   private void requireKnown(SymbolId id, SpringBootDeclaration owner, String role) {
      if (!this.knownSymbols.contains(id) || id.value().startsWith("sir://unknown")) {
         this.error(owner, role + " is not present in the Lowered IR symbol manifest: " + id);
      }
   }

   private void addNode(LoweredNodeId id, LoweredOrigin origin, String role) {
      if (!this.nodeIds.add(id)) {
         this.error(origin, "duplicate LoweredNodeId for " + role + ": " + id);
      }
   }

   private void error(SpringBootDeclaration declaration, String message) {
      if (declaration != null) {
         this.error(declaration.origin(), message);
      }
   }

   private void error(LoweredOrigin origin, String message) {
      this.diagnostics.add(LoweringDiagnostics.error("SIR-LOWER-IR-001", message, origin.span(), origin.ownerSymbol().orElse(null), origin.sourceNodeId()));
   }

   private enum VersionProfileStatus {
      OK,
      UNKNOWN;
   }
}
