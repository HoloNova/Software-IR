package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ActorIdentityTransportPlan;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.CredentialIdentityAdapter;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.CredentialSource;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Property;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Optional;

/**
 * Renders the authenticator that recognises the bearer tokens of the credential entities.
 *
 * <p>It is the bean the {@code external} actor mode asks for (same name, the only adapter), so the
 * application needs no new mode: the deployer still sets {@code kcg.actor-identity.mode=external}. It is
 * conditional on that setting, so a local development run in {@code local-fixed} mode, which refuses any
 * adapter bean, still starts. A request counts as authenticated only when its header carries a token that
 * is a well-formed uuid, names a stored credential and has not expired; anything else answers no identity,
 * and the interceptor turns that into a 401. Which credential entity answers is decided by the actor
 * entity the route declares.
 */
final class CredentialIdentityAdapterRenderer {

    private CredentialIdentityAdapterRenderer() {
    }

    static GeneratedFile render(GenerationContext ctx, CredentialIdentityAdapter adapter) {
        ImportSorter imports = new ImportSorter();
        imports.add("jakarta.servlet.http.HttpServletRequest");
        imports.add("java.time.Instant");
        imports.add("java.util.Optional");
        imports.add("java.util.UUID");
        imports.add("org.springframework.boot.autoconfigure.condition.ConditionalOnProperty");
        imports.add("org.springframework.stereotype.Component");
        imports.add(ctx.model().applicationMain().packageName() + "." + ctx.model().applicationMain().simpleName());
        for (CredentialSource source : adapter.sources()) {
            imports.add(ctx.artifact(source.actorEntity(), Role.ENTITY_MODEL).qualifiedName());
            imports.add(ctx.artifact(source.credentialEntity(), Role.ENTITY_MODEL).qualifiedName());
            imports.add(ctx.artifact(source.credentialEntity(), Role.MAPPER).qualifiedName());
        }

        String content = GenerationContext.assembleSource(adapter.packageName(), imports, renderBody(ctx, adapter));
        return new GeneratedFile(adapter.path(), content, adapter.id(), Optional.empty());
    }

    private static String renderBody(GenerationContext ctx, CredentialIdentityAdapter adapter) {
        String application = ctx.model().applicationMain().simpleName();
        StringBuilder out = new StringBuilder();
        out.append("@Component(").append(StringEscape.javaString(ActorIdentityTransportPlan.EXTERNAL_ADAPTER_BEAN_NAME)).append(")\n");
        out.append("@ConditionalOnProperty(name = ").append(StringEscape.javaString(ActorIdentityTransportPlan.MODE_PROPERTY))
            .append(", havingValue = \"external\")\n");
        out.append("public class ").append(adapter.simpleName()).append(" implements ").append(application).append(".ActorIdentityAdapter {\n\n");
        out.append("    private static final String SCHEME = \"Bearer \";\n\n");
        for (CredentialSource source : adapter.sources()) {
            SpringArtifact mapper = ctx.artifact(source.credentialEntity(), Role.MAPPER);
            out.append("    private final ").append(mapper.simpleName()).append(' ').append(lowerFirst(mapper.simpleName())).append(";\n");
        }

        out.append("\n    public ").append(adapter.simpleName()).append("(");
        String separator = "";
        for (CredentialSource source : adapter.sources()) {
            SpringArtifact mapper = ctx.artifact(source.credentialEntity(), Role.MAPPER);
            out.append(separator).append(mapper.simpleName()).append(' ').append(lowerFirst(mapper.simpleName()));
            separator = ", ";
        }

        out.append(") {\n");
        for (CredentialSource source : adapter.sources()) {
            String name = lowerFirst(ctx.artifact(source.credentialEntity(), Role.MAPPER).simpleName());
            out.append("        this.").append(name).append(" = ").append(name).append(";\n");
        }

        out.append("    }\n\n");
        out.append("    @Override\n");
        out.append("    public Optional<?> resolveAuthenticatedIdentity(HttpServletRequest request, ").append(application).append(".ActorIdentityContext context) {\n");
        out.append("        UUID token = bearerToken(request);\n");
        out.append("        if (token == null) {\n");
        out.append("            return Optional.empty();\n");
        out.append("        }\n");
        for (CredentialSource source : adapter.sources()) {
            SpringArtifact actor = ctx.artifact(source.actorEntity(), Role.ENTITY_MODEL);
            SpringArtifact credential = ctx.artifact(source.credentialEntity(), Role.ENTITY_MODEL);
            out.append("        if (").append(actor.simpleName()).append(".class.equals(context.actorEntityType())) {\n");
            out.append("            return by").append(credential.simpleName()).append("(token);\n");
            out.append("        }\n");
        }

        out.append("        return Optional.empty();\n");
        out.append("    }\n\n");
        for (CredentialSource source : adapter.sources()) {
            EntityDeclaration entity = ctx.entity(source.credentialEntity());
            SpringArtifact mapper = ctx.artifact(source.credentialEntity(), Role.MAPPER);
            String subject = getter(propertyOf(entity, source.subjectField()));
            String expiry = getter(propertyOf(entity, source.expiryField()));
            out.append("    private Optional<?> by").append(entity.javaName()).append("(UUID token) {\n");
            out.append("        ").append(entity.javaName()).append(" row = ").append(lowerFirst(mapper.simpleName())).append(".selectById(token);\n");
            out.append("        if (row == null || row.").append(expiry).append("() == null || !row.").append(expiry).append("().isAfter(Instant.now())) {\n");
            out.append("            return Optional.empty();\n");
            out.append("        }\n");
            out.append("        return Optional.ofNullable(row.").append(subject).append("());\n");
            out.append("    }\n\n");
        }

        out.append("    /** The token of an {@code Authorization: Bearer <uuid>} header, or null when there is none that is well formed. */\n");
        out.append("    private static UUID bearerToken(HttpServletRequest request) {\n");
        out.append("        String header = request.getHeader(\"Authorization\");\n");
        out.append("        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {\n");
        out.append("            return null;\n");
        out.append("        }\n");
        out.append("        String text = header.substring(SCHEME.length()).trim();\n");
        out.append("        if (text.length() != 36) {\n");
        out.append("            return null;\n");
        out.append("        }\n");
        out.append("        try {\n");
        out.append("            return UUID.fromString(text);\n");
        out.append("        } catch (IllegalArgumentException malformed) {\n");
        out.append("            return null;\n");
        out.append("        }\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }

    private static String propertyOf(EntityDeclaration entity, SymbolId field) {
        return entity.fields().stream()
            .filter(property -> property.sourceSymbol().equals(field))
            .map(Property::javaName)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("credential field is not a property of " + entity.javaName() + ": " + field));
    }

    private static String getter(String property) {
        return "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }

    private static String lowerFirst(String value) {
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
