package com.chunkpilot.config;

import java.util.ArrayList;
import java.util.List;

/**
 * ChunkPilot 配置
 * 字段全部可调，可从 chunkpilot.toml 加载，支持 /chunkpilot reload 热重载
 *
 * 设计原则：
 *  - 没有写死的常量
 *  - 每个 Provider 的所有参数都有开关
 *  - 速度档位可被服主完全覆盖
 */
public class ChunkPilotConfig {

    // ===== [general] 全局 =====
    public boolean enabled = true;
    public String mode = "sector"; // none | sector | aggressive
    public String logLevel = "info";
    /**
     * 输出语言 (i18n). "auto" = 玩家消息跟随其客户端语言, 控制台/日志用 en_us;
     * 显式填 "zh_cn" / "en_us" 则**所有**输出都强制该语言。
     * 语言文件在 jar 内 `assets/chunkpilot/lang/*.json`, 新增语言只需丢一个文件 + 加进 I18n.SHIPPED。
     */
    public String language = "auto";

    // ===== [speed] 速度追踪 =====
    public int speedWindowTicks = 20;        // 滑窗大小（20 = 1 秒）
    public int speedRecentTicks = 5;         // 近期加权段
    public double lowSpeedThreshold = 0.75;  // 低于此速度不干预（b/t ≈ 15 b/s）
    /** 滞回下限: 速度 <= 此值才真正拆除扇形票. 默认 = 0.6×threshold 之下, 防止鞘翅巡航速度(0.75~0.83 b/t)骑阈值导致每 tick 整拆整建. */
    public double lowSpeedRevoke = 0.45;

    // ===== [speedTiers] 速度档位 =====
    public List<SpeedTier> speedTiers = new ArrayList<>();
    public String curveMode = "stepped"; // stepped | linear
    public String customFormula = "";

    // ===== [structure] 结构感知 =====
    public boolean structureEnabled = true;
    public boolean structurePriorityLoading = true;
    public List<String> interestingStructures = new ArrayList<>();
    public int edgeDelayTicks = 2;

    // ===== [protection] 保护机制 =====
    public int maxMspt = 40;
    public int disableMspt = 50;
    public double maxMemoryPercent = 75;
    public int ticketExpiryTicks = 100; // 5 秒 grace period
    public boolean autoLiteMode = true;
    public int liteModeRadius = 6;

    /**
     * v0.11.6 非阻塞碰撞查询 (治"墙"/"停摆"/"无 C2ME 崩"的根因).
     *
     * 1.21.3 的实体碰撞会走
     *   Entity.move → collide → collideBoundingBox → collectColliders
     *     → Level.getChunkForCollisions(x,z) → getChunk(x,z,FULL,false)
     *       → ServerChunkCache.getChunk → mainThreadProcessor.managedBlock(future)  ← **主线程 park**
     * 也就是"实体包围盒碰到的区块只要还没生成完, 主线程就原地等到它生成完".
     * 高速飞行时包围盒每 0.5 秒就压到一个新区块上, 没有 C2ME 时这个等待实测
     * 2.8~45 秒 (bench bot jstack 实证, 8 次抓栈签名完全一致), 最终被
     * Server Watchdog 判为 "A single server tick took 60.00 seconds" 强制关服 ——
     * 这就是"无 C2ME 会崩"和客户端"墙"(服务端卡住 → 位置包堆积 → 被拉回)的真正根因.
     *
     * 打开本项后: 碰撞查询只使用**已经生成好的**区块; 未就绪的区块按原版
     * "区块不存在"处理 (返回 null = 无碰撞), 主线程永不 park.
     * 语义上与原版客户端一致 (客户端未加载的区块本来就没有碰撞), 因此
     * 服务端与客户端对"玩家能不能穿过去"的判断反而更一致 → 回弹更少.
     */
    public boolean nonBlockingCollision = true;

