package cn.server.antiad;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 地址情报探测：判断一个地址到底是不是真的 Minecraft 服务器，结论只当线索用（给 AI 判断、给管理员通报）。
 *
 * <p>1) <b>SRV 判定</b>：查 {@code _minecraft._tcp.<域名>}。这个记录只有 MC 客户端会用，存在基本就说明这里开着 MC 服，
 * 而且必须按记录里的目标主机/端口去 ping —— 否则「域名 + SRV 指向别的端口」的服务器会被漏掉。
 * <p>2) <b>协议探测</b>：按 Minecraft 服务器列表协议（SLP）ping 一次，真服务器会回版本 / 在线人数 / MOTD。
 * <p>3) <b>网页标题</b>：没确认是服务器时，抓一眼 http(s) 首页的 &lt;title&gt;，看是不是「XX 我的世界服务器」这类站点。
 *
 * <p>安全：只探公网地址（回环 / 内网 / 链路本地 / CGNAT / 组播一律拒绝），重定向逐跳检查，
 * 整次探测有总预算（{@code probe.timeout-ms}），同一地址有缓存（{@code probe.cache-seconds}）。
 * 所有方法都会阻塞，只能在异步线程调用。
 */
public final class Probe {
    /** probed = 是否真的探测过；minecraft = 是否确认是 MC 服务器；text = 一行中文说明。 */
    public record Result(boolean probed, boolean minecraft, String text) {
        static final Result NONE = new Result(false, false, "");
    }

    record Srv(int priority, int weight, String target, int port) {}

