package cn.server.antiad;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 纯文本广告检测，不接触 Bukkit API，方便单测。只认确定性的 IPv4 与已知后缀的域名。 */
public final class Detector {
    private static final Pattern COLOR = Pattern.compile("(?i)[&\u00a7](?:#[0-9a-f]{6}|[0-9a-fk-orx])");
    private static final Pattern HIDDEN = Pattern.compile("\\p{Cf}");
    private static final Pattern COLLAPSE = Pattern.compile("[ \\t]+");
    private static final Pattern BRACKET_DOT = Pattern.compile("(?i)[\\[【（(<{]\\s*(?:dot|点)\\s*[\\]】）)>}]");
    /** 方括号里夹着的字面点：xxx[.]com、(.)、{.}、【.】、<.> 这类写法也还原成“.”。 */
    private static final Pattern BRACKET_LITERAL_DOT = Pattern.compile("[\\[【（(<{]\\s*[.。．｡·・•﹒、]\\s*[\\]】）)>}]");
    private static final Pattern DOT = Pattern.compile("(?i)\\s*(?:dot|点|[.。．｡·・•﹒、])\\s*");
    private static final Pattern DIGIT_SPACE = Pattern.compile("(?<=\\d)\\s+(?=\\d)");
    private static final Pattern LETTER_SPACE = Pattern.compile("(?<=\\b[a-z0-9])\\s+(?=[a-z0-9]\\b)");
    private static final Pattern CJK_SPACE = Pattern.compile("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)");
    private static final Pattern NUMERIC_IP = Pattern.compile("(?<![\\d.])(\\d{8,10})(?![\\d])");
    private static final long MAX_IPV4 = 4_294_967_295L;
    private static final Pattern DOMAIN = Pattern.compile("(?<![a-z0-9.-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}(?![a-z0-9-])");

    private Detector() {}

    /** 归一化：去掉颜色与隐藏字符、全角转半角、统一各种“点”的写法、去掉数字/单字母之间用来绕过检测的空格。 */
    public static String normalize(String raw) {
        String text = COLOR.matcher(raw).replaceAll("");
        text = HIDDEN.matcher(text).replaceAll("");
        text = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        text = COLLAPSE.matcher(text).replaceAll(" ");
        text = BRACKET_DOT.matcher(text).replaceAll(".");
        text = BRACKET_LITERAL_DOT.matcher(text).replaceAll(".");
        text = DOT.matcher(text).replaceAll(".");
        text = DIGIT_SPACE.matcher(text).replaceAll("");
        text = LETTER_SPACE.matcher(text).replaceAll("");
        return CJK_SPACE.matcher(text).replaceAll("");
    }

    /** 没有任何文字（纯数字、标点、表情）的消息不可能是广告，不必送 AI。 */
    public static boolean hasLetters(String text) {
        return text.codePoints().anyMatch(Character::isLetter);
    }

