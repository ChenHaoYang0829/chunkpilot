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
 * v0.11.6 "主线程永不 park" —— neoforge 移植 (main 作业书 §2 第 2 项)。
 *
 * **语义与 `com.chunkpilot.fabric.mixin.ServerChunkCacheNonBlockingMixin` 逐条等价**:
 *   只在「服务端主线程 + 该区块已在可见表里 (票范围内) + 票级 <= 33 + 尚未 FULL」时,
 *   把结果换成 {@link EmptyLevelChunk} (空气 + 空流体 + 无方块实体 + PLAINS 生物群系), 从而**不 park**。
 *
 * 其它情况一律走原版 (与 fabric 侧同一套判据):
 *   - 非主线程 (worldgen worker) → 完全不动;
 *   - holder == null (不在任何票范围内) → 不动 (原版 load=false 会立刻返回 null);
 *   - 票级 > 33 (不在加载范围) → 不动 (原版返回 null);
 *   - 已经 FULL → 不动 (原版不会阻塞);
 *   - **仍然先调用 `getChunkFutureMainThread`** (补票 + 排生成任务) 再决定是否替换 ——
 *     只判 isDone 而跳过这一步会丢掉"我需要这个区块"的压力, fabric 侧实测吞吐掉 3~4 倍。
 *
 * 兼容性 (作业书 §3): 开关 = **已有的** `[protection] nonBlockingGetChunk` (默认 false ⇒ 默认=原版);
 * 不新增配置键; 不吞异常 (catch Throwable 后放行原版); `require=0, expect=0` 软失败。
 * 影响面严格限制在服务端主线程的"未就绪区块读取"这一条路径上, 不触碰任何已加载区块的返回值。
 *
 * javap 依据 (1.21.9/1.21.10/1.21.11 一致):
 *   ServerChunkCache.getChunk(int,int,ChunkStatus,boolean) -> ChunkAccess
 *   ServerChunkCache.getVisibleChunkIfPresent(long) -> ChunkHolder (private)
 *   ServerChunkCache.mainThread : Thread (package-private final)
 *   ServerChunkCache.getChunkFutureMainThread(int,int,ChunkStatus,boolean) (private)
 *   ChunkHolder.getTicketLevel() -> int ; ChunkHolder.getFullChunkFuture() -> CompletableFuture<ChunkResult<LevelChunk>>
 *   EmptyLevelChunk(Level, ChunkPos, Holder<Biome>)
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheNonBlockingMixin {

    /** 已缓存的空区块 (按 level identity + chunkPos). 只在危机路径上使用, 数量很小. */
    private static final Map<Long, LevelChunk> EMPTY_CACHE = new ConcurrentHashMap<>();
    private static final int EMPTY_CACHE_MAX = 512;
    private static final int FULL_CHUNK_LEVEL = 33;

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true,
            require = 0, expect = 0)
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

            ChunkHolder holder = acc.chunkpilot$getVisibleChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
            if (holder == null) return;                             // 原版会立刻返回 null, 不阻塞
            if (holder.getTicketLevel() > FULL_CHUNK_LEVEL) return;  // 不在加载范围, 原版返回 null

            CompletableFuture<ChunkResult<LevelChunk>> full = holder.getFullChunkFuture();
            if (full.isDone()) {
                ChunkResult<LevelChunk> res = full.getNow(null);
                if (res != null && res.isSuccess() && res.orElse(null) != null) {
                    return;                                         // 已经 FULL → 原版不会阻塞
                }
            }

            // ★ 关键: 先把原版那一步"取 future"执行掉 —— 它会补票 + 把生成任务排进调度器.
            CompletableFuture<ChunkResult<ChunkAccess>> fut =
                acc.chunkpilot$getChunkFutureMainThread(chunkX, chunkZ, status, load);
            if (fut != null && fut.isDone()) return;                // 已就绪 → 走原版 (join 立即返回)

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

    /** 诊断: 把"是谁在阻塞读区块"打出来 (每 5 秒最多一次). 也是开关生效的日志证据. */
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
