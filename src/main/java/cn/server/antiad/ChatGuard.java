package cn.server.antiad;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * 新玩家的入口管制：聊天（含 /me）、告示牌、铁砧改名、书与笔。
 * 命中不删消息：取消原版事件（顺带让 TrChat 这类格式化插件跳过），只把内容补回给发送者本人。
 * 判定顺序：地址规则 → 敏感词（交 AI）→ 跨消息拼接 → 结构异常（软）→ AI 语义审核。
 */
public final class ChatGuard implements Listener {
    /** AI 判定期间扣下的消息，结论出来后决定放行还是只给发送者看。 */
    private record Hold(Player player, ChatRenderer renderer, Set<Audience> viewers, Component message) {}

    private final AntiAdPlugin plugin;
    private final Strikes strikes = new Strikes();
    /** 地址情报探测（SRV + 协议探测 + 网页标题），结论只当线索。 */
    private final Probe probes = new Probe();
    private final Map<UUID, Deque<String>> contexts = new HashMap<>();
    private final Map<UUID, String> refire = new HashMap<>();

    ChatGuard(AntiAdPlugin plugin) {
        this.plugin = plugin;
    }

    /** 重启后从记录库恢复窗口内的累计次数。 */
    void seed(Map<UUID, List<Long>> records) {
        strikes.seed(records);
    }

    /** 当前窗口内的累计命中次数，供 /antiad check 查看。 */
    int strikes(UUID player) {
        return strikes.count(player, System.currentTimeMillis(), plugin.settings().windowSeconds);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        Settings settings = plugin.settings();
        if (!settings.checkChat) return;
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        // AI 通过后重放的那一条：交给聊天插件按它自己的格式广播，这里不再处理，避免死循环。
        if (isRefire(player, text)) return;

        String blocked = blockedMatch(player, text);
        String match = blocked != null ? blocked : adMatch(player, text);
        String word = match == null ? wordMatch(player, text) : null;
        boolean ai = aiWanted(player);
        if (match != null || (word != null && !ai)) {
            shadow(event, player);
            InetAddress address = address(player);
            String kind = blocked != null ? "blocked" : match != null ? "ad" : "word";
            plugin.runFor(player, () -> handle(player, text, match, word, address, kind));
            return;
        }
        // 老玩家（免检）到此结束：不检查、不扣分、也不受行为分类影响。
        if (!watched(plugin, player)) return;
        List<String> history = remember(player, text, ai);

        // 跨消息拼接：把最近几条拼起来再查一次地址，对付把域名拆成多条发。端口可能在前一条里，所以把拼条后的整段当文本。
        String combined = combineHit(history, text, settings);
        if (combined != null) {
            shadow(event, player);
            InetAddress address = address(player);
            plugin.runFor(player, () -> handle(player, combined, combined, null, address, "combine"));
            return;
        }

        // 结构异常（超长/数字过多/生僻字）：软处理 —— 只隐藏、通报、扣行为分，不计入封禁次数。
        String issue = Detector.structureIssue(text, settings);
        if (issue != null) {
            shadow(event, player);
            String ip = addressText(player);
            String reason = issue;
            plugin.runFor(player, () -> {
                plugin.record(player, ip, "structure", reason, text, "hidden");
                plugin.behavior().penalty(player, false);
                plugin.notifyStaff("&e" + player.getName() + " &7消息不合规（&f" + reason + "&7），已仅本人可见");
            });
            return;
        }

        if (!ai) return;
        Behavior.Kind behavior = plugin.behavior().kind(player);
        if (behavior == Behavior.Kind.NORMAL) return;
        // 纯数字、表情、标点不可能是广告；其余过预筛；可疑/广告号不看预筛，全部送 AI。
        boolean suspicious = behavior == Behavior.Kind.SUSPICIOUS || behavior == Behavior.Kind.ADVERTISER;
        if (!suspicious && word == null && (!Detector.hasLetters(text) || !Detector.suspicious(text, settings.aiTriggerWords))) return;

        List<String> hints = Detector.hints(text, settings.bannedWords, settings.aiTriggerWords);
        Hold hold = new Hold(player, event.renderer(), new LinkedHashSet<>(event.viewers()), event.message());
        event.setCancelled(true);
        try {
            plugin.aiPool().submit(() -> {
                aiJudge(player, text, hints, history, hold);
            });
        } catch (RejectedExecutionException e) {
            // 队列满了（刷屏）：直接放行这条，别扣着不给玩家看，也不花这次 token。
            event.setCancelled(false);
            plugin.getLogger().info("AI 待审队列已满（" + AntiAdPlugin.AI_QUEUE_LIMIT + " 条），直接放行 " + player.getName() + " 的消息");
        }
    }

