package cn.server.antiad;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 审核：把消息和该玩家的上下文发给 OpenAI 兼容的 chat/completions 接口，
 * 只认返回里的 {"ad":true/false,"reason":"..."}。judge 是阻塞调用，必须在异步线程里跑。
 */
public final class Ai {
    /** ad = 是否广告；tokens 用于 /antiad cost 的费用估算。 */
    public record Verdict(boolean ad, String reason, int promptTokens, int completionTokens) {}

    private static final Pattern AD = Pattern.compile("\"ad\"\\s*:\\s*(true|false)\\s*[,}]", Pattern.CASE_INSENSITIVE);
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT_TOKENS = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern COMPLETION_TOKENS = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");
    private static final int MAX_MESSAGE = 300;

    private final Logger logger;
    private final HttpClient http;

    public Ai(Logger logger) {
        this.logger = logger;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public Verdict judge(Settings settings, long minutes, int hits, List<String> hints, List<String> context, String message) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(settings.aiUrl))
                    .timeout(Duration.ofSeconds(settings.aiTimeout))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + settings.aiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(request(settings, minutes, hits, hints, context, message), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                logger.warning("AI 审核返回 HTTP " + response.statusCode());
                return fallback(settings, "接口返回 " + response.statusCode());
            }
            Verdict verdict = parse(response.body());
            if (verdict == null) {
                logger.warning("AI 审核返回无法解析: " + shorten(response.body(), 200));
                return fallback(settings, "无法解析");
            }
            return verdict;
        } catch (Exception e) {
            logger.warning("AI 审核失败（" + e.getClass().getSimpleName() + "），按" + (settings.aiFailOpen ? "放行" : "命中") + "处理");
            return fallback(settings, "调用失败");
        }
    }

    /** fail-open = 放行（宁漏勿误伤），fail-closed = 当作广告。失败时没有 token 用量。 */
    private static Verdict fallback(Settings settings, String reason) {
        return new Verdict(!settings.aiFailOpen, reason, 0, 0);
    }

    /** 从模型返回里找出 {"ad":...,"reason":"..."}；找不到返回 null。 */
    static Verdict parse(String body) {
        if (body == null) return null;
        // 模型常把 JSON 放在字符串里（转义过），先把 \" 还原再匹配。
        String text = body.replace("\\\"", "\"").replace("\\\\", "\\");
        Matcher ad = AD.matcher(text);
        if (!ad.find()) return null;
        Matcher reason = REASON.matcher(text);
        return new Verdict(Boolean.parseBoolean(ad.group(1)), reason.find() ? reason.group(1) : "",
                count(PROMPT_TOKENS, text), count(COMPLETION_TOKENS, text));
    }

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void close() {
        http.shutdownNow();
    }

    /** OpenAI 兼容的请求体；提示词里的占位符在这里替换。 */
    static String request(Settings settings, long minutes, int hits, List<String> hints, List<String> context, String message) {
        String prompt = settings.aiPrompt
                .replace("{self}", settings.selfDomains.isEmpty() ? "（未配置）" : String.join("、", settings.selfDomains))
                .replace("{minutes}", String.valueOf(minutes))
                .replace("{hits}", String.valueOf(hits))
                .replace("{words}", hints.isEmpty() ? "（无）" : String.join("、", hints))
                .replace("{context}", context.isEmpty() ? "（暂无）" : String.join("\n", context))
                .replace("{message}", shorten(message, MAX_MESSAGE));
        return "{\"model\":\"" + escape(settings.aiModel) + "\",\"temperature\":0,\"max_tokens\":128,\"messages\":[{\"role\":\"user\",\"content\":\""
                + escape(prompt) + "\"}]}";
    }

    /** 玩家输入会进 JSON，引号、反斜杠和控制字符必须转义。 */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? ' ' : c);
            }
        }
        return out.toString();
    }

    private static String shorten(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }
}
