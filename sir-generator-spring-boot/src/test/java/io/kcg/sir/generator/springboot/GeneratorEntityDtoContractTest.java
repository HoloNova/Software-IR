package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.EntityReference;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.ScalarKind;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Generation;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.InputDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.PersistenceShape;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Property;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorEntityDtoContractTest {

    private static final String CAMPUS_MARKET = "valid/campus-market.sir";
    private static final String ENTITY_DTO_CONTRACT = "valid/entity-dto-contract.sir";

    @Test
    void entityRendersLoweredTableAutoIdentityFieldsAndAccessorsInOrder() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(CAMPUS_MARKET);
        EntityDeclaration goods = entity(model, "Goods");
        SpringArtifact artifact = artifact(model, goods.sourceSymbol(), Role.ENTITY_MODEL);
        String source = generatedSource(model, artifact);

        assertEquals(Generation.AUTO_INCREMENT, goods.identity().generation());
        assertEquals(ScalarKind.LONG, goods.identity().type().kind());
        assertTrue(source.startsWith("package " + artifact.packageName() + ";\n"), source);
        assertTrue(source.contains("@TableName(\"" + goods.tableName() + "\")\n"
                + "public class " + goods.javaName() + " {\n"), source);
        assertTrue(source.contains("    @TableId(value = \"id\", type = IdType.AUTO)\n"
                + "    private Long id;"), source);

        List<JavaField> fields = List.of(
                new JavaField("title", "String"),
                new JavaField("price", "java.math.BigDecimal"),
                new JavaField("sellerId", "Long"),
                new JavaField("status", "GoodsStatus"));
        assertEntityFieldsAndAccessors(source, fields);
        assertAccessor(source, "Long", "id");
        assertTrue(source.contains("import java.math.BigDecimal;"), source);
        assertFalse(source.contains("jakarta.validation"),
                "entity persistence model must not acquire DTO validation imports: " + source);
        assertFalse(source.contains("@NotBlank"),
                "entity constraints belong to request validation, not EntityRenderer: " + source);
    }

    @Test
    void uuidEntityRendersAllSupportedFieldTypeShapesAndAssignUuidIdentity() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(ENTITY_DTO_CONTRACT);
        EntityDeclaration account = entity(model, "Account");
        SpringArtifact artifact = artifact(model, account.sourceSymbol(), Role.ENTITY_MODEL);
        String source = generatedSource(model, artifact);

        assertEquals(Generation.UUID, account.identity().generation());
        assertEquals(ScalarKind.UUID, account.identity().type().kind());
        assertTrue(source.contains("@TableName(\"account\")"), source);
        assertTrue(source.contains("    @TableId(value = \"account_id\", type = IdType.ASSIGN_UUID)\n"
                + "    private java.util.UUID accountId;"), source);
        assertAccessor(source, "java.util.UUID", "accountId");

        List<JavaField> fields = List.of(
                new JavaField("fullName", "String"),
                new JavaField("email", "String"),
                new JavaField("active", "Boolean"),
                new JavaField("retryCount", "Integer"),
                new JavaField("legacyNumber", "Long"),
                new JavaField("creditLimit", "java.math.BigDecimal"),
                new JavaField("externalKey", "java.util.UUID"),
                new JavaField("openedOn", "java.time.LocalDate"),
                new JavaField("updatedAt", "java.time.Instant"),
                new JavaField("nickname", "java.util.Optional<String>"),
                new JavaField("aliases", "java.util.List<String>"),
                new JavaField("state", "AccountState"));
        assertEntityFieldsAndAccessors(source, fields);

        assertContainsImports(source, List.of(
                "java.math.BigDecimal",
                "java.time.Instant",
                "java.time.LocalDate",
                "java.util.List",
                "java.util.Optional",
                "java.util.UUID"));
        assertFalse(source.contains("jakarta.validation"),
                "entity source must remain a persistence model even when source fields have constraints: " + source);
    }

    @Test
    void entityReferenceUsesLoweredIdentityStorageNameAndColumnWithoutObjectField() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(ENTITY_DTO_CONTRACT);
        EntityDeclaration purchase = entity(model, "Purchase");
        Property reference = purchase.fields().stream()
                .filter(field -> field.persistenceShape() == PersistenceShape.REFERENCE_ID)
                .findFirst()
                .orElseThrow();
        EntityReference type = assertInstanceOf(EntityReference.class, reference.type());
        SpringArtifact artifact = artifact(model, purchase.sourceSymbol(), Role.ENTITY_MODEL);
        String source = generatedSource(model, artifact);

        assertEquals("accountId", reference.javaName());
        assertEquals("account_id", reference.columnName().orElseThrow());
        assertEquals(ScalarKind.UUID, type.identityStorageType().kind());
        assertTrue(source.contains("    @TableField(\"account_id\")\n"
                + "    private java.util.UUID accountId;"), source);
        assertAccessor(source, "java.util.UUID", "accountId");
        assertFalse(source.contains("private Account account"),
                "EntityRenderer must consume the lowered reference-id shape, not reconstruct an object field: " + source);
        assertFalse(source.contains("private java.util.UUID account;"),
                "EntityRenderer must preserve the lowered Java property name: " + source);
    }

    @Test
    void dtoRendersLoweredFieldTypesOrderImportsAndAccessors() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(ENTITY_DTO_CONTRACT);
        InputDeclaration input = input(model, "RegisterAccountInput");
        SpringArtifact artifact = artifact(model, input.sourceSymbol(), Role.REQUEST_DTO);
        String source = generatedSource(model, artifact);

        assertTrue(source.startsWith("package " + artifact.packageName() + ";\n"), source);
        assertTrue(source.contains("public class " + input.javaName() + " {\n"), source);
        List<JavaField> fields = List.of(
                new JavaField("fullName", "String"),
                new JavaField("email", "String"),
                new JavaField("active", "Boolean"),
                new JavaField("retryCount", "Integer"),
                new JavaField("legacyNumber", "Long"),
                new JavaField("creditLimit", "java.math.BigDecimal"),
                new JavaField("externalKey", "java.util.UUID"),
                new JavaField("openedOn", "java.time.LocalDate"),
                new JavaField("updatedAt", "java.time.Instant"),
                new JavaField("nickname", "java.util.Optional<String>"),
                new JavaField("tags", "java.util.List<String>"),
                new JavaField("state", "AccountState"));
        assertFieldsAndAccessors(source, fields);

        assertContainsImports(source, List.of(
                "com.example.entitydto.domain.AccountState",
                "java.math.BigDecimal",
                "java.time.Instant",
                "java.time.LocalDate",
                "java.util.List",
                "java.util.Optional",
                "java.util.UUID"));
        assertEquals(input.fields().stream().map(Property::javaName).toList(),
                fields.stream().map(JavaField::name).toList(),
                "test expectations must track the LoweredInput field sequence");
    }

    @Test
    void dtoRendersEveryValidationConstraintInLoweredOrderOnItsOwningField() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(ENTITY_DTO_CONTRACT);
        InputDeclaration input = input(model, "RegisterAccountInput");
        String source = generatedSource(model, artifact(model, input.sourceSymbol(), Role.REQUEST_DTO));

        assertTrue(source.contains("    @NotBlank\n"
                + "    @Size(min = 1, max = 80)\n"
                + "    private String fullName;"), source);
        assertTrue(source.contains("    @Email\n"
                + "    private String email;"), source);
        assertTrue(source.contains("    @DecimalMin(\"-10.50\")\n"
                + "    @DecimalMax(\"999.99\")\n"
                + "    private java.math.BigDecimal creditLimit;"), source);

        assertContainsImports(source, List.of(
                "jakarta.validation.constraints.DecimalMax",
                "jakarta.validation.constraints.DecimalMin",
                "jakarta.validation.constraints.Email",
                "jakarta.validation.constraints.NotBlank",
                "jakarta.validation.constraints.Size"));
        assertEquals(1, occurrences(source, "@NotBlank"), source);
        assertEquals(1, occurrences(source, "@Email"), source);
        assertEquals(1, occurrences(source, "@Size("), source);
        assertEquals(1, occurrences(source, "@DecimalMin("), source);
        assertEquals(1, occurrences(source, "@DecimalMax("), source);
        assertOrdered(source, List.of(
                "@NotBlank",
                "@Size(min = 1, max = 80)",
                "private String fullName;",
                "@Email",
                "private String email;",
                "@DecimalMin(\"-10.50\")",
                "@DecimalMax(\"999.99\")",
                "private java.math.BigDecimal creditLimit;"));
    }

    private static EntityDeclaration entity(SpringBootLoweredModel model, String javaName) {
        return model.declarations().stream()
                .filter(EntityDeclaration.class::isInstance)
                .map(EntityDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals(javaName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing lowered entity: " + javaName));
    }

    private static InputDeclaration input(SpringBootLoweredModel model, String javaName) {
        return model.declarations().stream()
                .filter(InputDeclaration.class::isInstance)
                .map(InputDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals(javaName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing lowered input: " + javaName));
    }

    private static SpringArtifact artifact(SpringBootLoweredModel model, SymbolId owner, Role role) {
        return model.artifacts().stream()
                .filter(candidate -> candidate.ownerSymbol().equals(owner) && candidate.role() == role)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing lowered artifact for " + owner + " / " + role));
    }

    private static String generatedSource(SpringBootLoweredModel model, SpringArtifact artifact) {
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(model);
        String path = "src/main/java/" + artifact.packageName().replace('.', '/')
                + "/" + artifact.simpleName() + ".java";
        return GeneratorTestSupport.fileAt(files, path).content();
    }

    private static void assertEntityFieldsAndAccessors(String source, List<JavaField> fields) {
        for (JavaField field : fields) {
            String column = snake(field.name());
            assertTrue(source.contains("    @TableField(\"" + column + "\")\n"
                    + "    private " + field.type() + " " + field.name() + ";"),
                    "missing entity field mapping for " + field.name() + ":\n" + source);
        }
        assertFieldsAndAccessors(source, fields);
    }

    private static void assertFieldsAndAccessors(String source, List<JavaField> fields) {
        assertOrdered(source, fields.stream()
                .map(field -> "private " + field.type() + " " + field.name() + ";")
                .toList());
        fields.forEach(field -> assertAccessor(source, field.type(), field.name()));
    }

    private static void assertAccessor(String source, String type, String name) {
        String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        assertTrue(source.contains("    public " + type + " get" + capitalised + "() {\n"
                + "        return " + name + ";\n"
                + "    }"), "missing getter for " + name + ":\n" + source);
        assertTrue(source.contains("    public void set" + capitalised + "(" + type + " " + name + ") {\n"
                + "        this." + name + " = " + name + ";\n"
                + "    }"), "missing setter for " + name + ":\n" + source);
    }

    private static void assertContainsImports(String source, List<String> imports) {
        for (String type : imports) {
            assertTrue(source.contains("import " + type + ";"),
                    "missing required import " + type + ":\n" + source);
            assertEquals(1, occurrences(source, "import " + type + ";"),
                    "import must occur exactly once: " + type);
        }
    }

    private static void assertOrdered(String source, List<String> fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = source.indexOf(fragment, previous + 1);
            assertTrue(current > previous,
                    "expected ordered fragment " + fragment + " after index " + previous + ":\n" + source);
            previous = current;
        }
    }

    private static int occurrences(String source, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(fragment, index)) >= 0) {
            count++;
            index += fragment.length();
        }
        return count;
    }

    private static String snake(String name) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            if (Character.isUpperCase(current)) {
                out.append('_').append(Character.toLowerCase(current));
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }

    private record JavaField(String name, String type) {
    }
}
