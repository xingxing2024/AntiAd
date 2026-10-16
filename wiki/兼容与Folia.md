# 兼容与 Folia

## 服务端

| 核心 | 状态 |
|---|---|
| Paper / Purpur 1.21.1 ~ 26.x | ✅ 实测 Purpur 1.21.1（Java 21）与 26.3-2642（Java 25） |
| Folia 1.21.1 ~ 26.x | ✅ 实测 Folia 26.2-7（`folia-supported: true`） |
| Spigot / 1.21.1 以下 | ❌ 用了 Paper 的 `AsyncChatEvent`、`BanListType`、区域化调度等 API |

## 调度模型（Folia 安全）

- 插件不再使用 `Bukkit.getScheduler()`，全部走 Paper 的**区域化调度**：
  - 全局工作：`Bukkit.getGlobalRegionScheduler()`
  - 针对某个玩家的工作（补发消息、踢出、封禁、通报、读在线时长）：`player.getScheduler()`
  - 数据库读写：`Bukkit.getAsyncScheduler()`（**绝不**在服务端线程写库）
- 同一套代码在 Paper 上会落到主线程，因此不需要分版本构建、也不用反射。
- Folia 语义提醒：玩家退出/被封后，**排队中的该玩家任务会被丢弃**（不会报错）。所以我们的处理都在事件当下抓取所需数据（IP、名字）后再调度。

## 聊天格式化插件（TrChat / CMI 聊天 / 等）

很多服务器用插件接管聊天：它们自己 `event.setCancelled(true)` 再按自己的格式群发。这会导致"只改原版收件人"的方案失效。

AntiAd 的做法是：**在它们之前（`EventPriority.LOW`）就取消事件**。这类插件通常在 `HIGHEST` 且 `ignoreCancelled = true`，事件被取消后它们会自动跳过 → 消息不会广播出去 → 我们再**只补发给发送者**。

已实测（A/B 对照，装了 TrChat 2.5.2 的环境）：

| | 旧实现（只改 viewers） | 当前实现（先取消再补发） |
|---|---|---|
| 模拟 HIGHEST 聊天插件是否广播 | **会广播**（漏洞） | 不广播 |

### `ai.refire-approved`

AI「判定通过」的消息有两种放行方式：

- `false`（默认）：由 AntiAd 按**原版格式**补发给原观众 —— 任何服务器都能用，但没有聊天插件的前缀/颜色。
- `true`：插件**重新合成一次聊天事件**（不用 `player.chat()`，后者在本核心上不触发 `AsyncChatEvent`），让聊天插件自己按它的格式广播；**要求服务器确实装了这类插件**，否则这条消息没人广播、会丢。

## 代理端（Velocity / BungeeCord）广播聊天的情况

如果聊天是**代理端插件**（Velocity 上的聊天插件）直接广播的，那么后端 Bukkit 插件**无法**隐藏消息（消息压根不经过后端插件）。此时只能：

1. 把过滤放在代理端（Velocity 插件）；或
2. 让聊天回到后端格式化（后端装聊天插件，代理只转发）。

## 其他入口插件

| 入口 | 事件 | 可能的冲突 |
|---|---|---|
| 告示牌 | `SignChangeEvent` | 签名/告示牌编辑类插件可能在同一事件上工作；我们只是取消事件 |
| 铁砧改名 | `PrepareAnvilEvent` | 物品编辑类插件；我们只是清掉结果 |
| 书与笔 | `PlayerEditBookEvent` | 书内容类插件；我们只是取消事件 |
| `/me` 等 | `PlayerCommandPreprocessEvent` | 只检查 `settings.checked-commands` 里列出的命令根 |

## 备份插件

DriveBackupV2 这类备份会锁住 SQLite 文件。因为我们的写入都在异步线程 + 批量事务，**备份期间只会让记录晚一点落盘，不会卡服务器**（这条是实测修过的：早期版本在主线程逐条写库，曾触发 Watchdog 10 秒卡顿）。

## 已知不检测 / 拦不住

- **IPv6**：聊天里出现 IPv6 基本只会误伤，不做检测。
- **夹字还原**：`m我c.哈e x a m p l e.点n e t` 这类在域名里夹汉字/噪声的写法识别不了。
- **代理端广播的聊天**：见上。
- **私聊/领地内私有告示牌等**：只覆盖聊天、`/me` 类命令、告示牌、铁砧、书与笔；不做全书扫描、不查昵称/前缀（那些由别的插件负责）。
