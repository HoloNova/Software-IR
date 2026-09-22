package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.TransportPlan;

/** Renders the Java response type selected by a capability's TransportPlan. */
final class ResponseTypeRenderer {

    private ResponseTypeRenderer() {
    }

    static String render(
            GenerationContext ctx,
            SpringBootDeclaration.CapabilityDeclaration capability
    ) {
        LoweredJavaType outputType = capability.outputType();
        return switch (capability.transportPlan().responseRepresentation()) {
            case VOID -> "void";
            case VALUE -> renderPayloadType(outputType);
            case ENTITY_BODY -> renderEntityBody(outputType);
            case LIST -> {
                LoweredJavaType.ListValue list = requireList(outputType);
                yield "java.util.List<" + renderPayloadType(list.elementType()) + ">";
            }
            case OPTIONAL -> {
                LoweredJavaType.OptionalValue optional = requireOptional(outputType);
                yield "java.util.Optional<" + renderPayloadType(optional.elementType()) + ">";
            }
            case PAGE -> {
                LoweredJavaType.PageValue page = requirePage(outputType);
                yield "PageResponse<" + renderPayloadType(page.elementType()) + ">";
            }
            case PROJECTION -> renderPayloadType(outputType);
        };
    }

    static void collectImports(
            GenerationContext ctx,
            ImportSorter imports,
            SpringBootDeclaration.CapabilityDeclaration capability,
            String currentPackage
    ) {
        LoweredJavaType outputType = capability.outputType();
        switch (capability.transportPlan().responseRepresentation()) {
            case VOID -> {
                // no response type import
            }
            case VALUE -> collectPayloadImports(ctx, imports, outputType, currentPackage);
            case ENTITY_BODY -> collectPayloadImports(ctx, imports, outputType, currentPackage);
            case LIST -> {
                imports.add("java.util.List");
                collectPayloadImports(ctx, imports, requireList(outputType).elementType(), currentPackage);
            }
            case OPTIONAL -> {
                imports.add("java.util.Optional");
                collectPayloadImports(ctx, imports, requireOptional(outputType).elementType(), currentPackage);
            }
            case PROJECTION -> collectPayloadImports(ctx, imports, outputType, currentPackage);
            case PAGE -> {
                imports.add(ctx.model().basePackage() + ".api.PageResponse");
                collectPayloadImports(ctx, imports, requirePage(outputType).elementType(), currentPackage);
            }
        }
    }

    private static String renderEntityBody(LoweredJavaType type) {
        return switch (type) {
            case LoweredJavaType.EntityReference reference -> reference.entityJavaName();
            case LoweredJavaType.Declared declared
                    when declared.kind() == LoweredJavaType.DeclaredKind.ENTITY -> declared.javaName();
            default -> throw new IllegalStateException(
                    "ENTITY_BODY requires an entity response type, got: " + type);
        };
    }

    private static String renderPayloadType(LoweredJavaType type) {
        return switch (type) {
            case LoweredJavaType.Scalar scalar -> TypeRenderer.renderBoxedType(scalar);
            case LoweredJavaType.Declared declared -> declared.javaName();
            case LoweredJavaType.EntityReference reference -> reference.entityJavaName();
            case LoweredJavaType.ListValue list ->
                    "java.util.List<" + renderPayloadType(list.elementType()) + ">";
            case LoweredJavaType.OptionalValue optional ->
                    "java.util.Optional<" + renderPayloadType(optional.elementType()) + ">";
            case LoweredJavaType.PageValue page ->
                    "PageResponse<" + renderPayloadType(page.elementType()) + ">";
        };
    }

    private static void collectPayloadImports(
            GenerationContext ctx,
            ImportSorter imports,
            LoweredJavaType type,
            String currentPackage
    ) {
        switch (type) {
            case LoweredJavaType.Scalar scalar -> TypeRenderer.importFor(scalar).ifPresent(imports::add);
            case LoweredJavaType.Declared declared -> {
                String packageName = ctx.declaredPackage(declared);
                if (!packageName.equals(currentPackage)) {
                    imports.add(packageName + "." + declared.javaName());
                }
            }
            case LoweredJavaType.EntityReference reference -> {
                String packageName = ctx.model().basePackage() + ".domain";
                if (!packageName.equals(currentPackage)) {
                    imports.add(packageName + "." + reference.entityJavaName());
                }
            }
            case LoweredJavaType.ListValue list -> {
                imports.add("java.util.List");
                collectPayloadImports(ctx, imports, list.elementType(), currentPackage);
            }
            case LoweredJavaType.OptionalValue optional -> {
                imports.add("java.util.Optional");
                collectPayloadImports(ctx, imports, optional.elementType(), currentPackage);
            }
            case LoweredJavaType.PageValue page -> {
                imports.add(ctx.model().basePackage() + ".api.PageResponse");
                collectPayloadImports(ctx, imports, page.elementType(), currentPackage);
            }
        }
    }

    private static LoweredJavaType.ListValue requireList(LoweredJavaType type) {
        if (type instanceof LoweredJavaType.ListValue list) {
            return list;
        }
        throw new IllegalStateException("LIST response requires ListValue outputType, got: " + type);
    }

    private static LoweredJavaType.OptionalValue requireOptional(LoweredJavaType type) {
        if (type instanceof LoweredJavaType.OptionalValue optional) {
            return optional;
        }
        throw new IllegalStateException("OPTIONAL response requires OptionalValue outputType, got: " + type);
    }

    private static LoweredJavaType.PageValue requirePage(LoweredJavaType type) {
        if (type instanceof LoweredJavaType.PageValue page) {
            return page;
        }
        throw new IllegalStateException("PAGE response requires PageValue outputType, got: " + type);
    }
}
