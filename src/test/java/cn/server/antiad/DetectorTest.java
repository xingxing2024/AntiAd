package cn.server.antiad;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectorTest {
    private static Settings settings() {
        Settings s = new Settings();
        s.checkChat = true;
        s.checkNumericIp = true;
        s.hits = 3;
        s.windowSeconds = 60;
        s.allowedDomains = new HashSet<>(Set.of("example.com", "yourserver.cn"));
        s.allowedDomains.addAll(Settings.trustedDomains());
        s.allowedIps = new HashSet<>(Set.of("1.2.3.4"));
        s.tlds = Set.of("com", "cn", "net", "io", "me", "shop", "com.cn");
        return s;
    }

    private static List<String> find(String text) {
        return Detector.find(text, settings());
    }

    @Test void 直接写出IP或域名会被命中() {
        assertEquals(List.of("98.139.101.1"), find("来玩我的服 98.139.101.1:25565"));
        assertEquals(List.of("mc.example.net"), find("新服 mc.example.net 等你"));
        assertEquals(List.of("a.com", "b.net"), find("a.com 和 b.net 都开服了"));
    }

    @Test void 数字形式的IP会被命中() {
        assertEquals(List.of("3107365173 (185.54.161.53)"), find("服务器 3107365173 来玩"));
        assertEquals(List.of("999999999 (59.154.201.255)"), find("地址是 999999999"));
        assertTrue(find("3232235777").isEmpty());       // 192.168.1.1，内网不算
        assertTrue(find("20261012 开新档").isEmpty());   // 日期不算
        assertTrue(find("金币 9999999").isEmpty());      // 7 位不够
        Settings off = settings();
        off.checkNumericIp = false;
        assertTrue(Detector.find("服务器 3107365173", off).isEmpty());
    }

    @Test void 重复出现只算一次() {
        assertEquals(List.of("mc.example.net"), find("mc.example.net 快去 mc.example.net"));
    }

    @Test void 分隔与全角写法绕不过去() {
        assertEquals(List.of("98.139.101.1"), find("来玩 9 8 . 1 3 9 . 1 0 1 . 1 快"));
        assertEquals(List.of("mc.example.net"), find("mc(dot)example(dot)net"));
        assertEquals(List.of("mc.example.net"), find("mc 点 example 点 net"));
        assertEquals(List.of("mc.example.net"), find("MC [DOT] example [DOT] net"));
        assertEquals(List.of("mc.example.net"), find("ｍｃ．ｅｘａｍｐｌｅ．ｎｅｔ"));
        assertEquals(List.of("mc.example.net"), find("m c . e x a m p l e . n e t"));
        assertEquals(List.of("9.9.9.9"), find("9.9.§c9.§r9"));
        assertEquals(List.of("9.9.9.9"), find("9.9§#ff0000.9.§#ff00009"));   // §#RRGGBB 十六进制颜色也去掉
    }

    @Test void 白名单内的自己服务器地址不命中() {
        assertEquals(List.of(), find("本服地址 play.example.com 或 1.2.3.4"));
        assertEquals(List.of(), find("子域名 mc.yourserver.cn 也可以"));
        Settings settings = settings();
        settings.allowedIps.add("185.54.161.53");
        assertEquals(List.of(), Detector.find("服务器 3107365173", settings));
        settings.allowedIps.add("3107365173");
        assertEquals(List.of(), Detector.find("加群 3107365173", settings));   // 自己的群号写进白名单，不会被当成数字 IP
    }

    @Test void 局域网与无意义数字不命中() {
        assertEquals(List.of(), find("192.168.1.100 内网主机"));
        assertEquals(List.of(), find("127.0.0.1 是本机"));
        assertEquals(List.of(), find("10.0.0.1 和 172.20.3.4"));
        assertEquals(List.of(), find("999.999.999.999"));
        assertEquals(List.of(), find("版本更新到 1.20.1 了"));
        assertEquals(List.of(), find("我打了 5.5 分"));
        assertEquals(List.of(), find("今晚 12:30 打末影龙"));
    }

    @Test void 普通聊天不命中() {
        assertEquals(List.of(), find("今天挖了一整天矿，累死了"));
        assertEquals(List.of(), find("resource pack mod.jar 已上传"));
        assertEquals(List.of(), find("mc.example 不是域名"));
        assertEquals(List.of(), find("今天玩得开心. 明天见"));
        assertEquals(List.of(), find("快来看我的作品，点这里"));
    }

    @Test void 内置服务器后缀() {
        Set<String> tlds = Settings.builtInTlds();
        assertTrue(tlds.size() > 300);
        assertTrue(tlds.contains("ren") && tlds.contains("icu") && tlds.contains("com") && tlds.contains("jp"));
        assertFalse(tlds.contains("zip") || tlds.contains("mov") || tlds.contains("aarp"));
        Settings settings = settings();
        settings.tlds = tlds;
        assertEquals(List.of("mc.ren"), Detector.find("来玩 mc.ren", settings));
        assertEquals(List.of("mc.example.icu"), Detector.find("新服 mc.example.icu 等你", settings));
    }

    @Test void 平台与社区链接不算广告() {
        assertEquals(List.of(), find("看这个视频 www.bilibili.com/video/BV1xx 挺好看的"));
        assertEquals(List.of(), find("资源在 github.com/xxx/mod 自己下"));
        assertEquals(List.of(), find("教程去 mcmod.cn 搜 工业时代"));
        assertEquals(List.of(), find("官网 minecraft.net 有说明"));
        assertTrue(Settings.trustedDomains().contains("bilibili.com") && Settings.trustedDomains().contains("modrinth.com"));
        assertEquals(List.of("fan.shop"), find("来玩 fan.shop"));   // 不在受信任表里的域名照常命中
    }

    @Test void 纯数字与符号不送AI() {
        assertFalse(Detector.hasLetters("123"));
        assertFalse(Detector.hasLetters("666 ………"));
        assertFalse(Detector.hasLetters("🎉🎉"));
        assertTrue(Detector.hasLetters("123群"));
        assertTrue(Detector.hasLetters("hello"));
    }

    @Test void 疑似筛选() {
        Set<String> words = Set.of("加群", "来玩", "服务器");
        assertTrue(Detector.suspicious("加群 123456", words));
        assertTrue(Detector.suspicious("看看 mc.example.ren", words));        // 链接样式
        assertTrue(Detector.suspicious("看我主页 www.abc.xyz", words));
        assertTrue(Detector.suspicious("加我 qq 吧", Set.of("qq", "加群")));
        assertTrue(Detector.suspicious("今天一起挖矿吗", Set.of()));           // 关键词表为空 = 全都送
        assertFalse(Detector.suspicious("今天一起挖矿吗", words));
        assertFalse(Detector.suspicious("卡死了 好卡", words));
        assertTrue(Detector.looksLikeAddress("新服 mc.unknown-tld"));
        assertFalse(Detector.looksLikeAddress("今天玩得开心. 明天见"));
    }

    @Test void 送给AI的线索() {
        Set<String> banned = Set.of("卖号", "代练");
        Set<String> triggers = Set.of("加群", "来玩");
        assertEquals(List.of("卖号", "加群"), Detector.hints("卖号的加群", banned, triggers));
        assertEquals(List.of("卖号"), Detector.hints("卖号找我", banned, triggers));
        assertEquals(List.of(), Detector.hints("今天一起挖矿吗", banned, triggers));
        assertEquals(List.of("链接样式"), Detector.hints("看看 mc.example.ren", banned, triggers));
    }

    @Test void 结构异常只看形状() {
        Settings s = settings();
        s.structureEnabled = true;
        s.maxLength = 20;
        s.minDigits = 8;
        s.maxDigitRatio = 0.5;
        s.maxRareChars = 2;
        assertNotNull(Detector.structureIssue("这是一条非常非常长的普通聊天消息已经超过限制了吧", s));
        assertNotNull(Detector.structureIssue("我的手机号 13800138000", s));
        assertNotNull(Detector.structureIssue("𠀀𠀁𠀂你好", s));                 // CJK 扩展 B 区字符
        assertNull(Detector.structureIssue("你好，今天一起挖矿吗", s));
        assertNull(Detector.structureIssue("我有 123 个钻石", s));
        s.structureEnabled = false;
        assertNull(Detector.structureIssue("这是一条非常非常长的普通聊天消息已经超过限制了吧", s));
    }

    @Test void 跨消息拼接才露馅() {
        Settings s = settings();
        s.combineMessages = 3;
        assertEquals("mc.example.net", ChatGuard.combineHit(List.of("来看看这个 mc.example"), ".net 挺好玩的", s));
        // 历史里已经能拼出来的地址：后续消息不算新命中，避免连锁误伤
        assertNull(ChatGuard.combineHit(List.of("来看看这个 mc.example", ".net 挺好玩的"), "今天一起挖矿吗", s));
        assertNull(ChatGuard.combineHit(List.of("你好"), "今天一起挖矿吗", s));
        assertNull(ChatGuard.combineHit(List.of(), "今天一起挖矿吗", s));
    }

    @Test void 违禁词匹配() {
        Set<String> words = Set.of("外挂", "卖号", "sell account");
        assertEquals("卖号", Detector.matchedWord("谁会卖号？", words));
        assertEquals("卖号", Detector.matchedWord("卖　号 的加我", words));
        assertEquals("sell account", Detector.matchedWord("Sell   Account 私聊", words));
        assertNull(Detector.matchedWord("今天一起挖矿吧", words));
        assertNull(Detector.matchedWord("我正在挖矿，号角不错", words));
        assertNull(Detector.matchedWord("任何内容", Set.of()));
    }

    @Test void 归一化会保留可读文本() {
        assertEquals("mc.example.net", Detector.normalize("MC [DOT] Example [DOT] Net"));
        assertEquals("来玩 mc.example.net", Detector.normalize("来玩 ｍｃ．ｅｘａｍｐｌｅ．ｎｅｔ"));
    }
}
