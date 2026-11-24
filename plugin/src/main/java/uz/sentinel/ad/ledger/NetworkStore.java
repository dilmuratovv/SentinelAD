package uz.sentinel.ad.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared quit snapshots for networks whose servers sync player data (HuskSync and the like). Every server writes the
 * snapshot of a leaving player; the server the player joins next reads it once the synced data is in place. Any JDBC
 * database the server can reach: MySQL/MariaDB (what such networks run anyway) or an SQLite file in a shared folder;
 * Paper bundles both drivers. All database work runs on one background thread.
 */
public final class NetworkStore {
    /** One leaving player's snapshot: watched stackables and the seals they held. */
    public record Row(String server, long at, String amounts, String serials) {}

    private final String url, user, password;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SentinelAD-network");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    public NetworkStore(String url, String user, String password, Logger log) {
        this.url = url; this.user = user; this.password = password; this.log = log;
    }

    private Connection db() throws SQLException {
        if (connection != null && connection.isValid(2)) return connection;
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
        for (String driver : new String[]{"com.mysql.cj.jdbc.Driver", "org.sqlite.JDBC", "org.mariadb.jdbc.Driver"}) {
            try { Class.forName(driver); } catch (ClassNotFoundException ignored) { }
        }
        connection = user == null || user.isEmpty() ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
        try (Statement s = connection.createStatement()) {
            if (url.startsWith("jdbc:sqlite:")) s.execute("PRAGMA busy_timeout = 5000");
            s.execute("CREATE TABLE IF NOT EXISTS sentinelad_snapshots (uuid VARCHAR(36) PRIMARY KEY, server VARCHAR(64) NOT NULL, "
                    + "at BIGINT NOT NULL, amounts TEXT NOT NULL, serials TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sentinelad_edits (uuid VARCHAR(36) PRIMARY KEY, at BIGINT NOT NULL)");
        }
        return connection;
    }

    private void run(String what, SqlTask task) {
        io.execute(() -> {
            try { task.run(db()); }
            catch (SQLException e) { log.log(Level.WARNING, "Network store: could not " + what + ": " + e.getMessage()); }
        });
    }

    private interface SqlTask { void run(Connection c) throws SQLException; }

    /** Checks the connection now; returns an error text or null. Blocking: call once at startup. */
    public String test() {
        try {
            return io.submit(() -> { try { db(); return null; } catch (SQLException e) { return e.getMessage(); } }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return e.toString();
        }
    }

    public void put(String uuid, Row row) {
        run("save a snapshot", c -> {
            try (PreparedStatement p = c.prepareStatement("REPLACE INTO sentinelad_snapshots (uuid, server, at, amounts, serials) VALUES (?, ?, ?, ?, ?)")) {
                p.setString(1, uuid); p.setString(2, row.server()); p.setLong(3, row.at()); p.setString(4, row.amounts()); p.setString(5, row.serials());
                p.executeUpdate();
            }
        });
    }

    /** Lowers a snapshot written moments ago; does nothing when the row is gone or newer (the player already moved on). */
    public void refine(String uuid, long at, String amounts, String serials) {
        run("update a snapshot", c -> {
            try (PreparedStatement p = c.prepareStatement("UPDATE sentinelad_snapshots SET amounts = ?, serials = ? WHERE uuid = ? AND at = ?")) {
                p.setString(1, amounts); p.setString(2, serials); p.setString(3, uuid); p.setLong(4, at);
                p.executeUpdate();
            }
        });
    }

    /** Reads (and deletes) the snapshot; {@code done} gets null when there is none, plus the last admin edit time. */
    public void take(String uuid, Consumer<Object[]> done) {
        run("read a snapshot", c -> {
            Row row = null;
            long edit = Long.MIN_VALUE;
            try (PreparedStatement p = c.prepareStatement("SELECT server, at, amounts, serials FROM sentinelad_snapshots WHERE uuid = ?")) {
                p.setString(1, uuid);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) row = new Row(r.getString(1), r.getLong(2), r.getString(3), r.getString(4));
                }
            }
            try (PreparedStatement p = c.prepareStatement("SELECT at FROM sentinelad_edits WHERE uuid = ?")) {
                p.setString(1, uuid);
                try (ResultSet r = p.executeQuery()) { if (r.next()) edit = r.getLong(1); }
            }
            if (row != null) {
                try (PreparedStatement p = c.prepareStatement("DELETE FROM sentinelad_snapshots WHERE uuid = ? AND at = ?")) {
                    p.setString(1, uuid); p.setLong(2, row.at()); p.executeUpdate();
                }
            }
            done.accept(new Object[]{row, edit});
        });
    }

    /** A player joined here: a snapshot left from an older session must not be compared later. */
    public void forget(String uuid) {
        run("clear a snapshot", c -> {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM sentinelad_snapshots WHERE uuid = ?")) {
                p.setString(1, uuid); p.executeUpdate();
            }
        });
    }

    /** An admin or console item command named this player (OpenInv, rollbacks): excuses a later difference anywhere. */
    public void edited(String uuid, long at) {
        run("save an edit", c -> {
            try (PreparedStatement p = c.prepareStatement("REPLACE INTO sentinelad_edits (uuid, at) VALUES (?, ?)")) {
                p.setString(1, uuid); p.setLong(2, at); p.executeUpdate();
            }
        });
    }

    public void close() {
        io.shutdown();
        try { io.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
    }
}
