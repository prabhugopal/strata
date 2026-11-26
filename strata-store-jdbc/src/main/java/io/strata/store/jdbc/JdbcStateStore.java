package io.strata.store.jdbc;

import com.typesafe.config.Config;
import io.strata.StrataException;
import io.strata.EntityTypeNames;
import io.strata.StrataSettings;
import io.strata.spi.StaleRevisionException;
import io.strata.spi.StateStore;
import io.strata.spi.StateStoreProvider;
import io.strata.spi.Pending;
import io.strata.spi.StoredEntry;
import io.strata.spi.StoredState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * strata.store.kind = "jdbc". One row per entity with an optimistic revision column, so a
 * stale owner can never overwrite newer state (it gets {@link StaleRevisionException}).
 * Blocking JDBC runs on the configured store executor (virtual threads by default).
 * Connections come from a HikariCP pool sized by {@code strata.store.jdbc.pool}.
 */
public final class JdbcStateStore implements StateStore {
    private final String url, table, journal, results;
    private final int maxKeyLength;
    private final com.zaxxer.hikari.HikariDataSource pool;
    private final int timeoutSeconds, maxIdLength;
    private final long maxStateBytes;
    private final Executor io;

    private static String identifier(Config c, String key) {
        String v = c.getString(key);
        if (!v.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("strata.store.jdbc." + key + " must be a plain SQL identifier: " + v);
        return v;
    }

    JdbcStateStore(Config c, int maxIdLength, int maxKeyLength, Executor io) {
        this.maxIdLength = maxIdLength;
        this.maxStateBytes = c.getBytes("max-state-bytes");
        this.url = c.getString("url");
        this.table = identifier(c, "table");
        this.journal = identifier(c, "journal-table");
        this.results = identifier(c, "results-table");
        this.maxKeyLength = maxKeyLength;
        this.timeoutSeconds = (int) Math.max(1, c.getDuration("statement-timeout").toSeconds());
        this.io = io;
        var hc = new com.zaxxer.hikari.HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(c.getString("user"));
        hc.setPassword(c.getString("password"));
        hc.setPoolName("strata-jdbc");
        hc.setMaximumPoolSize(c.getInt("pool.max-size"));
        hc.setMinimumIdle(c.getInt("pool.min-idle"));
        hc.setConnectionTimeout(c.getDuration("pool.connection-timeout").toMillis());
        hc.setIdleTimeout(c.getDuration("pool.idle-timeout").toMillis());
        hc.setMaxLifetime(c.getDuration("pool.max-lifetime").toMillis());
        hc.setInitializationFailTimeout(c.getDuration("pool.initialization-timeout").toMillis());
        this.pool = new com.zaxxer.hikari.HikariDataSource(hc);
        try {
            if (c.getBoolean("create-schema")) createSchema();
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
    }

    private Connection connect() throws SQLException {
        return pool.getConnection();
    }

    @Override
    public void close() {
        pool.close();
    }

    private void createSchema() {
        String keyCols = "entity_type VARCHAR(" + EntityTypeNames.MAX_LENGTH + ") NOT NULL, entity_id VARCHAR(" + maxIdLength + ") NOT NULL, ";
        create(table, c -> "CREATE TABLE IF NOT EXISTS " + table + " (" + keyCols
                + "revision BIGINT NOT NULL, state " + blobType(c) + " NOT NULL, updated_at TIMESTAMP NOT NULL, "
                + "PRIMARY KEY (entity_type, entity_id))");
        // seq 0 is the invocation's header (command, argument, key); 1.. are its service calls.
        create(journal, c -> "CREATE TABLE IF NOT EXISTS " + journal + " (" + keyCols
                + "invocation_id VARCHAR(64) NOT NULL, seq INT NOT NULL, payload " + blobType(c) + " NOT NULL, created_at TIMESTAMP NOT NULL, "
                + "PRIMARY KEY (entity_type, entity_id, seq))");
        create(results, c -> "CREATE TABLE IF NOT EXISTS " + results + " (" + keyCols
                + "idem_key VARCHAR(" + maxKeyLength + ") NOT NULL, outcome " + blobType(c) + " NOT NULL, completed_at TIMESTAMP NOT NULL, "
                + "PRIMARY KEY (entity_type, entity_id, idem_key))");
    }

    private interface Ddl { String sql(Connection c) throws SQLException; }

    private void create(String name, Ddl ddl) {
        try (Connection c = connect(); var st = c.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            st.execute(ddl.sql(c));
        } catch (SQLException e) {
            // Several nodes starting together may race to create it (PostgreSQL's IF NOT EXISTS
            // isn't safe against that); if it exists now, another node won.
            if (tableExists(name)) return;
            throw new StrataException("Can't create table " + name + " at " + url, e);
        }
    }

    private boolean tableExists(String table) {
        try (Connection c = connect(); var st = c.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            st.executeQuery("SELECT 1 FROM " + table + " WHERE 1 = 0").close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private String blobType(Connection c) throws SQLException {
        return c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres") ? "BYTEA" : "VARBINARY(" + maxStateBytes + ")";
    }

    @Override
    public CompletionStage<Optional<StoredState>> load(String type, String id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement("SELECT revision, state FROM " + table + " WHERE entity_type = ? AND entity_id = ?")) {
                ps.setQueryTimeout(timeoutSeconds);
                ps.setString(1, type);
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new StoredState(rs.getLong(1), rs.getBytes(2))) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new StrataException("Can't load " + type + "/" + id, e);
            }
        }, io);
    }

