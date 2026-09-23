/*
 * Spring Boot target lowering: expresses a resolved, validated SIR program as the flat model the
 * Java/Spring generator renders. It reads the normalized semantic model, never the syntax tree, and
 * never touches the file system.
 */
package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.ast.AstExposureKind;
import io.kcg.sir.ast.AstGenerationStrategy;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.lowering.api.LoweredIrVersion;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.lowering.springboot.model.ActorBinding;
import io.kcg.sir.lowering.springboot.model.ActorIdentityRequirement;
import io.kcg.sir.lowering.springboot.model.ActorIdentityTransportPlan;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.PersistenceAction;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.lowering.springboot.model.TransportPlan;
import io.kcg.sir.lowering.springboot.profile.SpringBootQueryPolicy;
import io.kcg.sir.lowering.springboot.profile.SpringBootWritePolicy;
import io.kcg.sir.lowering.springboot.profile.SpringBootTargetProfile;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedBinding;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedConstraint;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEnum;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedIdentity;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.model.NormalizedView;
import io.kcg.sir.semantic.model.NormalizedViewField;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.ListType;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PageType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SpringBootModelLowerer {
    private final NormalizedSemanticModel source;
    private final SpringBootQueryPolicy queryPolicy;
    private final SpringBootWritePolicy writePolicy;
    private final Map<SymbolId, NormalizedEntity> entities = new LinkedHashMap<SymbolId, NormalizedEntity>();
    private final Map<SymbolId, String> targetMemberNames = new LinkedHashMap<SymbolId, String>();
    private final Map<SymbolId, NormalizedView> views = new LinkedHashMap<SymbolId, NormalizedView>();
    private final Map<SymbolId, NormalizedInput> inputs = new LinkedHashMap<SymbolId, NormalizedInput>();
    private final Map<SymbolId, SymbolId> viewSourceEntities = new LinkedHashMap<SymbolId, SymbolId>();
    /** Entity member symbol -> the column it persists to, for the entities of this software. */
    private final Map<SymbolId, String> columns = new LinkedHashMap<SymbolId, String>();

    public SpringBootModelLowerer(NormalizedSemanticModel source) {
        this(source, SpringBootQueryPolicy.V0_1);
    }

    public SpringBootModelLowerer(NormalizedSemanticModel source, SpringBootQueryPolicy queryPolicy) {
        this(source, queryPolicy, SpringBootWritePolicy.V0_1);
    }

    public SpringBootModelLowerer(NormalizedSemanticModel source, SpringBootQueryPolicy queryPolicy, SpringBootWritePolicy writePolicy) {
        this.source = source;
        this.queryPolicy = Objects.requireNonNull(queryPolicy, "queryPolicy");
        this.writePolicy = Objects.requireNonNull(writePolicy, "writePolicy");
        for (NormalizedDeclaration declaration : source.declarations()) {
            if (declaration instanceof NormalizedEntity) {
                NormalizedEntity entity = (NormalizedEntity)declaration;
                this.entities.put(entity.id(), entity);
                this.targetMemberNames.put(entity.identity().id(), entity.identity().name());
                for (NormalizedField field : entity.fields()) {
                    this.targetMemberNames.put(field.id(), this.targetPropertyName(field, true));
                    this.columns.put(
                        field.id(), field.type() instanceof RefType ? this.snake(field.name()) + "_id" : this.snake(field.name()));
                }
                continue;
            }
            if (declaration instanceof NormalizedInput) {
                NormalizedInput input = (NormalizedInput)declaration;
                this.inputs.put(input.id(), input);
                for (NormalizedField field : input.fields()) {
                    this.targetMemberNames.put(field.id(), this.targetPropertyName(field, false));
                }
                continue;
            }
            if (declaration instanceof NormalizedView) {
                NormalizedView view = (NormalizedView)declaration;
                this.views.put(view.id(), view);
                if (view.sourceEntity() != null) {
                    this.viewSourceEntities.put(view.id(), view.sourceEntity());
                }

                for (NormalizedViewField field : view.fields()) {
                    this.targetMemberNames.put(field.id(), field.name());
                }
                continue;
            }
            if (!(declaration instanceof NormalizedEnum)) continue;
            NormalizedEnum enumDeclaration = (NormalizedEnum)declaration;
            for (NormalizedEnum.NormalizedEnumMember member : enumDeclaration.members()) {
                this.targetMemberNames.put(member.id(), member.name());
            }
        }
    }

    public SpringBootLoweredModel lower() {
        ArrayList<SpringBootDeclaration> declarations = new ArrayList<SpringBootDeclaration>();
        ArrayList<SpringArtifact> artifacts = new ArrayList<SpringArtifact>();
        for (NormalizedDeclaration declaration : this.source.declarations()) {
            SpringBootDeclaration lowered = this.lowerDeclaration(declaration);
            declarations.add(lowered);
            artifacts.addAll(this.artifactsFor(declaration, lowered));
        }
        Optional<ActorIdentityTransportPlan> transportPlan = this.buildActorIdentityTransportPlan(declarations);
        ArrayList<ProjectArtifact> projectArtifacts = new ArrayList<ProjectArtifact>();
        if (this.hasPagedFind(declarations)) {
            projectArtifacts.add(this.pageResponseArtifact());
        }

        // The error contract and its constraint primitives belong to the target, not to the mix of
        // capabilities that happen to be declared: a project's supporting files must not appear and
        // disappear as capabilities are added or removed, because a change plan has to account for
        // every artifact that a candidate no longer produces.
        projectArtifacts.addAll(this.errorContractArtifacts());
        projectArtifacts.add(this.validationSupportArtifact());
        projectArtifacts.add(this.applicationConfigArtifact());

        return new SpringBootLoweredModel(LoweredIrVersion.V0_2, SpringBootTargetProfile.V0_2, this.source.softwareName(), this.source.metadata().displayName(), this.source.metadata().namespace(), declarations, artifacts, this.lowerMavenProject(), this.lowerApplicationMain(transportPlan), projectArtifacts);
    }

    private ProjectArtifact.MavenProject lowerMavenProject() {
        List<ProjectArtifact.MavenDependency> dependencies = List.of(new ProjectArtifact.MavenDependency("org.springframework.boot", "spring-boot-starter-web", "3.5.3", "compile"), new ProjectArtifact.MavenDependency("org.springframework.boot", "spring-boot-starter-validation", "3.5.3", "compile"), new ProjectArtifact.MavenDependency("com.baomidou", "mybatis-plus-spring-boot3-starter", "3.5.12", "compile"), new ProjectArtifact.MavenDependency("com.mysql", "mysql-connector-j", "9.3.0", "runtime"), new ProjectArtifact.MavenDependency("org.springframework.boot", "spring-boot-starter-test", "3.5.3", "test"));
        List<ProjectArtifact.MavenPlugin> plugins = List.of(new ProjectArtifact.MavenPlugin("org.springframework.boot", "spring-boot-maven-plugin", "3.5.3"));
        return new ProjectArtifact.MavenProject(this.nodeId("project", "maven"), this.projectOrigin(), "pom.xml", this.source.metadata().namespace(), this.kebab(this.source.softwareName()), "0.1.0-SNAPSHOT", SpringBootTargetProfile.V0_1.javaVersion(), SpringBootTargetProfile.V0_1.springBootVersion(), dependencies, plugins);
    }

    private ProjectArtifact.ApplicationMain lowerApplicationMain(
            Optional<ActorIdentityTransportPlan> transportPlan) {
        String basePackage = this.source.metadata().namespace();
        String simpleName = "Application";
        return new ProjectArtifact.ApplicationMain(this.nodeId("project", "application-main"), this.projectOrigin(), "src/main/java/" + basePackage.replace('.', '/') + "/" + simpleName + ".java", basePackage, simpleName, basePackage + ".persistence", transportPlan);
    }

    /**
     * Whether any capability writes through a request payload.
     *
     * <p>That is where candidate validation, field errors, and declared failure statuses become part of
     * the generated contract, so the supporting artifacts are emitted exactly then.
     */
    private boolean hasWritableCapability(List<SpringBootDeclaration> declarations) {
        return declarations.stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .anyMatch(capability -> capability.kind() == SpringBootDeclaration.CapabilityKind.COMMAND
                        && capability.input().isPresent());
    }

    /**
     * The error envelope, the exception base, the advice, and the constraint primitives.
     *
     * <p>The advice maps every failure the write slice can produce onto those artifacts: a declared
     * failure, a field-level rejection of the candidate, and a request the payload decoder refused.
     */
    private List<ProjectArtifact> errorContractArtifacts() {
        String packageName = this.source.metadata().namespace() + ".api";
        String packagePath = "src/main/java/" + packageName.replace('.', '/') + "/";
        String errorResponse = "ApiErrorResponse";
        String fieldError = "FieldError";
        String exceptionBase = "ApiException";
        return List.of(
                new ProjectArtifact.ApiErrorResponse(
                        this.nodeId("project", "api-error-response"), this.projectOrigin(),
                        packagePath + errorResponse + ".java", packageName, errorResponse, fieldError,
                        this.writePolicy.invalidRequestCode()),
                new ProjectArtifact.ApiExceptionBase(
                        this.nodeId("project", "api-exception-base"), this.projectOrigin(),
                        packagePath + exceptionBase + ".java", packageName, exceptionBase, errorResponse,
                        fieldError, this.writePolicy.invalidRequestCode()),
                new ProjectArtifact.ApiExceptionAdvice(
                        this.nodeId("project", "api-exception-advice"), this.projectOrigin(),
                        packagePath + "ApiExceptionAdvice.java", packageName, "ApiExceptionAdvice", exceptionBase,
                        errorResponse, fieldError, this.writePolicy.invalidRequestCode()));
    }

    /**
     * The constraint primitives a generated candidate check calls.
     *
     * <p>They are emitted only where a payload can write an entity, because that is the only place a
     * merged candidate has to be judged.
     */
    private ProjectArtifact.ValidationSupport validationSupportArtifact() {
        String packageName = this.source.metadata().namespace() + ".api";
        String packagePath = "src/main/java/" + packageName.replace('.', '/') + "/";
        List<String> codes = SpringBootWritePolicy.V0_1.validatedConstraintCodes();
        return new ProjectArtifact.ValidationSupport(
                this.nodeId("project", "validation-support"), this.projectOrigin(),
                packagePath + "ValidationSupport.java", packageName, "ValidationSupport",
                "ApiErrorResponse.FieldError", codes);
    }

    private String softwareName() {
        return this.source.softwareName();
    }

    /** The generated application configuration, including the decoder's unknown-property rule. */
    private ProjectArtifact.ApplicationConfig applicationConfigArtifact() {
        return new ProjectArtifact.ApplicationConfig(
                this.nodeId("project", "application-config"), this.projectOrigin(), "src/main/resources/application.yml",
                this.kebab(this.softwareName()), true);
    }

    private ProjectArtifact.PageResponse pageResponseArtifact() {
        String packageName = this.source.metadata().namespace() + ".api";
        String simpleName = "PageResponse";
        return new ProjectArtifact.PageResponse(this.nodeId("project", "page-response"), this.projectOrigin(), "src/main/java/" + packageName.replace('.', '/') + "/" + simpleName + ".java", packageName, simpleName);
    }

    /** The page envelope is emitted only when a declared workflow actually pages. */
    private boolean hasPagedFind(List<SpringBootDeclaration> declarations) {
        for (SpringBootDeclaration declaration : declarations) {
            if (!(declaration instanceof SpringBootDeclaration.CapabilityDeclaration)) continue;
            SpringBootDeclaration.CapabilityDeclaration capability = (SpringBootDeclaration.CapabilityDeclaration)declaration;
            for (SpringBootWorkflow.Step step : capability.workflow().steps()) {
                if (step instanceof SpringBootWorkflow.FindStep findStep && findStep.page().isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Optional<ActorIdentityTransportPlan> buildActorIdentityTransportPlan(List<SpringBootDeclaration> declarations) {
        ArrayList<ActorIdentityRequirement> requirements = new ArrayList<ActorIdentityRequirement>();
        for (SpringBootDeclaration declaration : declarations) {
            SpringBootDeclaration.CapabilityDeclaration capability;
            if (!(declaration instanceof SpringBootDeclaration.CapabilityDeclaration) || (capability = (SpringBootDeclaration.CapabilityDeclaration)declaration).actor().isEmpty() || capability.actorBinding().isEmpty()) continue;
            SpringBootWorkflow.Variable actorVar = capability.actor().get();
            ActorBinding binding = capability.actorBinding().get();
            SymbolId actorEntitySymbol = this.actorEntitySymbol(actorVar);
            String actorEntityJavaName = this.actorEntityJavaName(actorVar);
            requirements.add(new ActorIdentityRequirement(capability.sourceSymbol(), actorEntitySymbol, actorEntityJavaName, capability.httpMethod(), capability.route(), binding.attributeName(), binding.identityStorageType()));
        }
        if (requirements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ActorIdentityTransportPlan(ActorIdentityTransportPlan.Policy.EXPLICIT_EXTERNAL_OR_LOCAL_FIXED, "kcg.actor-identity.mode", "kcgActorIdentityTransport", "kcg-actor-local", "kcg.actor-identity.local.id", List.copyOf(requirements)));
    }

    private SymbolId actorEntitySymbol(SpringBootWorkflow.Variable actorVar) {
        return switch (actorVar.type()) {
            case LoweredJavaType.Declared declared
                    when declared.kind() == LoweredJavaType.DeclaredKind.ENTITY -> declared.symbolId();
            case LoweredJavaType.EntityReference reference -> reference.entitySymbol();
            default -> throw new IllegalStateException(
                    "actor variable must be Declared[ENTITY] or EntityReference: " + actorVar.type());
        };
    }

    private String actorEntityJavaName(SpringBootWorkflow.Variable actorVar) {
        return switch (actorVar.type()) {
            case LoweredJavaType.Declared declared
                    when declared.kind() == LoweredJavaType.DeclaredKind.ENTITY -> declared.javaName();
            case LoweredJavaType.EntityReference reference -> reference.entityJavaName();
            default -> throw new IllegalStateException(
                    "actor variable must be Declared[ENTITY] or EntityReference: " + actorVar.type());
        };
    }

    private LoweredOrigin projectOrigin() {
        SourcePosition pos = new SourcePosition(0, 1, 1);
        SourceSpan span = new SourceSpan(SourceId.of((String)"project"), pos, pos);
        return new LoweredOrigin(Optional.empty(), new AstNodeId("project"), span);
    }

    private SpringBootDeclaration lowerDeclaration(NormalizedDeclaration declaration) {
        return switch (declaration) {
            case NormalizedEnum value -> lowerEnum(value);
            case NormalizedEntity value -> lowerEntity(value);
            case NormalizedInput value -> lowerInput(value);
            case NormalizedView value -> lowerView(value);
            case NormalizedError value -> new SpringBootDeclaration.ErrorDeclaration(
                    symbolId(value.id(), "exception"),
                    origin(value.id(), value.sourceNodeId(), value.span()),
                    value.id(), value.name() + "Exception", this.lowerHttpStatus(value));
            case NormalizedCapability value -> lowerCapability(value);
        };
    }

    private SpringBootDeclaration.ViewDeclaration lowerView(NormalizedView value) {
        NormalizedEntity entity = this.entities.get(value.sourceEntity());
        if (entity == null) {
            throw new IllegalStateException("view source entity is not a lowered entity: " + value.sourceEntity());
        }

        String entityJavaName = entity.name();
        List<SpringBootDeclaration.ViewField> fields = value.fields().stream().map(field -> new SpringBootDeclaration.ViewField(
                this.symbolId(field.id(), "view-field"),
                this.origin(value.id(), field.sourceNodeId(), field.span()),
                field.id(),
                field.name(),
                this.lowerType(field.type()),
                field.sourceField(),
                this.targetMemberName(field.sourceField(), field.name()),
                this.lowerViewRelation(value, field))).toList();
        return new SpringBootDeclaration.ViewDeclaration(
                this.symbolId(value.id(), "view"),
                this.origin(value.id(), value.sourceNodeId(), value.span()),
                value.id(), value.name(), value.sourceEntity(), entityJavaName, fields);
    }

    /**
     * Turns a nested projection into the batch read it needs.
     *
     * <p>The side that holds the reference decides which property the read keys on, and that is
     * recorded here rather than derived again while rendering: a single related row is found through
     * the reference on this entity, a collection through the reference the related rows hold. A
     * collection is also ordered by the related identity, so the same page always answers with the
     * same order.
     */
    private Optional<SpringBootDeclaration.ViewRelationPlan> lowerViewRelation(NormalizedView view, NormalizedViewField field) {
        NormalizedViewField.Relation relation = field.relation().orElse(null);
        if (relation == null) {
            return Optional.empty();
        }

        NormalizedView targetView = this.views.get(relation.targetView());
        SymbolId targetEntitySymbol = targetView == null ? null : targetView.sourceEntity();
        NormalizedEntity targetEntity = targetEntitySymbol == null ? null : this.entities.get(targetEntitySymbol);
        NormalizedEntity sourceEntity = this.entities.get(view.sourceEntity());
        if (targetEntity == null || sourceEntity == null) {
            throw new IllegalStateException("a nested projection must relate two lowered entities: " + field.name());
        }

        String foreignKeyProperty = this.targetMemberName(field.sourceField(), field.name());
        String sourceIdentityProperty = this.identityPropertyName(sourceEntity);
        String targetIdentityProperty = this.identityPropertyName(targetEntity);
        boolean collection = relation.cardinality() == NormalizedViewField.Cardinality.TO_MANY;
        return Optional.of(new SpringBootDeclaration.ViewRelationPlan(
                this.nodeId(field.sourceNodeId().value(), "view-relation"),
                this.origin(view.id(), field.sourceNodeId(), field.span()),
                field.id(),
                collection ? SpringBootDeclaration.RelationCardinality.TO_MANY : SpringBootDeclaration.RelationCardinality.TO_ONE,
                relation.targetView(),
                targetEntitySymbol,
                collection ? sourceIdentityProperty : foreignKeyProperty,
                collection ? foreignKeyProperty : targetIdentityProperty,
                collection ? foreignKeyProperty : targetIdentityProperty,
                collection ? Optional.of(targetIdentityProperty) : Optional.empty()));
    }

    private String identityPropertyName(NormalizedEntity entity) {
        return this.targetMemberName(entity.identity().id(), entity.identity().name());
    }

    private String targetMemberName(SymbolId symbol, String fallback) {
        return this.targetMemberNames.getOrDefault(symbol, fallback);
    }

    private SpringBootDeclaration.EnumDeclaration lowerEnum(NormalizedEnum value) {
        List<SpringBootDeclaration.EnumMember> members = value.members().stream().map(member -> new SpringBootDeclaration.EnumMember(this.symbolId(member.id(), "enum-member"), this.origin(value.id(), member.sourceNodeId(), member.span()), member.id(), member.name())).toList();
        return new SpringBootDeclaration.EnumDeclaration(this.symbolId(value.id(), "enum"), this.origin(value.id(), value.sourceNodeId(), value.span()), value.id(), value.name(), members);
    }

    /** The response status a declared failure reports, taken from the resolved declaration. */
    private SpringBootDeclaration.HttpStatus lowerHttpStatus(NormalizedError error) {
        return switch (error.httpStatus()) {
            case 404 -> SpringBootDeclaration.HttpStatus.NOT_FOUND;
            case 409 -> SpringBootDeclaration.HttpStatus.CONFLICT;
            case 400 -> SpringBootDeclaration.HttpStatus.BAD_REQUEST;
            default -> throw new IllegalStateException(
                    "validated error status is unsupported by this target: " + error.httpStatus());
        };
    }

    private SpringBootDeclaration.EntityDeclaration lowerEntity(NormalizedEntity value) {
        NormalizedIdentity identity = value.identity();
        SpringBootDeclaration.Identity loweredIdentity = new SpringBootDeclaration.Identity(this.symbolId(identity.id(), "identity"), this.origin(value.id(), identity.sourceNodeId(), identity.span()), identity.id(), identity.name(), this.snake(identity.name()), this.scalar(identity.type()), identity.generation() == AstGenerationStrategy.AUTO ? SpringBootDeclaration.Generation.AUTO_INCREMENT : SpringBootDeclaration.Generation.UUID);
        List<SpringBootDeclaration.Property> fields = value.fields().stream().map(field -> this.lowerProperty((NormalizedField)field, value.id(), true)).toList();
        return new SpringBootDeclaration.EntityDeclaration(this.symbolId(value.id(), "entity"), this.origin(value.id(), value.sourceNodeId(), value.span()), value.id(), value.name(), this.snake(value.name()), loweredIdentity, fields, this.lowerVersionSpec(value));
    }

    /** The entity's version field, with the value the target profile initializes a new row to. */
    private Optional<SpringBootDeclaration.VersionSpec> lowerVersionSpec(NormalizedEntity entity) {
        return entity.versionField().flatMap(fieldSymbol -> entity.fields().stream()
                .filter(field -> field.id().equals(fieldSymbol))
                .findFirst()
                .map(field -> new SpringBootDeclaration.VersionSpec(
                        this.symbolId(field.id(), "version"),
                        this.origin(entity.id(), field.sourceNodeId(), field.span()),
                        field.id(),
                        this.targetPropertyName(field, true),
                        this.snake(field.name()),
                        this.scalar(field.type()),
                        this.writePolicy.versionInitialValue(),
                        this.writePolicy.versionIncrement())));
    }

    private SpringBootDeclaration.InputDeclaration lowerInput(NormalizedInput value) {
        List<SpringBootDeclaration.Property> fields = value.fields().stream().map(field -> this.lowerProperty((NormalizedField)field, value.id(), false)).toList();
        return new SpringBootDeclaration.InputDeclaration(this.symbolId(value.id(), "input"), this.origin(value.id(), value.sourceNodeId(), value.span()), value.id(), value.name(), fields, this.lowerPatchSpec(value));
    }

    /**
     * The payload's transport contract, taken from resolution's payload-field bindings.
     *
     * <p>The identity field is the payload field bound to the entity's identity, and the change set
     * is everything else in authored order; nothing here is matched by name.
     */
    /**
     * The payload declaration behind an input variable.
     *
     * <p>{@code capability.inputSymbol()} is the variable; its declared type names the payload, so this
     * follows the resolved type instead of matching a name.
     */
    private Optional<NormalizedInput> inputDeclaration(SymbolId inputVariableSymbol) {
        Symbol symbol = this.source.symbols().byId(inputVariableSymbol).orElse(null);
        if (!(symbol instanceof Symbol.VariableSymbol variable)
                || !(variable.type() instanceof DeclaredType declared)
                || declared.kind() != DeclaredType.DeclaredKind.INPUT) {
            return Optional.empty();
        }

        return Optional.ofNullable(this.inputs.get(declared.symbolId()));
    }

    private Optional<SpringBootDeclaration.PatchSpec> lowerPatchSpec(NormalizedInput input) {
        if (input.kind() != NormalizedInput.Kind.PATCH) {
            return Optional.empty();
        }

        SymbolId sourceEntitySymbol = input.patchSourceEntity().orElseThrow();
        NormalizedEntity entity = this.entities.get(sourceEntitySymbol);
        if (entity == null) {
            throw new IllegalStateException("patch payload source entity is not an entity of this software: " + sourceEntitySymbol);
        }

        SymbolId identityFieldSymbol = null;
        String identityPropertyName = null;
        List<SpringBootDeclaration.PatchChange> changes = new ArrayList<SpringBootDeclaration.PatchChange>();
        for (NormalizedField field : input.fields()) {
            SymbolId entityMember = field.patchSourceField().orElseThrow();
            String payloadProperty = this.targetPropertyName(field, false);
            if (entityMember.equals(entity.identity().id())) {
                identityFieldSymbol = field.id();
                identityPropertyName = payloadProperty;
                continue;
            }

            changes.add(new SpringBootDeclaration.PatchChange(
                    field.id(),
                    payloadProperty,
                    entityMember,
                    this.targetMemberName(entityMember, "unknown"),
                    this.columns.getOrDefault(entityMember, this.snake(entityMember.value()))));
        }

        if (identityFieldSymbol == null) {
            throw new IllegalStateException("a validated patch payload declares its identity: " + input.name());
        }

        return Optional.of(new SpringBootDeclaration.PatchSpec(
                sourceEntitySymbol,
                entity.name(),
                identityFieldSymbol,
                identityPropertyName,
                this.writePolicy.patchChangesPropertyName(),
                this.writePolicy.patchExpectedVersionPropertyName(),
                changes));
    }

    private SpringBootDeclaration.Property lowerProperty(NormalizedField field, SymbolId owner, boolean persistent) {
        DeclaredType declared;
        SirType sirType;
        SpringBootDeclaration.PersistenceShape shape = !persistent ? SpringBootDeclaration.PersistenceShape.NONE : (field.type() instanceof RefType ? SpringBootDeclaration.PersistenceShape.REFERENCE_ID : ((sirType = field.type()) instanceof DeclaredType && (declared = (DeclaredType)sirType).kind() == DeclaredType.DeclaredKind.ENUM ? SpringBootDeclaration.PersistenceShape.ENUM_TEXT : SpringBootDeclaration.PersistenceShape.SCALAR));
        String column = field.type() instanceof RefType ? this.snake(field.name()) + "_id" : this.snake(field.name());
        List<SpringBootDeclaration.Constraint> constraints = field.constraints().stream().map(constraint -> this.lowerConstraint((NormalizedConstraint)constraint, owner)).toList();
        return new SpringBootDeclaration.Property(this.symbolId(field.id(), "property"), this.origin(owner, field.sourceNodeId(), field.span()), field.id(), this.targetPropertyName(field, persistent), persistent ? Optional.of(column) : Optional.empty(), this.lowerType(field.type()), shape, constraints);
    }

    private SpringBootDeclaration.Constraint lowerConstraint(NormalizedConstraint constraint, SymbolId owner) {
        SpringBootDeclaration.ConstraintKind kind = switch (constraint.name()) {
            case "notBlank" -> SpringBootDeclaration.ConstraintKind.NOT_BLANK;
            case "email" -> SpringBootDeclaration.ConstraintKind.EMAIL;
            case "length" -> SpringBootDeclaration.ConstraintKind.SIZE;
            case "min" -> SpringBootDeclaration.ConstraintKind.DECIMAL_MIN;
            case "max" -> SpringBootDeclaration.ConstraintKind.DECIMAL_MAX;
            default -> throw new IllegalStateException("validated constraint is unsupported: " + constraint.name());
        };
        List<String> arguments = constraint.arguments().stream().map(this::constantValue).toList();
        return new SpringBootDeclaration.Constraint(this.nodeId(constraint.sourceNodeId().value(), "constraint"), this.origin(owner, constraint.sourceNodeId(), constraint.span()), kind, arguments);
    }

    private SpringBootDeclaration.CapabilityDeclaration lowerCapability(NormalizedCapability value) {
        boolean authenticated = value.requires().contains(AstRequirementKind.AUTHENTICATED);
        SpringBootDeclaration.TransactionMode transactionMode = value.requires().contains(AstRequirementKind.READONLY) ? SpringBootDeclaration.TransactionMode.READ_ONLY : (value.requires().contains(AstRequirementKind.ATOMIC) ? SpringBootDeclaration.TransactionMode.REQUIRED : SpringBootDeclaration.TransactionMode.NONE);
        SpringBootDeclaration.CapabilityKind kind = switch (value.exposure()) {
            default -> throw new MatchException(null, null);
            case AstExposureKind.COMMAND -> SpringBootDeclaration.CapabilityKind.COMMAND;
            case AstExposureKind.QUERY -> SpringBootDeclaration.CapabilityKind.QUERY;
        };
        SpringBootDeclaration.HttpMethod httpMethod = this.lowerHttpMethod(kind, value.inputSymbol());
        Optional<SpringBootWorkflow.Variable> actor = value.actorSymbol().map(this::variable);
        Optional<SpringBootWorkflow.Variable> input = value.inputSymbol().map(this::variable);
        Optional<ActorBinding> actorBinding = value.actorSymbol().map(this::lowerActorBinding);
        TransportPlan transportPlan = this.lowerTransportPlan(value, httpMethod, input.isPresent());
        return new SpringBootDeclaration.CapabilityDeclaration(this.symbolId(value.id(), "capability"), this.origin(value.id(), value.sourceNodeId(), value.span()), value.id(), value.name(), value.name() + "Service", value.name() + "Controller", this.lowerFirst(value.name()), httpMethod, "/api/" + this.kebab(value.name()), kind, transactionMode, authenticated, actor, input, actorBinding, transportPlan, this.lowerType(value.outputType()), value.fails(), this.lowerWorkflow(value));
    }

    private ActorBinding lowerActorBinding(SymbolId actorSymbol) {
        DeclaredType declaredType;
        LoweredJavaType.Scalar identityStorageType;
        Symbol.VariableSymbol symbol = (Symbol.VariableSymbol)this.source.symbols().byId(actorSymbol).orElseThrow();
        SirType actorType = symbol.type();
        if (actorType instanceof RefType) {
            RefType refType = (RefType)actorType;
            NormalizedEntity entity = this.entities.get(refType.entityId());
            identityStorageType = this.scalar(entity.identity().type());
        } else if (actorType instanceof DeclaredType && (declaredType = (DeclaredType)actorType).kind() == DeclaredType.DeclaredKind.ENTITY) {
            NormalizedEntity entity = this.entities.get(declaredType.symbolId());
            identityStorageType = this.scalar(entity.identity().type());
        } else {
            throw new IllegalStateException("actor must be an entity ref, got: " + String.valueOf(actorType));
        }
        return new ActorBinding(ActorBinding.ActorKind.REQUEST_ATTRIBUTE, "actorId", identityStorageType);
    }

    /**
     * The HTTP method a capability is exposed as: a query reads with GET, a patch changes with PATCH,
     * and every other command posts.
     */
    private SpringBootDeclaration.HttpMethod lowerHttpMethod(SpringBootDeclaration.CapabilityKind kind, Optional<SymbolId> inputSymbol) {
        if (kind == SpringBootDeclaration.CapabilityKind.QUERY) {
            return SpringBootDeclaration.HttpMethod.GET;
        }

        boolean patchPayload = inputSymbol
                .flatMap(this::inputDeclaration)
                .map(input -> input.kind() == NormalizedInput.Kind.PATCH)
                .orElse(false);
        return patchPayload ? SpringBootDeclaration.HttpMethod.PATCH : SpringBootDeclaration.HttpMethod.POST;
    }

    private TransportPlan lowerTransportPlan(NormalizedCapability capability, SpringBootDeclaration.HttpMethod httpMethod, boolean hasInput) {
        TransportPlan.InputBinding inputBinding = !hasInput ? TransportPlan.InputBinding.NONE : (httpMethod == SpringBootDeclaration.HttpMethod.GET ? TransportPlan.InputBinding.MODEL_ATTRIBUTE : TransportPlan.InputBinding.REQUEST_BODY);
        TransportPlan.ResponseRepresentation response = this.lowerResponseRepresentation(capability);
        return new TransportPlan(inputBinding, response);
    }

    private TransportPlan.ResponseRepresentation lowerResponseRepresentation(NormalizedCapability capability) {
        if (this.projectsReturnedEntity(capability)) {
            return TransportPlan.ResponseRepresentation.PROJECTION;
        }

        return switch (capability.outputType()) {
            case PrimitiveType value -> value == PrimitiveType.UNIT
                    ? TransportPlan.ResponseRepresentation.VOID
                    : TransportPlan.ResponseRepresentation.VALUE;
            case RefType ignored -> TransportPlan.ResponseRepresentation.ENTITY_BODY;
            case DeclaredType value -> value.kind() == DeclaredType.DeclaredKind.ENTITY
                    ? TransportPlan.ResponseRepresentation.ENTITY_BODY
                    : TransportPlan.ResponseRepresentation.VALUE;
            case OptionalType ignored -> TransportPlan.ResponseRepresentation.OPTIONAL;
            case ListType ignored -> TransportPlan.ResponseRepresentation.LIST;
            case PageType ignored -> TransportPlan.ResponseRepresentation.PAGE;
        };
    }

    /**
     * Whether this capability answers with a projection of the entity it returns.
     *
     * <p>That is exactly the case the type rule allowed: a declared view output for the very entity the
     * returned reference points at. The response is then rendered from the projection, never from the
     * entity's own shape.
     */
    private boolean projectsReturnedEntity(NormalizedCapability capability) {
        if (!(capability.outputType() instanceof DeclaredType view) || view.kind() != DeclaredType.DeclaredKind.VIEW) {
            return false;
        }

        SymbolId sourceEntity = this.viewSourceEntities.get(view.symbolId());
        if (sourceEntity == null) {
            return false;
        }

        for (NormalizedStep step : capability.workflow().steps()) {
            if (step instanceof NormalizedStep.ReturnStep returnStep
                    && returnStep.value() != null
                    && returnStep.value().type() instanceof RefType ref) {
                return ref.entityId().equals(sourceEntity);
            }
        }

        return false;
    }

    private SpringBootWorkflow lowerWorkflow(NormalizedCapability capability) {
        Set<SymbolId> entityHoldingVars = this.collectEntityHoldingVariables(capability);
        List<SpringBootWorkflow.Variable> variables = this.source.symbols().all().stream().filter(Symbol.VariableSymbol.class::isInstance).map(Symbol.VariableSymbol.class::cast).filter(symbol -> symbol.id().value().startsWith(capability.id().value() + "/")).map(symbol -> new SpringBootWorkflow.Variable(symbol.id(), symbol.name(), this.lowerVariableType(symbol.type(), entityHoldingVars.contains(symbol.id())))).toList();
        LinkedHashMap<SymbolId, Provenance> provenance = new LinkedHashMap<SymbolId, Provenance>();
        if (capability.actorSymbol().isPresent()) {
            provenance.put((SymbolId)capability.actorSymbol().get(), Provenance.EXISTING);
        }
        if (capability.inputSymbol().isPresent()) {
            provenance.put((SymbolId)capability.inputSymbol().get(), Provenance.EXISTING);
        }
        ArrayList<SpringBootWorkflow.Step> steps = new ArrayList<SpringBootWorkflow.Step>();
        for (NormalizedStep step : capability.workflow().steps()) {
            SpringBootWorkflow.Step lowered = this.lowerStep(step, capability.id(), provenance, capability.actorSymbol());
            steps.add(lowered);
            if (step instanceof NormalizedStep.CreateStep) {
                NormalizedStep.CreateStep createStep = (NormalizedStep.CreateStep)step;
                provenance.put(createStep.resultVariable(), Provenance.NEW);
                continue;
            }
            if (step instanceof NormalizedStep.LoadStep) {
                NormalizedStep.LoadStep loadStep = (NormalizedStep.LoadStep)step;
                provenance.put(loadStep.resultVariable(), Provenance.EXISTING);
                continue;
            }
            if (!(step instanceof NormalizedStep.PersistStep)) continue;
            NormalizedStep.PersistStep persistStep = (NormalizedStep.PersistStep)step;
            provenance.put(persistStep.targetVariable(), Provenance.EXISTING);
        }
        return new SpringBootWorkflow(this.nodeId(capability.workflow().sourceNodeId().value(), "workflow"), this.origin(capability.id(), capability.workflow().sourceNodeId(), capability.workflow().span()), variables, List.copyOf(steps));
    }

    private SpringBootWorkflow.Step lowerStep(NormalizedStep step, SymbolId owner, Map<SymbolId, Provenance> provenance, Optional<SymbolId> actorSymbol) {
        LoweredNodeId id = this.nodeId(step.sourceNodeId().value(), "step");
        LoweredOrigin origin = this.origin(owner, step.sourceNodeId(), step.span());
        return switch (step) {
            case NormalizedStep.ValidateStep value -> new SpringBootWorkflow.ValidateStep(
                    id, origin, lowerExpression(value.condition(), owner, actorSymbol), value.errorSymbol());
            case NormalizedStep.LoadStep value -> new SpringBootWorkflow.LoadStep(
                    id, origin, value.entitySymbol(), lowerExpression(value.idExpression(), owner, actorSymbol),
                    variable(value.resultVariable()), value.errorSymbol(), this.locksRow(value, owner));
            case NormalizedStep.FindStep value -> new SpringBootWorkflow.FindStep(
                    id, origin, value.entitySymbol(),
                    lowerExpression(value.predicate(), owner, actorSymbol, this.findItemSymbol(value)),
                    this.lowerOrderKeys(value), this.lowerPageSpec(value), this.lowerStringMatches(value, owner),
                    this.lowerStatementBudget(value),
                    variable(value.resultVariable()), variable(value.itemVariable()));
            case NormalizedStep.CreateStep value -> new SpringBootWorkflow.CreateStep(
                    id, origin, value.entitySymbol(), variable(value.resultVariable()),
                    value.bindings().stream().map(binding -> lowerBinding(binding, owner, actorSymbol)).toList());
            case NormalizedStep.UpdateStep value -> new SpringBootWorkflow.UpdateStep(
                    id, origin, value.targetVariable(),
                    value.bindings().stream().map(binding -> lowerBinding(binding, owner, actorSymbol)).toList(),
                    this.lowerConditionalUpdate(value, owner, actorSymbol));
            case NormalizedStep.PersistStep value -> {
                Provenance p = provenance.get(value.targetVariable());
                if (p == null) {
                    throw new IllegalStateException(
                            "cannot determine persistence action: unknown provenance for " + value.targetVariable());
                }
                PersistenceAction action = p == Provenance.NEW ? PersistenceAction.INSERT : PersistenceAction.UPDATE;
                NormalizedCapability capability = this.capability(owner);
                NormalizedStep.UpdateStep update = capability == null ? null : this.updateStepFor(capability, value.targetVariable());
                Optional<SpringBootWorkflow.ConditionalUpdate> conditional = action == PersistenceAction.UPDATE
                        ? this.lowerConditionalUpdate(update, owner, actorSymbol)
                        : Optional.empty();
                yield new SpringBootWorkflow.PersistStep(id, origin, value.targetVariable(), action, value.failure(), conditional);
            }
            case NormalizedStep.ReturnStep value -> new SpringBootWorkflow.ReturnStep(
                    id, origin, lowerExpression(value.value(), owner, actorSymbol));
        };
    }

    /**
     * Whether this load must lock the row it reads.
     *
     * <p>A versioned entity that the same capability then conditionally updates needs the row held for
     * the duration of the transaction, so the version the conditional update compares against is the
     * version this load saw.
     */
    private boolean locksRow(NormalizedStep.LoadStep step, SymbolId owner) {
        NormalizedCapability capability = this.capability(owner);
        if (capability == null) {
            return false;
        }

        NormalizedStep.UpdateStep update = this.updateStepFor(capability, step.resultVariable());
        return update != null && this.versionedEntityOfUpdate(update) != null
                && this.conditionalUpdateAllowed(update, owner);
    }

    private Optional<SpringBootWorkflow.ConditionalUpdate> lowerConditionalUpdate(
            NormalizedStep.UpdateStep update, SymbolId owner, Optional<SymbolId> actorSymbol) {
        if (update == null || !this.conditionalUpdateAllowed(update, owner)) {
            return Optional.empty();
        }

        NormalizedEntity entity = this.versionedEntityOfUpdate(update);
        if (entity == null) {
            return Optional.empty();
        }

        SpringBootDeclaration.VersionSpec version = this.lowerVersionSpec(entity).orElseThrow();
        SpringBootDeclaration.PatchSpec patch = this.patchSpecOfCapability(owner);
        if (patch == null) {
            throw new IllegalStateException("a versioned update must use its entity's patch payload: " + owner);
        }

        List<SpringBootWorkflow.ColumnAssignment> assignments = patch.changes().stream()
                .map(change -> new SpringBootWorkflow.ColumnAssignment(
                        change.entityFieldSymbol(),
                        change.entityPropertyName(),
                        change.entityColumnName(),
                        change.payloadPropertyName()))
                .toList();
        return Optional.of(new SpringBootWorkflow.ConditionalUpdate(
                entity.id(),
                this.snake(entity.name()),
                entity.identity().id(),
                entity.identity().name(),
                this.snake(entity.identity().name()),
                version.fieldSymbol(),
                version.javaName(),
                version.columnName(),
                this.writePolicy.versionIncrement(),
                this.persistFailureOf(owner, update.targetVariable()),
                assignments));
    }

    /** The declared failure the conditional persist reports when no row matched its version. */
    private Optional<SymbolId> persistFailureOf(SymbolId owner, SymbolId variable) {
        NormalizedCapability capability = this.capability(owner);
        if (capability == null) {
            return Optional.empty();
        }

        return capability.workflow().steps().stream()
                .filter(NormalizedStep.PersistStep.class::isInstance)
                .map(NormalizedStep.PersistStep.class::cast)
                .filter(persist -> persist.targetVariable().equals(variable))
                .findFirst()
                .flatMap(NormalizedStep.PersistStep::failure);
    }

    /**
     * A versioned update is conditional only when the same variable is the target of both the update
     * and the persist, which is what the semantic rules already guarantee for versioned entities.
     */
    private boolean conditionalUpdateAllowed(NormalizedStep.UpdateStep update, SymbolId owner) {
        NormalizedCapability capability = this.capability(owner);
        if (capability == null) {
            return false;
        }

        return capability.workflow().steps().stream()
                .filter(NormalizedStep.PersistStep.class::isInstance)
                .map(NormalizedStep.PersistStep.class::cast)
                .anyMatch(persist -> persist.targetVariable().equals(update.targetVariable()));
    }

    private NormalizedEntity versionedEntityOfUpdate(NormalizedStep.UpdateStep update) {
        SymbolId entitySymbol = this.entityOfVariable(update.targetVariable());
        if (entitySymbol == null) {
            return null;
        }

        NormalizedEntity entity = this.entities.get(entitySymbol);
        return entity != null && entity.versionField().isPresent() ? entity : null;
    }

    private SymbolId entityOfVariable(SymbolId variableSymbol) {
        Symbol symbol = this.source.symbols().byId(variableSymbol).orElse(null);
        return symbol instanceof Symbol.VariableSymbol variable
                ? switch (variable.type()) {
                    case RefType ref -> ref.entityId();
                    case DeclaredType declared when declared.kind() == DeclaredType.DeclaredKind.ENTITY -> declared.symbolId();
                    default -> null;
                }
                : null;
    }

    private NormalizedStep.UpdateStep updateStepFor(NormalizedCapability capability, SymbolId variable) {
        return capability.workflow().steps().stream()
                .filter(NormalizedStep.UpdateStep.class::isInstance)
                .map(NormalizedStep.UpdateStep.class::cast)
                .filter(step -> step.targetVariable().equals(variable))
                .findFirst()
                .orElse(null);
    }

    private NormalizedCapability capability(SymbolId owner) {
        for (NormalizedDeclaration declaration : this.source.declarations()) {
            if (declaration instanceof NormalizedCapability capability && capability.id().equals(owner)) {
                return capability;
            }
        }

        return null;
    }

    /**
     * The patch payload this capability is given, if any.
     *
     * <p>{@code capability.inputSymbol()} names the capability's input <em>variable</em>, so the payload
     * declaration is reached through that variable's declared type; inferring it from anything else
     * would mean matching by name again.
     */
    private SpringBootDeclaration.PatchSpec patchSpecOfCapability(SymbolId owner) {
        NormalizedCapability capability = this.capability(owner);
        if (capability == null || capability.inputSymbol().isEmpty()) {
            return null;
        }

        NormalizedInput input = this.inputDeclaration(capability.inputSymbol().orElseThrow()).orElse(null);
        return input == null ? null : this.lowerPatchSpec(input).orElse(null);
    }

    private List<SpringBootWorkflow.OrderKey> lowerOrderKeys(NormalizedStep.FindStep step) {
        return step.orderKeys().stream().map(key -> new SpringBootWorkflow.OrderKey(
                key.fieldSymbol(), this.targetMemberName(key.fieldSymbol(), "unknown"), key.descending())).toList();
    }

    private Optional<SpringBootWorkflow.PageSpec> lowerPageSpec(NormalizedStep.FindStep step) {
        return step.page().map(page -> new SpringBootWorkflow.PageSpec(
                page.pageField(),
                page.sizeField(),
                this.targetMemberName(page.pageField(), "page"),
                this.targetMemberName(page.sizeField(), "size"),
                this.queryPolicy.pageDefaultSize(),
                this.queryPolicy.pageMaxSize(),
                this.queryPolicy.pageMaxNumber(),
                page.errorSymbol()));
    }

    /**
     * Every literal string match of the predicate becomes an explicit plan entry, so the renderer
     * never has to decide on its own which comparisons need escaping.
     */
    private List<SpringExpression.StringMatch> lowerStringMatches(NormalizedStep.FindStep step, SymbolId owner) {
        ArrayList<SpringExpression.StringMatch> matches = new ArrayList<SpringExpression.StringMatch>();
        this.collectStringMatches(step.predicate(), owner, matches);
        return List.copyOf(matches);
    }

    private void collectStringMatches(NormalizedExpression expression, SymbolId owner, List<SpringExpression.StringMatch> matches) {
        if (!(expression instanceof NormalizedExpression.BinaryExpression binary)) {
            return;
        }

        if (binary.operator().name().equals("CONTAINS_LITERAL")) {
            matches.add(new SpringExpression.StringMatch(
                    this.nodeId(binary.sourceNodeId().value(), "string-match"),
                    this.origin(owner, binary.sourceNodeId(), binary.span()),
                    this.nodeId(binary.sourceNodeId().value(), "expression"),
                    this.queryPolicy.likeEscapeCharacter(),
                    this.queryPolicy.likeEscapedLiterals()));
        }

        this.collectStringMatches(binary.left(), owner, matches);
        this.collectStringMatches(binary.right(), owner, matches);
    }

    /**
     * The request property a binding reads, when its value is a plain reference to an input member.
     *
     * <p>A field error must point at the property the client actually sent, so this is recorded from
     * the resolved expression shape rather than assumed to equal the entity member's name.
     */
    private Optional<String> sourcePropertyName(SpringExpression value, SymbolId owner) {
        if (value instanceof SpringExpression.MemberExpression member
                && member.receiver() instanceof SpringExpression.NameExpression receiver) {
            Symbol symbol = this.source.symbols().byId(receiver.resolvedSymbol()).orElse(null);
            boolean inputVariable = symbol instanceof Symbol.VariableSymbol variable
                    && "input".equals(variable.name())
                    && variable.id().value().startsWith(owner.value() + "/");
            if (inputVariable) {
                return Optional.of(member.targetMember());
            }
        }

        return Optional.empty();
    }

    private SpringBootWorkflow.Binding lowerBinding(NormalizedBinding binding, SymbolId owner, Optional<SymbolId> actorSymbol) {
        String targetName = this.targetMemberNames.get(binding.fieldSymbol());
        SpringExpression value = this.lowerExpression(binding.value(), owner, actorSymbol);
        return new SpringBootWorkflow.Binding(binding.fieldSymbol(), targetName, value, this.sourcePropertyName(value, owner));
    }

    private SpringExpression lowerExpression(NormalizedExpression expression, SymbolId owner) {
        return this.lowerExpression(expression, owner, Optional.empty());
    }

    /**
     * The request property a presence test reads.
     *
     * <p>The test's target is a member access on the input payload, and its resolved member is the
     * payload field; the property name comes from the target's own naming, so the renderer asks the
     * payload's presence flag by the name the client used.
     */
    private String presencePropertyName(NormalizedExpression.PresentExpression expression) {
        if (expression.target() instanceof NormalizedExpression.MemberExpression member
                && member.resolvedMember().equals(expression.field())) {
            return this.targetMemberNames.getOrDefault(member.resolvedMember(), "unknown");
        }

        throw new IllegalStateException("validated presence test must read an input member: " + expression.sourceNodeId());
    }

    private SpringExpression lowerExpression(NormalizedExpression expression, SymbolId owner, Optional<SymbolId> actorSymbol) {
        return this.lowerExpression(expression, owner, actorSymbol, Optional.empty());
    }

    /**
     * Lowers one normalized expression.
     *
     * <p>{@code itemSymbol} is the variable a {@code find} predicate ranges over, and it is only
     * needed by an existence predicate: inside its correlated subquery that variable stands for the
     * root row, so its references become the root table's columns rather than values. It is passed
     * explicitly instead of being recognised by name so that a variable of the same type from
     * somewhere else in the workflow cannot be mistaken for the row being queried.
     */
    private SpringExpression lowerExpression(
            NormalizedExpression expression, SymbolId owner, Optional<SymbolId> actorSymbol, Optional<SymbolId> itemSymbol) {
        LoweredNodeId id = this.nodeId(expression.sourceNodeId().value(), "expression");
        LoweredOrigin origin = this.origin(owner, expression.sourceNodeId(), expression.span());
        LoweredJavaType type = this.lowerType(expression.type());
        return switch (expression) {
            case NormalizedExpression.BooleanLiteral value ->
                    new SpringExpression.BooleanLiteral(id, origin, type, value.value());
            case NormalizedExpression.IntegerLiteral value ->
                    new SpringExpression.IntegerLiteral(id, origin, type, value.value());
            case NormalizedExpression.DecimalLiteral value ->
                    new SpringExpression.DecimalLiteral(id, origin, type, value.value());
            case NormalizedExpression.StringLiteral value ->
                    new SpringExpression.StringLiteral(id, origin, type, value.value());
            case NormalizedExpression.UnitLiteral ignored -> new SpringExpression.UnitLiteral(id, origin, type);
            case NormalizedExpression.NowExpression ignored -> new SpringExpression.NowExpression(id, origin, type);
            case NormalizedExpression.NameExpression value -> new SpringExpression.NameExpression(
                    id, origin, type, value.resolvedSymbol(),
                    source.symbols().byId(value.resolvedSymbol()).orElseThrow().name());
            case NormalizedExpression.MemberExpression value -> {
                if (actorSymbol.isPresent()
                        && value.receiver() instanceof NormalizedExpression.NameExpression receiver
                        && receiver.resolvedSymbol().equals(actorSymbol.get())) {
                    yield new SpringExpression.NameExpression(
                            id, origin, type, actorSymbol.get(),
                            source.symbols().byId(actorSymbol.get()).orElseThrow().name());
                }
                yield new SpringExpression.MemberExpression(
                        id, origin, type, lowerExpression(value.receiver(), owner, actorSymbol, itemSymbol),
                        value.resolvedMember(),
                        targetMemberNames.getOrDefault(
                                value.resolvedMember(),
                                source.symbols().byId(value.resolvedMember()).orElseThrow().name()));
            }
            case NormalizedExpression.UnaryExpression value -> new SpringExpression.UnaryExpression(
                    id, origin, type, SpringExpression.UnaryOperator.valueOf(value.operator().name()),
                    lowerExpression(value.operand(), owner, actorSymbol, itemSymbol));
            case NormalizedExpression.BinaryExpression value -> new SpringExpression.BinaryExpression(
                    id, origin, type, lowerExpression(value.left(), owner, actorSymbol, itemSymbol),
                    SpringExpression.BinaryOperator.valueOf(value.operator().name()),
                    lowerExpression(value.right(), owner, actorSymbol, itemSymbol));
            case NormalizedExpression.PresentExpression value -> new SpringExpression.PayloadPresence(
                    id, origin, type, this.presencePropertyName(value));
            case NormalizedExpression.ExistsExpression value -> this.lowerExistsPredicate(value, id, origin, type, itemSymbol);
        };
    }

    private Optional<SymbolId> findItemSymbol(NormalizedStep.FindStep step) {
        return Optional.ofNullable(this.source.findItemBindings().get(step.sourceNodeId()));
    }

    /**
     * Lowers an existence predicate into the correlated subquery it stands for.
     *
     * <p>The referenced table and the field that points back at the queried root decide the join,
     * and both come from the model rather than from a guess about naming. Values inside the
     * condition become bound parameters instead of text, so a literal can never change the shape of
     * the statement.
     */
    private SpringExpression lowerExistsPredicate(
            NormalizedExpression.ExistsExpression value, LoweredNodeId id, LoweredOrigin origin,
            LoweredJavaType type, Optional<SymbolId> itemSymbol) {
        NormalizedEntity related = this.entities.get(value.entity());
        Symbol connectionSymbol = this.source.symbols().byId(value.connectionField()).orElse(null);
        SymbolId rootEntitySymbol = connectionSymbol instanceof Symbol.FieldSymbol field && field.type() instanceof RefType ref
                ? ref.entityId()
                : null;
        NormalizedEntity root = rootEntitySymbol == null ? null : this.entities.get(rootEntitySymbol);
        if (related == null || root == null) {
            throw new IllegalStateException("an existence predicate must relate two lowered entities: " + value.entity());
        }

        String alias = this.snake(related.name()) + EXISTENCE_ALIAS_SUFFIX;
        String relatedColumn = this.columns.getOrDefault(value.connectionField(), this.snake(connectionSymbol.name()));
        NormalizedExpression conditions = this.withoutConnection(value.conditions(), value.connectionField(), itemSymbol);
        ExistenceSql condition = conditions == null
                ? new ExistenceSql("", List.of())
                : this.existenceSql(conditions, related, root, alias, itemSymbol, List.of());
        String rootTable = this.snake(root.name());
        String subquery = "SELECT 1 FROM " + this.snake(related.name()) + " " + alias
                + " WHERE " + alias + "." + relatedColumn + " = " + rootTable + "." + this.snake(root.identity().name())
                + (condition.text().isEmpty() ? "" : " AND " + condition.text());
        return new SpringExpression.ExistsPredicate(id, origin, type, subquery, condition.arguments());
    }

    /**
     * The conditions without the comparison that already became the join.
     *
     * <p>The connection is the correlation itself, so repeating it inside the condition would ask
     * the same question twice. Only the top-level conjunction is searched: anywhere else the
     * connection would not mean "this row belongs to the queried row", which the semantic layer
     * already refuses.
     */
    private NormalizedExpression withoutConnection(
            NormalizedExpression expression, SymbolId connectionField, Optional<SymbolId> itemSymbol) {
        if (this.isConnection(expression, connectionField, itemSymbol)) {
            return null;
        }

        if (expression instanceof NormalizedExpression.BinaryExpression binary
                && binary.operator() == io.kcg.sir.ast.AstBinaryOperator.AND) {
            NormalizedExpression left = this.withoutConnection(binary.left(), connectionField, itemSymbol);
            NormalizedExpression right = this.withoutConnection(binary.right(), connectionField, itemSymbol);
            if (left == null) {
                return right;
            }

            if (right == null) {
                return left;
            }

            return binary;
        }

        return expression;
    }

    private boolean isConnection(
            NormalizedExpression expression, SymbolId connectionField, Optional<SymbolId> itemSymbol) {
        if (!(expression instanceof NormalizedExpression.BinaryExpression binary)
                || binary.operator() != io.kcg.sir.ast.AstBinaryOperator.EQ) {
            return false;
        }

        boolean forward = this.namesField(binary.left(), connectionField) && this.namesItem(binary.right(), itemSymbol);
        boolean backward = this.namesField(binary.right(), connectionField) && this.namesItem(binary.left(), itemSymbol);
        return forward || backward;
    }

    private boolean namesField(NormalizedExpression expression, SymbolId fieldSymbol) {
        return expression instanceof NormalizedExpression.NameExpression name && name.resolvedSymbol().equals(fieldSymbol);
    }

    private boolean namesItem(NormalizedExpression expression, Optional<SymbolId> itemSymbol) {
        return itemSymbol.isPresent()
                && expression instanceof NormalizedExpression.NameExpression name
                && name.resolvedSymbol().equals(itemSymbol.get());
    }

    /**
     * The correlated condition text and the values it binds, in placeholder order.
     *
     * <p>The recognised shapes are the ones the target can translate without guessing: comparisons
     * of related columns with the queried row's columns, literals or enum members, combined with
     * {@code and}, {@code or} and {@code not}. Anything else is rejected before lowering, so this
     * never silently drops a condition.
     */
    private ExistenceSql existenceSql(
            NormalizedExpression expression, NormalizedEntity related, NormalizedEntity root, String alias,
            Optional<SymbolId> itemSymbol, List<SpringExpression.ExistsArgument> arguments) {
        return switch (expression) {
            case NormalizedExpression.BinaryExpression binary when isComparison(binary.operator()) -> {
                ExistenceSql left = this.existenceSql(binary.left(), related, root, alias, itemSymbol, arguments);
                ExistenceSql right = this.existenceSql(binary.right(), related, root, alias, itemSymbol, left.arguments());
                yield new ExistenceSql(left.text() + " " + comparisonOperator(binary.operator()) + " " + right.text(), right.arguments());
            }
            case NormalizedExpression.BinaryExpression binary when binary.operator() == io.kcg.sir.ast.AstBinaryOperator.AND
                    || binary.operator() == io.kcg.sir.ast.AstBinaryOperator.OR -> {
                ExistenceSql left = this.existenceSql(binary.left(), related, root, alias, itemSymbol, arguments);
                ExistenceSql right = this.existenceSql(binary.right(), related, root, alias, itemSymbol, left.arguments());
                String operator = binary.operator() == io.kcg.sir.ast.AstBinaryOperator.AND ? " AND " : " OR ";
                yield new ExistenceSql("(" + left.text() + operator + right.text() + ")", right.arguments());
            }
            case NormalizedExpression.UnaryExpression unary when unary.operator() == io.kcg.sir.ast.AstUnaryOperator.NOT -> {
                ExistenceSql operand = this.existenceSql(unary.operand(), related, root, alias, itemSymbol, arguments);
                yield new ExistenceSql("NOT (" + operand.text() + ")", operand.arguments());
            }
            case NormalizedExpression.NameExpression name -> new ExistenceSql(
                    this.columnReference(name.resolvedSymbol(), related, root, alias, itemSymbol), arguments);
            case NormalizedExpression.MemberExpression member -> this.memberReference(member, related, root, alias, itemSymbol, arguments);
            case NormalizedExpression.StringLiteral literal -> this.boundValue(new SpringExpression.ExistsArgument.Text(literal.value()), arguments);
            case NormalizedExpression.BooleanLiteral literal -> this.boundValue(new SpringExpression.ExistsArgument.Flag(literal.value()), arguments);
            case NormalizedExpression.IntegerLiteral literal -> this.boundValue(
                    new SpringExpression.ExistsArgument.Integral(literal.value().longValueExact()), arguments);
            case NormalizedExpression.DecimalLiteral literal -> this.boundValue(
                    new SpringExpression.ExistsArgument.Decimal(literal.value()), arguments);
            default -> throw new IllegalStateException(
                    "unsupported existence-predicate condition reached lowering: " + expression.getClass().getSimpleName());
        };
    }

    /** A related column, or a column of the queried row when the reference names the find item. */
    private String columnReference(
            SymbolId resolvedSymbol, NormalizedEntity related, NormalizedEntity root, String alias, Optional<SymbolId> itemSymbol) {
        if (itemSymbol.isPresent() && resolvedSymbol.equals(itemSymbol.get())) {
            return this.snake(root.name()) + "." + this.snake(root.identity().name());
        }

        boolean relatedField = related.fields().stream().anyMatch(field -> field.id().equals(resolvedSymbol));
        Symbol symbol = this.source.symbols().byId(resolvedSymbol).orElse(null);
        if (!relatedField || !(symbol instanceof Symbol.FieldSymbol field)) {
            throw new IllegalStateException("unsupported existence-predicate reference: " + symbol);
        }

        return alias + "." + this.columns.getOrDefault(resolvedSymbol, this.snake(field.name()));
    }

    /** A column of the queried row, or the persisted text of an enum member. */
    private ExistenceSql memberReference(
            NormalizedExpression.MemberExpression member, NormalizedEntity related, NormalizedEntity root, String alias,
            Optional<SymbolId> itemSymbol, List<SpringExpression.ExistsArgument> arguments) {
        boolean onItem = itemSymbol.isPresent()
                && member.receiver() instanceof NormalizedExpression.NameExpression receiver
                && receiver.resolvedSymbol().equals(itemSymbol.get());
        if (onItem) {
            Symbol memberSymbol = this.source.symbols().byId(member.resolvedMember()).orElseThrow();
            return new ExistenceSql(
                    this.snake(root.name()) + "." + this.columns.getOrDefault(member.resolvedMember(), this.snake(memberSymbol.name())),
                    arguments);
        }

        Symbol memberOwner = this.source.symbols().byId(member.resolvedMember()).orElse(null);
        Symbol enumType = member.receiver() instanceof NormalizedExpression.NameExpression receiver
                ? this.source.symbols().byId(receiver.resolvedSymbol()).orElse(null)
                : null;
        if (enumType instanceof Symbol.TypeSymbol type && type.kind() == io.kcg.sir.semantic.symbol.SymbolKind.ENUM
                && memberOwner instanceof Symbol.EnumMemberSymbol) {
            return this.boundValue(new SpringExpression.ExistsArgument.Text(memberOwner.name()), arguments);
        }

        throw new IllegalStateException("unsupported existence-predicate member reference: " + member);
    }

    private ExistenceSql boundValue(SpringExpression.ExistsArgument value, List<SpringExpression.ExistsArgument> arguments) {
        ArrayList<SpringExpression.ExistsArgument> all = new ArrayList<SpringExpression.ExistsArgument>(arguments);
        String placeholder = "{" + all.size() + "}";
        all.add(value);
        return new ExistenceSql(placeholder, List.copyOf(all));
    }

    private static boolean isComparison(io.kcg.sir.ast.AstBinaryOperator operator) {
        return switch (operator) {
            case EQ, NE, LT, LE, GT, GE -> true;
            default -> false;
        };
    }

    private static String comparisonOperator(io.kcg.sir.ast.AstBinaryOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            default -> throw new IllegalArgumentException("not a comparison operator: " + operator);
        };
    }

    /** The set of reads one find promises, counted from the projection it answers with. */
    private SpringBootWorkflow.StatementBudget lowerStatementBudget(NormalizedStep.FindStep step) {
        int pageStatements = step.page().isPresent() ? 2 : 1;
        SymbolId viewSymbol = this.responseViewSymbol(step);
        return new SpringBootWorkflow.StatementBudget(pageStatements, viewSymbol == null ? 0 : this.associationReadCount(viewSymbol));
    }

    private SymbolId responseViewSymbol(NormalizedStep.FindStep step) {
        Symbol symbol = this.source.symbols().byId(step.resultVariable()).orElse(null);
        SirType type = symbol instanceof Symbol.VariableSymbol variable ? variable.type() : null;
        if (type instanceof PageType page) {
            type = page.element();
        }

        if (type instanceof ListType list) {
            type = list.element();
        }

        return type instanceof DeclaredType declared && declared.kind() == DeclaredType.DeclaredKind.VIEW ? declared.symbolId() : null;
    }

    /** Every association below this view costs one batch read, at every declared projection level. */
    private int associationReadCount(SymbolId viewSymbol) {
        NormalizedView view = this.views.get(viewSymbol);
        if (view == null) {
            return 0;
        }

        int count = 0;
        for (NormalizedViewField field : view.fields()) {
            if (field.relation().isPresent()) {
                count += 1 + this.associationReadCount(field.relation().get().targetView());
            }
        }

        return count;
    }

    /** The suffix of the alias a correlated subquery gives the related table. */
    private static final String EXISTENCE_ALIAS_SUFFIX = "_rel";

    /** The condition text and the values it binds, in placeholder order. */
    private record ExistenceSql(String text, List<SpringExpression.ExistsArgument> arguments) {
        private ExistenceSql {
            Objects.requireNonNull(text, "text");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }
    }


    private SpringBootWorkflow.Variable variable(SymbolId symbolId) {
        Symbol.VariableSymbol symbol = (Symbol.VariableSymbol)this.source.symbols().byId(symbolId).orElseThrow();
        return new SpringBootWorkflow.Variable(symbol.id(), symbol.name(), this.lowerVariableType(symbol.type(), true));
    }

    private Set<SymbolId> collectEntityHoldingVariables(NormalizedCapability capability) {
        LinkedHashSet<SymbolId> ids = new LinkedHashSet<SymbolId>();
        for (NormalizedStep step : capability.workflow().steps()) {
            switch (step) {
                case NormalizedStep.LoadStep s -> ids.add(s.resultVariable());
                case NormalizedStep.CreateStep s -> ids.add(s.resultVariable());
                case NormalizedStep.FindStep s -> {
                    ids.add(s.resultVariable());
                    ids.add(s.itemVariable());
                }
                default -> { }
            }
        }
        return ids;
    }

    private LoweredJavaType lowerVariableType(SirType type, boolean entityHolding) {
        LoweredJavaType lowered = this.lowerType(type);
        if (entityHolding && lowered instanceof LoweredJavaType.EntityReference) {
            LoweredJavaType.EntityReference ref = (LoweredJavaType.EntityReference)lowered;
            return new LoweredJavaType.Declared(LoweredJavaType.DeclaredKind.ENTITY, ref.entitySymbol(), ref.entityJavaName());
        }
        return lowered;
    }

    private LoweredJavaType lowerType(SirType type) {
        return switch (type) {
            case PrimitiveType value -> scalar(value);
            case OptionalType value -> new LoweredJavaType.OptionalValue(lowerType(value.element()));
            case ListType value -> new LoweredJavaType.ListValue(lowerType(value.element()));
            case PageType value -> new LoweredJavaType.PageValue(lowerType(value.element()));
            case DeclaredType value -> new LoweredJavaType.Declared(
                    LoweredJavaType.DeclaredKind.valueOf(value.kind().name()), value.symbolId(), value.name());
            case RefType value -> {
                NormalizedEntity entity = this.entities.get(value.entityId());
                yield new LoweredJavaType.EntityReference(
                        value.entityId(), value.entityName(), scalar(entity.identity().type()));
            }
        };
    }

    private LoweredJavaType.Scalar scalar(SirType type) {
        PrimitiveType primitive = (PrimitiveType)type;
        LoweredJavaType.ScalarKind kind = switch (primitive) {
            default -> throw new MatchException(null, null);
            case PrimitiveType.BOOLEAN -> LoweredJavaType.ScalarKind.BOOLEAN;
            case PrimitiveType.INT32 -> LoweredJavaType.ScalarKind.INTEGER;
            case PrimitiveType.INT64 -> LoweredJavaType.ScalarKind.LONG;
            case PrimitiveType.DECIMAL -> LoweredJavaType.ScalarKind.BIG_DECIMAL;
            case PrimitiveType.STRING -> LoweredJavaType.ScalarKind.STRING;
            case PrimitiveType.UUID -> LoweredJavaType.ScalarKind.UUID;
            case PrimitiveType.DATE -> LoweredJavaType.ScalarKind.LOCAL_DATE;
            case PrimitiveType.DATE_TIME -> LoweredJavaType.ScalarKind.INSTANT;
            case PrimitiveType.UNIT -> LoweredJavaType.ScalarKind.VOID;
        };
        return new LoweredJavaType.Scalar(kind);
    }

    private List<SpringArtifact> artifactsFor(NormalizedDeclaration sourceDeclaration, SpringBootDeclaration lowered) {
        ArrayList<SpringArtifact> result = new ArrayList<SpringArtifact>();
        switch (lowered) {
            case SpringBootDeclaration.EnumDeclaration value ->
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.ENUM, this.source.metadata().namespace() + ".domain", value.javaName()));
            case SpringBootDeclaration.EntityDeclaration value -> {
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.ENTITY_MODEL, this.source.metadata().namespace() + ".domain", value.javaName()));
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.MAPPER, this.source.metadata().namespace() + ".persistence", value.javaName() + "Mapper"));
            }
            case SpringBootDeclaration.InputDeclaration value ->
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.REQUEST_DTO, this.source.metadata().namespace() + ".api", value.javaName()));
            case SpringBootDeclaration.ViewDeclaration value ->
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.VIEW_DTO, this.source.metadata().namespace() + ".api", value.javaName()));
            case SpringBootDeclaration.ErrorDeclaration value ->
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.EXCEPTION, this.source.metadata().namespace() + ".api", value.javaName()));
            case SpringBootDeclaration.CapabilityDeclaration value -> {
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.SERVICE, this.source.metadata().namespace() + ".application", value.serviceName()));
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.CONTROLLER, this.source.metadata().namespace() + ".api", value.controllerName()));
            }
        }
        return List.copyOf(result);
    }

    private SpringArtifact artifact(NormalizedDeclaration declaration, LoweredOrigin origin, SpringArtifact.Role role, String packageName, String simpleName) {
        return new SpringArtifact(this.symbolId(declaration.id(), "artifact-" + role.name().toLowerCase(Locale.ROOT)), origin, declaration.id(), role, packageName, simpleName);
    }

    private LoweredNodeId symbolId(SymbolId symbolId, String role) {
        return this.nodeId(symbolId.value(), role);
    }

    private LoweredNodeId nodeId(String sourceId, String role) {
        String encoded = URLEncoder.encode(sourceId, StandardCharsets.UTF_8);
        String profileId = SpringBootTargetProfile.V0_2.id();
        return new LoweredNodeId("lir://" + profileId + "/" + role + "/" + encoded);
    }

    private LoweredOrigin origin(SymbolId owner, AstNodeId nodeId, SourceSpan span) {
        return new LoweredOrigin(Optional.of(owner), nodeId, span);
    }

    private String constantValue(NormalizedExpression expression) {
        return switch (expression) {
            case NormalizedExpression.IntegerLiteral value -> value.value().toString();
            case NormalizedExpression.DecimalLiteral value -> value.value().toPlainString();
            case NormalizedExpression.UnaryExpression value -> value.operator().name().equals("NEGATE")
                    ? "-" + constantValue(value.operand())
                    : "!" + constantValue(value.operand());
            default -> throw new IllegalStateException("validated constraint argument is not constant");
        };
    }

    private String lowerFirst(String value) {
        int first = value.codePointAt(0);
        return new String(Character.toChars(Character.toLowerCase(first))) + value.substring(Character.charCount(first));
    }

    private String targetPropertyName(NormalizedField field, boolean persistent) {
        return field.type() instanceof RefType ? field.name() + "Id" : field.name();
    }

    private String snake(String value) {
        return this.separated(value, '_');
    }

    private String kebab(String value) {
        return this.separated(value, '-');
    }

    private String separated(String value, char separator) {
        int point;
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length(); offset += Character.charCount(point)) {
            point = value.codePointAt(offset);
            if (Character.isUpperCase(point) && result.length() > 0) {
                result.append(separator);
            }
            result.appendCodePoint(Character.toLowerCase(point));
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static enum Provenance {
        NEW,
        EXISTING;

    }
}
