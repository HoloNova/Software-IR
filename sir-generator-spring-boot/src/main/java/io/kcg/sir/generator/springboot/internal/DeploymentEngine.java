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
            """ + (plan.credentialAuthentication() ? "kcg:\n  actor-identity:\n    mode: external\n" : ""));
        add(files, plan, "deploy/deploy.sh", substitute(DeploymentTemplates.ENTRY, plan));
        add(files, plan, "deploy/compose.yaml", substitute(DeploymentTemplates.COMPOSE, plan));
        add(files, plan, "deploy/Dockerfile", substitute(DeploymentTemplates.DOCKERFILE, plan));
        add(files, plan, "deploy/build.sh", DeploymentTemplates.BUILD);
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
            start verifies the existing database; it does not create a missing volume.
            stop preserves data, images, credentials and migration history. Reuse the same state directory.
            A package with different bytes cannot take over this state: schema/application updates are not implemented yet.
            An unfinished lock is not expired automatically. Inspect the named coordinator and logs before removing it.
            There is no automatic database clean/repair or arbitrary schema recovery.

            State contains random persistent secrets, artifacts and instance identity; keep it outside the package and Git.
            The app has only SELECT/INSERT/UPDATE/DELETE, not migration credentials or DDL privileges.
            No demo rows/accounts are injected. Business tests must use a separate deployment instance.
            Defaults are explicit budgets, adjustable via KCG_BUILD_MEMORY/CPUS, KCG_DB_MEMORY/CPUS,
            KCG_APP_MEMORY/CPUS and KCG_MIGRATE_MEMORY; they are not production capacity claims.
            Swagger, distributed HTTP test suites and data-preserving update are subsequent steps, not delivered here.
            """);
        return List.copyOf(files);
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