    /** AI 审核的实际工作：先探地址情报，再交给模型判断，最后回到玩家线程处置。 */
    private void aiJudge(Player player, String text, List<String> hints, List<String> history, Hold hold) {
        Settings current = plugin.settings();
        long start = System.currentTimeMillis();
        // 送 AI 之前先把消息里的地址探一下：结论是"对方真的是台 MC 服"还是"只是个网站"，直接决定判定准确率。
        String intel = probeIntel(text, current);
        Ai.Verdict verdict = plugin.ai().judge(current, minutes(player),
                strikes.count(player.getUniqueId(), System.currentTimeMillis(), current.windowSeconds), hints, intel, history, text);
        long cost = System.currentTimeMillis() - start;
        plugin.recordUsage(verdict);
        // AI 输出也写到后台，管理员能核对每一条判定（包括"不是广告"）。
        plugin.getLogger().info("AI 审核 " + player.getName() + "：" + (verdict.ad() ? "是广告" : "不是广告")
                + "（理由：" + verdict.reason() + "；耗时 " + cost + "ms；tokens " + verdict.promptTokens() + "+" + verdict.completionTokens()
                + "；线索：" + (hints.isEmpty() ? "无" : String.join("、", hints)) + "；地址情报：" + intel + "）消息：" + text);
        plugin.runFor(player, () -> deliver(hold, text, verdict));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Settings settings = plugin.settings();
        String body = event.getMessage().substring(1).strip();
        String root = body.split(" +", 2)[0].toLowerCase(Locale.ROOT);
        if (!settings.checkedCommands.contains(root)) return;
        Player player = event.getPlayer();
        String blocked = blockedMatch(player, body);
        String match = blocked != null ? blocked : adMatch(player, body);
        if (match == null) return;
        // /me 这类广播命令没法只让发送者看见，直接拦下。
        // 先取消再取 IP：取不到 IP（正在掉线）时也不能让广告漏出去。
        event.setCancelled(true);
        InetAddress address = address(player);
        if (address == null) return;
        handle(player, body, match, null, address, blocked != null ? "blocked" : "ad");
    }

    /** 告示牌：新玩家把地址写在牌子上，同样拦下。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void sign(SignChangeEvent event) {
        if (!plugin.settings().checkSigns) return;
        checkEntry(event.getPlayer(), String.join(" ", event.getLines()), "sign", () -> event.setCancelled(true));
    }

    /** 铁砧改名：把地址写进物品名/lore 也算入口。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void anvil(PrepareAnvilEvent event) {
        if (!plugin.settings().checkAnvil) return;
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        ItemStack result = event.getResult();
        if (result == null || !result.hasItemMeta()) return;
        ItemMeta meta = result.getItemMeta();
        StringBuilder text = new StringBuilder();
        if (meta.hasDisplayName()) text.append(PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
        List<Component> lore = meta.lore();
        if (lore != null) {
            for (Component line : lore) text.append(' ').append(PlainTextComponentSerializer.plainText().serialize(line));
        }
        checkEntry(player, text.toString(), "anvil", () -> event.setResult(null));
    }

    /** 书与笔：书页内容也算入口。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void book(PlayerEditBookEvent event) {
        if (!plugin.settings().checkBooks) return;
        StringBuilder text = new StringBuilder();
        for (Component page : event.getNewBookMeta().pages()) {
            text.append(' ').append(PlainTextComponentSerializer.plainText().serialize(page));
        }
        checkEntry(event.getPlayer(), text.toString(), "book", () -> event.setCancelled(true));
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** 告示牌/铁砧/书这类入口：命中地址就拦下并按命中累计。 */
    private void checkEntry(Player player, String text, String kind, Runnable block) {
        Settings settings = plugin.settings();
        if (text.isBlank() || !watched(plugin, player)) return;
        String blocked = blockedMatch(player, text);
        List<String> hits = blocked != null ? List.of() : Detector.find(text, settings);
        if (blocked == null && hits.isEmpty()) return;
        block.run();
        handle(player, text, blocked != null ? blocked : hits.get(0), null, address(player), blocked != null ? "blocked" : kind);
    }

