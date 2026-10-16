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
    /** probed = 是否真的探测过；minecraft = 确认是 MC 服务器；portOpen = 25565 连得上但没回状态（强线索）；text = 一行中文说明。 */
    public record Result(boolean probed, boolean minecraft, boolean portOpen, String text) {
        static final Result NONE = new Result(false, false, false, "");
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
        boolean portOpen = false;
        int pingPort = port;
        String pingHost = host;

        // 网页标题和协议探测并行跑：地址是网站时 SLP 会一直等到超时，串行加起来太慢。
        // shortcut: 每条未缓存的探测多开一个线程（有 10 分钟缓存、探测频率低，不值得为它再建线程池）。
        CompletableFuture<String> titleTask = fetchTitle ? CompletableFuture.supplyAsync(() -> title(host, deadline)) : null;

        Srv srv = srv(host, deadline);
        if (srv != null) {
            // _minecraft._tcp 记录只有 MC 客户端会查，存在基本就等于「这里开着 MC 服」。
            minecraft = true;
            // 玩家自己写了端口时，客户端会直接用那个端口连（不看 SRV），所以按消息里的端口探。
            boolean explicitPort = port != 25565;
            pingHost = explicitPort ? host : srv.target();
            pingPort = explicitPort ? port : srv.port();
            notes.append("SRV _minecraft._tcp → ").append(srv.target()).append(':').append(srv.port());
            if (explicitPort) notes.append("（消息里写了端口 ").append(port).append("，按它连）");
            notes.append("；");
        }

        Status status = status(pingHost, pingPort, deadline);
        if (status != null && status.portOpen() && status.version().isEmpty() && status.max() == 0 && status.motd().isEmpty()) {
            // 端口连得上但不回状态：多数是 enable-status=false，也可能只挡了探测。25565 上不会有别的东西，算作服务器。
            portOpen = true;
            notes.append(pingPort).append(" 端口上确实有服务在应答，但没回 Minecraft 状态 —— 对方多半关了 enable-status 或屏蔽了探测请求，仍然算作 Minecraft 服务器；");
        } else if (status != null) {
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
        if (!minecraft && !portOpen && titleTask != null) {
            String title = join(titleTask, deadline);
            if (title != null && !title.isBlank()) {
                notes.append("首页标题：").append(title);
                if (!SERVER_WORDS.matcher(title).find()) notes.append("（不像服务器站点）");
                notes.append("；");
            }
        }

        String text = notes.toString();
        if (text.isEmpty()) text = "没有可用信息；";
        return new Result(true, minecraft, portOpen, shorten(text));
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

    record Status(String version, int online, int max, String motd, boolean portOpen) {
        /** 连上了端口、但没拿到服务器状态（开了 enable-status=false 或屏蔽了探测）。 */
        static final Status OPEN_ONLY = new Status("", 0, 0, "", true);
    }

    /** 握手 + status 请求。连不上返回 null；连上了但没状态（被屏蔽）返回 {@link Status#OPEN_ONLY}。 */
    static Status status(String host, int port, long deadline) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), (int) remaining(deadline, 1200));
            socket.setSoTimeout((int) remaining(deadline, 1200));
        } catch (Exception e) {
            close(socket);
            return null;   // 端口连不上：没人听、被防火墙挡了、或者根本不在线
        }
        try {
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
            // 先看对方是不是"说了话"：真服务器即使关了 enable-status 也会回一个包；
            // 而高防/云防火墙常常把所有端口的 TCP 连接都接住、然后一声不吭 —— 那种不算服务器。
            int first = firstByte(in);
            if (first < 0) return null;                       // 超时或直接断开：判定为端口无响应
            int length = first & 0x7F, shift = 7;
            while ((first & 0x80) != 0) {
                first = in.read();
                if (first < 0) return Status.OPEN_ONLY;
                length |= (first & 0x7F) << shift;
                shift += 7;
                if (shift > 28) return Status.OPEN_ONLY;
            }
            if (length <= 0 || length > MAX_STATUS) return Status.OPEN_ONLY;
            byte[] payload = new byte[length];
            in.readFully(payload);
            DataInputStream packet = new DataInputStream(new ByteArrayInputStream(payload));
            if (readVarInt(packet) != 0x00) return Status.OPEN_ONLY;
            String json = readString(packet);
            if (!json.contains("\"version\"") || !(json.contains("\"players\"") || json.contains("\"description\""))) return Status.OPEN_ONLY;
            return new Status(textOf(VERSION, json), number(ONLINE, json), number(MAX, json), clean(textOf(MOTD, json)), false);
        } catch (Exception e) {
            // 有响应但读不下去（半包、乱码）：端口上确实有东西在说话，当作被屏蔽了状态的服。
            return Status.OPEN_ONLY;
        } finally {
            close(socket);
        }
    }

    /** 读第一个字节；超时或对端直接断开都返回 -1（表示对方什么都没说）。 */
    private static int firstByte(DataInputStream in) {
        try {
            return in.read();
        } catch (java.net.SocketTimeoutException e) {
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
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

    /** 紧跟主机后面的端口：「mc.example.com:47514」「host: 47514」都认。 */
    private static final Pattern PORT_AFTER = Pattern.compile("\\s*:\\s*(\\d{1,5})");
    /** 中文写法：「端口 47514」「端口号:47514」「reverse 47514」。只在紧跟主机的 40 个字内找。 */
    private static final Pattern PORT_WORD = Pattern.compile("(?i)(?:端口号?|反向|port|reverse)\\s*[:：]?\\s*(\\d{2,5})");

    /**
     * 消息里紧跟在该主机后面的端口。支持 `host:47514`、`host: 47514`、`host 端口 47514` 这几种写法；
     * 没写端口就返回默认 25565（这时如果服务端配了 SRV，{@link #investigate} 会用 SRV 指向的端口）。
     */
    public static int portOf(String text, String host) {
        if (text == null || host == null || host.isBlank()) return 25565;
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerHost = host.toLowerCase(Locale.ROOT);
        int fallback = 0;
        for (int at = lowerText.indexOf(lowerHost); at >= 0; at = lowerText.indexOf(lowerHost, at + 1)) {
            String tail = text.substring(at + host.length());
            Matcher plain = PORT_AFTER.matcher(tail);
            if (plain.lookingAt()) {
                int port = parsePort(plain.group(1));
                if (port > 0) return port;
            }
            if (fallback == 0) {
                String near = text.substring(at, Math.min(text.length(), at + host.length() + 40));
                Matcher named = PORT_WORD.matcher(near);
                if (named.find()) fallback = parsePort(named.group(1));
            }
        }
        return fallback > 0 ? fallback : 25565;
    }

    private static int parsePort(String digits) {
        try {
            int port = Integer.parseInt(digits);
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
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