    private record Entry(Result result, long expires) {}

    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\\{[^{}]{0,64}?\"name\"\\s*:\\s*\"([^\"]{0,32})\"");
    private static final Pattern ONLINE = Pattern.compile("\"online\"\\s*:\\s*(\\d{1,7})");
    private static final Pattern MAX = Pattern.compile("\"max\"\\s*:\\s*(\\d{1,7})");
    private static final Pattern MOTD = Pattern.compile("\"description\"\\s*:\\s*\"([^\"]{0,120})\"");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.{0,300}?)</title>");
    private static final Pattern COLORS = Pattern.compile("(?i)[\u00a7&][0-9a-fk-or]");
    /** 页面里提到这些词，说明它很可能就是个 MC 服/开服站点。 */
    private static final Pattern SERVER_WORDS = Pattern.compile("(?i)(我的世界|minecraft|开服|服务端|腐竹|服主|java版|基岩版|整合包|服务器|客户端|je|be)");
    private static final int MAX_STATUS = 64 * 1024;
    private static final int MAX_PAGE = 64 * 1024;
    private static final int MAX_TEXT = 220;
    /** 缓存上限：到达就整个清掉（条目都是几十字节，没必要做 LRU）。 */
    private static final int MAX_CACHE = 512;
    private static final String USER_AGENT = "AntiAd/1.0 (server address check)";

    private final Map<String, Entry> cache = new HashMap<>();
    /** 仅单测用：允许探测 127.0.0.1 这种内网地址（对着本机假服务器测协议）。 */
    private final boolean allowPrivate;

    public Probe() {
        this(false);
    }

    Probe(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /** 探测入口（异步线程调用）：带缓存，关掉探测时返回「没探过」。 */
    public Result lookup(String host, int port, Settings settings) {
        if (!settings.probeEnabled || host == null || host.isBlank()) return Result.NONE;
        String key = host.toLowerCase(Locale.ROOT) + ":" + port;
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry != null && entry.expires() > now) return entry.result();
        }
        Result result = investigate(host, port, settings);
        synchronized (cache) {
            if (cache.size() >= MAX_CACHE) cache.clear();
            cache.put(key, new Entry(result, now + settings.probeCacheSeconds * 1000L));
        }
        return result;
    }

    private Result investigate(String host, int port, Settings settings) {
        return investigate(host, port, Math.max(200, settings.probeTimeoutMs), settings.probeFetchTitle);
    }

    /** 真正的探测逻辑（包内可见，方便对着真实服务器做一次性自检，也方便单测）。 */
    Result investigate(String host, int port, int timeoutMs, boolean fetchTitle) {
        long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs);
        if (!allowPrivate && !publicHost(host)) return Result.NONE;

        StringBuilder notes = new StringBuilder();
        boolean minecraft = false;
        int pingPort = port;
        String pingHost = host;

        // 网页标题和协议探测并行跑：地址是网站时 SLP 会一直等到超时，串行加起来太慢。
        // shortcut: 每条未缓存的探测多开一个线程（有 10 分钟缓存、探测频率低，不值得为它再建线程池）。
        CompletableFuture<String> titleTask = fetchTitle ? CompletableFuture.supplyAsync(() -> title(host, deadline)) : null;

        Srv srv = srv(host, deadline);
        if (srv != null) {
            // _minecraft._tcp 记录只有 MC 客户端会查，存在基本就等于「这里开着 MC 服」。
            minecraft = true;
            pingHost = srv.target();
            pingPort = srv.port();
            notes.append("SRV _minecraft._tcp → ").append(srv.target()).append(':').append(srv.port()).append("；");
        }

        Status status = status(pingHost, pingPort, deadline);
        if (status != null) {
            minecraft = true;
            notes.append(pingPort).append(" 端口回应了 Minecraft 状态");
            if (!status.version().isEmpty()) notes.append("（版本 ").append(status.version());
            if (status.max() > 0) notes.append(status.version().isEmpty() ? "（" : "，").append("在线 ").append(status.online()).append('/').append(status.max());
            if (!status.version().isEmpty() || status.max() > 0) notes.append("）");
            if (!status.motd().isEmpty()) notes.append("，MOTD：").append(status.motd());
            notes.append("；");
        } else {
            notes.append(pingPort).append(" 端口无响应；");
        }

        // 已经确认是服务器就不用网页标题了；没确认才把它当线索写进去。
        if (!minecraft && titleTask != null) {
            String title = join(titleTask, deadline);
            if (title != null && !title.isBlank()) {
                notes.append("首页标题：").append(title);
                if (!SERVER_WORDS.matcher(title).find()) notes.append("（不像服务器站点）");
                notes.append("；");
            }
        }

        String text = notes.toString();
        if (text.isEmpty()) text = "没有可用信息；";
        return new Result(true, minecraft, shorten(text));
    }

    // ---------- SRV ----------

    /** 查 {@code _minecraft._tcp.<host>}；没有记录、DNS 不通、JDK 缺 DNS provider 都返回 null。 */
    static Srv srv(String host, long deadline) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(remaining(deadline, 600)));
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            InitialDirContext context = new InitialDirContext(env);
            try {
                Attributes attributes = context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
                Attribute records = attributes.get("SRV");
                if (records == null || records.size() == 0) return null;
                Srv best = null;
                for (int i = 0; i < records.size(); i++) {
                    Srv parsed = parseSrv(String.valueOf(records.get(i)));
                    // 多条记录时取优先级最高（数字最小）的一条，优先级相同取权重大的。
                    if (parsed == null) continue;
                    if (best == null || parsed.priority() < best.priority()
                            || (parsed.priority() == best.priority() && parsed.weight() > best.weight())) best = parsed;
                }
                return best;
            } finally {
                context.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** SRV 记录文本：「0 5 25567 play.example.net.」→ priority/weight/target/port。 */
    static Srv parseSrv(String record) {
        if (record == null) return null;
        String[] parts = record.strip().split("\\s+");
        if (parts.length < 4) return null;
        try {
            int priority = Integer.parseInt(parts[0]);
            int weight = Integer.parseInt(parts[1]);
            int port = Integer.parseInt(parts[2]);
            String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
            if (port < 1 || port > 65535 || target.isEmpty()) return null;
            return new Srv(priority, weight, target, port);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- 服务器列表协议（SLP） ----------

    record Status(String version, int online, int max, String motd) {}

    /** 握手 + status 请求；不是 Minecraft 服务器（或没响应）返回 null。 */
    static Status status(String host, int port, long deadline) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) remaining(deadline, 1200));
            socket.setSoTimeout((int) remaining(deadline, 1200));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(handshake);
            writeVarInt(body, 0x2F);          // 协议版本：服务端不校验内容，随便填
            writeString(body, host);
            body.writeShort(port);
            writeVarInt(body, 1);             // next state = status
            writePacket(out, 0x00, handshake.toByteArray());
            writePacket(out, 0x00, new byte[0]);   // status request
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = readVarInt(in);
            if (length <= 0 || length > MAX_STATUS) return null;
            byte[] payload = new byte[length];
            in.readFully(payload);
            DataInputStream packet = new DataInputStream(new ByteArrayInputStream(payload));
            if (readVarInt(packet) != 0x00) return null;
            String json = readString(packet);
            if (!json.contains("\"version\"") || !(json.contains("\"players\"") || json.contains("\"description\""))) return null;
            return new Status(textOf(VERSION, json), number(ONLINE, json), number(MAX, json), clean(textOf(MOTD, json)));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writePacket(DataOutputStream out, int id, byte[] payload) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, id);
        packet.write(payload);
        writeVarInt(out, buffer.size());
        buffer.writeTo(out);
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int position = 0; position < 5; position++) {
            int read = in.readByte();
            value |= (read & 0x7F) << (position * 7);
            if ((read & 0x80) == 0) return value;
        }
        return -1;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0 || length > MAX_STATUS) return "";
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ---------- 网页标题 ----------

    /** 抓首页 &lt;title&gt;；https 不行再试一次 http。 */
    static String title(String host, long deadline) {
        String title = fetchTitle(newUrl("https://" + host + "/"), deadline);
        return title != null ? title : fetchTitle(newUrl("http://" + host + "/"), deadline);
    }

    /** 抓标题（可重定向，逐跳检查主机是不是公网）；失败返回 null。 */
    static String fetchTitle(URL start, long deadline) {
        if (start == null) return null;
        String url = start.toString();
        for (int hop = 0; hop < 3; hop++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout((int) remaining(deadline, 1200));
                connection.setReadTimeout((int) remaining(deadline, 1200));
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                int code = connection.getResponseCode();
                if (code / 100 == 3) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return null;
                    URL next = new URL(URI.create(url).resolve(location).toString());
                    if (!publicHost(next.getHost())) return null;
                    url = next.toString();
                    continue;
                }
                if (code / 100 != 2) return null;
                String html = read(connection.getInputStream(), MAX_PAGE, charsetOf(connection.getContentType()));
                Matcher matcher = TITLE.matcher(html);
                return matcher.find() ? clean(matcher.group(1)) : null;
            } catch (Exception e) {
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    private static String join(CompletableFuture<String> task, long deadline) {
        try {
            return task.get(Math.max(MIN_STEP_MS, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;   // 抓不到就不写这条线索，不影响协议探测的结论
        }
    }

    private static URL newUrl(String url) {
        try {
            return new URL(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            Matcher matcher = Pattern.compile("(?i)charset=([\\w-]{2,20})").matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1));
                } catch (Exception ignored) {
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String read(InputStream in, int max, Charset charset) throws IOException {
        byte[] bytes = in.readNBytes(max);
        return new String(bytes, charset);
    }

    // ---------- 地址与文本工具 ----------

    /** 从命中的地址字符串里取出可探测的主机名：「12345678 (1.2.3.4)」「mc.example.com:25565」都能处理。 */
    public static String hostOf(String match) {
        if (match == null) return null;
        String value = match.strip();
        int open = value.indexOf('(');
        if (open >= 0) {
            int close = value.indexOf(')', open);
            value = close > open ? value.substring(open + 1, close) : value.substring(open + 1);
        }
        value = value.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int colon = value.lastIndexOf(':');
        if (colon > 0) value = value.substring(0, colon);
        return value.isBlank() ? null : value;
    }

    /** 消息里紧跟在该主机后面的端口（「mc.example.com:25567」），没写就用默认端口。 */
    public static int portOf(String text, String host) {
        if (text != null && host != null && !host.isBlank()) {
            Matcher matcher = Pattern.compile(Pattern.quote(host) + ":(\\d{1,5})", Pattern.CASE_INSENSITIVE).matcher(text);
            if (matcher.find()) {
                try {
                    int port = Integer.parseInt(matcher.group(1));
                    if (port >= 1 && port <= 65535) return port;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 25565;
    }

    /** 逐个解析结果都必须是公网地址（防止拿插件去打内网）。 */
    static boolean publicHost(String host) {
        if (host == null || host.isBlank()) return false;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) if (!publicAddress(address)) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255;
            if (a == 0 || a >= 224) return false;                              // 0.0.0.0/8、组播、保留
            if (a == 100 && b >= 64 && b <= 127) return false;                 // CGNAT 100.64/10
            if (a == 192 && (b == 0 || b == 168)) return false;                // 192.0.0/24、192.0.2/24、内网
            if (a == 198 && (b == 18 || b == 19 || b == 51)) return false;     // 198.18/15、198.51.100/24
            if (a == 203 && b == 0) return false;                              // 203.0.113/24
        } else if ((bytes[0] & 0xFE) == 0xFC) {
            return false;                                                      // IPv6 唯一本地 fc00::/7
        }
        return true;
    }

    /** 每个环节至少给这么多毫秒：否则前一步（SRV 查询、DNS）慢一点就会把预算吃光，后面的探测会误判成"没有服务器"。 */
    private static final long MIN_STEP_MS = 400;

    private static long remaining(long deadline, long max) {
        long left = deadline - System.currentTimeMillis();
        if (left < MIN_STEP_MS) left = MIN_STEP_MS;
        return Math.min(left, max);
    }

    private static String textOf(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int number(Pattern pattern, String text) {
        String value = textOf(pattern, text);
        try {
            return value.isEmpty() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 去掉颜色代码、压平空白、截断。 */
    static String clean(String text) {
        if (text == null) return "";
        String value = COLORS.matcher(text).replaceAll("").replaceAll("\\s+", " ").strip();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static String shorten(String text) {
        String value = text.strip();
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) + "…" : value;
    }

    /** 仅单测用：当前缓存里的条目数。 */
    int cached() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** 仅单测用：清空缓存。 */
    void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
