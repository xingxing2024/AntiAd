# AntiAd

支持 **Paper / Purpur / Folia 1.21.1 ~ 26.x** 的聊天与行为管制插件（反服务器广告）：一份 jar 通吃，Java 21 或更新即可（已在 Java 21 + Purpur 1.21.1、Java 25 + Purpur 26.3 实测）。

> **完整手册见 [`wiki/`](wiki/Home.md)**（同一份内容也发布在 [GitHub Wiki](https://github.com/xingxing2024/AntiAd/wiki)）：安装与升级、[配置详解](wiki/配置详解.md)、[判定流程](wiki/判定流程.md)、[行为分类与老玩家](wiki/行为分类与老玩家.md)、[AI 审核](wiki/AI审核.md)、[命令与权限](wiki/命令与权限.md)、[记录与查询](wiki/记录与查询.md)、[兼容与 Folia](wiki/兼容与Folia.md)、[常见问题](wiki/常见问题.md)、[开发与发布](wiki/开发与发布.md)。
> 这份 README 是快速上手；`FEATURES.md` 是功能清单；`wiki/` 是逐项说明。

只盯**受管制玩家**的聊天。命中的消息**不删**：消息照常发出，但只有发送者自己看得到，其他玩家都不知道；管理员会收到通报。命中累计到阈值后封禁 **IP 与账号**。

## 行为

1. 检查范围（**老玩家判定**）：累计游玩时长 > `veteran.play-hours`（默认 24 小时）+ 行为分 ≥ `veteran.min-score`（25）+ 近 `veteran.clean-hours`（24）小时零违规，三条都满足才算老玩家 → 完全免检（不查、不扣分、不统计、不会被封）。其余玩家一律受管制，其中首次进服后 `settings.new-player-minutes`（默认 30）分钟内**强制检查**；检查强度由行为分类决定（"正常"分类只跑规则、不花 AI 额度）。
2. 判定顺序：先跑确定性规则（IP / 数字 IP / 域名）；命中就先处理后继续累计。没命中时，敏感词命中、含预筛关键词或链接样式的消息，连同**该玩家最近的发言、进服时长、窗口内已命中次数、命中的敏感词**一起交给 AI 判断（敏感词只是线索，判不判广告由 AI 定）。
3. 命中后的表现：
   - 规则命中：消息立即只给发送者看，其他玩家收不到；管理员收到通报。
   - AI 命中：消息先被**扣住**（发送者也要等 AI 结论，默认超时 2 秒）；AI 说不是广告就照原样放行给所有人，说是广告就只回给发送者并按命中处理。
   - 玩家本人**收不到任何提示**（不警告、不通知）。
4. 累计与封禁：同一玩家在 `trigger.window-seconds`（默认 60）秒内累计到 `trigger.hits`（默认 3）次，第 3 次封禁 IP + 账号、踢出，封禁理由与踢出提示里带 `{contact}`（默认「服务器交流群」）。
5. `/me` 这类广播命令没有“只给本人看”的通道，命中广告时直接拦下，其余照常按累计规则处理（不走 AI）。
6. 误伤可控：`antiad.bypass`（默认 OP）完全跳过；`whitelist.domains` 写你自己服务器域名（默认 `example.com` 是示例，记得改成自己的），`whitelist.ips` 写入口 IP 与你自己用的号码（例如自己的 QQ 群号，写进去就不会被当成数字 IP）；内置**受信任站点表**（B站、YouTube、GitHub、mcmod、Modrinth、CurseForge、MC 官方与常见论坛、网盘等，见 jar 内 `trusted-domains.txt`）里的链接一律不算广告，不隐藏、不累计、不封禁。

## 检测内容（宁漏不误伤）

- IPv4：`a.b.c.d` 每段 ≤255；内网 / 回环 / 100.64 段、带前导零的写法不算。
- 数字 IP：`3107365173` 这种十进制整数形式（8~10 位，转成公网 IPv4 才算；`20261012` 这类日期不算）。可用 `settings.check-numeric-ip: false` 关掉。
- 域名：后缀在内置的 379 个服务器常用后缀里（全部两字母国别后缀 + 常见通用后缀，含 `ren`、`icu`、`wang`；品牌后缀与 `zip`/`mov` 这类文件扩展名不收）。可用 `tlds` 覆盖，写 `tlds: []` 表示只检测 IP。
- 敏感词：`banned-words`。AI 开启时命中只作为线索送 AI 判断（不会因为「卖号角」这种包含直接隐藏）；AI 未启用时退回旧行为：命中即只给发送者看，不处罚。大小写 / 全角 / 字间空格不敏感。
- 绕过写法归一化：全角转半角、颜色代码插字（`9.9.§c9.§r9`）、字间空格（`9 8 . 1 3 9 . 1 0 1 . 1`、`m c . c o m`）、`(dot)` / `[DOT]` / `点` / `。` / `·`。
- 白名单域名同时覆盖子域名（写 `example.com`，`play.example.com` 也放行）。
- 不检测 IPv6：聊天里出现 IPv6 字符串基本只会误伤。

## 地址情报（判断"这到底是不是 MC 服务器"）

命中地址、或消息要送 AI 时，插件会顺带做一次技术探测，回答"这个地址是不是一台真的 Minecraft 服务器"，**免费、不调 AI**：

- **SRV 判定**：查 `_minecraft._tcp.<域名>`。这个记录只有 MC 客户端会查，存在基本就是 MC 服；并按其指向的主机/端口去连 —— 所以"域名 + SRV 指向 25567 端口"的服务器也能被认出来。
- **协议探测**：按 Minecraft 服务器列表协议 ping 一次，真服务器会回版本 / 在线人数 / MOTD。
- **网页标题**：没确认是服务器时，抓一眼首页 `<title>`，看是不是「XX 我的世界服务器」站点。

结论有三个用途：① 作为 `{probe}` 线索进 AI 提示词；② 命中地址时给管理员补一条通报；③ **决定这次命中算不算封禁次数** —— 有 SRV 记录、状态有回应、或"端口有服务在应答只是不回状态"（`enable-status=false`／屏蔽探测，玩家照样能进）才算确认；随手打一个不存在的网址只会被隐藏 + 轻扣分，不会累计到封号（`probe.confirm-required: false` 可关掉这个确认）。

端口不一定是 25565：消息里写了就按消息里的探（`host:47514`、`host: 47514`、`host 端口 47514` 都认），没写就查 `_minecraft._tcp` SRV 记录并按它指向的端口探，都没有才用 25565。只探**公网地址**（回环/内网/CGNAT/组播一律拒绝，不当内网扫描器），全异步、有缓存、有超时预算，可 `probe.enabled: false` 关掉。

## AI 审核（默认接智谱 GLM-4-Flash，免费）

1. 去 `https://open.bigmodel.cn` 注册，创建 API Key（GLM-4-Flash 官方标称免费）。
2. 填进 `plugins/AntiAd/config.yml` 的 `ai.api-key`，`/antiad reload` 生效。`api-key` 留空 = AI 自动不启用，聊天完全不受影响。
3. 也可以换成任意 OpenAI 兼容接口：本地 Ollama / LM Studio（完全免费离线）、DeepSeek（按量付费）、硅基流动、OpenRouter `:free` —— 现成地址都写在配置注释里。

- 速度优先：默认 `timeout-seconds: 2`，AI 调用走插件自己的线程池（`ai.max-concurrent` 默认 3，防止免费额度被限流，改后需重启）；超时、限流（429）、报错一律按 `fail-open` 放行。用 DeepSeek 这类首字延迟 1~3 秒的服务时，把 `timeout-seconds` 调到 4~6，否则会频繁超时=放行。
- 免费额度高峰会变慢或限流，这时命中判定等于“放行”，IP/域名/违禁词规则仍在兜底 —— 宁可漏也不误伤。
- 只审规则没命中的消息，且只审新玩家窗口内的消息：老玩家、已被规则命中的消息不会消耗 token。
- `prompt` 可改，占位符：`{self}`、`{minutes}`、`{hits}`、`{words}`（命中的敏感词线索）、`{probe}`（地址情报）、`{context}`、`{message}`；模型必须回 `{"ad":true/false,"reason":"..."}`（解析时按这个形状找，回显提示词不算判定）。
- **注意**：玩家的聊天内容会发送给智谱的接口，请自行确认隐私与合规；模型被提示词注入时可能误判（单条误判只隐藏消息，连续 3 次才封禁）。

### 怎么判断 AI 审核到底有没有在跑

1. `/antiad check <玩家>` 最后一行：`AI 审核：已开启（glm-4-flash）` 或 `未开启（没填 api-key）`。
2. `/antiad ai 快来玩别的服 mc.other.top`：真的发一次请求，回报 `判定：是广告 / 不是广告（理由、耗时）`；key 错、限流、超时都会直接显示。
3. 启动日志里有一条 `AI 审核已开启：glm-4-flash。` 或 `AI 审核未开启。`。
4. 实际发生过的判定看 `/antiad records`（类型显示 `AI`，附模型给的理由）和控制台通报。
5. 控制台告警含义：`AI 审核返回 HTTP 401` = key 不对；`HTTP 429` = 免费额度限流；`无法解析` = 模型没按 JSON 回答；`AI 审核失败（HttpTimeoutException）` = 超时。这几种默认都按 `fail-open` 放行，IP/域名/违禁词规则仍然兜底。

### 怎么更便宜

按性价比排（前两条已经是默认行为）：

1. **疑似预筛（默认开）**：只有消息含 `ai.trigger-words` 里的词、或含链接样式（`http` / `www.` / `@` / 任意点号域名，不管后缀认不认识）才送 AI。新玩家前半小时大多是「你好」「怎么玩」「好卡」，这一步通常省掉 80% 以上的调用。想全量送（最不容易漏、最贵）就写 `trigger-words: []`。
2. **精简提示词**：默认提示词已从约 330 字压到约 200 字，输入 tokens 省四成左右。`ai.prompt` 越短越便宜，漏判风险也越高。
3. **上下文条数**：`ai.context-messages: 5 → 2`，每次再省 50~150 输入 tokens。
4. **窗口**：`new-player-minutes: 30 → 10`，广告基本集中在进服头几分钟，调用量近似减半。
5. **换模型**：GLM-4-Flash、本地 Ollama 免费；DeepSeek 有夜间错峰折扣（北京时间 00:30–08:30），不改配置自动生效。
6. **已内置的其他省钱点**：纯数字/符号不送、规则（IP/域名/违禁词）已命中不送、超时不重试、老玩家完全不送、提示词前缀固定可命中 DeepSeek 的前缀缓存。

预筛的代价：既不含关键词也没有链接的广告（比如「我那个服挺好玩的，人超多」）会被漏掉。要堵这类就补 `trigger-words`，或者干脆设成 `[]` 全量交给 AI。

## 玩家行为分类（决定检查强度）

按玩家**行为**累计打分，不按时间算；分数实时更新（内存里算、每分钟与退出时落库）。只统计受管制的玩家，**老玩家完全不参与**（照旧不检查、不扣分、不会被封）。

- 新玩家默认 `behavior.default-score`（20）分。
- 正常游玩加分：破坏方块、用指令（`blocks-per-point: 50`、`commands-per-point: 10`），合计最多 `max-activity-score`（20）分；在线时长只展示、不计分。
- 违规扣分：判定为广告一次 `-3`（`ad-hit-penalty`），敏感词命中 `-1`（AI 关闭时才直接算命中），进来就退什么都没干 `-1`，反复进出（第 4 次起）`-1`。
- 分类与检查强度：

| 分数 | 分类 | 检查强度 |
|---|---|---|
| ≥ 30 | 正常 | 只跑 IP/域名规则，不再花 AI 额度 |
| 20~29 | 观望 | 默认：按 `ai.trigger-words` 预筛后再送 AI |
| < 20 | 重点关注 | 所有带文字的消息都送 AI；封禁阈值降为 2 次 |
| ≤ 5，或被本插件封过 | 广告号 | 阈值 1 次，下一次命中直接封（封禁记录永久） |

查看：`/antiad check <玩家>`（分数、分类、累计方块/指令/进服、当前阈值、是否被封过）、`/antiad behavior [数量]`（按最近活跃列出行为画像）。

## 匿名统计（bStats）

插件内置 [bStats](https://bstats.org/plugin/bukkit/Anti-Ad/34706)（插件 ID 34706），只上报：插件版本、服务端软件与版本、Java 版本、在线人数、正版模式、系统与 CPU 核数、服务端所在地区（bStats 按请求 IP 判定）。**不含**玩家名、IP、聊天内容或记录库数据。

不想要统计：编辑 `plugins/bStats/config.yml` 把 `enabled` 改成 `false`（bStats 全局配置，对所有用了 bStats 的插件生效）。

## 原创与依赖

- 本项目为**原创实现**，未复制任何其他插件的代码；文档中提到的 TrChat、fakeplayer、Vault、PlaceholderAPI、ProtocolLib、bStats 等仅为兼容性说明或测试环境，与本项目无隶属或衍生关系。
- 第三方依赖（随包分发，遵循各自许可）：
  - [bStats](https://bstats.org)（org.bstats，MIT，已重定位到 `cn.server.antiad.bstats`）
- 运行时依赖：SQLite 驱动 [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)（org.xerial，Apache-2.0）**不打进 jar**，由插件自带的 Paper 加载器 `AntiAdLoader` 在首次启动时从**阿里云公共仓库**下载（官方源只作兜底），详见 [安装](wiki/安装与升级.md)。
- 内置数据：`tlds.txt` 取自已公开的 IANA 根区后缀（另做了筛选），`trusted-domains.txt` 为自行整理。

## 记录

命中记录写入 SQLite：`plugins/AntiAd/antiad.db`，包含时间、玩家、IP、类型（广告 / AI / 违禁词 / 封禁）、命中内容与整条消息。重启后 60 秒窗口内的累计次数从这里恢复，不会因重启清零；保留 `storage.keep-days` 天（默认 30，0 = 永久）。

同一张库里还按天累计 AI 用量（调用次数、输入/输出 tokens），`/antiad cost` 按 `ai.price` 的单价估算费用。单价要按你实际用的服务改：DeepSeek `deepseek-chat` 常见档位是输入 2 / 输出 8（元每百万 tokens），智谱 GLM-4-Flash 免费可填 0。命中记录与用量都会被 `keep-days` 一起清理。

## 命令

`/antiad`（别名 `/adban`），需要 `antiad.admin`：

- `/antiad check <玩家>` —— 该玩家现在是否在检查范围内：老玩家三条判定、进服多久、是否拥有绕过权限、当前已累计几次、记录库总条数与封禁次数、在线时的 IP 与封禁状态、行为分与分类、AI 是否开启
- `/antiad ban <玩家> [时长 30s/10m/2h/7d] [理由…]` —— 手动封禁 **IP + 账号**（时长留空 = 永久），在线时踢出，写记录并永久标记为广告号
- `/antiad unban <玩家>` —— 解封账号 + 该玩家最近记录里的 IP
- `/antiad ai <文本>` —— 真的调用一次 AI，回报判定、理由、耗时；接口/Key 有问题时会直接显示错误（如 HTTP 401、429、无法解析）
- `/antiad cost` —— 每日 AI 调用次数、输入/输出 tokens 与估算费用（近 7 天逐日 + 近 30 天 + 建库以来累计）
- `/antiad records [玩家]` —— 查看最近 10 条命中记录（含隐藏的原文与 AI 判定理由）
- `/antiad test <文本>` —— 只试跑规则检测（归一化、广告命中、违禁词命中），不处理、不记录、不调 AI
- `/antiad reload` —— 重载配置（`ai.max-concurrent` 需重启）
- `/antiad help`

**短写**：根命令也可以写成 `/ad`（或 `/adban`），子命令都有简写 —— `/ad c`=check、`b`=ban、`ub`=unban、`bh`=behavior、`a`=ai、`co`=cost、`r`=records、`t`=test、`rl`=reload、`h`（或 `?`）=help。例如 `/ad c Steve`、`/ad b Steve 2h 发广告`。

解封：`/pardon-ip <地址>` + `/pardon <玩家名>`（封禁名单就是 `banned-ips.json` 与 `banned-players.json`）。

## 权限

`antiad.admin`（管理命令，默认 OP）、`antiad.notify`（接收命中通报，默认 OP）、`antiad.bypass`（跳过检查，默认 OP）。

## 安装

1. 服务器 Java **21 或更新**（1.21.x 服务端用 Java 21，26.x 用 Java 25 —— 插件按 Java 21 编译，两边都能跑）。
2. 把 `target/AntiAd-1.0.8.jar`（约 110 KB）放进 `plugins`。
3. 首次启动会自动从**阿里云镜像**下载 SQLite 驱动到服务端根目录的 `libraries/org/xerial/sqlite-jdbc/`（想换源：启动参数 `-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=<仓库>`；**无外网**就手动下 `https://maven.aliyun.com/repository/public/org/xerial/sqlite-jdbc/3.51.0.0/sqlite-jdbc-3.51.0.0.jar` 放进那个目录）。
4. 启动一次后编辑 `plugins/AntiAd/config.yml`（把 `whitelist.domains` / `whitelist.ips` 改成自己服务器的域名、入口 IP、自己的 QQ 群号，`punish.contact` 改成你的解封联系方式，要用 AI 再填 `ai.api-key`），然后 `/antiad reload`。

## 构建与测试

`mvn package`（**对着 Paper 1.21.1 API 构建、编译目标 Java 21**，所以一份 jar 能跑在 1.21.1 ~ 26.x）。**支持 Paper / Purpur / Folia**（`folia-supported: true`，全部改用区域化调度，Paper 上自动落到主线程）。`mvn test` 覆盖检测器（命中、归一化、白名单、受信任平台、误伤用例、违禁词、数字 IP、内置后缀、跨消息拼接、结构调整）、行为分类与老玩家判定、计数、SQLite（含重启恢复）、地址情报探测（本机假 MC 服务器验协议、假 HTTP 服务器验标题、缓存与私网拦截）、AI 客户端（本地假接口验证请求体、解析、超时与 fail-open）。

## 已知限制

- AI 判定是并发的：同一玩家连发多条时，这些消息到达其他人那里的顺序可能交错（`shortcut` 在源码里标了升级点）。
- AI 审核期间消息被扣住，聊天签名/举报信息会丢失，其他玩家看到的时间等于 AI 响应时间。
- 记录写入在主线程（只在命中时发生，一次几毫秒）。
- 违禁词用包含匹配，「卖号角」这类也会命中（只会隐藏，不处罚）。
