package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.api.LoweredNodeId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Optional delivery target: physical storage decisions, never a reconstruction from rendered Java. */
public record DeploymentPlan(LoweredNodeId id, ProjectArtifact.MavenProject applicationProject,
        List<Table> tables, boolean credentialAuthentication, Images images) {
    public DeploymentPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(applicationProject); Objects.requireNonNull(images);
        tables = List.copyOf(tables);
    }
    public record Images(String builder, String runtime, String mysql) {}
    public record Table(String name, List<Column> columns) {
        public Table { identifier(name); columns = List.copyOf(columns); }
    }
    public enum Storage { INT, BIGINT, BOOLEAN, VARCHAR, LONGTEXT, UUID, DATE, DATETIME }
    public record ForeignKey(String table, String column) {
        public ForeignKey { identifier(table); identifier(column); }
    }
    public record Column(String name, Storage storage, int length, boolean nullable, boolean primary,
            boolean autoIncrement, boolean unique, Optional<ForeignKey> reference) {
        public Column {
            identifier(name); Objects.requireNonNull(storage); Objects.requireNonNull(reference);
            if (storage == Storage.VARCHAR && (length < 1 || length > 768)) throw new IllegalArgumentException("invalid VARCHAR length");
        }
    }
    private static void identifier(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("deployment SQL identifier is unsupported: " + name);
    }
}
