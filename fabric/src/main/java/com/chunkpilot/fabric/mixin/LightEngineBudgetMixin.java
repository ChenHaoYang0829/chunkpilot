package com.chunkpilot.fabric.mixin;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * v0.11.0 光照/合并每 tick 上限 (治"墙").
 *
 * 背景: vanilla LightEngine.runLightUpdates() 把 increase/decrease 队列整个排空,
 * 高速飞行时一大批区块同时进入主线程光照/合并 → 主线程被堵死 8~29 秒 (Can't keep up),
 * 前沿停摆 → 玩家撞墙进虚空.
 *
 * 这里在 propagateIncreases()/propagateDecreases() HEAD 把队列截断到每 tick 上限,
 * 超出的条目存到 deferred, 在 runLightUpdates() RETURN 重新入队, 留到下 tick 处理.
 * 这样把 29s 的突发拆成多个 tick 的小块, 主线程不再被堵死.
 *
 * 任何异常回退 vanilla.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineBudgetMixin {

    /** 每 tick 每个队列最多处理的光照条目数 (pair 数). 可调. */
    private static final int MAX_PER_TICK = 64;

    @Shadow private LongArrayFIFOQueue decreaseQueue;
    @Shadow private LongArrayFIFOQueue increaseQueue;

    private static final List<long[]> DEFERRED_INC = new ArrayList<>();
    private static final List<long[]> DEFERRED_DEC = new ArrayList<>();

    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void chunkpilot$resetBudget(CallbackInfoReturnable<Integer> cir) {
        try {
            DEFERRED_INC.clear();
            DEFERRED_DEC.clear();
        } catch (Throwable t) { }
    }

    @Inject(method = "propagateIncreases", at = @At("HEAD"))
    private void chunkpilot$capIncrease(CallbackInfoReturnable<Integer> cir) {
        try {
            capQueue(this.increaseQueue, DEFERRED_INC);
        } catch (Throwable t) { }
    }

    @Inject(method = "propagateDecreases", at = @At("HEAD"))
    private void chunkpilot$capDecrease(CallbackInfoReturnable<Integer> cir) {
        try {
            capQueue(this.decreaseQueue, DEFERRED_DEC);
        } catch (Throwable t) { }
    }

    @Inject(method = "runLightUpdates", at = @At("RETURN"))
    private void chunkpilot$reEnqueueDeferred(CallbackInfoReturnable<Integer> cir) {
        try {
            reEnqueue(this.increaseQueue, DEFERRED_INC);
            reEnqueue(this.decreaseQueue, DEFERRED_DEC);
        } catch (Throwable t) { }
    }

    private static void capQueue(LongArrayFIFOQueue q, List<long[]> deferred) {
        if (q == null || q.size() <= MAX_PER_TICK * 2) return;
        List<long[]> all = new ArrayList<>(q.size() / 2);
        while (!q.isEmpty()) {
            long pos = q.dequeueLong();
            long level = q.dequeueLong();
            all.add(new long[]{pos, level});
        }
        int keep = Math.min(MAX_PER_TICK, all.size());
        for (int i = 0; i < keep; i++) {
            q.enqueue(all.get(i)[0]);
            q.enqueue(all.get(i)[1]);
        }
        for (int i = keep; i < all.size(); i++) {
            deferred.add(all.get(i));
        }
    }

    private static void reEnqueue(LongArrayFIFOQueue q, List<long[]> deferred) {
        for (long[] p : deferred) {
            q.enqueue(p[0]);
            q.enqueue(p[1]);
        }
        deferred.clear();
    }
}
