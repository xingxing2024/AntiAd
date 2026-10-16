package cn.server.antiad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorTest {
    private static Settings settings() {
        Settings s = new Settings();
        s.hits = 3;
        s.behaviorEnabled = true;
        s.defaultScore = 20;
        s.normalScore = 30;
        s.suspiciousScore = 19;
        s.advertiserScore = 5;
        s.blocksPerPoint = 50;
        s.commandsPerPoint = 10;
        s.maxActivityScore = 20;
        s.adHitPenalty = 3;
        s.wordHitPenalty = 1;
        return s;
    }

    private static Behavior.Profile profile(int blocks, int minutes, int commands, int penalty, boolean banned) {
        return new Behavior.Profile(blocks, minutes, commands, 1, penalty, banned);
    }

    @Test void 新玩家默认二十分算观望() {
        assertEquals(20, Behavior.score(settings(), profile(0, 0, 0, 0, false)));
        assertEquals(Behavior.Kind.WATCH, Behavior.classify(settings(), profile(0, 0, 0, 0, false)));
    }

    @Test void 真的在玩会变成正常() {
        // 500 方块 / 100 指令 = 10+10 = 20 分，加基础 20 = 40
        assertEquals(40, Behavior.score(settings(), profile(500, 25, 100, 0, false)));
        assertEquals(Behavior.Kind.NORMAL, Behavior.classify(settings(), profile(500, 25, 100, 0, false)));
    }

    @Test void 在线时长不计分() {
        assertEquals(20, Behavior.score(settings(), profile(0, 999, 0, 0, false)));
        assertEquals(Behavior.Kind.WATCH, Behavior.classify(settings(), profile(0, 999, 0, 0, false)));
    }

    @Test void 行为加分封顶() {
        assertEquals(40, Behavior.score(settings(), profile(999999, 9999, 9999, 0, false)));
    }

    @Test void 命中扣分逐级降类() {
        Settings s = settings();
        assertEquals(Behavior.Kind.WATCH, Behavior.classify(s, profile(0, 0, 0, 0, false)));          // 20 → 观望
        assertEquals(Behavior.Kind.SUSPICIOUS, Behavior.classify(s, profile(0, 0, 0, -1, false)));    // 19 → 低于 20，重点关注
        assertEquals(Behavior.Kind.SUSPICIOUS, Behavior.classify(s, profile(0, 0, 0, -6, false)));    // 14 → 重点关注
        assertEquals(Behavior.Kind.ADVERTISER, Behavior.classify(s, profile(0, 0, 0, -15, false)));   // 5 → 广告号
        assertEquals(Behavior.Kind.ADVERTISER, Behavior.classify(s, profile(500, 25, 100, 0, true))); // 封过就是广告号
        assertEquals(Behavior.Kind.NORMAL, Behavior.classify(s, profile(500, 25, 100, -3, false)));  // 40-3=37 仍是正常
    }

    @Test void 阈值随分类收紧() {
        Settings s = settings();
        assertEquals(3, Behavior.threshold(s, Behavior.Kind.WATCH));
        assertEquals(3, Behavior.threshold(s, Behavior.Kind.NORMAL));
        assertEquals(2, Behavior.threshold(s, Behavior.Kind.SUSPICIOUS));
        assertEquals(1, Behavior.threshold(s, Behavior.Kind.ADVERTISER));
    }

    @Test void 老玩家三条都满足才免检() {
        Settings s = settings();
        s.veteranPlayHours = 24;
        s.veteranMinScore = 25;
        s.veteranCleanHours = 24;
        long day = 24 * 72000L;
        assertTrue(Behavior.veteran(s, day + 1, 25, 0));
        assertFalse(Behavior.veteran(s, day, 25, 0));          // 时长必须大于 24 小时
        assertFalse(Behavior.veteran(s, day * 2, 24, 0));      // 行为分要 ≥ 25
        assertFalse(Behavior.veteran(s, day * 2, 30, 1));      // 近 24 小时有违规
        Settings off = settings();
        off.veteranPlayHours = 0;
        off.veteranMinScore = 0;
        off.veteranCleanHours = 0;
        assertFalse(Behavior.veteran(off, day * 10, 99, 0));   // 三个都设 0 = 关闭判定
    }

    @Test void 连进连退算可疑行为() {
        assertTrue(Behavior.churn(0, 0));
        assertTrue(Behavior.churn(1, 0));
        assertFalse(Behavior.churn(2, 0));
        assertFalse(Behavior.churn(0, 5));
    }
}
