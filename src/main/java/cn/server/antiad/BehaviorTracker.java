package cn.server.antiad;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实时监测玩家行为：正常游玩（破坏方块、用指令、进服）加分，命中违规扣分。
 * 分数按行为累计、不按时间算；内存里实时更新，每分钟与退出时落一次库。
 */
public final class BehaviorTracker implements Listener {
    private final AntiAdPlugin plugin;
    private final Map<UUID, Behavior.Profile> profiles = new HashMap<>();
    /** 本次会话：{进入时间, 进入时的方块数}，用来识别"进来就退、什么都没干"。 */
    private final Map<UUID, long[]> sessions = new HashMap<>();
    /** 老玩家判定缓存：在线时长（tick）与近 clean-hours 内的违规次数，每分钟在玩家线程上刷新。 */
    private final Map<UUID, Long> playTicks = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();

    BehaviorTracker(AntiAdPlugin plugin) {
        this.plugin = plugin;
    }

    /** 分类；行为系统关掉时一律按默认强度处理。 */
    Behavior.Kind kind(Player player) {
        return kind(player.getUniqueId());
    }

    synchronized Behavior.Kind kind(UUID player) {
        Settings settings = plugin.settings();
        if (!settings.behaviorEnabled) return Behavior.Kind.WATCH;
        return Behavior.classify(settings, profile(player));
    }

    /** 该类玩家的封禁阈值（可疑 2、广告号 1）。 */
    int threshold(Player player) {
        return threshold(player.getUniqueId());
    }

    int threshold(UUID player) {
        Settings settings = plugin.settings();
        return settings.behaviorEnabled ? Behavior.threshold(settings, kind(player)) : settings.hits;
    }

    synchronized int score(Player player) {
        return score(player.getUniqueId());
    }

    synchronized int score(UUID player) {
        return Behavior.score(plugin.settings(), profile(player));
    }

    /** 违规扣分：只对窗口内的玩家计分，老玩家不受影响。 */
    synchronized void penalty(Player player, boolean adHit) {
        Settings settings = plugin.settings();
        if (!settings.behaviorEnabled || !watched(player)) return;
        Behavior.Profile profile = profile(player.getUniqueId());
        profile.penalty -= Behavior.penalty(settings, adHit);
        profile.dirty = true;
    }

    /** 本插件封过的号：永久留档，以后一律按广告号处理。 */
    synchronized void markBanned(Player player) {
        markBanned(player.getUniqueId(), player.getName());
    }

    synchronized void markBanned(UUID id, String name) {
        if (!plugin.settings().behaviorEnabled) return;
        Behavior.Profile profile = profile(id);
        profile.banned = true;
        profile.dirty = true;
        // 落库走异步，别在服务端线程写数据库。
        plugin.runAsync(() -> {
            Store store = plugin.store();
            if (store != null) store.markBanned(id, name);
        });
    }

    /** 在玩家所属线程上读在线时长（只读玩家数据，不碰数据库）。 */
    synchronized void refreshPlayTicks(Player player) {
        playTicks.put(player.getUniqueId(), (long) player.getStatistic(Statistic.PLAY_ONE_MINUTE));
    }

    /** 刷新近 clean-hours 小时内的违规次数（查库，只应该在异步线程调用）。 */
    synchronized void refreshViolations(UUID player) {
        Store store = plugin.store();
        long since = System.currentTimeMillis() - plugin.settings().veteranCleanHours * 3600_000L;
        violations.put(player, store == null ? 0 : store.violations(player, since));
    }

    synchronized long playTicks(UUID player) {
        return playTicks.getOrDefault(player, 0L);
    }

    synchronized int violations(UUID player) {
        return violations.getOrDefault(player, 0);
    }

