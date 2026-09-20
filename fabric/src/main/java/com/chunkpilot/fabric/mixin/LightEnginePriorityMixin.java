package com.chunkpilot.fabric.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.ChunkSendConfig;
import com.chunkpilot.core.SpeedTracker;
import com.chunkpilot.generation.ChunkWeightCalculator;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v0.11.0 点1: 让光照/合并按前方优先排序.
 *
 * 背景: vanilla 1.21.3 的 LightEngine 内部光照更新队列 (decreaseQueue/increaseQueue)
 * 是 FIFO (LongArrayFIFOQueue), 光照传播(最贵的合并部分)按插入顺序跑, 不按前方优先.
 * 这里在 runLightUpdates() HEAD 把两个队列 drain 出来, 按前方优先权重排序, 再重排 enqueue,
 * 让 vanilla 的 propagateIncreases/Decreases 按新顺序处理.
 *
 * 优先级权重复用发送器 ChunkSendScheduler 的投影公式 + core radius.
 * 任何异常回退 vanilla (不干扰光照).
 */
@Mixin(LightEngine.class)
public abstract class LightEnginePriorityMixin {

    @Shadow private LongArrayFIFOQueue decreaseQueue;
    @Shadow private LongArrayFIFOQueue increaseQueue;

    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void chunkpilot$reorderLightQueue(CallbackInfoReturnable<Integer> cir) {
        try {
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null) return;
            ChunkSendConfig cfg = cp.getConfig().chunkSend;
            if (cfg == null || !cfg.enabled) return;
            SpeedTracker st = cp.getOptimizer().getSpeedTracker();
            UUID playerId = st.getFastestPlayerId();
            if (playerId == null) return;
            double vBpt = st.getSpeed(playerId);
            if (!ChunkWeightCalculator.shouldActivate(vBpt, cfg.v_min)) return;
            double vMs = vBpt * 20.0;
            double dirRad = st.getDirection(playerId);
            double dirX = Math.cos(dirRad), dirZ = Math.sin(dirRad);
            int[] pc = cp.getPlatform().getPlayerChunkPos(playerId);
            if (pc == null) return;
            int viewDistance = cp.getPlatform().getServerRenderDistance();
            double lookAheadChunks = vMs * cfg.lookAheadSeconds / 16.0;
            double D = ChunkWeightCalculator.computeEffectiveRadius(viewDistance, lookAheadChunks);
            if (D <= 0) return;

            reorderQueue(this.decreaseQueue, pc[0], pc[1], vMs, dirX, dirZ, D, cfg.k);
            reorderQueue(this.increaseQueue, pc[0], pc[1], vMs, dirX, dirZ, D, cfg.k);
        } catch (Throwable t) {
            // 任何异常不干扰 vanilla
        }
    }

    private static void reorderQueue(LongArrayFIFOQueue q, int pcx, int pcz,
            double vMs, double dirX, double dirZ, double D, double k) {
        if (q == null || q.isEmpty()) return;
        List<long[]> pairs = new ArrayList<>();
        while (!q.isEmpty()) {
            long pos = q.dequeueLong();
            long level = q.dequeueLong();
            pairs.add(new long[]{pos, level});
        }
        pairs.sort((a, b) -> Double.compare(
            priority(b[0], pcx, pcz, vMs, dirX, dirZ, D, k),
            priority(a[0], pcx, pcz, vMs, dirX, dirZ, D, k)));
        for (long[] p : pairs) {
            q.enqueue(p[0]);
            q.enqueue(p[1]);
        }
    }

    private static double priority(long chunkPos, int pcx, int pcz,
            double vMs, double dirX, double dirZ, double D, double k) {
        int cx = ChunkPos.getX(chunkPos);
        int cz = ChunkPos.getZ(chunkPos);
        double dx = cx - pcx;
        double dz = cz - pcz;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= 3.0) return 1e9 - d;  // core radius
        double closingSpeed = vMs * (dirX * dx + dirZ * dz) / (d + 1e-9);
        double rho = d / D;
        double w = closingSpeed / (1.0 + k * rho);
        if (w <= 0) return 0.000001 / (1.0 + d);  // side/back
        return w;
    }
}
