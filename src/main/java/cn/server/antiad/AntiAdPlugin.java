package cn.server.antiad;

import io.papermc.paper.ban.BanListType;
import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AntiAdPlugin extends JavaPlugin {
    private Settings settings;
    private Store store;
    private Ai ai;
    private ChatGuard guard;
    private BehaviorTracker behavior;
    private ExecutorService aiPool;
    private Metrics metrics;
    private final java.util.concurrent.atomic.AtomicBoolean flushing = new java.util.concurrent.atomic.AtomicBoolean();

    @Override public void onEnable() {
        saveDefaultConfig();
        fillMissingConfig();
        settings = Settings.load(getConfig());
        // bStats 匿名统计（插件 ID 34706）：只上报版本/服务端/Java/在线人数这类信息，
        // 不含玩家名、聊天内容或 IP；管理员可在 plugins/bStats/config.yml 里全局关闭。
        metrics = new Metrics(this, 34706);
        ai = new Ai(getLogger());
        // AI 审核走自己的线程池，不和服务器其他异步任务抢线程，结论更快回来。
        // 并发数可配（免费接口有 QPS 限流，调小可减少 429），改这里需要重启。
        aiPool = Executors.newFixedThreadPool(settings.aiConcurrent, runnable -> {
            Thread thread = new Thread(runnable, "AntiAd-AI");
            thread.setDaemon(true);
            return thread;
        });
        guard = new ChatGuard(this);
        behavior = new BehaviorTracker(this);
        openStore();
        PluginCommand command = Objects.requireNonNull(getCommand("antiad"));
        AntiAdCommand executor = new AntiAdCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(guard, this);
        getServer().getPluginManager().registerEvents(behavior, this);
        // 每分钟：玩家数据在玩家自己的线程上读（Folia 安全），数据库读写全部丢到异步线程（不卡服务端线程）。
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, scheduled -> {
            for (Player online : Bukkit.getOnlinePlayers()) runFor(online, () -> behavior.refreshPlayTicks(online));
            flushPeriodic();
        }, 1200L, 1200L);
        getLogger().info("AntiAd 已启用：只检查 " + (settings.newPlayerMinutes > 0 ? "进服 " + settings.newPlayerMinutes + " 分钟内的新玩家" : "所有玩家")
                + "，命中的消息仅本人可见，管理员会收到通报；" + settings.windowSeconds + " 秒内累计 " + settings.hits + " 次广告命中将封禁 IP 与账号"
                + (settings.bannedWords.isEmpty() ? "。" : "；敏感词命中交 AI 判断。")
                + (store == null ? " 记录库未启用。" : " 记录写入 antiad.db。")
                + (settings.aiEnabled && !settings.aiKey.isBlank() ? " AI 审核已开启：" + settings.aiModel + "。" : " AI 审核未开启。"));
    }

    @Override public void onDisable() {
        if (behavior != null) behavior.flushAll();
        if (store != null) try {
            store.close();
        } catch (Exception e) {
            getLogger().warning("关闭记录库失败: " + e.getMessage());
        }
        if (ai != null) ai.close();
        if (aiPool != null) aiPool.shutdownNow();
    }

    /** 重载配置，只影响之后的检查；记录库需要重启才能换文件。 */
    public void reload() {
        reloadConfig();
        fillMissingConfig();
        settings = Settings.load(getConfig());
    }

    /**
     * 配置自动补全：拿 jar 内默认配置当模板，把用户配置里缺的键补上（注释一起带过去），
     * 改之前先备份成 config.yml.bak-时间戳。返回补了哪些键。
     */
    private List<String> fillMissingConfig() {
        List<String> added = new ArrayList<>();
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) return added;
            defaults.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            getLogger().warning("配置自动补全失败（读默认配置）: " + e.getMessage());
            return added;
        }
        FileConfiguration config = getConfig();
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path) || config.isSet(path)) continue;
            config.set(path, defaults.get(path));
            // 连注释一起补：叶子键和各级父级都带上，但别覆盖用户自己写的注释。
            for (String current = path; current != null; ) {
                List<String> mine = config.getComments(current);
                List<String> theirs = defaults.getComments(current);
                if (theirs != null && !theirs.isEmpty() && (mine == null || mine.isEmpty())) config.setComments(current, theirs);
                int dot = current.lastIndexOf('.');
                current = dot <= 0 ? null : current.substring(0, dot);
            }
            added.add(path);
        }
        if (added.isEmpty()) return added;
        File file = new File(getDataFolder(), "config.yml");
        String backup = "config.yml.bak-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        try {
            Files.copy(file.toPath(), new File(getDataFolder(), backup).toPath(), StandardCopyOption.REPLACE_EXISTING);
            saveConfig();
        } catch (IOException e) {
            getLogger().warning("配置自动补全后保存失败: " + e.getMessage());
            return List.of();
        }
        getLogger().info("配置自动补全 " + added.size() + " 项：" + String.join("、", added) + "（原文件已备份为 " + backup + "）");
        return added;
    }

    private void openStore() {
        if (!settings.storageEnabled) return;
        try {
            store = new Store(getDataFolder().toPath().resolve("antiad.db"), getLogger());
            long now = System.currentTimeMillis();
            if (settings.keepDays > 0) store.purge(now - settings.keepDays * 86_400_000L);
            guard.seed(store.adHits(now - settings.windowSeconds * 1000L));
        } catch (Exception e) {
            getLogger().warning("无法打开记录库 antiad.db，改为只记在内存中: " + e.getMessage());
            store = null;
        }
    }

    public Settings settings() {
        return settings;
    }

    /** 在全局区域线程执行（Folia 与 Paper 通用）。 */
    public void runGlobal(Runnable task) {
        if (!isEnabled()) return;   // 关服过程中排队中的任务直接丢弃，避免"插件已禁用还注册任务"的报错
        Bukkit.getGlobalRegionScheduler().run(this, scheduled -> task.run());
    }

    /** 在异步线程执行（Folia 与 Paper 通用）：数据库读写一律走这里，别阻塞服务端线程。 */
    public void runAsync(Runnable task) {
        if (!isEnabled()) return;
        Bukkit.getAsyncScheduler().runNow(this, scheduled -> task.run());
    }

    /** 在玩家所属区域线程执行（Folia 与 Paper 通用）。 */
    public void runFor(Player player, Runnable task) {
        if (!isEnabled()) return;
        player.getScheduler().run(this, scheduled -> task.run(), null);
    }

    /** 异步落库：批量写行为画像 + 刷新各在线的违规计数；上一次没跑完就跳过这次，避免堆积。 */
    private void flushPeriodic() {
        if (!flushing.compareAndSet(false, true)) return;
        runAsync(() -> {
            try {
                behavior.flushAll();
                for (Player online : Bukkit.getOnlinePlayers()) behavior.refreshViolations(online.getUniqueId());
            } finally {
                flushing.set(false);
            }
        });
    }

    /** 没启用记录库时什么都不做。 */
    public void record(Player player, String ip, String kind, String match, String content, String action) {
        record(player.getUniqueId(), player.getName(), ip, kind, match, content, action);
    }

    /** 按 UUID + 名字记录（离线玩家也能记）。记录只用于事后查证，丢到异步线程写，绝不阻塞服务端线程。 */
    public void record(java.util.UUID uuid, String name, String ip, String kind, String match, String content, String action) {
        if (store == null) return;
        long now = System.currentTimeMillis();
        runAsync(() -> store.record(now, uuid, name, ip, kind, match, content, action));
    }

    /**
     * 封禁：按配置二选一 —— `plugin` 用自带封禁（写 banned-ips/players.json），
     * `command` 执行你自己的封禁指令（{player}/{reason}/{seconds}/{duration} 会被替换）。address 可为 null。
     */
    public void ban(java.util.UUID uuid, String name, InetAddress address, String reason, Instant expires) {
        long seconds = expires == null ? 0 : Math.max(1, java.time.Duration.between(Instant.now(), expires).getSeconds());
        if (settings.banByCommand) {
            consoleCommand(settings.banCommand, name, reason, seconds);
            return;
        }
        if (address != null) Bukkit.getBanList(BanListType.IP).addBan(address, reason, expires, "AntiAd");
        Player online = Bukkit.getPlayer(uuid);
        PlayerProfile profile = online != null ? online.getPlayerProfile() : Bukkit.createProfile(uuid, name);
        Bukkit.getBanList(BanListType.PROFILE).addBan(profile, reason, expires, "AntiAd");
    }

    /** 解封：`command` 模式执行自定义解封指令；`plugin` 模式解账号 + 该玩家最近记录的 IP。 */
    public void unban(java.util.UUID uuid, String name, String lastIp) {
        if (settings.banByCommand) {
            consoleCommand(settings.unbanCommand, name, "", 0);
            return;
        }
        Player online = Bukkit.getPlayer(uuid);
        Bukkit.getBanList(BanListType.PROFILE).pardon(online != null ? online.getPlayerProfile() : Bukkit.createProfile(uuid, name));
        InetAddress address = addressOf(lastIp);
        if (address != null) Bukkit.getBanList(BanListType.IP).pardon(address);
    }

    private static InetAddress addressOf(String ip) {
        if (ip == null) return null;
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            return null;
        }
    }

    /** 填好占位符后以控制台身份执行（模板不含斜杠）。 */
    private void consoleCommand(String template, String name, String reason, long seconds) {
        String command = Messages.replace(template,
                "{player}", name,
                "{reason}", Messages.commandSafe(reason),
                "{seconds}", String.valueOf(seconds),
                "{duration}", Messages.durationText(seconds)).strip();
        if (command.isEmpty()) return;
        getLogger().info("执行自定义指令：" + command);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    /** 近一段时间的违规命中次数（老玩家判定用）。 */
    public int violations(java.util.UUID player, long since) {
        return store == null ? 0 : store.violations(player, since);
    }

    /** 地址情报：给 /antiad ai 与 AI 审核用的线索（会阻塞，必须在异步线程调用）。 */
    public String probeIntel(String text) {
        return guard.probeIntel(text, settings);
    }

    /** 记一次 AI 调用的 token 用量，用于 /antiad cost；记录库未启用时忽略。 */
    public void recordUsage(Ai.Verdict verdict) {
        if (store == null || (verdict.promptTokens() <= 0 && verdict.completionTokens() <= 0)) return;
        store.addUsage(LocalDate.now().toString(), verdict.promptTokens(), verdict.completionTokens());
    }

    public Store store() {
        return store;
    }

    public Ai ai() {
        return ai;
    }

    public BehaviorTracker behavior() {
        return behavior;
    }

    public ExecutorService aiPool() {
        return aiPool;
    }

    public int strikes(java.util.UUID player) {
        return guard.strikes(player);
    }

    /** 通报给有权限的在线玩家，同时写入控制台日志。每个收件人在自己的区域线程上收到消息（Folia 安全）。 */
    public void notifyStaff(String legacy) {
        getLogger().info(Messages.plain(legacy));
        if (settings.notifyPermission.isEmpty()) return;
        Component message = Messages.text(legacy);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(settings.notifyPermission)) runFor(online, () -> online.sendMessage(message));
        }
    }
}
