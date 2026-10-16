package cn.server.antiad;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeTest {
    private ServerSocket mc;
    private HttpServer web;
    private final AtomicInteger connections = new AtomicInteger();

    @AfterEach void stop() throws IOException {
        if (mc != null) mc.close();
        if (web != null) web.stop(0);
    }

    private static Settings settings(boolean fetchTitle) {
        Settings s = new Settings();
        s.probeEnabled = true;
        s.probeConfirmRequired = true;
        s.probeTimeoutMs = 3000;
        s.probeCacheSeconds = 600;
        s.probeFetchTitle = fetchTitle;
        return s;
    }

    /** 起一个假 MC 服务器，按 SLP 回一次 status；json 传 null 表示回垃圾数据。 */
    private int fakeServer(String json) throws IOException {
        mc = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            try (Socket socket = mc.accept()) {
                connections.incrementAndGet();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                Probe.readVarInt(in);                                // 包长
                Probe.readVarInt(in);                                // packet id
                Probe.readVarInt(in);                                // 协议版本
                in.readNBytes(Probe.readVarInt(in));                 // server address
                in.readShort();                                      // port
                Probe.readVarInt(in);                                // next state
                Probe.readVarInt(in);                                // status request: 包长
                Probe.readVarInt(in);                                // status request: id
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                if (json == null) {
                    out.writeBytes("这不是 Minecraft");
                } else {
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    DataOutputStream payload = new DataOutputStream(body);
                    payload.writeByte(0x00);
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    Probe.writeVarInt(payload, bytes.length);
                    payload.write(bytes);
                    Probe.writeVarInt(out, body.size());
                    body.writeTo(out);
                }
                out.flush();
            } catch (Exception ignored) {
                // 测试里连接断了无所谓
            }
        });
        thread.setDaemon(true);
        thread.start();
        return mc.getLocalPort();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static String statusJson() {
        return "{\"version\":{\"name\":\"1.20.1\",\"protocol\":763},\"players\":{\"max\":20,\"online\":3},"
                + "\"description\":\"§a小明服务器 §7欢迎你\"}";
    }

    @Test void 真服务器能被认出来() throws Exception {
        int port = fakeServer(statusJson());
        Probe.Result result = new Probe(true).lookup("127.0.0.1", port, settings(false));
        assertTrue(result.probed());
        assertTrue(result.minecraft());
        assertTrue(result.text().contains("1.20.1"), result.text());
        assertTrue(result.text().contains("3/20"), result.text());
        assertTrue(result.text().contains("小明服务器"), result.text());   // 颜色代码要被清掉
        assertFalse(result.text().contains("§"));
    }

    @Test void 状态被屏蔽但端口有应答也算服务器() throws Exception {
        int port = fakeServer(null);   // 连得上，但回的是垃圾数据
        Probe.Result result = new Probe(true).lookup("127.0.0.1", port, settings(false));
        assertTrue(result.probed());
        assertFalse(result.minecraft());
        assertTrue(result.portOpen(), "端口有应答但没回状态 → 算作服务器（enable-status=false / 屏蔽探测）");
        assertTrue(result.text().contains("有服务在应答"), result.text());
        assertTrue(ChatGuard.hardHit(settings(true), result), "这种也要计入封禁次数");
    }

    @Test void 只接连接不说话的不算服务器() throws Exception {
        // 高防/云防火墙常见行为：把 TCP 连接接住，然后一声不吭
        ServerSocket silent = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            try (Socket socket = silent.accept()) {
                Thread.sleep(5000);   // 接住就不管了
            } catch (Exception ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        Settings settings = settings(false);
        settings.probeTimeoutMs = 1200;
        Probe.Result result = new Probe(true).lookup("127.0.0.1", silent.getLocalPort(), settings);
        assertTrue(result.probed());
        assertFalse(result.minecraft());
        assertFalse(result.portOpen(), "只是接了连接、没说话 → 不能算确认（否则高防后面的假端口也会被当成服务器）");
        silent.close();
    }

    @Test void 端口没人听时给的是没响应() throws Exception {
        int port = freePort();
        Probe.Result result = new Probe(true).lookup("127.0.0.1", port, settings(false));
        assertTrue(result.probed());
        assertFalse(result.minecraft());
        assertFalse(result.portOpen());
    }

    @Test void 同一个地址走缓存不重复探测() throws Exception {
        int port = fakeServer(statusJson());
        Probe probe = new Probe(true);
        Settings settings = settings(false);
        assertTrue(probe.lookup("127.0.0.1", port, settings).minecraft());
        assertTrue(probe.lookup("127.0.0.1", port, settings).minecraft());
        assertEquals(1, connections.get());
        assertEquals(1, probe.cached());
        probe.clearCache();
        assertEquals(0, probe.cached());
    }

    @Test void 私网地址不探测() throws Exception {
        int port = fakeServer(statusJson());
        Probe.Result result = new Probe().lookup("127.0.0.1", port, settings(false));   // 线上构造器：拒绝私网
        assertFalse(result.probed());
        assertEquals(0, connections.get());
    }

    @Test void 关掉探测时什么都不做() throws Exception {
        Settings settings = settings(false);
        settings.probeEnabled = false;
        assertFalse(new Probe().lookup("example.com", 25565, settings).probed());
    }

    @Test void 网页标题作为线索() throws Exception {
        web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        web.createContext("/", exchange -> {
            byte[] body = "<html><head><title>小明我的世界服务器 - 开服啦</title></head></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        web.start();
        String title = Probe.fetchTitle(new URL("http://127.0.0.1:" + web.getAddress().getPort() + "/"), System.currentTimeMillis() + 3000);
        assertNotNull(title);
        assertTrue(title.contains("我的世界服务器"), title);
    }

    @Test void srv记录解析() {
        Probe.Srv srv = Probe.parseSrv("0 5 25567 play.example.net.");
        assertNotNull(srv);
        assertEquals(25567, srv.port());
        assertEquals("play.example.net", srv.target());
        assertEquals(0, srv.priority());
        assertEquals(5, srv.weight());
        assertNull(Probe.parseSrv("这里没有 SRV"));
        assertNull(Probe.parseSrv("0 5 99999 play.example.net."));   // 端口越界
        assertNull(Probe.parseSrv(null));
    }

    @Test void 从命中文本里取出主机与端口() {
        assertEquals("1.2.3.4", Probe.hostOf("12345678 (1.2.3.4)"));
        assertEquals("mc.example.com", Probe.hostOf("MC.Example.com:25567"));
        assertEquals("play.example.net", Probe.hostOf("https://play.example.net/join"));
        assertNull(Probe.hostOf("  "));
        assertEquals(25567, Probe.portOf("来玩 mc.example.com:25567", "mc.example.com"));
        assertEquals(25565, Probe.portOf("来玩 mc.example.com", "mc.example.com"));
        assertEquals(47514, Probe.portOf("来玩 mc.example.com: 47514", "mc.example.com"));        // 冒号后有空格
        assertEquals(47514, Probe.portOf("来玩 mc.example.com 端口 47514", "mc.example.com"));    // 中文写法
        assertEquals(47514, Probe.portOf("玩 mc.example.com 端口号:47514 很好玩", "mc.example.com"));
        assertEquals(47514, Probe.portOf("mc.example.com 反向 47514", "mc.example.com"));
        assertEquals(25565, Probe.portOf("mc.example.com 端口 99999999", "mc.example.com"));      // 端口越界 → 用默认
        assertEquals(25565, Probe.portOf("先加群 123456 再来看 mc.example.com", "mc.example.com")); // 主机后面的数字才认
    }

    @Test void 只探公网地址() throws Exception {
        assertFalse(Probe.publicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(Probe.publicAddress(InetAddress.getByName("10.1.2.3")));
        assertFalse(Probe.publicAddress(InetAddress.getByName("172.16.9.9")));
        assertFalse(Probe.publicAddress(InetAddress.getByName("192.168.1.1")));
        assertFalse(Probe.publicAddress(InetAddress.getByName("169.254.10.10")));
        assertFalse(Probe.publicAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(Probe.publicAddress(InetAddress.getByName("::1")));
        assertFalse(Probe.publicAddress(InetAddress.getByName("fc00::1")));
        assertTrue(Probe.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(Probe.publicAddress(InetAddress.getByName("1.1.1.1")));
    }
}
