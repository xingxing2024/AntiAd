package cn.server.antiad;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Settings {
    public boolean checkChat, storageEnabled, checkNumericIp, aiEnabled, aiFailOpen, aiRefire, behaviorEnabled;
    public boolean banByCommand;
    public boolean structureEnabled, checkSigns, checkAnvil, checkBooks;
    public boolean probeEnabled, probeFetchTitle;
    public int hits, windowSeconds, newPlayerMinutes, keepDays, aiTimeout, aiContext, aiConcurrent;
    public int probeTimeoutMs, probeCacheSeconds;
    public int normalScore, suspiciousScore, advertiserScore, defaultScore, blocksPerPoint, commandsPerPoint, maxActivityScore, adHitPenalty, wordHitPenalty;
    public int veteranPlayHours, veteranMinScore, veteranCleanHours;
    public int maxLength, minDigits, maxRareChars, combineMessages;
    public double maxDigitRatio;
    public double priceInput, priceOutput;
    public long banSeconds;
    public String banReason, kickMessage, notifyPermission, bypassPermission, contact, banCommand, unbanCommand;
    public String aiUrl, aiKey, aiModel, aiPrompt;
    public Set<String> checkedCommands, allowedDomains, allowedIps, tlds, bannedWords, selfDomains, aiTriggerWords;

    /** 打包的 tlds.txt / trusted-domains.txt 读不到时的兜底。 */
    private static final Set<String> FALLBACK_TLDS = Set.of("com", "cn", "net", "org", "ren", "top", "xyz", "icu", "vip", "shop", "me", "cc", "gg");
    private static final Set<String> FALLBACK_TRUSTED = Set.of("bilibili.com", "youtube.com", "github.com", "minecraft.net", "mcmod.cn", "modrinth.com", "curseforge.com");

    /** AI 预筛关键词：命中任一才送 AI，用来省 token。数字、表情、链接样式另有判断。 */
    private static final Set<String> DEFAULT_TRIGGER_WORDS = Set.of(
            "服务器", "开服", "腐竹", "服主", "新服", "来玩", "来我", "进服", "进游戏", "地址", "端口", "官网", "论坛", "网站",
            "链接", "加群", "群号", "扣群", "加我", "私聊", "联系", "找我", "介绍", "招人", "卖号", "代练", "交易", "赞助",
            "白送", "赠送", "送东西", "qq", "企鹅", "微信", "vx", "discord");

    /** AI 审核的默认提示词，与 config.yml 里的默认值一致。 */
    private static final String DEFAULT_AI_PROMPT = """
            你是 Minecraft 服务器的聊天审核员。判断这条消息是不是在给别的服务器/群做广告（拉人去别处、发联系方式、卖号代练等引流）。
            本服地址是 {self}；提本服或本服群、平台/wiki/mod 站/代码仓库/视频/存档的链接都不算广告。
            普通聊天、提问、报错、玩笑、数字、表情、单字都不算；没有明确引流意图就填 false，拿不准一律 false。
            进服 {minutes} 分钟，本窗口已命中 {hits} 次，命中的敏感词：{words}（只是线索，可能只是正常用词，结合上下文判断，不确定填 false）。
            他最近的发言：
            {context}
            本次消息：
            {message}
            只回一行 JSON，不要解释、不要代码块：{"ad":false,"reason":"一句话理由"}""";

    /** jar 里打包的服务器常用后缀与内置受信任站点，类加载时读一次。 */
    private static final Set<String> BUILT_IN_TLDS = loadResource("/tlds.txt", FALLBACK_TLDS);
    private static final Set<String> TRUSTED_DOMAINS = loadResource("/trusted-domains.txt", FALLBACK_TRUSTED);

    public static Settings load(FileConfiguration c) {
        Settings s = new Settings();
        s.checkChat = c.getBoolean("settings.check-chat", true);
        s.newPlayerMinutes = Math.max(0, c.getInt("settings.new-player-minutes", 30));
        s.checkNumericIp = c.getBoolean("settings.check-numeric-ip", true);
        s.checkedCommands = lowerSet(c.isSet("settings.checked-commands") ? c.getStringList("settings.checked-commands") : List.of("me"));
        s.notifyPermission = c.getString("settings.notify-permission", "antiad.notify");
        s.bypassPermission = c.getString("settings.bypass-permission", "antiad.bypass");
        s.hits = Math.max(1, c.getInt("trigger.hits", 3));
        s.windowSeconds = Math.max(1, c.getInt("trigger.window-seconds", 60));
        s.banSeconds = Math.max(0, c.getLong("punish.ban-seconds", 0));
        // 封禁方式：plugin（自带，写封禁名单）或 command（执行你自己的封禁指令）。
        s.banByCommand = "command".equalsIgnoreCase(c.getString("punish.mode", "plugin").strip());
        s.banCommand = c.getString("punish.ban-command", "ban {player} {reason}");
        s.unbanCommand = c.getString("punish.unban-command", "unban {player}");
        s.contact = c.getString("punish.contact", "服务器交流群");
        s.banReason = c.getString("punish.ban-reason", "宣传其他服务器（{match}），解封请联系{contact}");
        s.kickMessage = c.getString("punish.kick-message", "&c检测到宣传其他服务器，你的 IP 与账号已被封禁。\n&7解封请联系 {contact}");
        s.storageEnabled = c.getBoolean("storage.enabled", true);
        s.keepDays = Math.max(0, c.getInt("storage.keep-days", 30));
        s.aiEnabled = c.getBoolean("ai.enabled", true);
        s.aiUrl = c.getString("ai.url", "https://open.bigmodel.cn/api/paas/v4/chat/completions");
        s.aiKey = c.getString("ai.api-key", "");
        s.aiModel = c.getString("ai.model", "glm-4-flash");
        s.aiTimeout = Math.max(1, c.getInt("ai.timeout-seconds", 2));
        s.aiConcurrent = Math.min(16, Math.max(1, c.getInt("ai.max-concurrent", 3)));
        s.priceInput = Math.max(0, c.getDouble("ai.price.input", 2.0));
        s.priceOutput = Math.max(0, c.getDouble("ai.price.output", 8.0));
        s.aiContext = Math.max(0, c.getInt("ai.context-messages", 5));
        s.aiFailOpen = c.getBoolean("ai.fail-open", true);
        s.aiRefire = c.getBoolean("ai.refire-approved", false);
        s.behaviorEnabled = c.getBoolean("behavior.enabled", true);
        s.defaultScore = c.getInt("behavior.default-score", 20);
        s.normalScore = c.getInt("behavior.normal-score", 30);
        s.suspiciousScore = c.getInt("behavior.suspicious-score", 19);
        s.advertiserScore = c.getInt("behavior.advertiser-score", 5);
        s.blocksPerPoint = Math.max(0, c.getInt("behavior.blocks-per-point", 50));
        s.commandsPerPoint = Math.max(0, c.getInt("behavior.commands-per-point", 10));
        s.maxActivityScore = Math.max(0, c.getInt("behavior.max-activity-score", 20));
        s.adHitPenalty = Math.max(0, c.getInt("behavior.ad-hit-penalty", 3));
        s.wordHitPenalty = Math.max(0, c.getInt("behavior.word-hit-penalty", 1));
        s.veteranPlayHours = Math.max(0, c.getInt("veteran.play-hours", 24));
        s.veteranMinScore = Math.max(0, c.getInt("veteran.min-score", 25));
        s.veteranCleanHours = Math.max(0, c.getInt("veteran.clean-hours", 24));
        s.structureEnabled = c.getBoolean("structure.enabled", true);
        s.maxLength = Math.max(0, c.getInt("structure.max-length", 120));
        s.minDigits = Math.max(1, c.getInt("structure.min-digits", 8));
        s.maxDigitRatio = Math.min(1, Math.max(0, c.getDouble("structure.max-digit-ratio", 0.5)));
        s.maxRareChars = Math.max(0, c.getInt("structure.max-rare-chars", 2));
        s.combineMessages = Math.max(1, c.getInt("structure.combine-messages", 3));
        s.checkSigns = c.getBoolean("structure.check-signs", true);
        s.checkAnvil = c.getBoolean("structure.check-anvil", true);
        s.checkBooks = c.getBoolean("structure.check-books", true);
        // 地址情报探测：判断命中的地址到底是不是真的 Minecraft 服务器（SRV + 协议探测 + 网页标题）。
        s.probeEnabled = c.getBoolean("probe.enabled", true);
        s.probeTimeoutMs = Math.max(200, c.getInt("probe.timeout-ms", 2000));
        s.probeCacheSeconds = Math.max(0, c.getInt("probe.cache-seconds", 600));
        s.probeFetchTitle = c.getBoolean("probe.fetch-title", true);
        s.aiPrompt = c.getString("ai.prompt", DEFAULT_AI_PROMPT);
        // AI 预筛：关键词表为空 = 所有带文字的消息都送 AI；不写这一项 = 用内置关键词表，只送像广告的。
        if (c.isSet("ai.trigger-words")) {
            s.aiTriggerWords = new HashSet<>();
            for (String word : c.getStringList("ai.trigger-words")) if (!word.isBlank()) s.aiTriggerWords.add(word);
        } else {
            s.aiTriggerWords = DEFAULT_TRIGGER_WORDS;
        }
        // 自己服务器的地址 + 内置受信任平台（B站、GitHub、mcmod 这类）都不算广告。
        s.selfDomains = lowerSet(c.getStringList("whitelist.domains"));
        s.allowedDomains = new HashSet<>(s.selfDomains);
        s.allowedDomains.addAll(TRUSTED_DOMAINS);
        s.allowedIps = new HashSet<>(c.getStringList("whitelist.ips"));
        // tlds 不写 = 内置的服务器常用后缀；写空列表 = 不检测域名，只检测 IP。
        s.tlds = c.isSet("tlds") ? lowerSet(c.getStringList("tlds")) : builtInTlds();
        s.bannedWords = new HashSet<>();
        for (String word : c.getStringList("banned-words")) if (!word.isBlank()) s.bannedWords.add(word);
        return s;
    }

    /** 内置的服务器常用后缀：全部两字母国别后缀 + 常见通用后缀（含 ren/icu/wang），不含品牌与文件扩展名后缀。 */
    static Set<String> builtInTlds() {
        return BUILT_IN_TLDS;
    }

    /** 内置受信任站点，命中这些域名的链接不算广告。 */
    static Set<String> trustedDomains() {
        return TRUSTED_DOMAINS;
    }

    /** 读打包的资源文件（一行一个），读不到或为空时用兜底集合。 */
    private static Set<String> loadResource(String path, Set<String> fallback) {
        try (InputStream stream = Settings.class.getResourceAsStream(path)) {
            if (stream == null) return fallback;
            Set<String> result = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.strip().toLowerCase(Locale.ROOT);
                    if (!value.isEmpty() && !value.startsWith("#")) result.add(value);
                }
            }
            return result.isEmpty() ? fallback : Set.copyOf(result);
        } catch (IOException e) {
            return fallback;
        }
    }

    /** 白名单允许写成 http://example.com/ 或 *.example.com 这种常见形式。 */
    private static Set<String> lowerSet(Collection<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            String normalized = value.toLowerCase(Locale.ROOT).strip()
                    .replaceFirst("^https?://", "").replaceFirst("^\\*\\.", "").replaceFirst("^\\.", "");
            int slash = normalized.indexOf('/');
            if (slash >= 0) normalized = normalized.substring(0, slash);
            if (!normalized.isBlank()) result.add(normalized);
        }
        return result;
    }
}
