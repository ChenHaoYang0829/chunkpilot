package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.neoforge.util.NonBlockingStats;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 二阶段 B2 整改: 把 fabric 的 {@code ServerChunkCacheNonBlockingMixin} **按语义等价移植到 neoforge**.
 *
 * ============================ 问题本质 ============================
 * `ServerChunkCache.getChunk(x, z, status, load)` 只要拿到的 ChunkHolder 还没到目标
 * 状态, 就会走:
 *     mainThreadProcessor.managedBlock(future::isDone)   // BlockableEventLoop
 *       → LockSupport.parkNanos                          // **主线程原地等待**
 * 而 `load = false` **并不豁免** —— 只要 holder 在可见表里就会等.
 *
 * 更致命的是它是**正反馈死锁**: 主线程被 park 住 → `ChunkMap.tick()` /
 * `DistanceManager.runAllUpdates()` 这些"把生成任务推下去"的步骤全都停摆 →
 * 那个区块永远到不了目标状态 → park 永远不返回.
 *
 * bench bot 抓到的 park 入口 (每修掉一个就冒下一个, 说明必须一刀切到底):
 *   ① handleMovePlayer → Entity.collide → collectColliders → Level.getChunkForCollisions
 *   ② handleMovePlayer → doCheckFallDamage → updateFluidHeight… → Level.getFluidState
 *   ③ ServerPlayer.tick → PlayerTrigger → LocationPredicate.matches → getNoiseBiome
 *   ④ (还会有更多)
 * 它们最终都收敛到同一个方法: **ServerChunkCache.getChunk**.
 * ⇒ ①②由 {@link LevelNonBlockingReadMixin} + {@link ServerGamePacketListenerMoveMixin} 覆盖,
 *   ③④以及"没见过的下一个入口"由本类兜底.
 *
 * ============================ 本 Mixin 做什么 (与 fabric 逐条一致) ============================
 *   只在"服务端主线程 + 该区块已在可见表里 (玩家票范围内) + 尚未到 FULL"时,
 *   把结果换成 {@link EmptyLevelChunk} (空气 + 空流体 + 无方块实体 + 指定生物群系),
 *   从而**不 park**.
 *
 *   其它情况一律走原版:
 *     - 非主线程 (worldgen worker) → 完全不动;
 *     - holder == null (不在任何票范围内) → 不动 (原版 load=false 会立刻返回 null);
 *     - 票级 &gt; 33 (不在加载范围) → 不动 (原版会返回 null);
 *     - 已经 FULL → 不动 (原版不会阻塞).
 *
 * 语义影响: "正在生成中的区块"对读取方等于不存在 —— 与原版**客户端**一致.
 *   代价: 极端落后时实体可能短暂穿过正在生成的区块/流体判定短暂失效,
 *   而原版在这种场合的结局是 park 十几秒乃至崩服, 这是严格更优的取舍.
 *
 * ⚠ **本类受已有开关 `[protection] nonBlockingGetChunk` 控制, 而该键的默认值是 false**
 *   (代码默认与 jar 内模板一致). 也就是说: 用户首次装上 CP 时本类**不生效**,
 *   需要服主显式打开 —— 这是既有配置口径, 本整改**不改默认值**
 *   (作业书 §3.2: 挂已有开关, 不新增未文档化的开关).
 *   默认口径下的 park 防护由上面 ①② 两条 (nonBlockingCollision=true) 提供.
 *
 * ============================ javap 依据 (四个版本逐版核对, 结论完全一致) ============================
 *   net.minecraft.server.level.ServerChunkCache:
 *     public  ChunkAccess getChunk(int,int,net.minecraft.world.level.chunk.status.ChunkStatus,boolean);
 *     private ChunkHolder getVisibleChunkIfPresent(long);
 *     final   java.lang.Thread mainThread;
 *     private CompletableFuture&lt;ChunkResult&lt;ChunkAccess&gt;&gt; getChunkFutureMainThread(int,int,ChunkStatus,boolean);
 *   net.minecraft.world.level.chunk.EmptyLevelChunk:
 *     public EmptyLevelChunk(net.minecraft.world.level.Level, net.minecraft.world.level.ChunkPos,
 *                            net.minecraft.core.Holder&lt;net.minecraft.world.level.biome.Biome&gt;);
 *   核对对象: ① NeoForge 打补丁后的 -server.jar ② loom 缓存同版本 mapped MC jar.
 *
 * ============================ 防御性注入 ============================
 *   `require = 0, expect = 0` (作业书 §3.3): 目标缺失只是本 mixin 不生效, 不崩启动.
 
 * ============================ ★ 26.x 适配 (port/26.1, 2026-09-24) ============================
 * 本文件从 `/data/cp-port/1.21.5/neoforge/...` 的 B2 整改版**逐字照抄**, 只做了 26.1 的真实 API 差异替换
 * (每一条都有 javap 实证, 见 `artifacts/26.1/REPORT.md` §3.2 与 §11):
 *   · `ChunkPos` 在 26.1 变成 **record** ⇒ `asLong(int,int)` → `pack(int,int)`,
 *     字段 `x`/`z` → 访问器 `x()`/`z()`; (`toLong()` → `pack()`)
 *   · 26.1 **不再混淆** ⇒ 不再需要任何 refmap; 本模块的 `fixRefmap` 已整体删除
 *     (见根报告 §2.2 B6), 所以"新增 mixin 要同步改硬编码 refmap"这条历史约束**已消失**,
 *     新增 `ServerChunkCacheAccessor` 不再有额外成本。
 *   · 注入点全部用 26.1 **内层真实服务端 jar**(`META-INF/versions/26.1/server-26.1.jar`)
 *     与 loom 的 `minecraft-merged-deobf-26.1.jar` 双向核对:
 *       Level.getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;                       public
 *       Level.getBlockState(Lnet/minecraft/core/BlockPos;)L.../block/state/BlockState;               public
 *       Level.getFluidState(Lnet/minecraft/core/BlockPos;)L.../material/FluidState;                  public
 *       ServerChunkCache.getChunk(IIL.../chunk/status/ChunkStatus;Z)L.../chunk/ChunkAccess;          public
 *       ServerChunkCache.getVisibleChunkIfPresent(J)Lnet/minecraft/server/level/ChunkHolder;         private
 *       ServerChunkCache.mainThread:Ljava/lang/Thread;                                               包可见 final
 *       ServerChunkCache.getChunkFutureMainThread(IIL.../ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;  private
 *       ChunkMap.updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;)V                     private (offset 58 调用 ChunkTrackingView.of)
 *       ChunkMap.runGenerationTask(Lnet/minecraft/server/level/ChunkGenerationTask;)V                private (offset 34 调用 ChunkTaskDispatcher.submit)
 *       ChunkTaskDispatcher.submit(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V           public
 *       ServerGamePacketListenerImpl.handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V  public
 *     ⇒ 全部命中, 与 1.21.5 的字节码形状一致; 且 26.1 / 26.1.1 / 26.1.2 三者 javap 输出 IDENTICAL。
 *   · 每个注入点都保留 `require = 0, expect = 0`: 目标缺失只是**该点不生效**, 绝不让服务端启动崩。
 * ================================================================================
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheNonBlockingMixin {

    /** ★ 26.x 诊断: @Inject handler 被调用次数 + 首次调用打一行 INFO. */
    private static final java.util.concurrent.atomic.AtomicLong cpGetChunkCalls =
        new java.util.concurrent.atomic.AtomicLong();

    /** 已缓存的空区块 (按 level + chunkPos). 只在危机路径上使用, 数量很小. */
    private static final Map<Long, LevelChunk> EMPTY_CACHE = new ConcurrentHashMap<>();
    private static final int EMPTY_CACHE_MAX = 512;
    private static final int FULL_CHUNK_LEVEL = 33;

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void chunkpilot$nonBlockingGetChunk(int chunkX, int chunkZ, ChunkStatus status,
                                                boolean load,
                                                CallbackInfoReturnable<ChunkAccess> cir) {
        try {
            if (cpGetChunkCalls.incrementAndGet() == 1L) {
                org.slf4j.LoggerFactory.getLogger("ChunkPilot").info(
                    "[ChunkPilot] 非阻塞 getChunk(neoforge): @Inject handler 首次被调用 "
                    + "(说明 ServerChunkCache.getChunk 的注入点已命中)");
            }
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null || cp.getConfig() == null || !cp.getConfig().nonBlockingGetChunk) return;

            ServerChunkCache self = (ServerChunkCache) (Object) this;
            ServerChunkCacheAccessor acc = (ServerChunkCacheAccessor) self;

            // 只有主线程会被 managedBlock 卡住; worker 线程不能动 (worldgen 正在用)
            if (Thread.currentThread() != acc.chunkpilot$mainThread()) return;

            ChunkHolder holder = acc.chunkpilot$getVisibleChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
            if (holder == null) return;                     // 原版会立刻返回 null, 不阻塞
            if (holder.getTicketLevel() > FULL_CHUNK_LEVEL) return; // 不在加载范围, 原版返回 null

            CompletableFuture<ChunkResult<LevelChunk>> full = holder.getFullChunkFuture();
            if (full.isDone()) {
                ChunkResult<LevelChunk> res = full.getNow(null);
                if (res != null && res.isSuccess() && res.orElse(null) != null) {
                    return;                                  // 已经 FULL → 原版不会阻塞
                }
            }

            // ★ 关键: 先把原版那一步"取 future"执行掉 —— 它会补票 + 把生成任务排进调度器.
            //   只判 isDone 而跳过这一步 = 顺手丢掉了"我需要这个区块"的压力,
            //   fabric 侧实测会让服务器直接空转 (Worker 线程全 idle), 吞吐掉 3~4 倍.
            CompletableFuture<ChunkResult<ChunkAccess>> fut =
                acc.chunkpilot$getChunkFutureMainThread(chunkX, chunkZ, status, load);
            if (fut != null && fut.isDone()) return;         // 已就绪 → 走原版 (join 立即返回)

            if (self.getLevel() == null) return;
            ServerLevel level = (ServerLevel) self.getLevel();
            cir.setReturnValue(chunkpilot$emptyChunk(level, chunkX, chunkZ));
            NonBlockingStats.countParkSubstitution();
            chunkpilot$maybeLogStack(chunkX, chunkZ, status);
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    private static volatile long cpLastLogSecond = 0L;

    /** 诊断: 把"是谁在阻塞读区块"打出来 (每 5 秒最多一次). 也是本 mixin 生效的日志证据. */
    private static void chunkpilot$maybeLogStack(int x, int z, ChunkStatus status) {
        try {
            long sec = System.currentTimeMillis() / 5000L;
            if (sec == cpLastLogSecond) return;
            cpLastLogSecond = sec;
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder("[ChunkPilot] 非阻塞替换 caller: chunk=(")
                .append(x).append(',').append(z).append(") status=").append(status)
                .append(" | ");
            int shown = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.startsWith("java.") || cn.contains("ServerChunkCacheNonBlockingMixin")) continue;
                sb.append(e.getMethodName()).append('@').append(cn.substring(cn.lastIndexOf('.') + 1)).append("  ");
                if (++shown >= 8) break;
            }
            org.slf4j.LoggerFactory.getLogger("ChunkPilot").info(sb.toString());
        } catch (Throwable t) { /* ignore */ }
    }

    /** 取/建一个"空气区块". 位置信息只用于 ChunkPos, 内容恒为空. */
    private static LevelChunk chunkpilot$emptyChunk(ServerLevel level, int chunkX, int chunkZ) {
        long key = ((long) System.identityHashCode(level) << 42)
                 ^ (((long) chunkX & 0x1FFFFF) << 21)
                 ^ ((long) chunkZ & 0x1FFFFF);
        LevelChunk cached = EMPTY_CACHE.get(key);
        if (cached != null) return cached;
        if (EMPTY_CACHE.size() > EMPTY_CACHE_MAX) EMPTY_CACHE.clear();
        Holder<Biome> biome = level.registryAccess()
            .lookupOrThrow(Registries.BIOME)
            .getOrThrow(Biomes.PLAINS);
        LevelChunk chunk = new EmptyLevelChunk(level, new ChunkPos(chunkX, chunkZ), biome);
        EMPTY_CACHE.put(key, chunk);
        return chunk;
    }
}
