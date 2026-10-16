package cn.server.antiad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test void 短写命令映射到规范子命令() {
        assertEquals("check", AntiAdCommand.canonical("c"));
        assertEquals("check", AntiAdCommand.canonical("INFO"));
        assertEquals("ban", AntiAdCommand.canonical("b"));
        assertEquals("unban", AntiAdCommand.canonical("ub"));
        assertEquals("unban", AntiAdCommand.canonical("pardon"));
        assertEquals("behavior", AntiAdCommand.canonical("bh"));
        assertEquals("ai", AntiAdCommand.canonical("a"));
        assertEquals("cost", AntiAdCommand.canonical("co"));
        assertEquals("records", AntiAdCommand.canonical("r"));
        assertEquals("records", AntiAdCommand.canonical("logs"));
        assertEquals("reload", AntiAdCommand.canonical("rl"));
        assertEquals("help", AntiAdCommand.canonical("?"));
        assertEquals("nonsense", AntiAdCommand.canonical("nonsense"));
    }

    @Test void 是否计入封禁次数由探测结论决定() {
        Settings settings = new Settings();
        Probe.Result live = new Probe.Result(true, true, false, "25565 端口回应了 Minecraft 状态");
        Probe.Result blocked = new Probe.Result(true, false, true, "25565 端口是开着的（连得上），但没回服务器状态");
        Probe.Result dead = new Probe.Result(true, false, false, "25565 端口无响应；首页标题：某网站（不像服务器站点）");
        Probe.Result none = Probe.Result.NONE;

        settings.probeEnabled = true;
        settings.probeConfirmRequired = true;
        assertTrue(ChatGuard.hardHit(settings, live), "状态回应 → 计入");
        assertTrue(ChatGuard.hardHit(settings, blocked), "端口开着只是屏蔽了状态（enable-status=false）→ 也计入");
        assertFalse(ChatGuard.hardHit(settings, dead), "端口无响应/只是网站 → 不计入（不封号）");
        assertFalse(ChatGuard.hardHit(settings, none), "域名解析不了 → 不计入");

        settings.probeConfirmRequired = false;   // 关掉确认要求 = 老行为
        assertTrue(ChatGuard.hardHit(settings, dead));
        assertTrue(ChatGuard.hardHit(settings, none));

        settings.probeConfirmRequired = true;
        settings.probeEnabled = false;           // 整个探测关掉 = 老行为
        assertTrue(ChatGuard.hardHit(settings, none));
    }

    @Test void 规范子命令名不会被短写改写() {
        for (String sub : java.util.List.of("check", "ban", "unban", "behavior", "ai", "cost", "records", "test", "reload", "help")) {
            assertEquals(sub, AntiAdCommand.canonical(sub), "规范名 " + sub + " 被短写表改掉了");
        }
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
