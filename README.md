# ChunkPilot

**高速移动时的智能区块加载优化 Mod** — 让玩家**前方即将到达**的区块优先加载、生成与发送，同时按服务端负载自适应降载。

适用于鞘翅飞行、火车、飞艇等高速交通场景，尤其在**服务器配置差 / 网络差 / 客户端配置差**时效果明显。

> **English**: A Minecraft mod that makes chunk loading **direction-aware**. Vanilla loads chunks in a symmetric square around the player, so flying forward wastes half the worldgen budget on the terrain behind you. ChunkPilot shifts the whole load + client-visibility window forward: chunks ahead of you are loaded further out and delivered earlier. Measured on 34 m/s elytra flight: forward-loaded ratio **0.124 → 0.58 (4.7×)**, forward frontier **4.0 → 14.1 chunks (3.5×)**.

| | |
|---|---|
| **平台** | Fabric + NeoForge + Forge |
| **Minecraft** | 1.20.1 – 1.21.11, 26.1 – 26.3（见下方版本表） |
| **Java** | 21（1.21.x）/ 25（26.x） |
| **许可证** | MIT |

---

## 它解决什么问题

原版的区块加载有三层，**每一层都只按距离排序，不分方向**：

| 环节 | 原版行为 |
|---|---|
| **生成** | PLAYER ticket 给出以玩家为中心的**对称方块**等级场 |
| **发送** | `ChunkTrackingView` 同样是**以玩家为中心的对称窗口** |
| **排队** | 只按切比雪夫距离排序，正前方和正后方优先级完全相同 |

往北飞的时候，正北 5 格和正南 5 格优先级一模一样 —— **一半的算力被花在了身后**。

ChunkPilot 把整个「加载窗口 + 客户端可见窗口」**整体前移**：

```
原版:     [ 10 格 | 玩家 | 10 格 ]        前后对称
ChunkPilot:[  4 格 | 玩家 | 16 格 ]        前方 +6，后方不变
```

---

## 核心功能

### 1. 前瞻窗口（Forward Look-ahead Window）

在玩家**前方**放置锚点票，并对客户端可见窗口做同样的前移：

```toml
[forward_window]
enabled = true
aheadChunks  = 13    # 锚点前移量
forwardExtra = 6     # 生成侧前向延伸：前方可达 = 视距 + 6
trackingShift = 6    # 发送侧前移量
```

**设计要点**：锚点等级场与玩家等级场都是 1-Lipschitz 的，取二者最小值仍是 1-Lipschitz ⇒ 不会出现「邻居等级差过大」导致的加载链断裂。锚点越远，同样的前方延伸越便宜（`A = 视距 + forwardExtra/2` 时额外加载面积最小）。

### 2. 非阻塞碰撞查询（默认开）

治「墙」和回弹的直接根因。高速飞行时主线程会在碰撞查询里同步等待未生成完的区块：

```
handleMovePlayer → collectColliders → Level.getChunkForCollisions
  → ServerChunkCache.getChunk → managedBlock → parkNanos   ← 主线程停在这里
```

实测单次 park 可达 **2.8 ~ 45 秒** → `Can't keep up` → Watchdog 强杀。开启后未就绪的区块对碰撞「不存在」（与原版客户端行为一致），主线程永不 park。

### 3. 自适应降载

按服务器 MSPT 动态调节请求速率，并带**在途请求窗口**（`maxOutstandingRequests`）与 TTL 兜底 —— 请求速率跟随 worldgen 实际吞吐，不会把服务器压垮。

### 4. 速度分档加载 / 交通 Mod 联动

按速度分档（步行 / 慢飞 / 快飞 / 极速）给出不同扇形参数；可检测 Create、MTR、Immersive Railroading 等交通 Mod 的载具并提前加载其前方路径。

### 5. 诊断命令

```
/chunkpilot diagnose     # 需求 vs 吞吐、前方前沿、是否该装 C2ME、视距/速度建议
/chunkpilot probe <x> <z> # 只读探针：是否已加载 / 票等级（绝不加载区块）
```

---

## 实测数据

> 基准：玩家鞘翅持续最高速 **34 m/s**（1.70 blocks/tick），view-distance 10，全新地形，60 秒巡航

### 5 bot 并发（C2ME 环境）

