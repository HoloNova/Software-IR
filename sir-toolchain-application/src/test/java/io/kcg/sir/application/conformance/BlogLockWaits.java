package io.kcg.sir.application.conformance;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A wait must lead to our held post, possibly through a competing unique-key lock. */
final class BlogLockWaits {
    private static final int MAX_WAIT_EDGES = 256;
    record Edge(String transaction, long requestingConnection, long blockingConnection, String user) { }
    record Observed(Set<Long> connections, Set<String> transactions) {
        Observed { connections = Set.copyOf(connections); transactions = Set.copyOf(transactions); }
        boolean ready(int expected) { return connections.size() == expected && transactions.size() == expected; }
    }

    private BlogLockWaits() { }

    static Observed observe(List<Edge> edges, long blocker, String runtimeUser) {
        Set<Long> reachable = Set.of(blocker);
        while (true) {
            Set<Long> known = reachable;
            var next = new HashSet<>(known);
            edges.stream().filter(e -> known.contains(e.blockingConnection()))
                    .forEach(e -> next.add(e.requestingConnection()));
            if (next.equals(known)) break;
            reachable = Set.copyOf(next);
        }
        Set<Long> connected = reachable;
        var connections = new HashSet<Long>();
        var transactions = new HashSet<String>();
        for (Edge edge : edges) {
            if (edge.requestingConnection() != blocker && connected.contains(edge.requestingConnection())
                    && runtimeUser.equals(edge.user()) && edge.transaction() != null
                    && !edge.transaction().isBlank() && !edge.transaction().equals("0")) {
                connections.add(edge.requestingConnection());
                transactions.add(edge.transaction());
            }
        }
        return new Observed(connections, transactions);
    }

    static List<Edge> read(Connection connection, String schema) throws SQLException {
        // Both ends are real server thread ids; only locks requested in our owned schema enter the graph.
        String sql = "SELECT w.REQUESTING_ENGINE_TRANSACTION_ID, r.PROCESSLIST_ID, b.PROCESSLIST_ID, r.PROCESSLIST_USER "
                + "FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID "
                + "JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID "
                + "WHERE l.OBJECT_SCHEMA=? ORDER BY r.PROCESSLIST_ID,b.PROCESSLIST_ID LIMIT " + MAX_WAIT_EDGES;
        var edges = new ArrayList<Edge>();
        try (var query = connection.prepareStatement(sql)) {
            query.setString(1, schema);
            query.setQueryTimeout(1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) edges.add(new Edge(rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getString(4)));
            }
        }
        return List.copyOf(edges);
    }
}
