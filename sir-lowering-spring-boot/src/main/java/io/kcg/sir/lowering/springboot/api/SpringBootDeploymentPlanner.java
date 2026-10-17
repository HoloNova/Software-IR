package io.kcg.sir.lowering.springboot.api;

import io.kcg.sir.lowering.springboot.model.*;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.*;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.*;

/** Plans a separate, opt-in delivery without changing the ordinary generation contract. */
public final class SpringBootDeploymentPlanner {
    public DeploymentPlan plan(NormalizedSemanticModel semantic, SpringBootLoweredModel model) {
        Map<SymbolId, NormalizedField> fields = new HashMap<>();
        semantic.declarations().stream().filter(NormalizedEntity.class::isInstance).map(NormalizedEntity.class::cast)
            .forEach(e -> e.fields().forEach(f -> fields.put(f.id(), f)));
        Map<SymbolId, SpringBootDeclaration.EntityDeclaration> entities = new LinkedHashMap<>();
        model.declarations().stream().filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.EntityDeclaration.class::cast).forEach(e -> entities.put(e.sourceSymbol(), e));
        Set<SymbolId> credentialActors = new HashSet<>();
        model.projectArtifacts().stream().filter(ProjectArtifact.CredentialIdentityAdapter.class::isInstance)
            .map(ProjectArtifact.CredentialIdentityAdapter.class::cast).forEach(a -> a.sources().forEach(s -> credentialActors.add(s.actorEntity())));
        model.applicationMain().actorIdentityTransportPlan().ifPresent(t -> t.requirements().forEach(r -> {
            if (!credentialActors.contains(r.actorEntitySymbol())) throw new IllegalArgumentException(
                "authenticated actor requires an external adapter not supplied by this delivery: " + r.actorEntityJavaName());
        }));
        List<Table> tables = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (var entity : entities.values()) {
            if (!names.add(entity.tableName().toLowerCase(Locale.ROOT)) || entity.tableName().equalsIgnoreCase("flyway_schema_history"))
                throw new IllegalArgumentException("conflicting deployment table: " + entity.tableName());
            List<Column> columns = new ArrayList<>();
            var identity = entity.identity();
            columns.add(new Column(identity.sourceSymbol().value(), identity.columnName(), scalar(identity.type()), 0, false, true,
                identity.generation() == SpringBootDeclaration.Generation.AUTO_INCREMENT, false, Optional.empty()));
            for (var property : entity.fields()) {
                var field = Objects.requireNonNull(fields.get(property.sourceSymbol()));
                LoweredJavaType type = property.type();
                boolean nullable = type instanceof LoweredJavaType.OptionalValue;
                if (nullable) type = ((LoweredJavaType.OptionalValue) type).elementType();
                Storage storage;
                int length = 0;
                Optional<ForeignKey> reference = Optional.empty();
                if (field.secret()) { storage = Storage.VARCHAR; length = 255; }
                else if (type instanceof LoweredJavaType.EntityReference ref) {
                    var target = Objects.requireNonNull(entities.get(ref.entitySymbol()));
                    storage = scalar(ref.identityStorageType());
                    reference = Optional.of(new ForeignKey(target.tableName(), target.identity().columnName()));
                } else if (type instanceof LoweredJavaType.Declared declared && declared.kind() == LoweredJavaType.DeclaredKind.ENUM) {
                    var enumeration = model.declarations().stream().filter(SpringBootDeclaration.EnumDeclaration.class::isInstance)
                        .map(SpringBootDeclaration.EnumDeclaration.class::cast).filter(e -> e.sourceSymbol().equals(declared.symbolId())).findFirst().orElseThrow();
                    storage = Storage.VARCHAR;
                    length = Math.max(1, enumeration.members().stream().mapToInt(m -> m.javaName().length()).max().orElse(1));
                } else if (type instanceof LoweredJavaType.Scalar value) {
                    storage = scalar(value);
                    if (storage == Storage.LONGTEXT) {
                        int max = property.constraints().stream().filter(c -> c.kind() == SpringBootDeclaration.ConstraintKind.SIZE)
                            .mapToInt(c -> Integer.parseInt(c.arguments().get(1))).min().orElse(Integer.MAX_VALUE);
                        if (max > 0 && max <= 768) { storage = Storage.VARCHAR; length = max; }
                    }
                } else throw new IllegalArgumentException("unsupported persistent deployment type: " + type);
                if (field.unique() && storage == Storage.LONGTEXT)
                    throw new IllegalArgumentException("unique text needs a declared length at most 768: " + field.name());
                columns.add(new Column(property.sourceSymbol().value(), property.columnName().orElseThrow(), storage, length, nullable, false, false, field.unique(), reference));
            }
            Set<String> columnNames = new HashSet<>();
            int inlineBytes = 0;
            for (Column c : columns) {
                if (!columnNames.add(c.name().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("conflicting deployment column: " + c.name());
                inlineBytes += c.storage() == Storage.VARCHAR ? 4 * c.length() + 2 : c.storage() == Storage.UUID ? 145 : 32;
            }
            long indexes = columns.stream().filter(c -> c.primary() || c.unique() || c.reference().isPresent()).count();
            if (inlineBytes > 60000 || columns.size() > 1000 || indexes > 64)
                throw new IllegalArgumentException("deployment exceeds conservative MySQL row/column/index budget: " + entity.tableName());
            tables.add(new Table(entity.sourceSymbol().value(), entity.tableName(), columns));
        }
        var original = model.mavenProject();
        var dependencies = new ArrayList<>(original.dependencies());
        dependencies.add(new ProjectArtifact.MavenDependency("org.springframework.boot", "spring-boot-starter-actuator", "3.5.3", "compile"));
        // Interface documentation is generated from the running controllers; 2.8.9 is built against Spring Boot 3.5.
        dependencies.add(new ProjectArtifact.MavenDependency("org.springdoc", "springdoc-openapi-starter-webmvc-ui", "2.8.9", "compile"));
        var runtimeProject = new ProjectArtifact.MavenProject(original.id(), original.origin(), original.path(), original.groupId(),
            original.artifactId(), original.version(), original.javaVersion(), original.springBootParentVersion(), dependencies, original.plugins());
        return new DeploymentPlan(original.id(), runtimeProject, tables, !credentialActors.isEmpty(),
            new Images("maven:3.9.9-eclipse-temurin-21", "eclipse-temurin:21.0.7_6-jre-jammy", "mysql:8.4.5"),
            api(model, original.version()));
    }

    private static Api api(SpringBootLoweredModel model, String version) {
        var envelope = model.projectArtifacts().stream().filter(ProjectArtifact.ApiErrorResponse.class::isInstance)
            .map(ProjectArtifact.ApiErrorResponse.class::cast).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("the target emitted no API error envelope"));
        return new Api(model.basePackage(), model.displayName(), version, envelope.packageName() + "." + envelope.simpleName(),
            endpoints(model, envelope.invalidRequestCode()));
    }