    /**
     * v0.11.6 非阻塞方块/流体读取 (与 {@link #nonBlockingCollision} 配套).
     *
     * 同一根因的另一条路径 (jstack 实证):
     *   Entity.baseTick → updateInWaterStateAndDoWaterCurrentPushing
     *     → updateFluidHeightAndDoFluidPushing → Level.getFluidState(pos)
     *       → Level.getChunkAt → Level.getChunk(cx,cz,FULL,true)
     *         → ServerChunkCache.getChunk → mainThreadProcessor.managedBlock → park
     *   —— 这是**实体 tick** 路径 (不止玩家移动包), 所以必须服务端全局生效,
     *      否则高速飞行时每 tick 都会被自己的"脚下区块还没 FULL"卡住.
     *
     * true  = 未就绪区块的 getBlockState 返回 AIR, getFluidState 返回 EMPTY (不阻塞);
     * false = 只在玩家移动包处理期间这样 (更保守, 但实体 tick 仍可能停摆).
     */
    public boolean nonBlockingReads = false;

    /**
     * v0.11.6 非阻塞 ServerChunkCache.getChunk (最激进的一项, 单独开关).
     * true = 主线程读取"尚未到 FULL"的区块时直接拿到空气区块, 绝不 park.
     * 这是唯一能一次性覆盖所有 park 入口的做法 (碰撞/流体/生物群系/方块读取全部收敛到这里).
     */
    public boolean nonBlockingGetChunk = false;

    /**
     * v0.11.10 非阻塞"磁盘上是否已有完整区块"检查 —— 专治 **NeoForge** 的
     * `Server Watchdog: A single server tick took 60.00 seconds` 崩服 (1.21.3 实测).
     *
     * 阻塞点在原版 `ChunkMap.isExistingChunkFull` (vanilla 1.21.3 就有, 不是 NeoForge 引入的):
     *   ChunkMap.processUnloads → scheduleUnload → save(ChunkAccess)
     *     → isExistingChunkFull(pos) → readChunk(pos).join()   ← 主线程同步等一次**磁盘读**
     *
     * 为什么在 `sync-chunk-writes=true` 下必然出事:
     *   `sync` 会把 `RegionFileStorage` 的落盘改成"立即 write+fsync", 而 `IOWorker` 用
     *   `PriorityConsecutiveExecutor` 按 region 串行执行任务 ⇒ 同一 region 的读请求会被排在
     *   成千上万次 fsync 落盘之后。主线程这时在 join() 上原地等 ⇒ 一个 tick 卡到 ≥60 秒
     *   (实测两次崩服, 栈签名完全一致: ChunkMap.java:814 ← :776 ← :540 ← :500 ← :465)。
     *   34 m/s 巡航 + CP 前瞻窗口会让"卸载时还在 PROTOCHUNK 的区块"数量暴涨, 于是每次卸载
     *   都要做一次这样的同步磁盘读。C2ME 会接管整套 IO 从而绕开这条路径 —— 而 **C2ME 只有 fabric 版**,
     *   所以 NeoForge 必须自己治。
     *
     * true  = 只在"结果已经就绪"时才用磁盘答案; 未就绪时**绝不 join**, 直接按原版对"未缓存位置"
     *         的同一答案 false 处理 (= 允许写入), 并把读请求异步留在飞, 下一 tick 再取真答案。
     *         语义无害的理由: 原版 `isExistingChunkFull` 对**未缓存**的位置本来就返回 false ——
     *         它返回的是 `markPosition(...) == 1`, 而 `markPosition` 返回的是
     *         `Long2ByteOpenHashMap.put()` 的**旧值**(新键 = 0) ⇒ 首次询问恒为 false,
     *         那次磁盘读的作用只是**把结论写进 chunkTypeCache 供后续询问使用**。
     *         (javap 实测: ChunkMap 构造里 `new Long2ByteOpenHashMap()` 没有设 defaultReturnValue,
     *          所以新键 put 返回 0。)
     * false = 完全原版行为 (主线程 join, 可能崩服) —— 只在排查问题时用。
     *
     * 只对 **NeoForge 服务端**生效 (fabric 侧没有对应的 mixin; fabric 靠 C2ME)。
     */
    public boolean nonBlockingUnloadCheck = true;

