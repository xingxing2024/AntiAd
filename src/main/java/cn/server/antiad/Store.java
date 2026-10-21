package cn.server.antiad;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** SQLite 命中记录库：记录每次命中的原文与处理结果，重启后累计次数也从这里恢复。 */
public final class Store implements AutoCloseable {
    /** 记录类型：ad = 广告命中，word = 违禁词命中，ai = AI 判定为广告，ban = 已封禁。 */
    public record Hit(long hit, String player, String ip, String kind, String match, String content, String action) {}

    /** 一天的 AI 用量：{日期, 调用次数, 输入 tokens, 输出 tokens}。 */
    public record Usage(String day, int calls, int prompt, int completion) {}

    /** 玩家行为画像（按行为累计）：活跃度计数 + 命中扣分 + 是否被封过（永久）。 */
    public record BehaviorRow(UUID uuid, String name, int blocks, int minutes, int commands, int joins, int penalty, boolean banned, long updated) {}

    private final Connection connection;
    private final Logger logger;

    public Store(Path file, Logger logger) throws SQLException {
        this.logger = logger;
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS hits ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, hit INTEGER NOT NULL, uuid TEXT NOT NULL, player TEXT NOT NULL,"
                    + "ip TEXT, kind TEXT NOT NULL, match TEXT, content TEXT, action TEXT)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS hits_player ON hits(uuid, kind, hit)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS usage ("
                    + "day TEXT PRIMARY KEY, calls INTEGER NOT NULL, prompt INTEGER NOT NULL, completion INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS behavior ("
                    + "uuid TEXT PRIMARY KEY, name TEXT, blocks INTEGER NOT NULL, minutes INTEGER NOT NULL, commands INTEGER NOT NULL,"
                    + " joins INTEGER NOT NULL, penalty INTEGER NOT NULL, banned INTEGER NOT NULL, updated INTEGER NOT NULL)");
        }
    }

    /** 写入/更新行为画像：正常游玩加分、违规扣分，最后都体现在这一行上。 */
    public synchronized void saveBehavior(BehaviorRow row) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO behavior(uuid, name, blocks, minutes, commands, joins, penalty, banned, updated) VALUES(?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, blocks = excluded.blocks, minutes = excluded.minutes,"
                        + " commands = excluded.commands, joins = excluded.joins, penalty = excluded.penalty,"
                        + " banned = excluded.banned, updated = excluded.updated")) {
            statement.setString(1, row.uuid().toString());
            statement.setString(2, row.name());
            statement.setInt(3, row.blocks());
            statement.setInt(4, row.minutes());
            statement.setInt(5, row.commands());
            statement.setInt(6, row.joins());
            statement.setInt(7, row.penalty());
            statement.setInt(8, row.banned() ? 1 : 0);
            statement.setLong(9, row.updated());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "写入行为画像失败: " + e.getMessage());
        }
    }

    /** 一次事务批量写入行为画像：避免逐条 commit 造成大量 fsync（会把服务端线程卡住）。 */
    public synchronized void saveBehaviors(List<BehaviorRow> rows) {
        if (rows.isEmpty()) return;
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO behavior(uuid, name, blocks, minutes, commands, joins, penalty, banned, updated) VALUES(?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, blocks = excluded.blocks, minutes = excluded.minutes,"
                            + " commands = excluded.commands, joins = excluded.joins, penalty = excluded.penalty,"
                            + " banned = excluded.banned, updated = excluded.updated")) {
                for (BehaviorRow row : rows) {
                    statement.setString(1, row.uuid().toString());
                    statement.setString(2, row.name());
                    statement.setInt(3, row.blocks());
                    statement.setInt(4, row.minutes());
                    statement.setInt(5, row.commands());
                    statement.setInt(6, row.joins());
                    statement.setInt(7, row.penalty());
                    statement.setInt(8, row.banned() ? 1 : 0);
                    statement.setLong(9, row.updated());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            logger.log(Level.WARNING, "批量写入行为画像失败: " + e.getMessage());
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    /** 读一个玩家的行为画像，没有返回 null。行为按行为累计，不按时间清理。 */
    public synchronized BehaviorRow behavior(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, blocks, minutes, commands, joins, penalty, banned, updated FROM behavior WHERE uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return new BehaviorRow(player, rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getInt(4),
                            rows.getInt(5), rows.getInt(6), rows.getInt(7) != 0, rows.getLong(8));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "读取行为画像失败: " + e.getMessage());
        }
        return null;
    }

    /** 行为画像列表，最近有活动的在前。 */
    public synchronized List<BehaviorRow> behaviors(int limit) {
        List<BehaviorRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, name, blocks, minutes, commands, joins, penalty, banned, updated FROM behavior ORDER BY updated DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new BehaviorRow(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getInt(3), rows.getInt(4),
                            rows.getInt(5), rows.getInt(6), rows.getInt(7), rows.getInt(8) != 0, rows.getLong(9)));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "读取行为画像列表失败: " + e.getMessage());
        }
        return result;
    }

    /** 本插件封过的号永久标记（换名字也认得 UUID）。 */
    public synchronized void markBanned(UUID player, String name) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO behavior(uuid, name, blocks, minutes, commands, joins, penalty, banned, updated) VALUES(?,?,0,0,0,0,0,1,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, banned = 1, updated = excluded.updated")) {
            statement.setString(1, player.toString());
            statement.setString(2, name);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "写入封禁标记失败: " + e.getMessage());
        }
    }

    /** 记一次 AI 调用的 token 用量，按天累加（/antiad cost 用）。 */
    public synchronized void addUsage(String day, int promptTokens, int completionTokens) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO usage(day, calls, prompt, completion) VALUES(?, 1, ?, ?) "
                        + "ON CONFLICT(day) DO UPDATE SET calls = calls + 1, prompt = prompt + excluded.prompt, completion = completion + excluded.completion")) {
            statement.setString(1, day);
            statement.setInt(2, promptTokens);
            statement.setInt(3, completionTokens);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "写入 AI 用量失败: " + e.getMessage());
        }
    }

    /** 最近 days 天的用量，最新的在前。 */
    public synchronized List<Usage> usage(int days) {
        List<Usage> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT day, calls, prompt, completion FROM usage ORDER BY day DESC LIMIT ?")) {
            statement.setInt(1, days);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new Usage(rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getInt(4)));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "读取 AI 用量失败: " + e.getMessage());
        }
        return result;
    }

    public synchronized void record(long hit, UUID player, String name, String ip, String kind, String match, String content, String action) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO hits(hit, uuid, player, ip, kind, match, content, action) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, hit);
            statement.setString(2, player.toString());
            statement.setString(3, name);
            statement.setString(4, ip);
            statement.setString(5, kind);
            statement.setString(6, match);
            statement.setString(7, content);
            statement.setString(8, action);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "写入命中记录失败: " + e.getMessage());
        }
    }

    /** 窗口内的广告命中时间，用来在重启后恢复累计次数（action=soft/released 的没确认成广告，不算）。 */
    public synchronized Map<UUID, List<Long>> adHits(long since) {
        Map<UUID, List<Long>> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, hit FROM hits WHERE kind IN ('ad', 'ai') AND action NOT IN ('soft', 'released') AND hit >= ? ORDER BY hit")) {
            statement.setLong(1, since);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.computeIfAbsent(UUID.fromString(rows.getString(1)), key -> new ArrayList<>()).add(rows.getLong(2));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "读取命中记录失败: " + e.getMessage());
        }
        return result;
    }

    /** 最近的命中记录，最新的在前；player 为空表示不限玩家。 */
    public synchronized List<Hit> recent(String player, int limit) {
        List<Hit> result = new ArrayList<>();
        String sql = "SELECT hit, player, ip, kind, match, content, action FROM hits"
                + (player == null || player.isBlank() ? "" : " WHERE lower(player) = ?")
                + " ORDER BY hit DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (player != null && !player.isBlank()) statement.setString(index++, player.toLowerCase(java.util.Locale.ROOT));
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new Hit(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getString(5), rows.getString(6), rows.getString(7)));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询命中记录失败: " + e.getMessage());
        }
        return result;
    }

    /** 该玩家的记录条数与其中封禁次数：{总条数, 封禁次数}。 */
    public synchronized int[] summary(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), SUM(CASE WHEN kind = 'ban' THEN 1 ELSE 0 END) FROM hits WHERE uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return new int[]{rows.getInt(1), rows.getInt(2)};
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "统计命中记录失败: " + e.getMessage());
        }
        return new int[]{0, 0};
    }

    /** 某个玩家近一段时间内的违规命中次数（结构类只是形状、不算内容违规；没探测确认过/已放行的地址命中也不算）。 */
    public synchronized int violations(UUID player, long since) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM hits WHERE uuid = ? AND hit >= ? AND action NOT IN ('soft', 'released')"
                        + " AND kind IN ('ad', 'ai', 'word', 'combine', 'sign', 'anvil', 'book', 'ban')")) {
            statement.setString(1, player.toString());
            statement.setLong(2, since);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return rows.getInt(1);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "统计违规次数失败: " + e.getMessage());
        }
        return 0;
    }

    /** 该玩家最近一次被记录的 IP（用于离线封禁/解封），没有返回 null。 */
    public synchronized String lastIp(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ip FROM hits WHERE uuid = ? AND ip IS NOT NULL ORDER BY hit DESC LIMIT 1")) {
            statement.setString(1, player.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return rows.getString(1);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "读取最近 IP 失败: " + e.getMessage());
        }
        return null;
    }

    /** 删除 before 之前的记录与用量，keep-days 为 0 时不调用。 */
    public synchronized void purge(long before) {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM hits WHERE hit < ?")) {
            statement.setLong(1, before);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "清理命中记录失败: " + e.getMessage());
        }
        String cutoff = LocalDate.ofInstant(Instant.ofEpochMilli(before), ZoneId.systemDefault()).toString();
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM usage WHERE day < ?")) {
            statement.setString(1, cutoff);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "清理 AI 用量失败: " + e.getMessage());
        }
    }

    @Override public synchronized void close() throws SQLException {
        connection.close();
    }
}