    @Override
    public CompletionStage<Long> save(String type, String id, long expectedRevision, byte[] state) {
        return CompletableFuture.supplyAsync(() -> {
            Timestamp now = Timestamp.from(Instant.now());
            try (Connection c = connect()) {
                int rows;
                if (expectedRevision == 0) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                            + " (entity_type, entity_id, revision, state, updated_at) VALUES (?, ?, 1, ?, ?)")) {
                        ps.setQueryTimeout(timeoutSeconds);
                        ps.setString(1, type);
                        ps.setString(2, id);
                        ps.setBytes(3, state);
                        ps.setTimestamp(4, now);
                        rows = ps.executeUpdate();
                    } catch (SQLException duplicate) {
                        if (isDuplicateKey(duplicate)) throw new StaleRevisionException(type, id, expectedRevision);
                        throw duplicate;
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE " + table
                            + " SET revision = revision + 1, state = ?, updated_at = ? WHERE entity_type = ? AND entity_id = ? AND revision = ?")) {
                        ps.setQueryTimeout(timeoutSeconds);
                        ps.setBytes(1, state);
                        ps.setTimestamp(2, now);
                        ps.setString(3, type);
                        ps.setString(4, id);
                        ps.setLong(5, expectedRevision);
                        rows = ps.executeUpdate();
                    }
                }
                if (rows != 1) throw new StaleRevisionException(type, id, expectedRevision);
                return expectedRevision + 1;
            } catch (SQLException e) {
                throw new StrataException("Can't save " + type + "/" + id, e);
            }
        }, io);
    }

    // ---- durable invocations and idempotency keys ----------------------------------------------

    private interface Work<T> { T run(Connection c) throws SQLException; }

    private <T> CompletionStage<T> async(String what, Work<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = connect()) {
                return work.run(c);
            } catch (SQLException e) {
                throw new StrataException("Can't " + what, e);
            }
        }, io);
    }

    private PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        ps.setQueryTimeout(timeoutSeconds);
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof byte[] b) ps.setBytes(i + 1, b);
            else if (a instanceof Timestamp t) ps.setTimestamp(i + 1, t);
            else ps.setObject(i + 1, a);
        }
        return ps;
    }

    @Override
    public CompletionStage<Void> begin(String type, String id, String invocationId, byte[] header) {
        return async("record an invocation of " + type + "/" + id, c -> {
            c.setAutoCommit(false);
            try (var del = prepare(c, "DELETE FROM " + journal + " WHERE entity_type = ? AND entity_id = ?", type, id);
                 var ins = prepare(c, "INSERT INTO " + journal + " (entity_type, entity_id, invocation_id, seq, payload, created_at) VALUES (?, ?, ?, 0, ?, ?)",
                         type, id, invocationId, header, Timestamp.from(Instant.now()))) {
                del.executeUpdate();
                ins.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
            return null;
        });
    }

    @Override
    public CompletionStage<Void> record(String type, String id, String invocationId, int seq, byte[] entry) {
        return async("record a service call of " + type + "/" + id, c -> {
            // Only the invocation that owns the journal may append (a stale node's writes fail).
            c.setAutoCommit(false);
            try {
                try (var ps = prepare(c, "SELECT invocation_id FROM " + journal + " WHERE entity_type = ? AND entity_id = ? AND seq = 0 FOR UPDATE", type, id);
                     ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !invocationId.equals(rs.getString(1))) throw new StaleRevisionException(type, id, -1);
                }
                try (var ps = prepare(c, "INSERT INTO " + journal + " (entity_type, entity_id, invocation_id, seq, payload, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        type, id, invocationId, seq, entry, Timestamp.from(Instant.now()))) {
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
            return null;
        });
    }

    @Override
    public CompletionStage<Optional<Pending>> pending(String type, String id) {
        return async("read the unfinished invocation of " + type + "/" + id, c -> {
            try (var ps = prepare(c, "SELECT invocation_id, seq, payload FROM " + journal + " WHERE entity_type = ? AND entity_id = ? ORDER BY seq", type, id);
                 ResultSet rs = ps.executeQuery()) {
                String invocation = null;
                byte[] header = null;
                List<byte[]> entries = new ArrayList<>();
                while (rs.next()) {
                    if (rs.getInt(2) == 0) { invocation = rs.getString(1); header = rs.getBytes(3); }
                    else entries.add(rs.getBytes(3));
                }
                return invocation == null ? Optional.<Pending>empty() : Optional.of(new Pending(invocation, header, entries));
            }
        });
    }

    @Override
    public CompletionStage<Optional<byte[]>> completed(String type, String id, String key) {
        return async("read an idempotency key of " + type + "/" + id, c -> {
            try (var ps = prepare(c, "SELECT outcome FROM " + results + " WHERE entity_type = ? AND entity_id = ? AND idem_key = ?", type, id, key);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.<byte[]>empty();
            }
        });
    }

    @Override
    public CompletionStage<Long> commit(String type, String id, long expectedRevision, byte[] state,
                                        String invocationId, String key, byte[] outcome, Instant keepAfter) {
        if (invocationId == null && key == null && state != null) return save(type, id, expectedRevision, state);
        return async("commit " + type + "/" + id, c -> {
            Timestamp now = Timestamp.from(Instant.now());
            c.setAutoCommit(false);
            try {
                long written = expectedRevision;
                if (state != null) {
                    int rows;
                    if (expectedRevision == 0) {
                        try (var ps = prepare(c, "INSERT INTO " + table + " (entity_type, entity_id, revision, state, updated_at) VALUES (?, ?, 1, ?, ?)", type, id, state, now)) {
                            rows = ps.executeUpdate();
                        } catch (SQLException dup) {
                            if (isDuplicateKey(dup)) throw new StaleRevisionException(type, id, expectedRevision);
                            throw dup;
                        }
                    } else {
                        try (var ps = prepare(c, "UPDATE " + table + " SET revision = revision + 1, state = ?, updated_at = ? WHERE entity_type = ? AND entity_id = ? AND revision = ?",
                                state, now, type, id, expectedRevision)) {
                            rows = ps.executeUpdate();
                        }
                    }
                    if (rows != 1) throw new StaleRevisionException(type, id, expectedRevision);
                    written = expectedRevision + 1;
                }
                if (invocationId != null) {
                    try (var ps = prepare(c, "DELETE FROM " + journal + " WHERE entity_type = ? AND entity_id = ? AND invocation_id = ?", type, id, invocationId)) {
                        ps.executeUpdate();
                    }
                }
                if (key != null) {
                    try (var ps = prepare(c, "INSERT INTO " + results + " (entity_type, entity_id, idem_key, outcome, completed_at) VALUES (?, ?, ?, ?, ?)", type, id, key, outcome, now)) {
                        ps.executeUpdate();
                    }
                }
                if (keepAfter != null) {
                    try (var ps = prepare(c, "DELETE FROM " + results + " WHERE entity_type = ? AND entity_id = ? AND completed_at < ?", type, id, Timestamp.from(keepAfter))) {
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return written;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        });
    }

    @Override
    public CompletionStage<List<StoredEntry>> list(String type, String afterId, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT entity_id, revision, updated_at FROM " + table + " WHERE entity_type = ?"
                    + (afterId == null ? "" : " AND entity_id > ?") + " ORDER BY entity_id FETCH FIRST " + Math.max(1, limit) + " ROWS ONLY";
            try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(timeoutSeconds);
                ps.setString(1, type);
                if (afterId != null) ps.setString(2, afterId);
                List<StoredEntry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new StoredEntry(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant()));
                }
                return out;
            } catch (SQLException e) {
                throw new StrataException("Can't list " + type + " entities", e);
            }
        }, io);
    }

    private static boolean isDuplicateKey(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23"); // integrity constraint violation (all databases)
    }

    public static final class Provider implements StateStoreProvider {
        @Override public String kind() { return "jdbc"; }

        @Override
        public StateStore create(StrataSettings settings, Executor io) {
            return new JdbcStateStore(settings.config().getConfig("strata.store.jdbc"), settings.maxIdLength(),
                    settings.config().getInt("strata.durability.max-key-length"), io);
        }
    }
}
