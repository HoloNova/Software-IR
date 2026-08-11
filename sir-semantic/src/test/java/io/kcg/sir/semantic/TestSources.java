package io.kcg.sir.semantic;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstDocument;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public final class TestSources {

    private static final SirParser PARSER = new DefaultSirParser();
    private static final SirSemanticAnalyzer ANALYZER = new SirSemanticAnalyzer();

    private TestSources() {
    }

    public static SemanticAnalysis analyze(String source) {
        return ANALYZER.analyze(parse(source));
    }

    public static AstDocument parse(String source) {
        ParseResult parseResult = PARSER.parse(new SirSource(SourceId.of("test"), source));
        if (!parseResult.isSuccess()) {
            throw new AssertionError("Parse failed: " + messages(parseResult.diagnostics()));
        }
        return parseResult.document().orElseThrow();
    }

    public static SemanticAnalysis analyzeResource(String path) {
        return analyze(resource(path));
    }

    public static boolean hasError(SemanticAnalysis result, String code) {
        return result.diagnostics().stream().anyMatch(d -> d.code().value().equals(code) && d.isError());
    }

    public static List<String> errorCodes(SemanticAnalysis result) {
        return result.diagnostics().stream()
                .filter(Diagnostic::isError)
                .map(d -> d.code().value())
                .collect(Collectors.toList());
    }

    public static String resource(String path) {
        try (InputStream stream = TestSources.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing test resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(d -> d.code().value() + ": " + d.message())
                .collect(Collectors.joining("\n"));
    }

    public static String sir(String declarations) {
        return """
                sir 0.1

                software Test {
                  metadata {
                    displayName "Test";
                    namespace "test";
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
                """ + declarations + """
                  }
                }
                """;
    }

    public static final String VALID_CAMPUS_MARKET = "valid/campus-market.sir";
    public static final String VALID_ALL_WORKFLOW = "valid/all-workflow-steps.sir";
    public static final String VALID_UNIT_CAPABILITY = "valid/unit-capability.sir";

    public static final String DUPLICATE_ENTITY = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            entity User persistent {
              identity id: Int64 generated auto;
              field email: String;
            }
            """);

    public static final String DUPLICATE_ENUM_MEMBER = sir("""
            enum Status {
              ACTIVE,
              ACTIVE
            }
            """);

    public static final String DUPLICATE_FIELD = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
              field name: String;
            }
            """);

    public static final String UNDEFINED_TYPE = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field status: UndefinedType;
            }
            """);

    public static final String PRIMITIVE_SHADOWING = sir("""
            entity Boolean persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            """);

    public static final String DUPLICATE_ERROR = sir("""
            error NotFound;
            error NotFound;
            """);

    public static final String DUPLICATE_CAPABILITY = sir("""
            capability Ping {
              output Unit;
              requires readonly;
              expose query;
              workflow { return unit; }
            }
            capability Ping {
              output Unit;
              requires readonly;
              expose query;
              workflow { return unit; }
            }
            """);

    public static final String OPTIONAL_OF_OPTIONAL = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: Optional<Optional<String>>;
            }
            """);

    public static final String REF_TO_ENUM = sir("""
            enum Status { ACTIVE, INACTIVE }
            entity User persistent {
              identity id: Int64 generated auto;
              field status: Ref<Status>;
            }
            """);

    public static final String REF_TO_UNDEFINED = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field ref: Ref<Account>;
            }
            """);

    public static final String ENTITY_FIELD_WITHOUT_REF = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            entity Order persistent {
              identity id: Int64 generated auto;
              field buyer: User;
            }
            """);

    public static final String OPTIONAL_UNIT = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: Optional<Unit>;
            }
            """);

    public static final String UNIT_AS_FIELD = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field result: Unit;
            }
            """);

    public static final String INT32_TO_INT64 = sir("""
            entity Counter persistent {
              identity id: Int64 generated auto;
              field count: Int64;
            }
            input CreateInput {
              field count: Int32;
            }
            capability Create {
              input CreateInput;
              output Ref<Counter>;
              requires atomic;
              expose command;
              workflow {
                create Counter as c {
                  count: input.count;
                }
                persist c;
                return c;
              }
            }
            """);

    public static final String T_TO_OPTIONAL_T = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
              field nickname: Optional<String>;
            }
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires atomic;
              expose command;
              workflow {
                create User as u {
                  name: input.name;
                  nickname: input.name;
                }
                persist u;
                return u;
              }
            }
            """);

    public static final String RETURN_TYPE_MISMATCH = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            capability Ping {
              output Ref<User>;
              requires readonly;
              expose query;
              workflow { return "hello"; }
            }
            """);

    public static final String NOW_IS_DATETIME = sir("""
            entity Event persistent {
              identity id: Int64 generated auto;
              field timestamp: DateTime;
            }
            input CreateEventInput {
              field dummy: Boolean;
            }
            capability CreateEvent {
              input CreateEventInput;
              output Ref<Event>;
              requires atomic;
              expose command;
              workflow {
                create Event as e {
                  timestamp: now();
                }
                persist e;
                return e;
              }
            }
            """);

    public static final String VALIDATE_NON_BOOLEAN = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            error BadName;
            capability Create {
              input CreateInput;
              output Ref<User>;
              fails BadName;
              requires atomic;
              expose command;
              workflow {
                validate input else BadName;
                create User as u { name: input.name; }
                persist u;
                return u;
              }
            }
            input CreateInput {
              field name: String;
            }
            """);

    public static final String NOTBLANK_ON_INT32 = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field age: Int32 where notBlank;
            }
            """);

    public static final String MIN_ON_STRING = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String where min(5);
            }
            """);

    public static final String DUPLICATE_NOTBLANK = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String where notBlank, notBlank;
            }
            """);

    public static final String MIN_MAX_CONFLICT = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field age: Int32 where min(10), max(5);
            }
            """);

    public static final String LENGTH_WRONG_ARGS = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String where length(5);
            }
            """);

    public static final String UNKNOWN_CONSTRAINT = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String where weirdConstraint;
            }
            """);

    public static final String VALIDATE_UNDECLARED_ERROR = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires atomic;
              expose command;
              workflow {
                validate input.name else UndeclaredError;
                create User as u { name: input.name; }
                persist u;
                return u;
              }
            }
            """);

    public static final String LOAD_UNDECLARED_ERROR = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            input LookupInput {
              field id: Int64;
            }
            capability Lookup {
              input LookupInput;
              output Ref<User>;
              requires atomic;
              expose command;
              workflow {
                load User by input.id as u else UndeclaredError;
                return u;
              }
            }
            """);

    public static final String MISSING_RETURN = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires atomic;
              expose command;
              workflow {
                create User as u { name: input.name; }
                persist u;
              }
            }
            """);

    public static final String UNDEFINED_VARIABLE = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires atomic;
              expose command;
              workflow {
                create User as u { name: nonexistent.name; }
                persist u;
                return u;
              }
            }
            """);

    public static final String CREATE_MISSING_REQUIRED = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
              field email: String;
            }
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires atomic;
              expose command;
              workflow {
                create User as u { name: input.name; }
                persist u;
                return u;
              }
            }
            """);

    public static final String QUERY_WITH_PERSIST = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires readonly;
              expose query;
              workflow {
                create User as u { name: input.name; }
                persist u;
                return u;
              }
            }
            """);

    public static final String QUERY_WITHOUT_READONLY = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            input LookupInput {
              field id: Int64;
            }
            capability Lookup {
              input LookupInput;
              output Ref<User>;
              expose query;
              workflow {
                load User by input.id as u else NotFound;
                return u;
              }
            }
            error NotFound;
            """);

    public static final String ATOMIC_PLUS_READONLY = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires atomic;
              requires readonly;
              expose command;
              workflow {
                create User as u { name: input.name; }
                persist u;
                return u;
              }
            }
            """);

    public static final String MULTI_WRITE_WITHOUT_ATOMIC = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
              field email: String;
            }
            input CreateInput {
              field name: String;
              field email: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              expose command;
              workflow {
                create User as u { name: input.name; email: input.email; }
                persist u;
                return u;
              }
            }
            """);

    public static final String ENUM_MEMBER_ACCESS = sir("""
            enum Status { ACTIVE, INACTIVE }
            entity User persistent {
              identity id: Int64 generated auto;
              field status: Status;
            }
            input CreateInput {
              field dummy: Boolean;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires atomic;
              expose command;
              workflow {
                create User as u { status: Status.ACTIVE; }
                persist u;
                return u;
              }
            }
            """);

    public static final String FAILS_NOT_SORTED = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            error ZetaError;
            error AlphaError;
            capability Create {
              input CreateInput;
              output Ref<User>;
              fails ZetaError;
              fails AlphaError;
              requires atomic;
              expose command;
              workflow {
                create User as u { name: input.name; }
                persist u;
                return u;
              }
            }
            input CreateInput {
              field name: String;
            }
            """);

    public static final String REQUIRES_NOT_SORTED = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires readonly;
              requires atomic;
              expose command;
              workflow {
                create User as u { name: input.name; }
                persist u;
                return u;
              }
            }
            """);

    public static final String FAILS_DUPLICATE = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            error BadName;
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              fails BadName;
              fails BadName;
              requires atomic;
              expose command;
              workflow {
                create User as u { name: input.name; }
                persist u;
                return u;
              }
            }
            """);

    public static final String REQUIRES_DUPLICATE = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            input CreateInput {
              field name: String;
            }
            capability Create {
              input CreateInput;
              output Ref<User>;
              requires atomic;
              requires atomic;
              expose command;
              workflow {
                create User as u { name: input.name; }
                persist u;
                return u;
              }
            }
            """);

    public static final String FORWARD_REFERENCE = sir("""
            entity Goods persistent {
              identity id: Int64 generated auto;
              field seller: Ref<User>;
            }
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String;
            }
            """);

    public static final String CONSTRAINT_ORDER_PRESERVED = sir("""
            entity User persistent {
              identity id: Int64 generated auto;
              field name: String where notBlank, length(1, 100);
              field email: String where email, notBlank;
            }
            """);
}