    /** 主线程执行：AI 说不是广告就照原样放行，是广告就只回给发送者并按命中处理。 */
    private void deliver(Hold hold, String text, Ai.Verdict verdict) {
        Player player = hold.player();
        if (!player.isOnline()) return;
        if (!verdict.ad()) {
            if (plugin.settings().aiRefire) {
                // 让聊天插件（TrChat 这类）重新广播这条，保留它的前缀/格式。
                // 用重新合成事件的方式，不用 player.chat()：后者在本核心上不触发 AsyncChatEvent。
                plugin.runAsync(() -> refire(player, hold, text));
                return;
            }
            for (Audience viewer : hold.viewers()) {
                viewer.sendMessage(hold.renderer().render(player, player.displayName(), hold.message(), viewer));
            }
            return;
        }
        player.sendMessage(hold.renderer().render(player, player.displayName(), hold.message(), player));
        handle(player, text, "AI：" + verdict.reason(), null, address(player), "ai");
    }

    /** 重新合成一次聊天事件（异步事件只能在异步线程触发），交给聊天插件按它的格式广播。 */
    private void refire(Player player, Hold hold, String text) {
        markRefire(player, text);
        try {
            Bukkit.getPluginManager().callEvent(new AsyncChatEvent(true, player, new LinkedHashSet<>(hold.viewers()),
                    hold.renderer(), hold.message(), hold.message(), null));
        } finally {
            // 事件没人消费（被更早的监听器取消、或玩家中途掉线）就把标记清掉：
            // 否则这个玩家下次发一模一样的内容会被当成"重放"直接跳过检查。
            clearRefire(player, text);
        }
    }

    /** 取消事件（同时拦住 TrChat 这类 HIGHEST 优先级的格式化插件），再把内容只补发给发送者。 */
    private void shadow(AsyncChatEvent event, Player player) {
        Hold hold = new Hold(player, event.renderer(), Set.of(), event.message());
        event.setCancelled(true);
        plugin.runFor(player, () -> player.sendMessage(hold.renderer().render(player, player.displayName(), hold.message(), player)));
    }

