package cn.server.antiad;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTest {
    private HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    private static Settings settings(String url) {
        Settings s = new Settings();
        s.aiUrl = url;
        s.aiKey = "test-key";
        s.aiModel = "test-model";
        s.aiTimeout = 2;
        s.aiFailOpen = true;
        s.selfDomains = java.util.Set.of("example.com");
        s.aiPrompt = "{self}|{minutes} 分钟 {hits} 次|线索 {words} / 情报 {probe} / 最近：{context} / 本次：{message}";
        return s;
    }

    /** 起一个假接口，返回固定内容。 */
    private String stub(int status, String body, long delayMillis, AtomicReference<String> captured) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (captured != null) captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                }
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @Test void 判定为广告() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String url = stub(200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"ad\\\":true,\\\"reason\\\":\\\"发服务器地址\\\"}\"}}],\"usage\":{\"prompt_tokens\":412,\"completion_tokens\":18}}", 0, body);
        Ai.Verdict verdict = new Ai(Logger.getLogger("AntiAdTest")).judge(settings(url), 3, 1, List.of("加群"), "mc.example.ren：SRV 指向 play.example.net:25567", List.of("你好", "有人吗"), "快来玩 mc.example.ren");
        assertTrue(verdict.ad());
        assertEquals("发服务器地址", verdict.reason());
        assertEquals(412, verdict.promptTokens());
        assertEquals(18, verdict.completionTokens());
        assertTrue(body.get().contains("mc.example.ren"));
        assertTrue(body.get().contains("test-model"));
        assertTrue(body.get().contains("3 分钟"));
        assertTrue(body.get().contains("有人吗"));
        assertTrue(body.get().contains("加群"));   // 命中词作为线索一起发给 AI
        assertTrue(body.get().contains("example.com"));   // 占位符 {self} 换成了本服域名
        assertTrue(body.get().contains("play.example.net:25567"));   // 地址情报（{probe}）也进了提示词
    }

    @Test void 判定不是广告() throws Exception {
        String url = stub(200, "```json\n{\"ad\": false, \"reason\": \"普通聊天\"}\n```", 0, null);
        Ai.Verdict verdict = new Ai(Logger.getLogger("AntiAdTest")).judge(settings(url), 1, 0, List.of(), "消息里没有地址", List.of(), "今天一起挖矿吗");
        assertFalse(verdict.ad());
        assertEquals("普通聊天", verdict.reason());
    }

    @Test void 接口报错与超时按failOpen处理() throws Exception {
        Ai ai = new Ai(Logger.getLogger("AntiAdTest"));
        assertFalse(ai.judge(settings(stub(500, "boom", 0, null)), 1, 0, List.of(), "消息里没有地址", List.of(), "x").ad());
        Settings closed = settings(settings(stub(200, "boom", 0, null)).aiUrl);
        closed.aiFailOpen = false;
        assertTrue(ai.judge(closed, 1, 0, List.of(), "消息里没有地址", List.of(), "x").ad());
        Settings timeout = settings(stub(200, "{\"ad\":true,\"reason\":\"慢\"}", 3000, null));
        timeout.aiTimeout = 1;
        assertFalse(ai.judge(timeout, 1, 0, List.of(), "消息里没有地址", List.of(), "x").ad());
        ai.close();
    }

    @Test void 无法解析与转义() {
        assertNull(Ai.parse("模型今天不想说话"));
        assertNull(Ai.parse(null));
        assertEquals("", Ai.parse("{\"ad\":true}").reason());
        assertEquals(true, Ai.parse("{\"ad\":true,\"reason\":\"地址\"}").ad());
        assertNull(Ai.parse("{\"ad\": true或false}"));          // 提示词回显不算判定
        String request = Ai.request(settings("http://x"), 2, 1, List.of("卖号"), "未探测", List.of("上一条"), "带\"引号\"\n和换行");
        assertFalse(request.contains("\n"));
        assertTrue(request.contains("\\\"引号\\\""));
        assertTrue(request.contains("卖号"));
    }

    @Test void 提示词没写probe时自动附上地址情报() {
        Settings s = settings("http://x");
        s.aiPrompt = "只判断这条：{message}";
        String request = Ai.request(s, 1, 0, List.of(), "mc.example.com：25565 端口回应了 Minecraft 状态（版本 1.20.1）", List.of(), "来玩");
        assertTrue(request.contains("地址情报"), request);
        assertTrue(request.contains("1.20.1"), request);
    }
}