| 组别 | 前方已加载占比 | 前方前沿 | 前/后 比 | 巡航 MSPT |
|---|---|---|---|---|
| C2ME only（原版） | 0.124 | 3.99 | 0.22 | 12.4 / 24.7 ms |
| **C2ME + ChunkPilot** | **0.564 ~ 0.585** | **14.0 ~ 14.2** | **2.7 ~ 2.9** | 15.6 ~ 20.7 ms，超 50ms **0 次** |

⇒ 前方集中度 **4.7×**，前方前沿深度 **3.5×**，6 次重复极差 ±0.011。

### 单玩家长距离（150 秒连续飞 5100 格）

| 指标 | 结果 |
|---|---|
| 脚下覆盖 | **1.0（600/600 tick）** |
| 进入区块前已送达 | **100%** |
| 人在区块内被卸载 | **0 次** |
| 被服务端拉回 | 1 次 / 150 秒，位移仅 1.72 格（≈0.05 秒） |
| 巡航 MSPT | 8.79 ms 均值 / 14.0 ms 峰值，超 50ms **0 次** |

### 无 C2ME 时

原版吞吐是硬约束（≈20~40 区块/秒/整服），ChunkPilot **无法创造吞吐**，但能把有限的吞吐集中到前方：

| 组别 | 覆盖 | 被拉回 | 前方前沿 | 前方集中度 |
|---|---|---|---|---|
| 原版 | 0.170 | 115 | 0.97 | 0.060 |
| **ChunkPilot** | **0.242** | **27** | **2.85** | **0.195** |

⇒ 覆盖 **1.4×**，被拉回 **1/4**，前方集中度 **3.3×** —— 不再是负优化。

---

## 安装

1. 安装对应的 Mod 加载器（Fabric Loader / NeoForge / Forge）
2. 把 jar 放进 `mods/` 目录
3. 启动服务器或客户端

**服务端建议**：ChunkPilot 是**服务端** Mod（客户端不需要装）。服务器同时装 **C2ME** 可获得最大收益 —— ChunkPilot 检测到 C2ME 后会自动让出调度权，只做窗口前移，两者不冲突。

---

## 配置

配置文件：`config/chunkpilot.toml`（首次启动自动生成）

```toml
[general]
enabled  = true
logLevel = "info"
language = "auto"        # auto | en_us | zh_cn —— 玩家消息跟随各自客户端语言

[forward_window]
enabled       = true
aheadChunks   = 13       # 锚点前移量
forwardExtra  = 6        # 生成侧前向延伸
trackingShift = 6        # 发送侧可见窗口前移

[protection]
maxMspt              = 40   # 超过则降载
disableMspt          = 50   # 超过则完全停手
nonBlockingCollision = true # 治「墙」/回弹（默认开，强烈建议保持）
nonBlockingReads     = false
nonBlockingGetChunk  = false # 危机开关：绝不 park，但交付量掉 3~4 倍
```

**参数取舍**：`aheadChunks=13, forwardExtra=6, trackingShift=6` 是实测最优组合 —— 前方集中度 ≈0.58、前沿 ≈14 格，且 MSPT 与原版同档。更大的前移量（如 `10/8/8`）集中度可到 0.70+，但 MSPT 峰值会到 57.7ms，代价不值得。

---

## 命令

所有命令需要 OP 权限。语言跟随执行者客户端（控制台/RCON 用英文）。

| 命令 | 作用 |
|---|---|
| `/chunkpilot status` | 当前状态 |
| `/chunkpilot diagnose` | 健康检查：需求 vs 吞吐、前方前沿、C2ME 建议 |
| `/chunkpilot player [名]` | 单玩家详情（速度 / 方向 / 票数） |
| `/chunkpilot net [名]` | 玩家网络统计 |
| `/chunkpilot probe <x> <z>` | **只读**探针（绝不加载区块） |
| `/chunkpilot gen [queue\|stats]` | 生成器状态 |
| `/chunkpilot send [status]` | 发送调度器状态 |
| `/chunkpilot reload [config]` | 热重载配置 / 重载视距 |
| `/chunkpilot config show` | 打印生效配置 |
| `/chunkpilot lang [auto\|en_us\|zh_cn]` | 切换输出语言 |
| `/chunkpilot help` | 命令列表 |

---

## 支持的版本

`main` 分支对应 Fabric 1.21.3 + NeoForge 1.21.1。其它版本在对应分支上：

