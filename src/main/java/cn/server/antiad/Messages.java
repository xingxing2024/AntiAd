package cn.server.antiad;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** 消息工具：配置里的文本一律用 & 颜色代码书写。 */
public final class Messages {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private Messages() {}

    public static Component text(String legacy) {
        return LEGACY.deserialize(legacy);
    }

    /** 替换 {占位符}，pairs 按 “键, 值” 成对传入。 */
    public static String replace(String legacy, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) legacy = legacy.replace(pairs[i], pairs[i + 1]);
        return legacy;
    }

    /** 替换占位符并转成 Adventure 组件。 */
    public static Component fill(String legacy, String... pairs) {
        return text(replace(legacy, pairs));
    }

    /** 秒数 → 简短时长文本（用于给外部封禁指令当参数）：`2h`、`30m`、`45s`；0 或负数返回空字符串。 */
    public static String durationText(long seconds) {
        if (seconds <= 0) return "";
        if (seconds % 86400L == 0) return seconds / 86400L + "d";
        if (seconds % 3600L == 0) return seconds / 3600L + "h";
        if (seconds % 60L == 0) return seconds / 60L + "m";
        return seconds + "s";
    }

    /** 去掉换行与多余空格，避免理由里的换行把外部指令拆成两条。 */
    public static String commandSafe(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s{2,}", " ").trim();
    }

    /** 供控制台日志使用：去掉颜色代码。 */
    public static String plain(String legacy) {
        return PlainTextComponentSerializer.plainText().serialize(text(legacy));
    }
}
