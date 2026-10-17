package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan.*;
import java.util.*;

/** Renders explicit target storage/recipe choices, without reading the original software. */
public final class DeploymentEngine {
    public List<GeneratedFile> render(DeploymentPlan plan) {
        List<GeneratedFile> files = new ArrayList<>();
        var pom = PomRenderer.render(plan.applicationProject());
        add(files, plan, "app/pom.xml", pom.content());
        StringBuilder sql = new StringBuilder("-- Delivery v1: initialize a new, dedicated MySQL 8.4 schema.\n");
        StringBuilder contract = new StringBuilder();
        List<String> references = new ArrayList<>();
        int fk = 0;
        for (Table table : plan.tables()) {
            List<String> definitions = new ArrayList<>();
            for (Column column : table.columns()) {
                String definition = "  " + quote(column.name()) + " " + sqlType(column) + (column.nullable() ? " NULL" : " NOT NULL");
                if (column.autoIncrement()) definition += " AUTO_INCREMENT";
                if (column.primary()) definition += " PRIMARY KEY";
                if (column.unique()) definition += " UNIQUE";
                definitions.add(definition);
                var ref = column.reference();
                contract.append(table.name()).append('\t').append(column.name()).append('\t').append(metadataType(column))
                    .append('\t').append(column.storage() == Storage.UUID ? 36 : column.length())
                    .append('\t').append(column.nullable()).append('\t').append(column.primary()).append('\t').append(column.autoIncrement())
                    .append('\t').append(column.unique()).append('\t').append(ref.map(ForeignKey::table).orElse(""))
                    .append('\t').append(ref.map(ForeignKey::column).orElse("")).append('\n');
                if (ref.isPresent()) {
                    var target = ref.get();
                    references.add("ALTER TABLE " + quote(table.name()) + " ADD CONSTRAINT " + quote("kcg_fk_" + fk++)
                        + " FOREIGN KEY (" + quote(column.name()) + ") REFERENCES " + quote(target.table()) + " (" + quote(target.column())
                        + ") ON DELETE RESTRICT ON UPDATE RESTRICT;");
                }
            }
            sql.append("CREATE TABLE ").append(quote(table.name())).append(" (\n").append(String.join(",\n", definitions))
                .append("\n) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;\n\n");
        }
        references.forEach(r -> sql.append(r).append('\n'));
        add(files, plan, "deploy/migrations/V1__initialize.sql", sql.toString());
        add(files, plan, "deploy/schema.tsv", contract.toString());
        add(files, plan, "app/src/main/resources/application-kcg-deploy.yml", """
            spring:
              config:
                import: 'configtree:/run/secrets/'
              datasource:
                hikari:
                  maximum-pool-size: 4
            management:
              endpoints:
                web:
                  exposure:
                    include: health
              endpoint:
                health:
                  probes:
                    enabled: true
                  group:
                    readiness:
                      include: readinessState,db
            springdoc:
              # Query inputs are bound from individual parameters; show them that way, not as one object.
              default-flat-param-object: true
            """ +(plan.credentialAuthentication() ? "kcg:\n  actor-identity:\n    mode: external\n" : ""));
        var api = plan.api();
        String packagePath = api.basePackage().replace('.', '/');
        add(files, plan, "app/src/main/java/" + packagePath + "/KcgOpenApiConfiguration.java", DeploymentTemplates.OPENAPI
            .replace("@PACKAGE@", api.basePackage()).replace("@VERSION@", StringEscape.javaString(api.version()))
            .replace("@ENVELOPE@", api.errorEnvelopeClass()).replace("@ERRORS@", errors(api.endpoints()))
            .replace("@TITLE@", StringEscape.javaString(api.title())));
        add(files, plan, "app/src/test/java/" + packagePath + "/contract/HttpContractIT.java", DeploymentTemplates.CONTRACT_TEST
            .replace("@PACKAGE@", api.basePackage()).replace("@ENDPOINTS@", endpoints(api.endpoints())));
        add(files, plan, "deploy/deploy.sh", substitute(DeploymentTemplates.ENTRY, plan));
        add(files, plan, "deploy/compose.yaml", substitute(DeploymentTemplates.COMPOSE, plan));
        add(files, plan, "deploy/Dockerfile", substitute(DeploymentTemplates.DOCKERFILE, plan));
        add(files, plan, "deploy/build.sh", DeploymentTemplates.BUILD);
        add(files, plan, "deploy/contract-test.sh", DeploymentTemplates.CONTRACT);
        add(files, plan, "deploy/acceptance-test.sh", DeploymentTemplates.ACCEPTANCE);
        add(files, plan, "deploy/accounts.sh", DeploymentTemplates.ACCOUNTS);
        add(files, plan, "deploy/Bootstrap.java", DeploymentTemplates.BOOTSTRAP);
        add(files, plan, "deploy/tools/pom.xml", DeploymentTemplates.TOOLS_POM);
        add(files, plan, "deploy/tools/src/main/java/kcg/delivery/Migrate.java", DeploymentTemplates.MIGRATE);
        add(files, plan, "deploy/tools/src/main/java/kcg/delivery/Probe.java", DeploymentTemplates.PROBE);
        add(files, plan, "docs/README.md", """
            # Standalone backend delivery v1

            Requires Linux x86_64, Docker Engine/Compose and public dependency/image access.
            No host JDK, Maven, MySQL or KCG-Code is needed. This package is source, not a prebuilt image.
            From the package root (choose your own instance name, state path and port):

            ```sh
            sh deploy/deploy.sh deploy --instance demo --state-root /absolute/path/to/demo-state --port 8080
            sh deploy/deploy.sh status --instance demo --state-root /absolute/path/to/demo-state
            sh deploy/deploy.sh stop --instance demo --state-root /absolute/path/to/demo-state
            sh deploy/deploy.sh start --instance demo --state-root /absolute/path/to/demo-state
            ```

            deploy builds before starting the database, initializes an owned new volume through Flyway,
            verifies physical columns/keys/references, then waits for real HTTP + database readiness.
            The endpoint is http://127.0.0.1:<port>; business routes are those of your SIR.
            start verifies the existing database; it never migrates and does not create a missing volume.
            stop preserves data, images, credentials and migration history. Reuse the same state directory.
            Only update runs a different package on an existing instance (see "Update" below); other actions refuse it.
            An unfinished lock is not expired automatically. Inspect the named coordinator and logs before removing it.
            There is no automatic database clean/repair or arbitrary schema recovery.

            State contains random persistent secrets, artifacts and instance identity; keep it outside the package and Git.
            The app has only SELECT/INSERT/UPDATE/DELETE, not migration credentials or DDL privileges.
            No demo rows/accounts are injected. Business tests must use a separate deployment instance.
            Defaults are explicit budgets, adjustable via KCG_BUILD_MEMORY/CPUS, KCG_DB_MEMORY/CPUS,
            KCG_APP_MEMORY/CPUS, KCG_MIGRATE_MEMORY and KCG_TEST_MEMORY/CPUS; they are not production capacity claims.

            ## Interface documentation and contract test

            Swagger UI: http://127.0.0.1:<port>/swagger-ui/index.html (OpenAPI JSON: /v3/api-docs). It sends real
            requests to the deployed instance; authenticated operations take the access token through "Authorize".
            Documentation is generated from the running controllers; it does not decide whether a requirement is met.

            ```sh
            sh deploy/deploy.sh contract-test --instance demo --state-root /absolute/path/to/demo-state
            ```

            contract-test runs app/src/test/java/**/contract/HttpContractIT.java in a container against the running
            instance over real HTTP. Its requests are all refused before business code runs, so it writes no data.
            Results: <state>/reports/contract/contract-report.tsv (PASSED, FAILED, NOT_VERIFIED per check) and
            surefire-reports/. NOT_VERIFIED marks what needs business data or an account; a passing contract test is
            not a business acceptance. A plain `mvn test` of app/ does not select it and needs no running service.

            ## Business acceptance

            If this package carries acceptance/ (a separate Maven suite of the requirement), run it on a disposable
            instance only; it writes business data. Repeat the instance name to confirm:

            ```sh
            sh deploy/deploy.sh acceptance-test --instance demo-acc --state-root /absolute/path/to/demo-acc-state \\
              --confirm-disposable demo-acc
            ```

            The suite gets the application address and a verification connection to this instance's own database, never
            the application sources. Results: <state>/reports/acceptance/. Without acceptance/ the action prints
            KCG_ACCEPTANCE_NOT_RUN and exits 4; that is not a pass.

            ## Update

            ```sh
            sh deploy/deploy.sh update --instance demo --state-root /absolute/path/to/demo-state
            ```

            update moves a running instance to this package and keeps its data. It builds first (exit 4, nothing touched),
            checks the database read-only against this package's migration chain while the old application still serves,
            stops the application, applies only the missing versions while digesting every old row and value, then binds
            the instance to this package and starts it. Exit codes: 0 updated or already current; 3 refused, database
            unchanged (the old application is restarted); 5 the database may have changed and needs a person (no clean,
            repair or rollback; application stopped); 6 the database is at this package's version but the application did
            not start (retry start or update). An older package cannot run a newer database. No backup is taken.
            """);
        return List.copyOf(files);
    }
    /** The lowered interface as Java data of the contract test; every value was validated by the plan's records. */
    private static String endpoints(List<Endpoint> endpoints) {
        List<String> lines = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            List<String> rules = new ArrayList<>();
            for (TextRule rule : endpoint.textRules()) rules.add("new TextRule(" + StringEscape.javaString(rule.property()) + ", "
                + rule.notBlank() + ", " + rule.minLength() + ", "
                + (rule.maxLength().isPresent() ? "OptionalInt.of(" + rule.maxLength().getAsInt() + ")" : "OptionalInt.empty()") + ")");
            lines.add("            new Endpoint(" + StringEscape.javaString(endpoint.method()) + ", " + StringEscape.javaString(endpoint.route())
                + ", " + endpoint.authenticated() + ", " + StringEscape.javaString(endpoint.binding().name()) + ", List.of(" + String.join(", ", rules) + "), List.of("
                + String.join(", ", endpoint.errors().stream().map(e -> StringEscape.javaString(e.status() + " " + e.code())).toList()) + "))");
        }
        return String.join(",\n", lines);
    }
    private static String errors(List<Endpoint> endpoints) {
        List<String> lines = new ArrayList<>();
        for (Endpoint endpoint : endpoints) for (DeclaredError error : endpoint.errors())
            lines.add("            new String[] {" + StringEscape.javaString(endpoint.method() + " " + endpoint.route()) + ", \"" + error.status()
                + "\", " + StringEscape.javaString(error.code()) + "}");
        return String.join(",\n", lines);
    }
    private static String substitute(String text, DeploymentPlan plan) {
        return text.replace("@BUILDER@", plan.images().builder()).replace("@RUNTIME@", plan.images().runtime()).replace("@MYSQL@", plan.images().mysql());
    }
    private static void add(List<GeneratedFile> files, DeploymentPlan plan, String path, String content) {
        files.add(new GeneratedFile(path, content, plan.id(), Optional.empty()));
    }
    private static String quote(String identifier) { return "`" + identifier + "`"; }
    private static String metadataType(Column column) {
        return switch (column.storage()) {
            case INT -> "int"; case BIGINT -> "bigint"; case BOOLEAN -> "tinyint";
            case VARCHAR -> "varchar"; case LONGTEXT -> "longtext"; case UUID -> "char";
            case DATE -> "date"; case DATETIME -> "datetime";
        };
    }
    private static String sqlType(Column column) {
        return switch (column.storage()) {
            case INT -> "INT"; case BIGINT -> "BIGINT"; case BOOLEAN -> "BOOLEAN";
            case VARCHAR -> "VARCHAR(" + column.length() + ")"; case LONGTEXT -> "LONGTEXT";
            case UUID -> "CHAR(36)"; case DATE -> "DATE"; case DATETIME -> "DATETIME(6)";
        };
    }
}
