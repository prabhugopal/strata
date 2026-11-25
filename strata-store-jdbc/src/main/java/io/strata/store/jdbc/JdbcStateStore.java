package io.strata.store.jdbc;

import com.typesafe.config.Config;
import io.strata.StrataException;
import io.strata.EntityTypeNames;
import io.strata.StrataSettings;
import io.strata.spi.StaleRevisionException;
import io.strata.spi.StateStore;
import io.strata.spi.StateStoreProvider;
import io.strata.spi.StoredEntry;
import io.strata.spi.StoredState;

import java.sql.Connection;
import java.sql.DriverManager;
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
 *
 * <p>M0 opens a connection per operation; a pooled DataSource is a later step.
 */
public final class JdbcStateStore implements StateStore {
    private final String url, user, password, table;
    private final int timeoutSeconds, maxIdLength;
    private final long maxStateBytes;
    private final Executor io;

    JdbcStateStore(Config c, int maxIdLength, Executor io) {
        this.maxIdLength = maxIdLength;
        this.maxStateBytes = c.getBytes("max-state-bytes");
        this.url = c.getString("url");
        this.user = c.getString("user");
        this.password = c.getString("password");
        this.table = c.getString("table");
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("strata.store.jdbc.table must be a plain SQL identifier: " + table);
        }
        this.timeoutSeconds = (int) Math.max(1, c.getDuration("statement-timeout").toSeconds());
        this.io = io;
        if (c.getBoolean("create-schema")) createSchema();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private void createSchema() {
        try (Connection c = connect(); var st = c.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "entity_type VARCHAR(" + EntityTypeNames.MAX_LENGTH + ") NOT NULL, entity_id VARCHAR(" + maxIdLength + ") NOT NULL, "
                    + "revision BIGINT NOT NULL, state " + blobType(c) + " NOT NULL, updated_at TIMESTAMP NOT NULL, "
                    + "PRIMARY KEY (entity_type, entity_id))");
        } catch (SQLException e) {
            throw new StrataException("Can't create table " + table + " at " + url, e);
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
            return new JdbcStateStore(settings.config().getConfig("strata.store.jdbc"), settings.maxIdLength(), io);
        }
    }
}
