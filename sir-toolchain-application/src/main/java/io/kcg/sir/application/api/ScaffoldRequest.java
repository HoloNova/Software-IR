package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Asks for the declarations of the dual-token pattern, fitted to an account entity of an existing software.
 *
 * @param account       the entity that logs in; it must already declare the two fields below
 * @param nameField     its unique text field that names the account
 * @param passwordField its secret field that holds the password digest
 * @param prefix        put in front of every name the pattern declares, to keep them clear of existing names; may be empty
 * @param accessMinutes how long an access token lives
 * @param refreshDays   how long a refresh token lives
 */
public record ScaffoldRequest(Path sourceRoot, SourceId entry, String account, String nameField, String passwordField,
        String prefix, int accessMinutes, int refreshDays) {
    public static final int DEFAULT_ACCESS_MINUTES = 15;
    public static final int DEFAULT_REFRESH_DAYS = 7;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");
    private static final Pattern PREFIX = Pattern.compile("([A-Z][A-Za-z0-9]{0,31})?");

    public ScaffoldRequest {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(nameField, "nameField");
        Objects.requireNonNull(passwordField, "passwordField");
        Objects.requireNonNull(prefix, "prefix");
    }

    /** Why the request cannot be answered, or null when its parameters are acceptable. */
    String problem() {
        if (!NAME.matcher(account).matches()) return "--account must be a SIR name";
        if (!NAME.matcher(nameField).matches()) return "--name-field must be a SIR name";
        if (!NAME.matcher(passwordField).matches()) return "--password-field must be a SIR name";
        if (!PREFIX.matcher(prefix).matches()) return "--prefix must be empty or start with an upper-case letter";
        if (accessMinutes < 1 || accessMinutes > 1440) return "--access-minutes must be between 1 and 1440";
        if (refreshDays < 1 || refreshDays > 365) return "--refresh-days must be between 1 and 365";
        return null;
    }
}