    // P0: 扇形 ticket 总数硬上限 — 防止一次发几百个 ticket 造成过载 (chunks=227 bug)
    // v0.10: 提到 100 给 SectorBudgetController 的自适应档位留足中间空间
    // (旧 50 配 40 保底导致高负载档位被顶回, 自适应退化; 现保底按 20% 比例, 档位真正生效)
    public int maxSectorTickets = 100;

    /** 每 tick 最多新增多少个扇形票 — 限制全新世界中强制 FULL 加载触发的 worldgen 灌量 (v0.10.2: 4->2, 进一步压每 tick 冲击). */
    public int maxNewTicketsPerTick = 2;

    // ===== v0.10: 扇形远处浅层加载 (治本) =====
    // 参考原版区块加载等级机制: 远处区块用更浅的加载等级 (只生成地形骨架, 不触发实体 tick),
    // 近处才 FULL_TICKING. 这样预加载不再等于整块 worldgen, 压住高速飞行+新世界的生成爆炸.
    /** 距玩家多少 chunk 内用 FULL_TICKING (level 31), 之外用浅层等级. v0.10.2: 8->6 缩小 FULL 区域. */
    public int sectorShallowRadius = 6;
    /**
     * 远处区块的浅层生成状态 (ChunkStatus 名).
     * v0.10.1 修正: 原 "CARVERS" 在 1.21.3 已不是有效状态名 (解析成 empty=44 完全不生成).
     * 用 "surface"(level 35, 噪声+地表, 无特征/结构/实体) 作为地形骨架预生成.
     */
    public String sectorShallowStatus = "surface";

    // ===== [integration] Mod 联动 =====
    /** 总开关：false 时完全跳过 IntegrationManager 调度 */
    public boolean integrationEnabled = true;
    public Integration create = new Integration();
    public Integration createAeronautics = new Integration();
    public Integration mtr = new Integration();
    public Integration immersiveRailroading = new Integration();
    public Integration immersiveVehicles = new Integration();

    // ===== [generation] v0.3.0 轨道优先生成器 =====
    public GenerationConfig generation = new GenerationConfig();

    // ===== [chunk_send] v0.3.0 区块发送顺序优化 =====
    public ChunkSendConfig chunkSend = new ChunkSendConfig();

    // ===== [forward_window] v0.11.6 前瞻窗口 (生成侧锚点票 + 发送侧跟踪窗口前移) =====
    public ForwardWindowConfig forwardWindow = new ForwardWindowConfig();

    // ===== [client_render] v0.4.0 客户端自适应渲染 =====
    public ClientRenderConfig chunkRender = new ClientRenderConfig();

    // ===== 内部：默认值 =====
    boolean initialized = false;

    public void initDefaultsIfNeeded() {
        if (initialized) return;
        initialized = true;
        speedTiers.clear();
        interestingStructures.clear();

        // 默认速度档位
        speedTiers.add(new SpeedTier("walk",           0.00, 0.75, -1,   0,   0, 0));
        speedTiers.add(new SpeedTier("slow_flight",    0.75, 1.50,  4, 120,  -1, 3));
        speedTiers.add(new SpeedTier("fast_flight",    1.50, 2.50,  4,  70,  -1, 2));
        speedTiers.add(new SpeedTier("extreme_flight", 2.50, 999.0, 3,  35,  -2, 1));

        // 默认结构白名单
        interestingStructures.add("minecraft:village");
        interestingStructures.add("minecraft:desert_pyramid");
        interestingStructures.add("minecraft:jungle_temple");
        interestingStructures.add("minecraft:shipwreck");
        interestingStructures.add("minecraft:ocean_ruin");
        interestingStructures.add("minecraft:buried_treasure");
        interestingStructures.add("minecraft:pillager_outpost");
        interestingStructures.add("minecraft:mansion");
        interestingStructures.add("minecraft:monument");
        interestingStructures.add("minecraft:ancient_city");
        interestingStructures.add("minecraft:fortress");
        interestingStructures.add("minecraft:bastion_remnant");
        interestingStructures.add("minecraft:stronghold");
        interestingStructures.add("minecraft:end_city");
    }