    /** 刚记下一次违规：缓存立刻加一，老玩家判定立即失效（不用等下一分钟）。 */
    synchronized void countViolation(Player player) {
        violations.merge(player.getUniqueId(), 1, Integer::sum);
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        onJoin(player);
        refreshPlayTicks(player);
        UUID id = player.getUniqueId();
        plugin.runAsync(() -> refreshViolations(id));
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockBreak(BlockBreakEvent event) {
        onBlockBreak(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        onCommand(event.getPlayer());
    }

    private synchronized void onJoin(Player player) {
        if (!watched(player)) return;
        Behavior.Profile profile = profile(player.getUniqueId());
        profile.name = player.getName();
        profile.joins++;
        profile.dirty = true;
        sessions.put(player.getUniqueId(), new long[]{System.currentTimeMillis(), profile.blocks});
        // 反复进出是推广号的常见行为。
        if (profile.joins > 3) profile.penalty -= 1;
    }

    private synchronized void onQuit(Player player) {
        long[] session = sessions.remove(player.getUniqueId());
        if (session == null) return;   // 没被监测（老玩家或行为系统关闭）
        Behavior.Profile profile = profile(player.getUniqueId());
        int minutes = (int) ((System.currentTimeMillis() - session[0]) / 60_000L);
        int blocks = profile.blocks - (int) session[1];
        profile.minutes += minutes;   // 只用于展示，不计分
        // 进来就退、什么都没干：算可疑行为。
        if (Behavior.churn(minutes, blocks)) profile.penalty -= 1;
        flushAsync(player.getUniqueId());
    }

    private synchronized void onBlockBreak(Player player) {
        if (!watched(player)) return;
        Behavior.Profile profile = profile(player.getUniqueId());
        profile.blocks++;
        profile.dirty = true;
    }

    private synchronized void onCommand(Player player) {
        if (!watched(player)) return;
        Behavior.Profile profile = profile(player.getUniqueId());
        profile.commands++;
        profile.dirty = true;
    }

    /** 只统计受管制的玩家（老玩家不参与行为监测，也就不受影响）。 */
    private boolean watched(Player player) {
        return plugin.settings().behaviorEnabled && ChatGuard.watched(plugin, player);
    }

    /** 每分钟把"有变化"的画像一次事务批量落库（只应该在异步线程调用）。 */
    synchronized void flushAll() {
        Store store = plugin.store();
        if (store == null) return;
        List<Store.BehaviorRow> rows = new ArrayList<>();
        for (Map.Entry<UUID, Behavior.Profile> entry : profiles.entrySet()) {
            Behavior.Profile profile = entry.getValue();
            if (!profile.dirty) continue;
            profile.dirty = false;
            // 只查过一眼（/antiad check）的空画像不落库，免得把老玩家也写进行为列表。
            if (profile.blocks == 0 && profile.minutes == 0 && profile.commands == 0
                    && profile.joins == 0 && profile.penalty == 0 && !profile.banned) continue;
            rows.add(row(entry.getKey(), profile));
        }
        if (!rows.isEmpty()) store.saveBehaviors(rows);
    }

    /** 退出时立刻把这一份画像丢到异步线程落库（不在服务端线程写库）。 */
    private synchronized void flushAsync(UUID player) {
        Behavior.Profile profile = profiles.get(player);
        if (profile == null) return;
        profile.dirty = false;
        Store.BehaviorRow snapshot = row(player, profile);
        plugin.runAsync(() -> {
            Store store = plugin.store();
            if (store != null) store.saveBehaviors(List.of(snapshot));
        });
    }

    private static Store.BehaviorRow row(UUID uuid, Behavior.Profile profile) {
        return new Store.BehaviorRow(uuid, profile.name == null ? "?" : profile.name, profile.blocks, profile.minutes,
                profile.commands, profile.joins, profile.penalty, profile.banned, System.currentTimeMillis());
    }

    /** 内存没有就从库里读，读不到就是新画像（默认分由 Behavior.score 加）。 */
    synchronized Behavior.Profile profile(UUID player) {
        return profiles.computeIfAbsent(player, key -> {
            Behavior.Profile loaded = new Behavior.Profile();
            Store store = plugin.store();
            if (store == null) return loaded;
            Store.BehaviorRow row = store.behavior(key);
            if (row == null) return loaded;
            loaded.name = row.name();
            loaded.blocks = row.blocks();
            loaded.minutes = row.minutes();
            loaded.commands = row.commands();
            loaded.joins = row.joins();
            loaded.penalty = row.penalty();
            loaded.banned = row.banned();
            return loaded;
        });
    }
}
