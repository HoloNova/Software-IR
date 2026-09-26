package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstWorkflow;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Parser contract for the persistent declaration id written as {@code @id("...")} (Q16).
 *
 * <p>The parser's whole job here is shape plus a stable node path: a declaration that carries a
 * declared id must build its node path from that id instead of its name, so that renaming the
 * declaration leaves the node — and every node derived from it — identical. Which identities those
 * paths become, and what a change may conclude from them, belongs to the semantic layer and the
 * change layer.
 */
class DeclaredIdentityGrammarTest {
    private final DefaultSirParser parser = new DefaultSirParser();

    private static final String DECLARED_IDS = """
            sir 0.1

            software Identity {
              metadata {
                displayName "Identity";
                namespace "example.identity";
              }
              target {
                language java 21;
                framework spring_boot;
                persistence mybatis_plus;
                database mysql;
                build maven;
                interface rest;
              }
              declarations {
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String where notBlank @id("course-code");
                  field title: String @id("course-title");
                }
                error CourseNotFound;
                capability SearchCourses @id("search-courses") {
                  output Unit;
                  fails CourseNotFound;
                  requires readonly;
                  expose query;
                  workflow {
                    return unit;
                  }
                }
              }
            }
            """;

    @Test
    void capabilityDeclaredIdLivesInTheAstAndDrivesTheNodePath() {
        AstCapabilityDecl capability = capability(parse(DECLARED_IDS));

        assertEquals(Optional.of("search-courses"), capability.declaredId());
        assertTrue(capability.id().value().endsWith("/declared-capability/search-courses"),
                capability.id().value());
        assertFalse(capability.id().value().contains("SearchCourses"), capability.id().value());
    }

    @Test
    void entityMemberDeclaredIdLivesInTheAstAndDrivesTheNodePath() {
        List<AstField> fields = entity(parse(DECLARED_IDS)).fields();

        assertEquals(Optional.of("course-code"), fields.get(0).declaredId());
        assertEquals(Optional.of("course-title"), fields.get(1).declaredId());
        assertTrue(fields.get(0).id().value().endsWith("/declared-entity-field/course-code"),
                fields.get(0).id().value());
        assertTrue(fields.get(1).id().value().endsWith("/declared-entity-field/course-title"),
                fields.get(1).id().value());
    }

    @Test
    void renamingADeclaredCapabilityOrMemberKeepsEveryNodePath() {
        AstCapabilityDecl base = capability(parse(DECLARED_IDS));
        AstEntityDecl baseEntity = entity(parse(DECLARED_IDS));
        AstWorkflow baseWorkflow = base.workflow();

        String renamed = DECLARED_IDS
                .replace("SearchCourses", "SearchEnrollments")
                .replace("field title: String @id(\"course-title\");", "field name: String @id(\"course-title\");");
        AstCapabilityDecl candidate = capability(parse(renamed));
        AstEntityDecl candidateEntity = entity(parse(renamed));

        assertEquals(base.id(), candidate.id());
        assertEquals(baseWorkflow.id(), candidate.workflow().id());
        assertEquals(baseEntity.fields().get(1).id(), candidateEntity.fields().get(1).id());
        assertEquals("SearchEnrollments", candidate.name().text());
        assertEquals("name", candidateEntity.fields().get(1).name().text());
    }

    @Test
    void declarationWithoutADeclaredIdKeepsTheNameDerivedPath() {
        AstCapabilityDecl capability = capability(parse(DECLARED_IDS.replace(" @id(\"search-courses\")", "")));

        assertEquals(Optional.empty(), capability.declaredId());
        assertTrue(capability.id().value().endsWith("/capability/SearchCourses"), capability.id().value());
    }

    @Test
    void theAnnotationIsRejectedWhereTheLanguageDoesNotGivePersistentIdentity() {
        assertFalse(parse(DECLARED_IDS
                .replace("field title: String @id(\"course-title\");", "field title: String;")
                .replace("capability SearchCourses @id(\"search-courses\") {",
                        "input SearchCoursesInput {\n    field keyword: String @id(\"keyword\");\n  }\n  capability SearchCourses {"))
                .isSuccess());
    }

    private ParseResult parse(String source) {
        return parser.parse(new SirSource(SourceId.of("tests/declared-identity.sir"), source));
    }

    private static AstCapabilityDecl capability(ParseResult result) {
        assertTrue(result.isSuccess(), result.diagnostics().toString());
        for (AstDeclaration declaration : result.document().orElseThrow().software().declarations()) {
            if (declaration instanceof AstCapabilityDecl capability) {
                return capability;
            }
        }

        throw new AssertionError("no capability declaration parsed");
    }

    private static AstEntityDecl entity(ParseResult result) {
        assertTrue(result.isSuccess(), result.diagnostics().toString());
        return assertInstanceOf(AstEntityDecl.class, result.document().orElseThrow().software().declarations().get(0));
    }
}
