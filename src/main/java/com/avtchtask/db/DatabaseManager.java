package com.avtchtask.db;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe facade for SQLite access.
 *
 * Single responsibility: manage the database connection, schema, and read queries.
 * Write path is fully delegated to {@link AsyncBatchWriter} (group commit).
 *
 * Why single connection?  SQLite in-memory databases are not reliably shared
 * across JDBC connections even with ?mode=memory&cache=shared; a single connection
 * avoids all isolation / visibility issues.
 *
 * Config (app.properties):
 *   db.write.batch.size - max writes per transaction (default 100)
 */
public class DatabaseManager {

    // ---- State ---------------------------------------------------------------

    private final Connection      conn;
    private final ReentrantLock   lock   = new ReentrantLock(true); // fair FIFO, shared with writer
    private final AsyncBatchWriter writer;

    /** Exposes write-timing data; configure before running threads. */
    public final WriteMetrics metrics = new WriteMetrics();

    // ---- Constructors --------------------------------------------------------

    /**
     * @param dbFile    SQLite file path or ":memory:"
     * @param batchSize max writes per transaction (>= 1; higher = fewer, larger transactions)
     */
    public DatabaseManager(String dbFile, int batchSize) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        writer = new AsyncBatchWriter(conn, lock, Math.max(1, batchSize), metrics);
    }

    /** Backward-compatible default: batch size 100. */
    public DatabaseManager(String dbFile) throws SQLException {
        this(dbFile, 100);
    }

    // ---- Schema --------------------------------------------------------------

    public void initSchema() throws SQLException {
        lock.lock();
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS user_sessions (" +
                "  user_id   TEXT PRIMARY KEY," +
                "  logged_in INTEGER NOT NULL DEFAULT 0" +
                ")"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS data_modifications (" +
                "  id         INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  user_id    TEXT    NOT NULL," +
                "  created_at TEXT    NOT NULL" +
                ")"
            );
        } finally {
            lock.unlock();
        }
    }

    // ---- Write API (delegated to AsyncBatchWriter) ---------------------------

    /**
     * Enqueues the write and blocks until committed.
     * Concurrent callers are automatically batched into a single transaction.
     */
    public void execute(String sql, String... params) {
        writer.enqueue(sql, params);
    }

    // ---- Read API ------------------------------------------------------------

    public boolean queryBoolean(String sql, String param) {
        lock.lock();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            System.err.println("[DB ERROR] " + e.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    public Set<String> queryStringSet(String sql) {
        Set<String> result = new LinkedHashSet<>();
        lock.lock();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(rs.getString(1));
        } catch (SQLException e) {
            System.err.println("[DB ERROR] " + e.getMessage());
        } finally {
            lock.unlock();
        }
        return result;
    }

    public Map<String, Long> queryCountMap(String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        lock.lock();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.put(rs.getString(1), rs.getLong(2));
        } catch (SQLException e) {
            System.err.println("[DB ERROR] " + e.getMessage());
        } finally {
            lock.unlock();
        }
        return result;
    }

    // ---- Lifecycle -----------------------------------------------------------

    public void close() {
        writer.close();
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException ignored) { }
    }

    public int    getBatchSize()  { return writer.getBatchSize(); }
    public String getLockStats()  { return metrics.getSummary(); }
}

