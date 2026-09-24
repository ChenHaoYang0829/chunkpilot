package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.neoforge.util.MoveGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * v0.11.6 非阻塞区块读取 —— neoforge 移植 (main 作业书 §2 第 1 项)。
 *
 * **语义与 `com.chunkpilot.fabric.mixin.LevelNonBlockingReadMixin` 逐条等价**:
 * 三个入口全部先判"该区块是否**已经** FULL", 未就绪时返回**原版在"区块不存在"时本来就会返回的值**
 * (`null` / `Blocks.AIR` / `Fluids.EMPTY`), 不改变已加载区块的任何返回值、不改返回类型、不吞异常
 * (异常一律 `catch (Throwable)` 后放行原版)。
 *
 * ============================ 兼容性约束 (作业书 §3) ============================
 *  1. 只作用于 **ServerLevel**; 客户端 Level 本来就不阻塞 ⇒ 客户端逻辑零改动;
 *  2. 开关 = **已有的** `[protection] nonBlockingCollision` (碰撞) 与
 *     `[protection] nonBlockingReads` (方块/流体, 默认 false ⇒ 默认行为=原版);
 *     **不新增任何配置键**;
 *  3. 每个注入点 `require = 0, expect = 0` ⇒ 目标方法在某个版本不存在时**只是本 mixin 不生效**,
 *     绝不因硬编码 descriptor 让服务端启动崩溃 (本工程已多次因此崩服)。
 *
 * javap 依据 (1.21.9/1.21.10/1.21.11 三个版本的运行期 MC jar 逐个核对, 签名完全一致):
 *   Level.getChunkForCollisions(int,int) -> BlockGetter
 *   Level.getBlockState(BlockPos) -> BlockState
 *   Level.getFluidState(BlockPos) -> FluidState
 */
@Mixin(Level.class)
public abstract class LevelNonBlockingReadMixin {

    /** 该区块是否**已经**生成到 FULL (全程不阻塞: 一次 map 查找 + CompletableFuture.getNow). */
    private static boolean chunkpilot$isFullyReady(ServerLevel level, int chunkX, int chunkZ) {
        ChunkHolder holder = ((ServerChunkCacheAccessor) (ServerChunkCache) level.getChunkSource())
            .chunkpilot$getVisibleChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
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

    /** 方块/流体读取是否全局不阻塞 (false = 只在玩家移动包处理期间). */
    private static boolean chunkpilot$readsAlways() {
        ChunkPilot cp = ChunkPilot.getInstance();
        return cp == null || cp.getConfig() == null || cp.getConfig().nonBlockingReads;
    }

    // ---- 1) 碰撞查询: 始终生效 (只影响实体碰撞, 影响面最小, 收益最大) ----
    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true,
            require = 0, expect = 0)
    private void chunkpilot$nonBlockingCollision(int chunkX, int chunkZ,
                                                 CallbackInfoReturnable<BlockGetter> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, chunkX, chunkZ)) return;
            cir.setReturnValue(null); // 原版"区块不存在"路径
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    // ---- 2) 方块读取: 只在玩家移动包处理期间生效 ----
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true,
            require = 0, expect = 0)
    private void chunkpilot$nonBlockingBlockState(BlockPos pos,
                                                  CallbackInfoReturnable<BlockState> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            if (!chunkpilot$readsAlways() && !MoveGuard.active()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    // ---- 3) 流体读取: 只在玩家移动包处理期间生效 ----
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true,
            require = 0, expect = 0)
    private void chunkpilot$nonBlockingFluidState(BlockPos pos,
                                                  CallbackInfoReturnable<FluidState> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            if (!chunkpilot$readsAlways() && !MoveGuard.active()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }
}
