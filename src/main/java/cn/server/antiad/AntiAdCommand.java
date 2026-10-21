package cn.server.antiad;

import io.papermc.paper.ban.BanListType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AntiAdCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("check", "ban", "unban", "behavior", "ai", "cost", "records", "test", "reload", "help");
    /** 子命令短写：左边的简写映射到右边的规范名（`/antiad c 玩家` = `/antiad check 玩家`）。 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("c", "check"), Map.entry("info", "check"),
            Map.entry("b", "ban"),
            Map.entry("ub", "unban"), Map.entry("pardon", "unban"),
            Map.entry("bh", "behavior"),
            Map.entry("a", "ai"),
            Map.entry("co", "cost"),
            Map.entry("r", "records"), Map.entry("rec", "records"), Map.entry("logs", "records"),
            Map.entry("t", "test"),
            Map.entry("rl", "reload"),
            Map.entry("h", "help"), Map.entry("?", "help"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AntiAdPlugin plugin;

    AntiAdCommand(AntiAdPlugin plugin) {
        this.plugin = plugin;
    }

    /** 把短写换成规范子命令名；不是短写就原样返回（小写）。 */
    static String canonical(String input) {
        String value = input.toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(value, value);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("antiad.admin")) {
            sender.sendMessage(Messages.text("&c你没有权限使用该命令。"));
            return true;
        }
        switch (args.length == 0 ? "help" : canonical(args[0])) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(Messages.text("&aAntiAd 配置已重载（记录库换文件、AI 并发数需要重启）。"));
            }
            case "test" -> test(sender, args);
            case "check" -> checkAsync(sender, args);
            case "ban" -> ban(sender, args);
            case "unban" -> unban(sender, args);
            case "behavior" -> query(sender, out -> behavior(out, args));
            case "ai" -> ai(sender, args);
            case "cost" -> query(sender, out -> cost(out));
            case "records" -> query(sender, out -> records(out, args));
            default -> {
                sender.sendMessage(Messages.text("&6AntiAd &7命令（&f/ad&7 或 &f/antiad&7，短写见最后一行）："));
                sender.sendMessage(Messages.text("&e/antiad check <玩家> &7- 查看该玩家是否在检查范围内"));
                sender.sendMessage(Messages.text("&e/antiad ban <玩家> [时长 30s/10m/2h/7d] [理由…] &7- 手动封禁 IP 与账号"));
                sender.sendMessage(Messages.text("&e/antiad unban <玩家> &7- 解封该玩家及其最近记录的 IP"));
                sender.sendMessage(Messages.text("&e/antiad behavior [数量] &7- 行为画像与分类"));
                sender.sendMessage(Messages.text("&e/antiad ai <文本> &7- 真的调一次 AI，看判定/耗时/报错（验证 key 是否可用）"));
                sender.sendMessage(Messages.text("&e/antiad cost &7- 每日 AI 调用次数与估算费用"));
                sender.sendMessage(Messages.text("&e/antiad records [玩家] &7- 查看最近 10 条命中记录"));
                sender.sendMessage(Messages.text("&e/antiad test <文本> &7- 只试跑规则检测，不处理，可验证误伤"));
                sender.sendMessage(Messages.text("&e/antiad reload &7- 重载配置"));
                sender.sendMessage(Messages.text("&7短写：&fc&7=check &fb&7=ban &fub&7=unban &fbh&7=behavior &fa&7=ai &fco&7=cost &fr&7=records &ft&7=test &frl&7=reload"));
            }
        }
        return true;
    }

    private void test(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.text("&c用法：/antiad test <文本>"));
            return;
        }
        Settings settings = plugin.settings();
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        List<String> hits = Detector.find(text, settings);
        String blocked = Detector.blocked(text, settings);
        String word = Detector.matchedWord(text, settings.bannedWords);
        boolean ai = settings.aiEnabled && !settings.aiKey.isBlank();
        sender.sendMessage(Messages.text("&7归一化：&f" + Detector.normalize(text)));
        if (blocked != null) {
            String family = Detector.domainFamily(blocked);
            String tail = family.equals(blocked) ? "" : "（整个域名 &f" + family + "&7）";
            sender.sendMessage(Messages.text("&c命中黑名单域名：&f" + blocked + "&7" + tail
                    + "：隐藏 + 通报 + 立即封禁 IP 与账号"));
        }
        if (!hits.isEmpty()) sender.sendMessage(Messages.text("&c广告命中：" + String.join("、", hits)
                + "&7（累计 &f" + settings.hits + "&7 次 / &f" + settings.windowSeconds + "&7 秒封禁 IP 与账号）"));
        if (word != null) sender.sendMessage(Messages.text(ai
                ? "&e命中敏感词：&f" + word + "&7（作为线索送 AI，由 AI 判断是不是广告）"
                : "&c命中敏感词：&f" + word + "&7（AI 未启用：直接只给本人可见）"));
        if (hits.isEmpty() && word == null && blocked == null) sender.sendMessage(Messages.text(aiGate(text, settings)));
        if (blocked == null) probeTest(sender, text, hits, settings);
    }

    /** `/antiad test` 的地址情报：异步探测命中的地址到底是不是真的 MC 服务器，结论回来再补一条消息。 */
    private void probeTest(CommandSender sender, String text, List<String> hits, Settings settings) {
        if (!settings.probeEnabled) {
            sender.sendMessage(Messages.text("&7地址情报：&8已在配置里关闭（probe.enabled）"));
            return;
        }
        if (hits.isEmpty()) return;
        String normalized = Detector.normalize(text);
        List<String> hosts = new ArrayList<>();
        for (String hit : hits) {
            String host = Probe.hostOf(hit);
            if (host != null && !hosts.contains(host)) hosts.add(host);
        }
        if (hosts.isEmpty()) return;
        sender.sendMessage(Messages.text("&7地址情报探测中…（&f" + String.join("、", hosts) + "&7）"));
        Probe probes = new Probe();
        plugin.runAsync(() -> {
            for (String host : hosts) {
                Probe.Result result = probes.lookup(host, Probe.portOf(normalized, host), settings);
                boolean hard = ChatGuard.hardHit(settings, result);
                String line = result.probed()
                        ? "&7地址情报（&f" + host + "&7）：&f" + result.text()
                        : "&7地址情报（&f" + host + "&7）：&8没有结论（域名解析不了、内网地址或探测失败）";
                String message = line + (hard ? " &7→ &c会计入封禁次数" : " &7→ &a放行（不是 MC 服务器，聊天照常发出；不计入封禁次数）");
                plugin.runGlobal(() -> sender.sendMessage(Messages.text(message)));
            }
        });
    }

    /** 告诉管理员这条消息按现在的配置会不会被送进 AI，方便验证预筛和额度。 */
    private static String aiGate(String text, Settings settings) {
        if (!Detector.hasLetters(text)) return "&a规则未命中；纯数字/符号，不会送 AI。";
        if (!settings.aiEnabled || settings.aiKey.isBlank()) return "&a规则未命中。&7（AI 未启用）";
        if (!Detector.suspicious(text, settings.aiTriggerWords))
            return "&a规则未命中，也不像广告（无关键词、无链接）&7：不会送 AI，省额度。";
        List<String> hints = Detector.hints(text, settings.bannedWords, settings.aiTriggerWords);
        return "&a规则未命中。&7符合疑似筛选，会送 AI 审核（线索：" + (hints.isEmpty() ? "无" : String.join("、", hints)) + "）。";
    }

    /** 真的调一次 AI：用来确认 key / 模型 / 网络是否可用。 */
    private void ai(CommandSender sender, String[] args) {
        Settings settings = plugin.settings();
        if (!settings.aiEnabled || settings.aiKey.isBlank()) {
            sender.sendMessage(Messages.text("&7AI 审核未启用：ai.enabled = " + settings.aiEnabled
                    + "，api-key " + (settings.aiKey.isBlank() ? "&c为空" : "&a已填") + "&7。"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.text("&c用法：/antiad ai <文本>"));
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (!Detector.hasLetters(text)) {
            sender.sendMessage(Messages.text("&a预检直接放行：没有文字内容（纯数字/符号/表情），实际运行时不会送给 AI。"));
            return;
        }
        sender.sendMessage(Messages.text("&7正在请求 &f" + settings.aiModel + " &7(" + settings.aiUrl + ")…"));
        long start = System.currentTimeMillis();
        plugin.aiPool().submit(() -> {
            Ai.Verdict verdict = plugin.ai().judge(plugin.settings(), 0, 0,
                    Detector.hints(text, settings.bannedWords, settings.aiTriggerWords), plugin.probeIntel(text), List.of(), text);
            plugin.recordUsage(verdict);
            long cost = System.currentTimeMillis() - start;
            boolean failed = verdict.reason().contains("失败") || verdict.reason().contains("接口返回") || verdict.reason().contains("无法解析");
            Runnable reply = () -> sender.sendMessage(Messages.text(
                    (failed ? "&c接口有问题：&f" + verdict.reason() : verdict.ad() ? "&c判定：是广告" : "&a判定：不是广告")
                            + "&7（理由：&f" + verdict.reason() + "&7；耗时 &f" + cost + "ms&7；"
                            + (verdict.ad() ? "会按命中累计" : "会放行给所有人") + "）"));
            if (sender instanceof Player player) plugin.runFor(player, reply);
            else plugin.runGlobal(reply);
        });
    }

    private static String kindName(Behavior.Kind kind) {
        return switch (kind) {
            case NORMAL -> "&a正常";
            case WATCH -> "&e观望";
            case SUSPICIOUS -> "&6重点关注";
            case ADVERTISER -> "&c广告号";
        };
    }

    /** 最近 24 小时有活动的玩家 + 行为分类。 */
    private void behavior(CommandSender sender, String[] args) {
        Store store = plugin.store();
        if (store == null) {
            sender.sendMessage(Messages.text("&c记录库未启用，行为画像不会保存。"));
            return;
        }
        int limit = 10;
        if (args.length >= 2) {
            try {
                limit = Math.min(50, Math.max(1, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
            }
        }
        Settings settings = plugin.settings();
        List<Store.BehaviorRow> rows = store.behaviors(limit);
        if (rows.isEmpty()) {
            sender.sendMessage(Messages.text("&7还没有行为记录。"));
            return;
        }
        sender.sendMessage(Messages.text("&6行为画像（按行为累计：挖方块/指令加分，违规扣分）："));
        for (Store.BehaviorRow row : rows) {
            Behavior.Profile profile = new Behavior.Profile(row.blocks(), row.minutes(), row.commands(), row.joins(), row.penalty(), row.banned());
            sender.sendMessage(Messages.text("&f" + row.name() + " " + kindName(Behavior.classify(settings, profile))
                    + " &7行为分 &f" + Behavior.score(settings, profile)
                    + "&7（方块 " + row.blocks() + " / 在线 " + row.minutes() + "m / 指令 " + row.commands() + " / 进服 " + row.joins()
                    + " / 扣分 " + row.penalty() + "）"));
        }
    }

    /** 手动封禁：`/antiad ban <玩家> [时长] [理由…]`，按配置用插件自带封禁或你自己的封禁指令。 */
    private void ban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.text("&c用法：/antiad ban <玩家> [时长 30s/10m/2h/7d，留空=永久] [理由…]"));
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            sender.sendMessage(Messages.text("&7找不到玩家 &f" + args[1] + "&7：只查在线玩家和本服缓存过的玩家。"));
            return;
        }
        Settings settings = plugin.settings();
        long duration = 0;
        int reasonFrom = 2;
        if (args.length >= 3) {
            long parsed = seconds(args[2]);
            if (parsed >= 0) {
                duration = parsed;
                reasonFrom = 3;
            }
        }
        String reason = args.length > reasonFrom
                ? String.join(" ", Arrays.copyOfRange(args, reasonFrom, args.length))
                : Messages.replace(settings.banReason, "{match}", "管理员手动封禁", "{contact}", settings.contact);

        UUID id = target.getUniqueId();
        Player online = Bukkit.getPlayer(id);
        InetAddress address = online == null ? null : address(online);
        String ip = address != null ? address.getHostAddress() : (plugin.store() == null ? null : plugin.store().lastIp(id));
        if (address == null && ip != null) address = parseAddress(ip);
        Instant expires = duration > 0 ? Instant.now().plusSeconds(duration) : null;

        plugin.ban(id, target.getName(), address, reason, expires);
        plugin.behavior().markBanned(id, target.getName());
        plugin.record(id, target.getName(), ip, "ban", "手动封禁", reason, "banned");
        if (online != null) online.kick(Messages.fill(settings.kickMessage, "{match}", "管理员手动封禁", "{contact}", settings.contact));
        plugin.notifyStaff("&c" + sender.getName() + " &7手动封禁了 &f" + target.getName() + "&7（" + (ip == null ? "未找到 IP" : ip)
                + "，" + (duration > 0 ? duration + " 秒" : "永久") + "）：" + reason);
        sender.sendMessage(Messages.text(settings.banByCommand
                ? "&a已对你自己的封禁指令下达封禁：&f" + target.getName() + "&7（时长：" + (duration > 0 ? duration + " 秒" : "永久") + "）"
                : "&a已封禁 &f" + target.getName() + "&7（账号" + (address == null ? "，未找到 IP" : " + IP")
                + "，时长：" + (duration > 0 ? duration + " 秒" : "永久") + "）"));
    }

    /** 解封：`/antiad unban <玩家>`，账号 + 该玩家最近记录里的 IP 一起解。 */
    private void unban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.text("&c用法：/antiad unban <玩家>"));
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            sender.sendMessage(Messages.text("&7找不到玩家 &f" + args[1] + "&7：只查在线玩家和本服缓存过的玩家。"));
            return;
        }
        UUID id = target.getUniqueId();
        String ip = plugin.store() == null ? null : plugin.store().lastIp(id);
        plugin.unban(id, target.getName(), ip);
        if (plugin.settings().banByCommand) {
            plugin.notifyStaff("&a" + sender.getName() + " &7对 &f" + target.getName() + " &7执行了解封指令");
            sender.sendMessage(Messages.text("&a已执行解封指令（punish.unban-command）：&f" + target.getName()));
            return;
        }
        plugin.notifyStaff("&a" + sender.getName() + " &7解封了 &f" + target.getName() + (ip == null ? "" : "&7（含 IP " + ip + "）"));
        sender.sendMessage(Messages.text("&a已解封 &f" + target.getName()
                + (ip == null ? "&a（记录里没有 IP，若仍进不来请用 /pardon-ip <地址>）" : "&a 及其最近 IP &f" + ip)));
    }

    /** 在线优先，其次本服缓存过的离线玩家（不发网络请求）。 */
    private static OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }

    /** 文本 IP → InetAddress，解析不了返回 null。 */
    private static InetAddress parseAddress(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            return null;
        }
    }

    private static InetAddress address(Player player) {
        return player.getAddress() == null ? null : player.getAddress().getAddress();
    }

    /** 解析时长：`30s` / `10m` / `2h` / `7d`，纯数字 = 秒，`0` = 永久；解析失败返回 -1。 */
    static long seconds(String text) {
        Matcher matcher = Pattern.compile("(?i)^(\\d+)([smhd]?)$").matcher(text.strip());
        if (!matcher.matches()) return -1;
        long value = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "m" -> value * 60L;
            case "h" -> value * 3600L;
            case "d" -> value * 86400L;
            default -> value;
        };
    }

    /**
     * 命令里要查库的部分统一走这里：在异步线程查完，再回到主线程把消息发出去，
     * 主线程一秒都不等 SQLite（服务器正在批量写入画像、或别的插件在备份锁库时尤其明显）。
     */
    private void query(CommandSender sender, java.util.function.Consumer<CommandSender> body) {
        plugin.runAsync(() -> {
            Collector collector = new Collector();
            try {
                body.accept(collector);
            } catch (Throwable error) {
                plugin.getLogger().warning("命令执行出错: " + error);
                collector.sendMessage(Messages.text("&c命令执行出错：" + error.getClass().getSimpleName()));
            }
            List<Component> lines = collector.lines;
            plugin.runGlobal(() -> {
                for (Component line : lines) sender.sendMessage(line);
            });
        });
    }

    /** 只负责攒消息的假 sender：命令体照常 sendMessage，真正的发送等查完回到主线程统一做。 */
    private static final class Collector implements CommandSender {
        private final List<Component> lines = new ArrayList<>();

        @Override public void sendMessage(String message) { lines.add(Messages.text(message)); }
        @Override public void sendMessage(String... messages) { for (String message : messages) sendMessage(message); }
        @Override public void sendMessage(UUID sender, String message) { sendMessage(message); }
        @Override public void sendMessage(UUID sender, String... messages) { sendMessage(messages); }
        @Override public void sendMessage(Component message) { lines.add(message); }
        @Override public String getName() { return "AntiAd"; }
        @Override public Component name() { return Component.text("AntiAd"); }
        @Override public Spigot spigot() { return null; }
        @Override public Server getServer() { return Bukkit.getServer(); }
        @Override public boolean isPermissionSet(String name) { return false; }
        @Override public boolean isPermissionSet(Permission perm) { return false; }
        @Override public boolean hasPermission(String name) { return true; }
        @Override public boolean hasPermission(Permission perm) { return true; }
        @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin plugin) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin plugin, int ticks) { return null; }
        @Override public void removeAttachment(PermissionAttachment attachment) { }
        @Override public void recalculatePermissions() { }
        @Override public Set<PermissionAttachmentInfo> getEffectivePermissions() { return Set.of(); }
        @Override public boolean isOp() { return false; }
        @Override public void setOp(boolean value) { }
    }

    /**
     * `/antiad check`：在线时长、IP、封禁状态这些玩家数据必须在主线程（玩家的线程）上先取好，
     * 取完再去异步线程查库 —— 不然就是在别人的线程上读玩家数据了。
     */
    private void checkAsync(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.text("&c用法：/antiad check <玩家>"));
            return;
        }
        String name = args[1];
        Player online = Bukkit.getPlayerExact(name);
        OfflinePlayer target = resolve(name);
        if (target == null) {
            sender.sendMessage(Messages.text("&7找不到玩家 &f" + name + "&7：只查在线玩家和本服缓存过的玩家。"));
            return;
        }
        long first = target.getFirstPlayed();
        long ticks = online == null ? 0 : online.getStatistic(Statistic.PLAY_ONE_MINUTE);
        InetSocketAddress socket = online == null ? null : online.getAddress();
        boolean banned = online != null && online.isBanned();
        query(sender, out -> check(out, target, online, first, ticks, socket, banned));
    }

    /** 查看某个玩家现在是否在检查范围内，以及已经累计到第几次（查库部分在异步线程）。 */
    private void check(CommandSender sender, OfflinePlayer target, Player online, long first, long ticks, InetSocketAddress socket, boolean banned) {
        Settings settings = plugin.settings();
        UUID id = target.getUniqueId();
        long minutes = first <= 0 ? -1 : (System.currentTimeMillis() - first) / 60_000L;
        boolean bypass = !settings.bypassPermission.isEmpty() && online != null && online.hasPermission(settings.bypassPermission);
        boolean whitelisted = settings.playerAllowed(id, target.getName());

        sender.sendMessage(Messages.text("&6" + target.getName() + " &7（" + (online != null ? "在线" : "离线") + "）"
                + (minutes < 0 ? "&7从未进过服" : "&7进服已 &f" + minutes + " &7分钟")));
        if (whitelisted) sender.sendMessage(Messages.text("&a玩家白名单：是 &7（whitelist.players 里写了这个名字/UUID，完全免检）"));
        else if (bypass && online != null) sender.sendMessage(Messages.text("&a拥有绕过权限 &f" + settings.bypassPermission + "&7：完全免检"));
        if (settings.behaviorEnabled && online != null) {
            int violations = plugin.behavior().violations(id);
            boolean veteran = ChatGuard.veteran(plugin, online);
            sender.sendMessage(Messages.text("&7老玩家判定：累计 &f" + String.format(Locale.ROOT, "%.1f", ticks / 72000.0) + " &7小时（需 > &f"
                    + settings.veteranPlayHours + "&7）、行为分 &f" + plugin.behavior().score(id) + " &7（需 ≥ &f" + settings.veteranMinScore
                    + "&7）、近 &f" + settings.veteranCleanHours + " &7小时违规 &f" + violations + " &7次（需 0）→ "
                    + (veteran ? "&a老玩家，免检" : "&c受管制")));
        }
        if (minutes < 0) {
            sender.sendMessage(Messages.text("&7从未进服：进服后才会开始计时与统计。"));
        } else {
            boolean watched = online != null && ChatGuard.watched(plugin, online);
            sender.sendMessage(Messages.text("&7当前状态：" + (bypass ? "&c有绕过权限，完全跳过检查"
                    : online == null ? "&7离线（在线时才判定是否受管制）"
                    : watched ? "&c受管制（按行为分类决定检查强度）" : "&a免检（老玩家）")
                    + "&7；已命中 &f" + plugin.strikes(id) + "/" + plugin.behavior().threshold(id) + " &7次"));
        }
        if (plugin.store() != null) {
            int[] summary = plugin.store().summary(id);
            sender.sendMessage(Messages.text("&7记录库：共 &f" + summary[0] + " &7条，其中封禁 &f" + summary[1] + " &7次。"));
        } else {
            sender.sendMessage(Messages.text("&7记录库未启用。"));
        }
        if (settings.behaviorEnabled) {
            Behavior.Profile profile = plugin.behavior().profile(id);
            sender.sendMessage(Messages.text("&7行为分 &f" + plugin.behavior().score(id) + " &7（" + kindName(plugin.behavior().kind(id))
                    + "&7，累计：方块 &f" + profile.blocks + "&7 / 在线 &f" + profile.minutes + "&7m / 指令 &f" + profile.commands
                    + "&7 / 进服 &f" + profile.joins + "&7；该类封禁阈值 &f" + plugin.behavior().threshold(id) + "&7）"));
            if (profile.banned) sender.sendMessage(Messages.text("&c这个号被本插件封过，永久按广告号处理。"));
        }
        if (online != null) {
            boolean ipBanned = socket != null && Bukkit.getBanList(BanListType.IP).isBanned(socket.getAddress());
            sender.sendMessage(Messages.text("&7IP：&f" + (socket == null ? "未知" : socket.getAddress().getHostAddress())
                    + "&7；账号封禁：" + (banned ? "&c是" : "&a否")
                    + "&7；IP 封禁：" + (ipBanned ? "&c是" : "&a否")));
        }
        sender.sendMessage(Messages.text("&7AI 审核：" + (settings.aiEnabled && !settings.aiKey.isBlank()
                ? "&a已开启（" + settings.aiModel + "）" : "&c未开启（没填 api-key）")));
    }

    /** 每日 AI 调用次数与估算费用（单价取自 ai.price）。 */
    private void cost(CommandSender sender) {
        Settings settings = plugin.settings();
        Store store = plugin.store();
        if (store == null) {
            sender.sendMessage(Messages.text("&c记录库未启用（storage.enabled=false 或打开失败），统计不到 AI 费用。"));
            return;
        }
        List<Store.Usage> last30 = store.usage(30);
        if (last30.isEmpty()) {
            sender.sendMessage(Messages.text("&7还没有 AI 调用：只有新玩家窗口内的消息才会调用 AI，纯数字和规则命中的不调用。"));
            return;
        }
        sender.sendMessage(Messages.text("&6AI 费用估算 &7（单价：输入 &f" + settings.priceInput + " &7/ 输出 &f" + settings.priceOutput + " &7元每百万 tokens，可在 ai.price 调整）"));
        String today = LocalDate.now().toString();
        double week = 0, month = 0;
        int weekCalls = 0, monthCalls = 0;
        for (int i = 0; i < last30.size(); i++) {
            Store.Usage row = last30.get(i);
            month += cost(row, settings);
            monthCalls += row.calls();
            if (i >= 7) continue;
            week += cost(row, settings);
            weekCalls += row.calls();
            sender.sendMessage(Messages.text("&7" + row.day() + (row.day().equals(today) ? " &a今天" : "")
                    + " &7调用 &f" + row.calls() + " &7次 | 输入 &f" + row.prompt() + "&7/输出 &f" + row.completion()
                    + " &7tokens | 约 &f¥" + money(cost(row, settings))));
        }
        double total = 0;
        int totalCalls = 0;
        for (Store.Usage row : store.usage(100_000)) {
            total += cost(row, settings);
            totalCalls += row.calls();
        }
        sender.sendMessage(Messages.text("&7近 7 天：&f" + weekCalls + "&7 次，约 &f¥" + money(week)
                + "&7；近 30 天：&f" + monthCalls + "&7 次，约 &f¥" + money(month)));
        sender.sendMessage(Messages.text("&7建库以来：&f" + totalCalls + "&7 次，约 &f¥" + money(total)
                + "&7（统计到 &f" + settings.keepDays + "&7 天前，keep-days=0 时永久保留）"));
    }

    private static double cost(Store.Usage row, Settings settings) {
        return row.prompt() / 1_000_000d * settings.priceInput + row.completion() / 1_000_000d * settings.priceOutput;
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private void records(CommandSender sender, String[] args) {
        Store store = plugin.store();
        if (store == null) {
            sender.sendMessage(Messages.text("&c记录库未启用（storage.enabled=false 或打开失败），改用内存计数。"));
            return;
        }
        String player = args.length >= 2 ? args[1] : null;
        List<Store.Hit> hits = store.recent(player, 10);
        if (hits.isEmpty()) {
            sender.sendMessage(Messages.text("&7没有记录。"));
            return;
        }
        sender.sendMessage(Messages.text("&6最近 " + hits.size() + " 条命中记录" + (player == null ? "" : "（" + player + "）") + "："));
        for (Store.Hit hit : hits) {
            String kind = switch (hit.kind()) {
                case "ad" -> "&e广告";
                case "ai" -> "&eAI";
                case "word" -> "&e敏感词";
                case "combine" -> "&e拼接";
                case "structure" -> "&e结构";
                case "sign" -> "&e告示牌";
                case "anvil" -> "&e铁砧";
                case "book" -> "&e书";
                case "blocked" -> "&c黑名单";
                default -> "&c封禁";
            };
            String action = switch (hit.action() == null ? "" : hit.action()) {
                case "banned" -> "&c已封禁";
                case "released" -> "&a已放行&8（不是 MC 服务器）";
                case "soft" -> "&7只隐藏&8（没探测到服务器）";
                default -> "&7已隐藏";
            };
            sender.sendMessage(Messages.text("&7" + TIME.format(Instant.ofEpochMilli(hit.hit())) + " " + kind + " &f" + hit.player()
                    + " &7" + (hit.ip() == null ? "" : hit.ip()) + " " + action
                    + (hit.match() == null ? "" : " &7命中 &f" + hit.match())
                    + " &7：&f" + shorten(hit.content())));
        }
    }

    private static String shorten(String text) {
        if (text == null) return "";
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && List.of("check", "records", "ban", "unban").contains(canonical(args[0]))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }
}
