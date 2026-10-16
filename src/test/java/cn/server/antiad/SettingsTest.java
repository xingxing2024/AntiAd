package cn.server.antiad;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 玩家白名单（whitelist.players）的匹配规则。 */
class SettingsTest {
    private static final UUID ID = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    @Test void 名字不分大小写() {
        Settings s = new Settings();
        s.allowedPlayers = Settings.playerSet(List.of("Steve", "  Alex  "));
        assertTrue(s.playerAllowed(ID, "Steve"));
        assertTrue(s.playerAllowed(ID, "steve"));
        assertTrue(s.playerAllowed(ID, "STEVE"));
        assertTrue(s.playerAllowed(ID, "Alex"));
        assertFalse(s.playerAllowed(ID, "Herobrine"));
        assertFalse(s.playerAllowed(ID, null));
    }

    @Test void UUID带不带横线都认() {
        Settings s = new Settings();
        s.allowedPlayers = Settings.playerSet(List.of("8667ba71b85a4004af54457a9734eed7"));
        assertTrue(s.playerAllowed(ID, "随便什么名字"));
        s.allowedPlayers = Settings.playerSet(List.of("8667ba71-b85a-4004-af54-457a9734eed7"));
        assertTrue(s.playerAllowed(ID, "Whatever"));
        assertFalse(s.playerAllowed(UUID.randomUUID(), "Whatever"));
    }

    @Test void 空名单不生效且忽略空条目() {
        Settings s = new Settings();
        s.allowedPlayers = Settings.playerSet(List.of());
        assertFalse(s.playerAllowed(ID, "Steve"));
        assertEquals(Set.of(), Settings.playerSet(List.of("", "   ", "-")));
    }
}