    /** 广告命中：返回命中的 IP/域名，没有返回 null。 */
    private String adMatch(Player player, String text) {
        if (!watched(plugin, player)) return null;
        List<String> hits = Detector.find(text, plugin.settings());
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 整个域名黑名单命中：管理员确认过的域名，命中即按广告处理（不等累计、不探测）。 */
    private String blockedMatch(Player player, String text) {
        if (!watched(plugin, player)) return null;
        return Detector.blocked(text, plugin.settings());
    }

    /** 敏感词命中：返回命中的词（AI 开启时只是线索，由 AI 决定）。 */
    private String wordMatch(Player player, String text) {
        Settings settings = plugin.settings();
        if (settings.bannedWords.isEmpty() || !watched(plugin, player)) return null;
        return Detector.matchedWord(text, settings.bannedWords);
    }

    /**
     * 把最近几条拼起来再查一次地址：任意一条单独看都不像广告，拼起来才露馅。
     * 只有"加上本条才拼得出来"的地址才算命中 —— 否则历史里的碎片会让后面每条消息都连锁误判。
     */
    static String combineHit(List<String> history, String text, Settings settings) {
        if (settings.combineMessages <= 1 || history.isEmpty()) return null;
        int keep = Math.min(history.size(), settings.combineMessages - 1);
        StringBuilder joined = new StringBuilder();
        for (String part : history.subList(history.size() - keep, history.size())) joined.append(part);
        List<String> before = Detector.find(joined.toString(), settings);
        List<String> now = Detector.find(joined.toString() + text, settings);
        now.removeAll(before);
        return now.isEmpty() ? null : now.get(0);
    }

    /** 是否在管制范围：白名单玩家与有绕过权限的免检，老玩家免检，其余都查（含刚进服的强制管制期）。 */
    static boolean watched(AntiAdPlugin plugin, Player player) {
        Settings settings = plugin.settings();
        // 玩家白名单（config 里写死的人）：完全免检，等价于给了 bypass 权限 —— 不用担心忘了给权限。
        if (settings.playerAllowed(player.getUniqueId(), player.getName())) return false;
        if (!settings.bypassPermission.isEmpty() && player.hasPermission(settings.bypassPermission)) return false;
        long now = System.currentTimeMillis();
        // 前 new-player-minutes 分钟一律强制检查。
        if (settings.newPlayerMinutes > 0 && newPlayer(player.getFirstPlayed(), now, settings.newPlayerMinutes)) return true;
        // 行为系统关掉时没有行为分可判，就按老规矩（只看窗口）。
        if (!settings.behaviorEnabled) return false;
        return !veteran(plugin, player);
    }

    /** 老玩家 = 累计游玩时长 > play-hours + 行为分 >= min-score + 近 clean-hours 小时零违规。只用缓存，聊天线程不碰 NMS。 */
    static boolean veteran(AntiAdPlugin plugin, Player player) {
        Settings settings = plugin.settings();
        BehaviorTracker tracker = plugin.behavior();
        return Behavior.veteran(settings, tracker.playTicks(player.getUniqueId()),
                tracker.score(player.getUniqueId()), tracker.violations(player.getUniqueId()));
    }

    private boolean aiWanted(Player player) {
        Settings settings = plugin.settings();
        return settings.aiEnabled && !settings.aiKey.isBlank() && watched(plugin, player);
    }

    static boolean newPlayer(long firstPlayed, long now, int minutes) {
        return minutes <= 0 || firstPlayed <= 0 || now - firstPlayed <= minutes * 60_000L;
    }

    /** 记下这条发言，返回它之前的最近几条（拼接检测和 AI 上下文都用它）。两者都不用时直接短路。 */
    private synchronized List<String> remember(Player player, String text, boolean ai) {
        Settings settings = plugin.settings();
        if (!ai && settings.combineMessages <= 1) return List.of();
        Deque<String> history = contexts.computeIfAbsent(player.getUniqueId(), key -> new ArrayDeque<>());
        List<String> snapshot = new ArrayList<>(history);
        history.addLast(text);
        int keep = Math.max(1, Math.max(settings.aiContext, settings.combineMessages));
        while (history.size() > keep) history.removeFirst();
        return snapshot;
    }

    private synchronized void forget(UUID player) {
        contexts.remove(player);
        refire.remove(player);
    }

    /** 是不是 AI 通过后我们自己触发的重放（那条要交给聊天插件正常广播）。 */
    private synchronized boolean isRefire(Player player, String text) {
        String pending = refire.remove(player.getUniqueId());
        return pending != null && pending.equals(text);
    }

    private synchronized void markRefire(Player player, String text) {
        refire.put(player.getUniqueId(), text);
    }

    /** 清掉这条重放标记（只在内容还一致时清，避免误删紧接着的新标记）。 */
    private synchronized void clearRefire(Player player, String text) {
        String pending = refire.get(player.getUniqueId());
        if (pending != null && pending.equals(text)) refire.remove(player.getUniqueId());
    }

    /** 主线程执行：写记录、通报管理员。规则命中的地址要等一次探测确认 —— 确认是活的 MC 服务器才计入封禁次数（防止随手打一个不存在的网址被累计封号）。 */
    private void handle(Player player, String text, String match, String word, InetAddress address, String kind) {
        Settings settings = plugin.settings();
        String ip = address == null ? null : address.getHostAddress();
        if (word != null) {
            plugin.behavior().penalty(player, false);
            plugin.behavior().countViolation(player);
            plugin.record(player, ip, "word", word, text, "hidden");
            plugin.notifyStaff("&e" + player.getName() + " &7的消息含违禁词（&f" + word + "&7），已仅本人可见：&f" + text);
            return;
        }
        // 整个域名黑名单（管理员确认过的广告域名）：不探测、不等累计，直接按广告处理。
        if ("blocked".equals(kind)) {
            finish(player, address, text, match, ip, kind, Probe.Result.NONE, true);
            return;
        }
        // 扣分在 finish 里按结论给：确认是服务器 = 广告命中扣分，没确认 = 只按轻度扣分。
        // AI 判定为广告的走语义结论（AI 已经看过探测结论），不再等探测；规则命中的地址才需要确认。
        if ("ai".equals(kind) || !settings.probeEnabled || !settings.probeConfirmRequired) {
            finish(player, address, text, match, ip, kind, Probe.Result.NONE, true);
            return;
        }
        String host = Probe.hostOf(match);
        if (host == null) {
            finish(player, address, text, match, ip, kind, Probe.Result.NONE, true);
            return;
        }
        int port = Probe.portOf(Detector.normalize(text), host);
        plugin.runAsync(() -> {
            Probe.Result result = probes.lookup(host, port, settings);
            boolean hard = hardHit(settings, result);
            finishSafely(player, address, text, match, ip, kind, result, hard);
        });
    }

    /**
     * 探测回来后才决定处置，这几秒里玩家可能已经掉线：掉线时不能再往实体线程上排任务
     * （会抛异常，这次命中也就白记了 —— 等于给了"发完立刻退"绕过计数的路子），改到全局线程做。
     */
    private void finishSafely(Player player, InetAddress address, String text, String match, String ip, String kind, Probe.Result result, boolean hard) {
        if (player.isOnline()) plugin.runFor(player, () -> finish(player, address, text, match, ip, kind, result, hard));
        else plugin.runGlobal(() -> finish(player, address, text, match, ip, kind, result, hard));
    }

    /**
     * 这次地址命中算不算"确认过的广告"：没开探测、或配置里不要求确认时保持老行为；
     * 否则必须确认对方是 MC 服务器 —— SRV 记录、状态回应、或者 25565 端口开着只是屏蔽了状态请求（enable-status=false / 只挡探测）。
     */
    static boolean hardHit(Settings settings, Probe.Result result) {
        if (!settings.probeEnabled || !settings.probeConfirmRequired) return true;
        return result.minecraft() || result.portOpen();
    }

    /** 探测回来了：确认过的计入封禁次数并可能封禁；没确认的只隐藏 + 通报 + 扣分（仍然是"只给本人看"）。 */
    private void finish(Player player, InetAddress address, String text, String match, String ip, String kind, Probe.Result result, boolean hard) {
        Settings settings = plugin.settings();
        if (!hard) {
            // action=soft：记录库里把这类命中从"封禁次数"和"老玩家判定"里排除掉，但管理员仍然查得到。
            plugin.behavior().penalty(player, false);
            plugin.record(player, ip, kind, match, text, "soft");
            plugin.notifyStaff("&e" + player.getName() + " &7消息里有地址（&f" + match + "&7），但探测不到活的 MC 服务器"
                    + (result.probed() ? "（&f" + result.text() + "&7）" : "（域名解析不了或探测超时）")
                    + "&7：只隐藏、不计入封禁次数。");
            return;
        }
        boolean blocked = "blocked".equals(kind);
        plugin.behavior().penalty(player, true);
        plugin.behavior().countViolation(player);
        int threshold = plugin.behavior().threshold(player);
        int count = strikes.hit(player.getUniqueId(), System.currentTimeMillis(), settings.windowSeconds);
        plugin.record(player, ip, kind, match, text, "hidden");
        if (result.probed()) plugin.notifyStaff("&7地址情报（&f" + match + "&7）：&f" + result.text());
        if (blocked) plugin.notifyStaff("&e" + player.getName() + " &7的消息命中黑名单域名（&f" + match + "&7）：直接按广告处理");
        else plugin.notifyStaff("&e" + player.getName() + " &7对话命中广告（&f" + match + "&7，" + count + "/" + threshold + "），已仅本人可见");
        if (address == null || (!blocked && count < threshold)) return;
        strikes.clear(player.getUniqueId());
        plugin.behavior().markBanned(player);
        plugin.record(player, ip, "ban", match, text, "banned");
        Instant expires = settings.banSeconds > 0 ? Instant.now().plusSeconds(settings.banSeconds) : null;
        String reason = Messages.replace(settings.banReason, "{match}", match, "{contact}", settings.contact);
        // IP 与账号一起封：换 IP 或换名字都进不来，解封需要管理员 pardon。
        plugin.ban(player.getUniqueId(), player.getName(), address, reason, expires);
        if (player.isOnline()) player.kick(Messages.fill(settings.kickMessage, "{match}", match, "{contact}", settings.contact));
        plugin.notifyStaff("&c" + player.getName() + " &7（&f" + ip + "&7）因宣传服务器广告被封禁 IP 与账号：" + match);
    }

    /** 送给 AI 的地址情报：消息里的地址逐个探测（最多两个），拼成一行；没地址/关掉了会说清楚。 */
    String probeIntel(String text, Settings settings) {
        if (!settings.probeEnabled) return "已关闭地址探测";
        // 用 addresses 而不是 find：规则没命中、但要送 AI 的消息里，域名往往不是我们认的后缀，
        // 那条消息恰恰最需要"对方到底是不是服务器"这个事实。
        List<String> addresses = Detector.addresses(text, settings);
        if (addresses.isEmpty()) return "消息里没有地址";
        String normalized = Detector.normalize(text);
        StringBuilder out = new StringBuilder();
        int probed = 0;
        for (String address : addresses) {
            if (probed >= 2) break;   // 一条消息最多探两个，别让玩家等太久
            String host = Probe.hostOf(address);
            if (host == null) continue;
            Probe.Result result = probes.lookup(host, Probe.portOf(normalized, host), settings);
            if (!result.probed()) continue;
            probed++;
            if (out.length() > 0) out.append("；");
            out.append(host).append("：").append(result.text());
        }
        return out.length() == 0 ? "探测无结论" : out.toString();
    }

    private static InetAddress address(Player player) {
        InetSocketAddress socket = player.getAddress();
        return socket == null ? null : socket.getAddress();
    }

    private static String addressText(Player player) {
        InetAddress address = address(player);
        return address == null ? null : address.getHostAddress();
    }

    private static long minutes(Player player) {
        long first = player.getFirstPlayed();
        return first <= 0 ? 0 : (System.currentTimeMillis() - first) / 60_000L;
    }
}
