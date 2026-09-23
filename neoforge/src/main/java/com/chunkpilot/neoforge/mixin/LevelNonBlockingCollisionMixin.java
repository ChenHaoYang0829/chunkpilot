package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.ChunkPilot;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * v0.11.10 NeoForge 非阻塞碰撞查询 —— 治**第二条**主线程 park 路径(与 fabric 的
 * {@code LevelNonBlockingReadMixin} 同源, 但只做碰撞这一支, 因为它是 neoforge 实测崩服的那条)。
 *
 * ============================ 实测崩服栈 (2026-09-24 06:47:59, 合并 main 之后) ============================
 *   java.lang.Error: ServerWatchdog detected that a single server tick took 60.00 seconds
 *     at Unsafe.park → LockSupport.parkNanos
 *     at BlockableEventLoop.managedBlock(BlockableEventLoop.java:137)
 *     at ServerChunkCache$MainThreadExecutor.managedBlock(ServerChunkCache.java:584)
 *     at ServerChunkCache.getChunk(ServerChunkCache.java:174)
 *     at Level.getChunk(Level.java:201)
 *     at Level.getChunkForCollisions(Level.java:796)          ← 实体碰撞查询
 *
 * 也就是说: 玩家高速飞行时, 包围盒所在的区块只要还没到 FULL, 主线程就会在
 * `ServerChunkCache.getChunk → managedBlock` 上原地 park(等生成完成)。
 * fabric 侧早就有治它的 mixin + 开关(`[protection] nonBlockingCollision`, 默认 **true**),
 * **neoforge 模块一直没有** ⇒ 这个开关在 neoforge 上是空转的, 而这条 park 会以
 * "崩服 / 世界落在玩家身后" 的形式表现出来。
 *
 * ============================ 本 Mixin 做什么 ============================
 *   `Level.getChunkForCollisions(cx,cz)`: 若该区块**已经**生成到 FULL → 完全走原版;
 *   否则返回 **null**(原版"区块不存在"的返回值, BlockCollisions 直接跳过该区块) ⇒ 主线程永不 park。
 *
 * 语义影响(与 fabric 侧同一取舍): "还没生成完的区块" 对碰撞而言等于不存在 ——
 *   这与原版**客户端**的行为一致(客户端没有的区块本来就没有碰撞), 所以服务端与客户端
 *   对"玩家能不能从这儿过去"的判断反而更一致 → 回弹更少。
 *
 * 只作用于 ServerLevel(客户端 Level 本来不阻塞), 只做 `getChunkForCollisions` 一条
 *   —— fabric 的另两条(`getBlockState`/`getFluidState`)由 `nonBlockingReads` 控制且默认关闭,
 *   在 neoforge 上保持"未实现=不改变行为", 避免引入没验证过的语义变化。
 * 任何异常都静默回退原版; 开关关闭 → 一行不改。
 */
@Mixin(Level.class)
public abstract class LevelNonBlockingCollisionMixin {

    /** 该区块是否**已经**到 FULL (全程非阻塞: 一次 map 查找 + getNow)。 */
    private static boolean chunkpilot$isFullyReady(ServerLevel level, int chunkX, int chunkZ) {
        ChunkHolder holder = ((ServerChunkCacheAccessor) (ServerChunkCache) level.getChunkSource())
            .chunkpilot$getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        if (holder == null) return false;
        CompletableFuture<ChunkResult<LevelChunk>> full = holder.getFullChunkFuture();
        if (!full.isDone()) return false;
        ChunkResult<LevelChunk> res = full.getNow(null);
        return res != null && res.isSuccess() && res.orElse(null) != null;
    }

    private static boolean chunkpilot$enabled() {
        ChunkPilot cp = ChunkPilot.getInstance();
        return cp != null && cp.getConfig() != null && cp.getConfig().nonBlockingCollision;
    }

    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void chunkpilot$nonBlockingCollision(int chunkX, int chunkZ, CallbackInfoReturnable<BlockGetter> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, chunkX, chunkZ)) return;   // 已 FULL → 原版
            cir.setReturnValue(null);                                     // 原版"区块不存在"路径
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }
}
