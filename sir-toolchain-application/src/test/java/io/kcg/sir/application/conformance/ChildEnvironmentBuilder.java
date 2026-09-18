package io.kcg.sir.application.conformance;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a fresh child-process environment from an empty map, copying only
 * the OS-specific allowlist needed for Java/Maven. It does not inherit
 * arbitrary environment variables.
 *
 * <p>The allowlist contains only platform-required Java/Maven variables,
 * isolated Maven repository settings, deterministic Locale/timezone,
 * non-secret port and actor-mode settings, and the explicit Spring
 * datasource variables.
 *
 * <p>No proxy credential, cloud credential, CI token, arbitrary inherited
 * secret, or unrelated environment variable is copied. Windows
 * {@code SystemRoot} is explicitly included when required.
 *
 * <p>Database usernames, passwords, credential-bearing JDBC URLs, tokens,
 * and secret query parameters never enter command-line arguments.
 */
public final class ChildEnvironmentBuilder {

    /** The fixed OS allowlist for Java/Maven platform variables. */
    static final Set<String> OS_ALLOWLIST = Set.of(
            "JAVA_HOME",
            "PATH",
            "SystemRoot",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "PROGRAMDATA",
            "COMPUTERNAME",
            "USERNAME",
            "PATHEXT",
            "COMSPEC",
            "NUMBER_OF_PROCESSORS",
            "PROCESSOR_ARCHITECTURE",
            "OS",
            "WINDIR",
            "HOMEDRIVE",
            "HOMEPATH");

    /** The explicit conformance/datasource variables the harness sets. */
    static final Set<String> HARNESS_VARIABLES = Set.of(
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "KCG_ACTOR_MODE",
            "KCG_ACTOR_LOCAL_ID",
            "KCG_ACTOR_IDENTITY_MODE",
            "KCG_ACTOR_IDENTITY_LOCAL_ID",
            "SPRING_PROFILES_ACTIVE",
            "MAVEN_OPTS",
            "M2_HOME",
            "MAVEN_HOME",
            "LANG",
            "LC_ALL",
            "USER_LANGUAGE",
            "USER_REGION",
            "TZ",
            "SERVER_PORT");

    private final Map<String, String> env = new LinkedHashMap<>();

    public ChildEnvironmentBuilder() {
    }

    /**
     * Copy only the OS-allowlisted variables from the current process
     * environment.
     */
    public ChildEnvironmentBuilder copyOsAllowlist() {
        Map<String, String> systemEnv = System.getenv();
        for (String key : OS_ALLOWLIST) {
            String value = systemEnv.get(key);
            if (value != null && !value.isEmpty()) {
                env.put(key, value);
            }
        }
        return this;
    }

    /**
     * Set the isolated Maven repository local path.
     */
    public ChildEnvironmentBuilder mavenRepoLocal(String absolutePath) {
        Objects.requireNonNull(absolutePath, "absolutePath");
        env.put("MAVEN_OPTS", "-Dmaven.repo.local=" + absolutePath);
        return this;
    }

    /**
     * Set deterministic Locale and timezone.
     */
    public ChildEnvironmentBuilder deterministicLocaleTimezone() {
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("USER_LANGUAGE", "en");
        env.put("USER_REGION", "US");
        env.put("TZ", "UTC");
        return this;
    }

    /**
     * Set the loopback server port.
     */
    public ChildEnvironmentBuilder serverPort(int port) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
        env.put("SERVER_PORT", Integer.toString(port, 10));
        return this;
    }

    /**
     * Set the Spring datasource URL from a typed {@link MysqlRuntimeJdbcUrl}
     * (P0-C2). The rendered URL is the only string written to the child
     * environment; no credential is ever embedded in the URL.
     */
    public ChildEnvironmentBuilder datasourceUrl(MysqlRuntimeJdbcUrl runtimeUrl) {
        Objects.requireNonNull(runtimeUrl, "runtimeUrl");
        env.put("SPRING_DATASOURCE_URL", runtimeUrl.rendered());
        return this;
    }

    /**
     * Set the Spring datasource username.
     */
    public ChildEnvironmentBuilder datasourceUsername(String username) {
        Objects.requireNonNull(username, "username");
        env.put("SPRING_DATASOURCE_USERNAME", username);
        return this;
    }

    /**
     * Set the Spring datasource password.
     */
    public ChildEnvironmentBuilder datasourcePassword(String password) {
        Objects.requireNonNull(password, "password");
        env.put("SPRING_DATASOURCE_PASSWORD", password);
        return this;
    }

    /**
     * Set actor mode (e.g. "local-fixed" or "external").
     *
     * <p>Sets both the legacy {@code KCG_ACTOR_MODE} name and the Spring-relaxed
     * {@code KCG_ACTOR_IDENTITY_MODE}: the generated application reads the property
     * {@code kcg.actor-identity.mode} from its Spring {@code Environment}, and Spring
     * resolves that property from the environment variable with dashes replaced by
     * underscores. Setting only the legacy name would leave the generated application
     * without a mode and it would refuse to start, so the canonical name is the one that
     * matters.
     */
    public ChildEnvironmentBuilder actorMode(String mode) {
        Objects.requireNonNull(mode, "mode");
        env.put("KCG_ACTOR_MODE", mode);
        env.put("KCG_ACTOR_IDENTITY_MODE", mode);
        return this;
    }

    /**
     * Set the local-fixed actor ID.
     *
     * <p>See {@link #actorMode(String)} for why both the legacy and the Spring-relaxed
     * variable names are set (property {@code kcg.actor-identity.local.id}).
     */
    public ChildEnvironmentBuilder actorLocalId(long actorId) {
        env.put("KCG_ACTOR_LOCAL_ID", Long.toString(actorId, 10));
        env.put("KCG_ACTOR_IDENTITY_LOCAL_ID", Long.toString(actorId, 10));
        return this;
    }

    /**
     * Set Spring profiles active (e.g. "kcg-actor-local").
     */
    public ChildEnvironmentBuilder springProfilesActive(String profiles) {
        Objects.requireNonNull(profiles, "profiles");
        env.put("SPRING_PROFILES_ACTIVE", profiles);
        return this;
    }

    /**
     * @return the built environment map. Only allowlisted and explicitly-set
     *         variables are present.
     */
    public Map<String, String> build() {
        return Map.copyOf(env);
    }

    /**
     * Verify that no credential or secret variable appears in the given
     * command-line arguments. This is a defensive check that credentials
     * never enter command-line arguments.
     *
     * @return true if the command line is free of credential tokens
     */
    public static boolean commandLineIsSafe(String[] command, Set<String> secretValues) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(secretValues, "secretValues");
        for (String arg : command) {
            for (String secret : secretValues) {
                if (secret != null && !secret.isEmpty() && arg.contains(secret)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return true if the environment map contains exactly the OS allowlist
     *         plus harness variables (no unexpected inherited variables).
     */
    public static boolean environmentIsAllowlisted(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        for (String key : env.keySet()) {
            if (!OS_ALLOWLIST.contains(key) && !HARNESS_VARIABLES.contains(key)) {
                return false;
            }
        }
        return true;
    }
}