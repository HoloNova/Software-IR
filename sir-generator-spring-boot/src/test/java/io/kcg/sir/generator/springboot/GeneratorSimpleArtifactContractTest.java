package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.ApplicationMain;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.MavenProject;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EnumDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.ErrorDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorSimpleArtifactContractTest {

    private static final String CAMPUS_MARKET = "valid/campus-market.sir";

    @Test
    void pomRendersFrozenProjectDependenciesAndPluginInLoweredOrder() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(CAMPUS_MARKET);
        MavenProject project = model.mavenProject();
        String pom = generatedContent(model, project.path());

        assertTrue(pom.contains("    <groupId>" + project.groupId() + "</groupId>"), pom);
        assertTrue(pom.contains("    <artifactId>" + project.artifactId() + "</artifactId>"), pom);
        assertTrue(pom.contains("    <version>" + project.version() + "</version>"), pom);
        assertTrue(pom.contains("        <java.version>" + project.javaVersion() + "</java.version>"), pom);
        assertTrue(pom.contains("<artifactId>spring-boot-starter-parent</artifactId>\n"
                + "        <version>3.5.3</version>"), pom);
        assertOrdered(pom, List.of(
                "<artifactId>spring-boot-starter-web</artifactId>",
                "<artifactId>spring-boot-starter-validation</artifactId>",
                "<artifactId>mybatis-plus-spring-boot3-starter</artifactId>",
                "<artifactId>mysql-connector-j</artifactId>",
                "<artifactId>spring-boot-starter-test</artifactId>"));
        assertTrue(dependencyBlock(pom, "mybatis-plus-spring-boot3-starter")
                .contains("<version>3.5.12</version>"), pom);
        assertTrue(dependencyBlock(pom, "mysql-connector-j")
                .contains("<version>9.3.0</version>"), pom);
        assertTrue(dependencyBlock(pom, "mysql-connector-j")
                .contains("<scope>runtime</scope>"), pom);
        assertTrue(dependencyBlock(pom, "spring-boot-starter-test")
                .contains("<scope>test</scope>"), pom);
        assertFalse(dependencyBlock(pom, "spring-boot-starter-web").contains("<scope>"), pom);
        assertTrue(pom.contains("<artifactId>spring-boot-maven-plugin</artifactId>\n"
                + "                <version>3.5.3</version>"), pom);
        assertFalse(pom.contains("\r"), "pom.xml must use LF only");
        assertTrue(pom.endsWith("</project>\n"), "pom.xml must end with one LF");
    }

    @Test
    void applicationRendersLoweredPackageMapperScanAndMainEntryPoint() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess("valid/unit-output.sir");
        ApplicationMain app = model.applicationMain();
        String source = generatedContent(model, app.path());

        assertTrue(source.startsWith("package " + app.packageName() + ";\n"), source);
        assertTrue(source.contains("@SpringBootApplication\n"), source);
        assertTrue(source.contains("@MapperScan(\"" + app.mapperScanPackage() + "\")\n"), source);
        assertTrue(source.contains("public class " + app.simpleName() + " {\n"), source);
        assertTrue(source.contains("public static void main(String[] args) {\n"
                + "        SpringApplication.run(" + app.simpleName() + ".class, args);\n"
                + "    }"), source);
    }

    @Test
    void enumPreservesLoweredTypeAndMemberOrderWithoutAddingMembers() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(CAMPUS_MARKET);
        EnumDeclaration declaration = model.declarations().stream()
                .filter(EnumDeclaration.class::isInstance)
                .map(EnumDeclaration.class::cast)
                .findFirst()
                .orElseThrow();
        SpringArtifact artifact = artifact(model, declaration.sourceSymbol(), Role.ENUM);
        String source = generatedContent(model, javaPath(artifact));

        assertTrue(source.startsWith("package " + artifact.packageName() + ";\n"), source);
        String enumBody = source.substring(source.indexOf("public enum "));
        assertEquals("public enum " + declaration.javaName() + " {\n"
                + "    AVAILABLE,\n"
                + "    SOLD;\n"
                + "}\n", enumBody,
                "enum renderer must preserve the complete lowered member sequence");
    }

    @Test
    void mapperTargetsTheEntityOwnedByItsLoweredArtifactAndAddsNoMethods() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(CAMPUS_MARKET);
        EntityDeclaration entity = model.declarations().stream()
                .filter(EntityDeclaration.class::isInstance)
                .map(EntityDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals("User"))
                .findFirst()
                .orElseThrow();
        SpringArtifact mapper = artifact(model, entity.sourceSymbol(), Role.MAPPER);
        SpringArtifact entityModel = artifact(model, entity.sourceSymbol(), Role.ENTITY_MODEL);
        String source = generatedContent(model, javaPath(mapper));

        assertTrue(source.startsWith("package " + mapper.packageName() + ";\n"), source);
        assertTrue(source.contains("import com.baomidou.mybatisplus.core.mapper.BaseMapper;\n"), source);
        assertTrue(source.contains("import org.apache.ibatis.annotations.Mapper;\n"), source);
        assertTrue(source.contains("import " + entityModel.qualifiedName() + ";\n"), source);
        String declaration = "public interface " + mapper.simpleName()
                + " extends BaseMapper<" + entityModel.simpleName() + ">";
        assertTrue(source.contains(declaration + " {\n"), source);
        int bodyStart = source.indexOf('{', source.indexOf(declaration)) + 1;
        int bodyEnd = source.lastIndexOf('}');
        assertTrue(source.substring(bodyStart, bodyEnd).isBlank(),
                "mapper renderer must not invent methods: " + source);
    }

    @Test
    void exceptionUsesLoweredJavaNameBadRequestStatusAndStableConstructor() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess(CAMPUS_MARKET);
        ErrorDeclaration error = model.declarations().stream()
                .filter(ErrorDeclaration.class::isInstance)
                .map(ErrorDeclaration.class::cast)
                .findFirst()
                .orElseThrow();
        SpringArtifact artifact = artifact(model, error.sourceSymbol(), Role.EXCEPTION);
        String source = generatedContent(model, javaPath(artifact));

        assertTrue(source.startsWith("package " + artifact.packageName() + ";\n"), source);

        // A declared failure states its own code and status through the shared failure base, so the
        // advice maps it without knowing any error by name.
        assertTrue(source.contains("public class " + error.javaName() + " extends ApiException {\n"), source);
        assertTrue(source.contains("super(\"" + sirErrorName(error) + "\", HttpStatus."
                + error.httpStatus().name() + ");"), source);
        assertEquals(error.javaName(), artifact.simpleName(),
                "exception file and declaration must use the Lowered IR Java name");
        assertFalse(source.contains("public class InvalidGoodsPrice extends"),
                "renderer must not reconstruct the Java type from the original SIR name");
    }

    /** The declared error's own name, which is the stable code the generated failure carries. */
    private static String sirErrorName(ErrorDeclaration error) {
        String symbol = error.sourceSymbol().value();
        return symbol.substring(symbol.lastIndexOf('/') + 1);
    }

    private static String generatedContent(SpringBootLoweredModel model, String path) {
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(model);
        return GeneratorTestSupport.fileAt(files, path).content();
    }

    private static SpringArtifact artifact(
            SpringBootLoweredModel model,
            io.kcg.sir.semantic.symbol.SymbolId owner,
            Role role) {
        return model.artifacts().stream()
                .filter(candidate -> candidate.ownerSymbol().equals(owner) && candidate.role() == role)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing lowered artifact for " + owner + " / " + role));
    }

    private static String javaPath(SpringArtifact artifact) {
        return "src/main/java/" + artifact.packageName().replace('.', '/')
                + "/" + artifact.simpleName() + ".java";
    }

    private static void assertOrdered(String content, List<String> fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = content.indexOf(fragment);
            assertTrue(current > previous,
                    "expected ordered fragment " + fragment + " after index " + previous + ":\n" + content);
            previous = current;
        }
    }

    private static String dependencyBlock(String pom, String artifactId) {
        String marker = "<artifactId>" + artifactId + "</artifactId>";
        int markerIndex = pom.indexOf(marker);
        if (markerIndex < 0) {
            throw new AssertionError("missing dependency: " + artifactId);
        }
        int start = pom.lastIndexOf("<dependency>", markerIndex);
        int end = pom.indexOf("</dependency>", markerIndex);
        if (start < 0 || end < 0) {
            throw new AssertionError("malformed dependency block for: " + artifactId);
        }
        return pom.substring(start, end + "</dependency>".length());
    }
}