    private static List<Endpoint> endpoints(SpringBootLoweredModel model, String invalidRequestCode) {
        Map<SymbolId, SpringBootDeclaration.ErrorDeclaration> declaredErrors = new HashMap<>();
        model.declarations().stream().filter(SpringBootDeclaration.ErrorDeclaration.class::isInstance)
            .map(SpringBootDeclaration.ErrorDeclaration.class::cast).forEach(e -> declaredErrors.put(e.sourceSymbol(), e));
        Map<SymbolId, SpringBootDeclaration.InputDeclaration> inputs = new HashMap<>();
        model.declarations().stream().filter(SpringBootDeclaration.InputDeclaration.class::isInstance)
            .map(SpringBootDeclaration.InputDeclaration.class::cast).forEach(i -> inputs.put(i.sourceSymbol(), i));
        List<Endpoint> endpoints = new ArrayList<>();
        model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).forEach(capability -> {
                Binding binding = switch (capability.transportPlan().inputBinding()) {
                    case NONE -> Binding.NONE;
                    case REQUEST_BODY -> Binding.BODY;
                    case MODEL_ATTRIBUTE -> Binding.QUERY;
                };
                List<TextRule> rules = new ArrayList<>();
                var input = capability.input().map(SpringBootWorkflow.Variable::type)
                    .filter(LoweredJavaType.Declared.class::isInstance).map(t -> inputs.get(((LoweredJavaType.Declared) t).symbolId()));
                if (binding == Binding.BODY && input.isPresent() && input.get() != null && input.get().patch().isEmpty())
                    input.get().fields().forEach(field -> textRule(field).ifPresent(rules::add));
                List<DeclaredError> errors = new ArrayList<>();
                // A JSON body is decoded and validated by the generated advice, which answers with the request envelope.
                if (binding == Binding.BODY) errors.add(new DeclaredError(400, invalidRequestCode));
                for (SymbolId failure : capability.failures()) {
                    var error = Objects.requireNonNull(declaredErrors.get(failure), "declared failure");
                    errors.add(new DeclaredError(switch (error.httpStatus()) {
                        case BAD_REQUEST -> 400; case UNAUTHORIZED -> 401; case NOT_FOUND -> 404; case CONFLICT -> 409;
                    }, errorCode(error)));
                }
                endpoints.add(new Endpoint(capability.httpMethod().name(), capability.route(), capability.authenticated(), binding, rules, errors));
            });
        return endpoints;
    }

    /** The code the application answers with: the SIR error name, exactly as the generated exception passes it. */
    private static String errorCode(SpringBootDeclaration.ErrorDeclaration error) {
        String symbol = error.sourceSymbol().value();
        return symbol.substring(symbol.lastIndexOf('/') + 1);
    }

    private static Optional<TextRule> textRule(SpringBootDeclaration.Property field) {
        LoweredJavaType type = field.type() instanceof LoweredJavaType.OptionalValue optional ? optional.elementType() : field.type();
        if (!(type instanceof LoweredJavaType.Scalar scalar) || scalar.kind() != LoweredJavaType.ScalarKind.STRING) return Optional.empty();
        boolean notBlank = false;
        int min = 0;
        OptionalInt max = OptionalInt.empty();
        for (var constraint : field.constraints()) {
            if (constraint.kind() == SpringBootDeclaration.ConstraintKind.NOT_BLANK) notBlank = true;
            if (constraint.kind() == SpringBootDeclaration.ConstraintKind.SIZE) {
                min = Math.max(min, Integer.parseInt(constraint.arguments().get(0)));
                int upper = Integer.parseInt(constraint.arguments().get(1));
                max = OptionalInt.of(max.isPresent() ? Math.min(max.getAsInt(), upper) : upper);
            }
        }
        return notBlank || min > 0 || max.isPresent() ? Optional.of(new TextRule(field.javaName(), notBlank, min, max)) : Optional.empty();
    }

    private static Storage scalar(LoweredJavaType.Scalar type) {
        return switch (type.kind()) {
            case INTEGER -> Storage.INT;
            case LONG -> Storage.BIGINT;
            case BOOLEAN -> Storage.BOOLEAN;
            case STRING -> Storage.LONGTEXT;
            case UUID -> Storage.UUID;
            case LOCAL_DATE -> Storage.DATE;
            case INSTANT -> Storage.DATETIME;
            // The language has no physical Decimal precision/scale contract yet. Never round it silently.
            case BIG_DECIMAL -> throw new IllegalArgumentException("Decimal deployment requires an explicit physical precision/scale contract; not supported in delivery v1");
            case VOID -> throw new IllegalArgumentException("Unit cannot be persisted");
        };
    }
}
