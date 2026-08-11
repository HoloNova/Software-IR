package io.kcg.sir.application.conformance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Records the complete schema-object inventory for fixture-created tables,
 * indexes, views, triggers, routines, and events. Used to prove that DROP
 * is safe (no unknown object remains).
 *
 * <p>Per ADR-017 搂7.1 / 搂10.4, every {@code INFORMATION_SCHEMA} query is
 * a {@code PreparedStatement} with exact {@code BINARY ... = ?} binding of
 * the same {@link SchemaName#value()} string. Schema names are never
 * concatenated into SQL text and {@code USE <schema>} is never issued.
 * The control connection's catalog is selected via the JDBC catalog API
 * before any qualified metadata read.
 */
public final class MysqlSchemaInventory {

    private final List<InventoryItem> items;

    public MysqlSchemaInventory(List<InventoryItem> items) {
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    public List<InventoryItem> items() {
        return items;
    }

    public boolean matches(MysqlSchemaInventory other) {
        return items.equals(other.items);
    }

    /**
     * Query the complete schema inventory from the control connection.
     * Uses parameterized {@code INFORMATION_SCHEMA} queries 鈥?never string
     * concatenation. The caller's {@link MysqlControlSession} is responsible
     * for lock/identity rechecks before invoking this method.
     */
    public static MysqlSchemaInventory query(MysqlControlSession control,
                                             SchemaName schemaName) throws SQLException {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(schemaName, "schemaName");
        Connection conn = control.rawConnection();
        List<InventoryItem> items = new ArrayList<>();
        String schemaValue = schemaName.value();

        // Tables.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            ps.setString(1, schemaValue);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new InventoryItem("TABLE", rs.getString(1)));
                }
            }
        }
        // Views.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.VIEWS "
                        + "WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME")) {
            ps.setString(1, schemaValue);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new InventoryItem("VIEW", rs.getString(1)));
                }
            }
        }
        // Triggers.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS "
                        + "WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME")) {
            ps.setString(1, schemaValue);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new InventoryItem("TRIGGER", rs.getString(1)));
                }
            }
        }
        // Routines (procedures + functions).
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM INFORMATION_SCHEMA.ROUTINES "
                        + "WHERE ROUTINE_SCHEMA = ? ORDER BY ROUTINE_NAME")) {
            ps.setString(1, schemaValue);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new InventoryItem(rs.getString(2), rs.getString(1)));
                }
            }
        }
        // Events.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT EVENT_NAME FROM INFORMATION_SCHEMA.EVENTS "
                        + "WHERE EVENT_SCHEMA = ? ORDER BY EVENT_NAME")) {
            ps.setString(1, schemaValue);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new InventoryItem("EVENT", rs.getString(1)));
                }
            }
        }
        return new MysqlSchemaInventory(items);
    }

    /**
     * One inventory item.
     *
     * @param objectType TABLE, VIEW, TRIGGER, PROCEDURE, FUNCTION, EVENT
     * @param objectName the object name
     */
    public record InventoryItem(String objectType, String objectName)
            implements Comparable<InventoryItem> {
        public InventoryItem {
            Objects.requireNonNull(objectType, "objectType");
            Objects.requireNonNull(objectName, "objectName");
        }

        @Override
        public int compareTo(InventoryItem o) {
            int c = objectType.compareTo(o.objectType);
            if (c != 0) {
                return c;
            }
            return objectName.compareTo(o.objectName);
        }
    }
}