    /** 生僻字数量：CJK 扩展 A 区与扩展 B 区以上，日常聊天几乎不会出现，多用于规避审查。 */
    public static int rareChars(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if ((codePoint >= 0x3400 && codePoint <= 0x4DBF) || (codePoint >= 0x20000 && codePoint <= 0x3FFFF)) count++;
        }
        return count;
    }

    private static int digitChars(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isDigit(text.charAt(i))) count++;
        return count;
    }

    /**
     * 结构化异常：只看消息的"形状"（长度、数字占比、生僻字），不依赖词库与 AI，也不做语义判断。
     * 返回原因用于记录与通报；正常返回 null。
     */
    public static String structureIssue(String raw, Settings s) {
        if (!s.structureEnabled) return null;
        String text = normalize(raw).replace(" ", "");
        if (text.isEmpty()) return null;
        if (s.maxLength > 0 && text.length() > s.maxLength) return "消息过长（" + text.length() + " 字符）";
        int digits = digitChars(text);
        if (digits >= s.minDigits && digits >= text.length() * s.maxDigitRatio) return "数字过多（" + digits + " 位）";
        int rare = rareChars(text);
        if (rare > s.maxRareChars) return "生僻字过多（" + rare + " 个）";
        return null;
    }

    private static final Pattern ADDRESSY = Pattern.compile("(?i)(https?://|www\\.|@|\\.[a-z][a-z0-9-]{1,30}(?![a-z0-9-]))");

    /** 消息里出现链接样式（http、www、@、任意后缀的点号域名）就算可疑。 */
    public static boolean looksLikeAddress(String raw) {
        return ADDRESSY.matcher(normalize(raw)).find();
    }

    /** AI 预筛：可疑才值得花钱送 AI。关键词表为空表示不做预筛（全都送）。 */
    public static boolean suspicious(String text, Set<String> triggerWords) {
        if (triggerWords.isEmpty()) return true;
        return looksLikeAddress(text) || matchedWord(text, triggerWords) != null;
    }

    /** 送给 AI 的线索：命中的敏感词、命中的预筛词、链接样式。只是线索，判不判广告由 AI 定。 */
    public static List<String> hints(String text, Set<String> bannedWords, Set<String> triggerWords) {
        Set<String> result = new LinkedHashSet<>();
        String banned = matchedWord(text, bannedWords);
        if (banned != null) result.add(banned);
        String trigger = matchedWord(text, triggerWords);
        if (trigger != null) result.add(trigger);
        if (result.isEmpty() && looksLikeAddress(text)) result.add("链接样式");
        return new ArrayList<>(result);
    }

    /** 违禁词：返回命中的词，没命中返回 null。先归一化再包含匹配，大小写、全角、字间空格都绕不过去。 */
    public static String matchedWord(String raw, Set<String> words) {
        if (words.isEmpty()) return null;
        String text = normalize(raw);
        for (String word : words) {
            String normalized = normalize(word);
            if (!normalized.isBlank() && text.contains(normalized)) return word;
        }
        return null;
    }

    /** 消息里出现的所有域名与公网 IP，不限后缀、也不管白名单 —— 地址情报探测用（比 find 范围宽，白名单内的仍会排除）。 */
    public static List<String> addresses(String raw, Settings s) {
        String text = normalize(raw);
        Set<String> hits = new LinkedHashSet<>();
        Matcher ip = IPV4.matcher(text);
        while (ip.find()) {
            String value = ip.group();
            if (publicIp(value) && !s.allowedIps.contains(value)) hits.add(value);
        }
        Matcher domain = DOMAIN.matcher(text);
        while (domain.find()) {
            String host = domain.group();
            // 自己服务器和内置受信任平台（B站/GitHub/mcmod 等）不用探，省一次外联。
            if (!endsWithAny(host, s.allowedDomains)) hits.add(host);
        }
        return new ArrayList<>(hits);
    }

    /** 返回命中的 IP/域名，按出现顺序去重；白名单内的地址不返回。 */
    public static List<String> find(String raw, Settings s) {
        String text = normalize(raw);
        Set<String> hits = new LinkedHashSet<>();
        Matcher ip = IPV4.matcher(text);
        while (ip.find()) {
            String value = ip.group();
            if (publicIp(value) && !s.allowedIps.contains(value)) hits.add(value);
        }
        Matcher domain = DOMAIN.matcher(text);
        while (domain.find()) {
            String host = domain.group();
            if (endsWithAny(host, s.tlds) && !endsWithAny(host, s.allowedDomains)) hits.add(host);
        }
        if (s.checkNumericIp) {
            Matcher numeric = NUMERIC_IP.matcher(text);
            while (numeric.find()) {
                String value = numeric.group(), dotted = numericToIp(value);
                if (dotted != null && publicIp(dotted) && !s.allowedIps.contains(dotted) && !s.allowedIps.contains(value)) hits.add(value + " (" + dotted + ")");
            }
        }
        return new ArrayList<>(hits);
    }

    /**
     * 整个域名黑名单（blocked-domains）：命中它的任何子域名（www.、mc.、play. 等）都算，返回命中的写法，没有返回 null。
     * 不要求后缀在 tlds 表里（品牌后缀、zip 这类文件扩展名后缀照拦）；与白名单冲突时以白名单为准。
     */
    public static String blocked(String raw, Settings s) {
        if (s.blockedDomains == null || s.blockedDomains.isEmpty()) return null;
        String text = normalize(raw);
        Matcher domain = DOMAIN.matcher(text);
        while (domain.find()) {
            String host = domain.group();
            if (endsWithAny(host, s.allowedDomains)) continue;
            if (endsWithAny(host, s.blockedDomains)) return host;
        }
        return null;
    }

    /** 十进制整数形式的 IP：3107365173 → 185.54.161.53。不是合法 IPv4 或形如日期时返回 null。 */
    private static String numericToIp(String digits) {
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value > MAX_IPV4 || isDate(digits)) return null;
        return (value >>> 24) + "." + (value >>> 16 & 255) + "." + (value >>> 8 & 255) + "." + (value & 255);
    }

    /** 20261012 这类日期不当作 IP。 */
    private static boolean isDate(String digits) {
        if (digits.length() != 8) return false;
        int year = Integer.parseInt(digits.substring(0, 4));
        int month = Integer.parseInt(digits.substring(4, 6));
        int day = Integer.parseInt(digits.substring(6, 8));
        return year >= 1990 && year <= 2099 && month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    /** 局域网、回环、运营商内网和带前导零的写法都不算广告。 */
    private static boolean publicIp(String value) {
        String[] parts = value.split("\\.");
        for (String part : parts) {
            if (part.length() > 1 && part.startsWith("0")) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
        if (a == 0 || a == 10 || a == 127) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 169 && b == 254) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        return true;
    }

    /** 域名后缀匹配：suffix 本身相等或以 “.suffix” 结尾，因此白名单填 example.com 也会放行 play.example.com。 */
    private static boolean endsWithAny(String host, Set<String> suffixes) {
        for (String suffix : suffixes) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        return false;
    }
}
