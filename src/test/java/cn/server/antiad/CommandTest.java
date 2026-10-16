package cn.server.antiad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandTest {
    @Test void 时长解析() {
        assertEquals(0, AntiAdCommand.seconds("0"));
        assertEquals(30, AntiAdCommand.seconds("30"));
        assertEquals(30, AntiAdCommand.seconds("30s"));
        assertEquals(600, AntiAdCommand.seconds("10m"));
        assertEquals(7200, AntiAdCommand.seconds("2h"));
        assertEquals(604800, AntiAdCommand.seconds("7d"));
        assertEquals(90, AntiAdCommand.seconds(" 90 "));
        assertEquals(-1, AntiAdCommand.seconds("刷广告"));     // 不是时长 → 当作理由
        assertEquals(-1, AntiAdCommand.seconds("5x"));
        assertEquals(-1, AntiAdCommand.seconds(""));
    }

    @Test void 外部封禁指令的占位符() {
        assertEquals("", Messages.durationText(0));
        assertEquals("45s", Messages.durationText(45));
        assertEquals("30m", Messages.durationText(1800));
        assertEquals("2h", Messages.durationText(7200));
        assertEquals("7d", Messages.durationText(604800));
        assertEquals("1h 30m", Messages.commandSafe("1h\n  30m"));
        assertEquals("宣传（a.com） 解封联系群", Messages.commandSafe(" 宣传（a.com）\n解封联系群 "));
        String rendered = Messages.replace("tempban {player} {duration} {seconds} {reason}",
                "{player}", "Steve", "{duration}", Messages.durationText(7200),
                "{seconds}", "7200", "{reason}", Messages.commandSafe("发广告\n请勿再犯"));
        assertEquals("tempban Steve 2h 7200 发广告 请勿再犯", rendered);
    }
}
