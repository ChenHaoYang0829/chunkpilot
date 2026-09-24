package com.chunkpilot.fabric.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.fabric.util.NonBlockingStats;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.11.6 "主线程永不 park" —— 一次性掐掉所有阻塞式区块读取, 修"无 C2ME 会崩"和"墙".
 *
 * ============================ 问题本质 (1.21.3 实证) ============================
 * `ServerChunkCache.getChunk(x, z, status, load)` 只要拿到的 ChunkHolder 还没到目标
 * 状态, 就会走:
 *     mainThreadProcessor.managedBlock(future::isDone)   // BlockableEventLoop
 *       → LockSupport.parkNanos                          // **主线程原地等待**
 * 而 `load = false` **并不豁免** —— 只要 holder 在可见表里就会等.
 *
 * 更致命的是它是**正反馈死锁**: 主线程被 park 住 → `ChunkMap.tick()` /
 * `DistanceManager.runAllUpdates()` 这些"把生成任务推下去"的步骤全都停摆 →
 * 那个区块永远到不了目标状态 → park 永远不返回.
 * CP 的 FreezeDetector 现场取证: 玩家 9x9 (r=4) 全 NOT-FULL, 同期
 * `[Gen] queue=1 outstanding=1/32` —— **生成队列几乎空着**, 服务器不是算不过来,
 * 是把自己锁死了. 无 C2ME 实测单次 2.8~45 秒, 最后
 * `Server Watchdog: A single server tick took 60.00 seconds` → 强制关服.
 *
 * bench bot 抓到的 park 入口 (每修掉一个就冒下一个, 说明必须一刀切到底):
 *   ① handleMovePlayer → Entity.collide → collectColliders → Level.getChunkForCollisions
 *   ② handleMovePlayer → doCheckFallDamage → updateFluidHeight… → Level.getFluidState
 *   ③ ServerPlayer.tick → PlayerTrigger → LocationPredicate.matches → getNoiseBiome
 *   ④ (还会有更多)
 * 它们最终都收敛到同一个方法: **ServerChunkCache.getChunk**.
 *
 * ============================ 本 Mixin 做什么 ============================
 *   只在"服务端主线程 + 该区块已在可见表里 (玩家票范围内) + 尚未到 FULL"时,
 *   把结果换成 {@link EmptyLevelChunk} (空气 + 空流体 + 无方块实体 + 指定生物群系),
 *   从而**不 park**.
 *
 *   其它情况一律走原版:
 *     - 非主线程 (worldgen worker) → 完全不动;
 *     - holder == null (不在任何票范围内) → 不动 (原版 load=false 会立刻返回 null);
 *     - 票级 > 33 (不在加载范围) → 不动 (原版会返回 null);
 *     - 已经 FULL → 不动 (原版不会阻塞).
 *
 * 语义影响: "正在生成中的区块"对读取方等于不存在 —— 与原版**客户端**一致.
 *   代价: 极端落后时实体可能短暂穿过正在生成的区块/流体判定短暂失效,
 *   而原版在这种场合的结局是 park 十几秒乃至崩服, 这是严格更优的取舍.
 *
 * 只影响服务端; 客户端本来就不阻塞.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheNonBlockingMixin {

    /** 已缓存的空区块 (按 level + chunkPos). 只在危机路径上使用, 数量很小. */
    private static final Map<Long, LevelChunk> EMPTY_CACHE = new ConcurrentHashMap<>();
    private static final int EMPTY_CACHE_MAX = 512;
    private static final int FULL_CHUNK_LEVEL = 33;

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void chunkpilot$nonBlockingGetChunk(int chunkX, int chunkZ, ChunkStatus status,
                                                boolean load,
                                                CallbackInfoReturnable<ChunkAccess> cir) {
        try {
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null || cp.getConfig() == null || !cp.getConfig().nonBlockingGetChunk) return;

            ServerChunkCache self = (ServerChunkCache) (Object) this;
            ServerChunkCacheAccessor acc = (ServerChunkCacheAccessor) self;

            // 只有主线程会被 managedBlock 卡住; worker 线程不能动 (worldgen 正在用)
            if (Thread.currentThread() != acc.chunkpilot$mainThread()) return;

            ChunkHolder holder = acc.chunkpilot$getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
            if (holder == null) return;                     // 原版会立刻返回 null, 不阻塞
            if (holder.getTicketLevel() > FULL_CHUNK_LEVEL) return; // 不在加载范围, 原版返回 null

            // 1.20.1: getFullChunkFuture() 返回 CompletableFuture<Either<...>> (无 ChunkResult)
            CompletableFuture<com.mojang.datafixers.util.Either<LevelChunk,
                ChunkHolder.ChunkLoadingFailure>> full = holder.getFullChunkFuture();
            if (full != null && full.isDone()) {
                com.mojang.datafixers.util.Either<LevelChunk, ChunkHolder.ChunkLoadingFailure> res =
                    full.getNow(null);
                if (res != null && res.left().isPresent() && res.left().get() != null) {
                    return;                                  // 已经 FULL → 原版不会阻塞
                }
            }

            // ★ 关键: 先把原版那一步"取 future"执行掉 —— 它会补票 + 把生成任务排进调度器.
            //   只判 isDone 而跳过这一步 = 顺手丢掉了"我需要这个区块"的压力,
            //   实测会让服务器直接空转 (Worker 线程全 idle), 吞吐掉 3~4 倍.
            CompletableFuture<com.mojang.datafixers.util.Either<ChunkAccess,
                ChunkHolder.ChunkLoadingFailure>> fut =
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

    /** 诊断: 把"是谁在阻塞读区块"打出来 (每 5 秒最多一次). */
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
        // 1.20.1: RegistryAccess.lookupOrThrow(...).getOrThrow(...) 是 1.21+ API;
        //   1.20.1 用 registryOrThrow(...).getHolderOrThrow(...) (javap 实证).
        Holder<Biome> biome = level.registryAccess()
            .registryOrThrow(Registries.BIOME)
            .getHolderOrThrow(Biomes.PLAINS);
        LevelChunk chunk = new EmptyLevelChunk(level, new ChunkPos(chunkX, chunkZ), biome);
        EMPTY_CACHE.put(key, chunk);
        return chunk;
    }
}
