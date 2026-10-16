package cn.server.antiad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewPlayerTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test void 窗口内的新玩家会检查() {
        assertTrue(ChatGuard.newPlayer(NOW, NOW, 30));
        assertTrue(ChatGuard.newPlayer(NOW - 29 * 60_000L, NOW, 30));
        assertTrue(ChatGuard.newPlayer(NOW - 30 * 60_000L, NOW, 30));
        assertTrue(ChatGuard.newPlayer(0, NOW, 30));
    }

    @Test void 老玩家直接跳过() {
        assertFalse(ChatGuard.newPlayer(NOW - 30 * 60_000L - 1, NOW, 30));
        assertFalse(ChatGuard.newPlayer(NOW - 24 * 3_600_000L, NOW, 30));
    }

    @Test void 配置为0表示不限制() {
        assertTrue(ChatGuard.newPlayer(NOW - 24 * 3_600_000L, NOW, 0));
    }
}