| MC | NeoForge | Fabric API | 分支 |
|---|---|---|---|
| 1.20.1 | `1.20.1-47.1.106`（artifact 名为 `forge`） | `0.92.12+1.20.1` | `port/1.20.1` · `forge/1.20.1` |
| 1.21.1 | `21.1.251` | `0.116.17+1.21.1` | `port/1.21.1` |
| 1.21.2 | `21.2.1-beta` | `0.106.1+1.21.2` | `port/1.21.2` |
| 1.21.3 | `21.3.97` | `0.114.1+1.21.3` | `port/1.21.3` |
| 1.21.4 | `21.4.157` | `0.119.4+1.21.4` | `port/1.21.4` |
| 1.21.5 | `21.5.98` | `0.128.2+1.21.5` | `port/1.21.5` |
| 1.21.6 | `21.6.20-beta` | `0.128.2+1.21.6` | `port/1.21.6` |
| 1.21.7 | `21.7.25-beta` | `0.129.0+1.21.7` | `port/1.21.7` |
| 1.21.8 | `21.8.54` | `0.136.1+1.21.8` | `port/1.21.8` |
| 1.21.9 | `21.9.16-beta` | `0.134.1+1.21.9` | `port/1.21.9` |
| 1.21.10 | `21.10.64` | `0.138.4+1.21.10` | `port/1.21.10` |
| 1.21.11 | `21.11.45` | `0.141.6+1.21.11` | `port/1.21.11` |
| 26.1 / 26.2 / 26.3 | 见 `port/26.x` 分支的 `build.gradle` | 同左 | `port/26.1` · `port/26.2` · `port/26.3` |

> 标 *beta* 的版本 NeoForge 官方只发布了 beta/rc 构建，可能与稳定版行为有差异。

**JDK 必须按版本选**：1.21.x 用 **JDK 21**，26.x 用 **JDK 25**。用错会在启动期报 `ClassNotFoundException: java.lang.Double` 这类**看起来像 Mod bug 实则是 JDK 选错**的错误。

---

## 从源码构建

**要求**：JDK 21、Gradle 9.6.1

```bash
export JAVA_HOME=/path/to/jdk-21
export GRADLE_USER_HOME=/path/to/gradle-home

gradle :common:test :fabric:build :neoforge:build \
  -x remapSourcesJar --no-daemon --no-parallel
```

**产物**：

| 平台 | 路径 |
|---|---|
| Fabric | `fabric/build/libs/fabric-<version>.jar` |
| NeoForge | `neoforge/build/devlibs/neoforge-<version>-dev.jar` ⚠️ 在 `devlibs/`，不是 `libs/` |

> ⚠️ **NeoForge 构建需要 `.local-maven-repo/`**：`neoforge/build.gradle` 依赖它提供 `cpw.mods:modlauncher:11.0.4` 与 `securejarhandler:3.0.8`（fancymodloader 要求 11.0.3，公开 maven 上找不到 11.0.4）。该目录未纳入版本控制，新克隆的仓库需要自行准备这两个 artifact，否则 `:neoforge:build` 会在依赖解析阶段失败。

**图标**：模组图标是两张内容相同的 128×128 PNG —— `common/src/main/resources/assets/chunkpilot/icon.png`（Fabric 的 `fabric.mod.json` 引用）与 `neoforge/src/main/resources/icon.png`（NeoForge 的 `logoFile`，路径相对 jar 根），修改时请同步替换。注意 `.gitignore` 里有 `*.png` 全局规则，图标是**显式放行**的发布资源，改动 `.gitignore` 时不要破坏那两条 `!` 例外 —— 否则克隆出的仓库会缺图标，jar 里的引用会变成悬空引用。

---

## 已知限制

- **无 C2ME 时吞吐是硬墙**。需求 = `Σ_玩家 (速度/16) × (2×视距+1)`；原版可持续交付约 20~40 区块/秒。单玩家 34 m/s 已超视距 10 的额度，5 人同速任何视距都不够。解法只有装 C2ME（吞吐 ×5）或降速/降视距。
- `nonBlockingGetChunk`（默认关）会让主线程永不 park，但原版区块管线会被饿死、交付量掉 3~4 倍 —— 作为「绝不崩服」的危机开关保留，机理尚未完全吃透。
- `chunk_send` 模块默认关闭（1.21.3 起发送链路改由可见窗口前移实现，不再需要单独重排）。

---

## 许可证

MIT
