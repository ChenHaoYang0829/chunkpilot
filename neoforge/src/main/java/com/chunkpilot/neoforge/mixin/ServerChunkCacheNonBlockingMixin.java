package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.neoforge.util.NonBlockingStats;
import com.chunkpilot.ChunkPilot;
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
 * v0.11.6 "主线程永不 park" (NeoForge 移植) —— 与 fabric 侧
 * `ServerChunkCacheNonBlockingMixin` **逐条等价**, 只改包名/工具类, 并加 `require = 0, expect = 0`.
 *
 * 只在"服务端主线程 + 该区块已在可见表里 (玩家票范围内) + 尚未到 FULL"时,
 *   把结果换成 {@link EmptyLevelChunk} (空气 + 空流体 + 无方块实体 + 指定生物群系), 从而**不 park**.
 *   其它情况一律走原版: 非主线程 / holder==null / 票级 > 33 / 已经 FULL.
 *
 * 开关: `[protection] nonBlockingGetChunk` (**默认 false**) ⇒ 默认口径下本 mixin 一行不改,
 *   与 fabric 完全同口径. 打开后它一次性覆盖碰撞/流体/生物群系/方块读取等所有 park 入口.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheNonBlockingMixin {

    /** 已缓存的空区块 (按 level + chunkPos). 只在危机路径上使用, 数量很小. */
    private static final Map<Long, LevelChunk> EMPTY_CACHE = new ConcurrentHashMap<>();
    private static final int EMPTY_CACHE_MAX = 512;
    private static final int FULL_CHUNK_LEVEL = 33;

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
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

            CompletableFuture<ChunkResult<LevelChunk>> full = holder.getFullChunkFuture();
            if (full.isDone()) {
                ChunkResult<LevelChunk> res = full.getNow(null);
                if (res != null && res.isSuccess() && res.orElse(null) != null) {
                    return;                                  // 已经 FULL → 原版不会阻塞
                }
            }

            // ★ 关键: 先把原版那一步"取 future"执行掉 —— 它会补票 + 把生成任务排进调度器.
            //   只判 isDone 而跳过这一步 = 顺手丢掉了"我需要这个区块"的压力,
            //   fabric 实测会让服务器直接空转 (Worker 线程全 idle), 吞吐掉 3~4 倍.
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
        Holder<Biome> biome = level.registryAccess()
            .lookupOrThrow(Registries.BIOME)
            .getOrThrow(Biomes.PLAINS);
        LevelChunk chunk = new EmptyLevelChunk(level, new ChunkPos(chunkX, chunkZ), biome);
        EMPTY_CACHE.put(key, chunk);
        return chunk;
    }
}
