package cn.server.antiad;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrikesTest {
    @Test void 窗口内累计到阈值() {
        Strikes strikes = new Strikes();
        UUID player = UUID.randomUUID();
        assertEquals(1, strikes.hit(player, 0, 60));
        assertEquals(2, strikes.hit(player, 30_000, 60));
        assertEquals(3, strikes.hit(player, 59_999, 60));
    }

    @Test void 窗口外的记录不累计() {
        Strikes strikes = new Strikes();
        UUID player = UUID.randomUUID();
        assertEquals(1, strikes.hit(player, 0, 60));
        assertEquals(1, strikes.hit(player, 61_000, 60));
        assertEquals(2, strikes.hit(player, 90_000, 60));
    }

    @Test void 玩家之间互不影响() {
        Strikes strikes = new Strikes();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertEquals(1, strikes.hit(first, 0, 60));
        assertEquals(1, strikes.hit(second, 0, 60));
        assertEquals(2, strikes.hit(first, 1_000, 60));
    }

    @Test void 记录过期后会清理() {
        Strikes strikes = new Strikes();
        UUID player = UUID.randomUUID();
        strikes.hit(player, 0, 60);
        assertEquals(1, strikes.tracked());
        assertEquals(1, strikes.hit(player, 120_000, 60));
        assertEquals(1, strikes.tracked());
        strikes.clear(player);
        assertEquals(0, strikes.tracked());
    }

    @Test void 重启后从记录恢复() {
        Strikes strikes = new Strikes();
        UUID player = UUID.randomUUID();
        strikes.seed(Map.of(player, List.of(0L, 30_000L)));
        assertEquals(3, strikes.hit(player, 59_000, 60));
    }
}