    /** 从外部重新加载（保留 initialized 标志并覆盖字段） */
    public void reload() {
        boolean wasInit = initialized;
        initialized = false;
        initDefaultsIfNeeded();
        if (!wasInit) initialized = true; // 避免重复填充
    }

    /** 根据速度匹配档位 */
    public SpeedTier matchTier(double speed) {
        for (SpeedTier tier : speedTiers) {
            if (speed >= tier.minSpeed && speed < tier.maxSpeed) {
                return tier;
            }
        }
        return null;
    }

    /**
     * 速度档位定义
     */
    public static class SpeedTier {
        public String name;
        public double minSpeed;     // blocks/tick
        public double maxSpeed;
        public int coreRadius;      // -1 = 原版, -2 = rd+2
        public double sectorAngle;  // 度
        public int sectorRadius;    // -1 = rd, -2 = rd+2
        public int tailRadius;

        public SpeedTier() {}

        public SpeedTier(String name, double min, double max, int core,
                         double angle, int sector, int tail) {
            this.name = name;
            this.minSpeed = min;
            this.maxSpeed = max;
            this.coreRadius = core;
            this.sectorAngle = angle;
            this.sectorRadius = sector;
            this.tailRadius = tail;
        }
    }

    /**
     * Mod 联动配置 — 每个 Provider 一个
     * 字段：
     *  - enabled: Provider 是否启用
     *  - lookAheadChunks: 轨道载具向前预加载的区块数
     *  - trackSideRadius: 轨道两侧各加载多少格（0 = 仅轨道）
     *  - minSpeed: 高速载具扇形模式的最低速度阈值（b/t）
     *  - sectorAngle: 高速载具扇形模式的角度
     *  - cacheTicks: 反射结果缓存多少 tick（避免每 tick 反射）
     */
    public static class Integration {
        public boolean enabled = true;
        public int lookAheadChunks = 8;     // 默认 8（不是 32）
        public int trackSideRadius = 2;     // 轨道两侧宽度
        public double minSpeed = 1.0;
        public double sectorAngle = 60;
        public int cacheTicks = 10;         // 反射结果缓存
    }

    // ===== 加载入口 =====

    /** 从默认 + 配置文件加载（文件不存在则用默认） */
    public static ChunkPilotConfig load() {
        ChunkPilotConfig config = new ChunkPilotConfig();
        config.initDefaultsIfNeeded();
        // 注意: 不再无条件清空默认档位/结构白名单. 若 toml 存在但没写对应节,
        // 默认值会保留 (否则用户写个只有 [general] 的 toml 会让整个 mod 静默失效).
        // 清空/替换动作交给 ConfigLoader: 只有真正遇到 [[speedTiers.tier]] 或
        // interestingStructures 键时才用用户值替换默认值.
        try {
            ConfigLoader.loadFromToml(config, locateConfigFile());
        } catch (Throwable t) {
            System.err.println("[ChunkPilot] Failed to load chunkpilot.toml, using defaults: " + t.getMessage());
            // 失败时恢复默认：重置 initialized 标志后重新初始化
            config.initialized = false;
            config.initDefaultsIfNeeded();
        }
        return config;
    }

    /** 找配置文件位置：服务端 config/chunkpilot.toml 优先 */
    private static java.nio.file.Path locateConfigFile() {
        String[] candidates = {
            "config/chunkpilot.toml",
            "chunkpilot.toml",
            "../config/chunkpilot.toml"
        };
        for (String c : candidates) {
            java.nio.file.Path p = java.nio.file.Paths.get(c);
            if (java.nio.file.Files.exists(p)) return p;
        }
        return java.nio.file.Paths.get("config/chunkpilot.toml"); // 不存在也会走默认
    }
}
