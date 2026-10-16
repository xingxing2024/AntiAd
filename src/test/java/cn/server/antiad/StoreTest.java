package cn.server.antiad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {
    @TempDir Path dir;

    private Store open() throws Exception {
        return new Store(dir.resolve("antiad.db"), Logger.getLogger("AntiAdTest"));
    }

    @Test void 记录与查询() throws Exception {
        long now = 1_000_000L;
        UUID player = UUID.randomUUID();
        try (Store store = open()) {
            store.record(now, player, "Steve", "1.2.3.4", "ad", "mc.example.net", "来玩 mc.example.net", "hidden");
            store.record(now + 1000, player, "Steve", "1.2.3.4", "word", "卖号", "卖号找我", "hidden");
            store.record(now + 2000, player, "Steve", "1.2.3.4", "ban", "mc.example.net", "来玩 mc.example.net", "banned");

            assertEquals(List.of(now), store.adHits(now - 1).get(player));
            assertNull(store.adHits(now + 1500).get(player));
            assertEquals("ban", store.recent(null, 10).get(0).kind());
            assertEquals("Steve", store.recent("steve", 10).get(0).player());
            assertEquals("mc.example.net", store.recent("Steve", 1).get(0).match());
            assertEquals(3, store.recent(null, 100).size());
            assertArrayEquals(new int[]{3, 1}, store.summary(player));
            assertArrayEquals(new int[]{0, 0}, store.summary(UUID.randomUUID()));
        }
    }

    @Test void 用量按天累加() throws Exception {
        try (Store store = open()) {
            store.addUsage("2026-10-13", 400, 20);
            store.addUsage("2026-10-13", 350, 15);
            store.addUsage("2026-10-12", 500, 30);
            List<Store.Usage> rows = store.usage(7);
            assertEquals(2, rows.size());
            assertEquals("2026-10-13", rows.get(0).day());
            assertEquals(2, rows.get(0).calls());
            assertEquals(750, rows.get(0).prompt());
            assertEquals(35, rows.get(0).completion());
            assertEquals(1, store.usage(1).size());
            store.purge(2_000_000_000_000L);
            assertEquals(0, store.usage(30).size());
        }
    }

    @Test void 行为画像按行为累计() throws Exception {
        try (Store store = open()) {
            UUID player = UUID.randomUUID();
            store.saveBehavior(new Store.BehaviorRow(player, "Steve", 500, 25, 100, 2, -3, false, 1L));
            Store.BehaviorRow row = store.behavior(player);
            assertEquals(500, row.blocks());
            assertEquals(25, row.minutes());
            assertEquals(-3, row.penalty());
            assertFalse(row.banned());
            assertEquals(1, store.behaviors(10).size());
            assertNull(store.behavior(UUID.randomUUID()));
            store.markBanned(player, "Steve");
            assertTrue(store.behavior(player).banned());
            assertTrue(store.behaviors(10).get(0).banned());
        }
    }

    @Test void 可以查到最近记录的IP() throws Exception {
        try (Store store = open()) {
            UUID player = UUID.randomUUID();
            store.record(1000L, player, "Steve", "203.0.113.9", "ad", "a.com", "a.com", "hidden");
            store.record(2000L, player, "Steve", "203.0.113.10", "ad", "a.com", "a.com", "hidden");
            store.record(3000L, player, "Steve", null, "ban", "a.com", "a.com", "banned");
            assertEquals("203.0.113.10", store.lastIp(player));   // 最近一条带 IP 的记录
            assertNull(store.lastIp(UUID.randomUUID()));
        }
    }

    @Test void 保留期外会被清理() throws Exception {
        try (Store store = open()) {
            store.record(0L, UUID.randomUUID(), "Old", "1.2.3.4", "ad", "a.com", "a.com", "hidden");
            store.purge(1_000L);
            assertTrue(store.recent(null, 10).isEmpty());
        }
    }

    @Test void 重启后累计次数能恢复() throws Exception {
        Path file = dir.resolve("antiad.db");
        UUID player = UUID.randomUUID();
        try (Store store = new Store(file, Logger.getLogger("AntiAdTest"))) {
            store.record(1000L, player, "Steve", "1.2.3.4", "ad", "a.com", "a.com", "hidden");
            store.record(2000L, player, "Steve", "1.2.3.4", "ad", "a.com", "a.com", "hidden");
        }
        try (Store reopened = new Store(file, Logger.getLogger("AntiAdTest"))) {
            assertEquals(2, reopened.adHits(0L).get(player).size());
        }
    }
}
