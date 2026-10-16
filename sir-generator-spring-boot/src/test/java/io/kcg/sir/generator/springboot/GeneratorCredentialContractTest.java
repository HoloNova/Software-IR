package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated-code contract of a credential entity. The authenticator is what turns a header into an
 * identity, so what it accepts has to be visible in the generated source: it takes the bean name the
 * {@code external} mode asks for, only in that mode, and answers no identity unless the token is well
 * formed, stored and unexpired.
 */
class GeneratorCredentialContractTest {
    private static final String BASE = "src/main/java/example/notes/";

    private final List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/bearer-credential.sir");

    @Test
    void theAuthenticatorIsTheBeanTheExternalModeAsksForAndOnlyInThatMode() {
        String adapter = content(BASE + "application/CredentialIdentityAdapter.java");

        assertTrue(adapter.contains("@Component(\"kcgActorIdentityTransport\")"), adapter);
        assertTrue(adapter.contains("@ConditionalOnProperty(name = \"kcg.actor-identity.mode\", havingValue = \"external\")"), adapter);
        assertTrue(adapter.contains("implements Application.ActorIdentityAdapter"), adapter);
    }

    @Test
    void aTokenIsAcceptedOnlyWhenWellFormedStoredAndNotExpired() {
        String adapter = content(BASE + "application/CredentialIdentityAdapter.java");

        assertTrue(adapter.contains("request.getHeader(\"Authorization\")"), adapter);
        assertTrue(adapter.contains("header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())"), adapter);
        assertTrue(adapter.contains("text.length() != 36"), adapter);
        assertTrue(adapter.contains("accessTokenMapper.selectById(token)"), adapter);
        assertTrue(adapter.contains("!row.getExpiresAt().isAfter(Instant.now())"), adapter);
        assertTrue(adapter.contains("Optional.ofNullable(row.getOwnerId())"), adapter);
        assertTrue(adapter.contains("if (User.class.equals(context.actorEntityType()))"), adapter);
    }

    @Test
    void aReferenceBoundFromARowStoresItsIdentity() {
        String login = content(BASE + "application/LoginService.java");

        assertTrue(login.contains("token.setOwnerId(user.getId());"), login);
        assertFalse(login.contains("setOwnerId(user)"), login);
        String post = content(BASE + "application/PostNoteService.java");
        assertTrue(post.contains("note.setAuthorId(actorId);"), post);
    }

    @Test
    void aProjectWithoutACredentialEntityGetsNoAuthenticator() {
        List<GeneratedFile> plain = GeneratorTestSupport.generateSuccess("valid/campus-market.sir");

        assertTrue(plain.stream().noneMatch(file -> file.relativePath().endsWith("CredentialIdentityAdapter.java")));
    }

    private String content(String path) {
        return files.stream()
                .filter(value -> value.relativePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing generated file " + path + " among " + files.stream().map(GeneratedFile::relativePath).toList()))
                .content();
    }
}
