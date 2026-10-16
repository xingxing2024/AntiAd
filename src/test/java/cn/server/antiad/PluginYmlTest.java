package cn.server.antiad;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守住 plugin.yml 的结构：这个文件一旦缩进写错（比如把子项写成了兄弟节点），
 * 插件会直接加载失败（"commands are of wrong type"），而构建不会报任何错。
 */
class PluginYmlTest {
    private static YamlConfiguration load() throws Exception {
        try (InputStream stream = PluginYmlTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream, "jar 里应该有 plugin.yml");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    @Test void 基本字段齐全() throws Exception {
        YamlConfiguration yaml = load();
        assertEquals("AntiAd", yaml.getString("name"));
        assertEquals("cn.server.antiad.AntiAdPlugin", yaml.getString("main"));
        assertFalse(yaml.getString("version", "").isBlank());
        assertFalse(yaml.getString("api-version", "").isBlank(), "api-version 决定能跑在哪些版本上");
        assertTrue(yaml.getBoolean("folia-supported"), "Folia 支持");
        assertEquals("cn.server.antiad.AntiAdLoader", yaml.getString("paper-plugin-loader"));
        assertTrue(yaml.getBoolean("paper-skip-libraries"));
    }

    @Test void 命令是结构而不是字符串() throws Exception {
        YamlConfiguration yaml = load();
        // 这一条就是防"缩进写错导致 commands 变成字符串"（Paper 会直接拒绝加载插件）
        assertNotNull(yaml.getConfigurationSection("commands"), "commands 必须是配置段");
        assertNotNull(yaml.getConfigurationSection("commands.antiad"), "commands.antiad 必须是配置段");
        assertEquals("反广告插件管理命令", yaml.getString("commands.antiad.description"));
        List<String> aliases = yaml.getStringList("commands.antiad.aliases");
        assertTrue(aliases.contains("adban"), "保留原别名");
        assertTrue(aliases.contains("ad"), "新增短命令 /ad");
        assertNotNull(yaml.getString("commands.antiad.usage"));
    }

    @Test void 权限节点齐全() throws Exception {
        YamlConfiguration yaml = load();
        for (String node : List.of("antiad.admin", "antiad.notify", "antiad.bypass")) {
            assertNotNull(yaml.getConfigurationSection("permissions." + node), node + " 必须有默认值");
            assertEquals("op", yaml.getString("permissions." + node + ".default"));
        }
    }

    @Test void 驱动由加载器拉取并有兜底声明() throws Exception {
        YamlConfiguration yaml = load();
        assertTrue(yaml.getStringList("libraries").contains("org.xerial:sqlite-jdbc:3.51.0.0"));
    }
}
