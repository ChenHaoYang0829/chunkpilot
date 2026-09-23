package com.chunkpilot.client;

/**
 * 帧时间采样器 (DESIGN.md §12.2)
 *
 * 滑动窗口 + EMA + P99 尖刺检测
 *
 * 设计:
 *  - 环形缓冲存最近 N 帧的帧时间 (纳秒)
 *  - EMA (指数移动平均) 平滑短期趋势
 *  - P99 统计检测尖刺 (GC 暂停、系统调度)
 *  - 同时分离 render-only 时间 (扣除 entities/UI/particles)
 *
 * 线程安全: 仅在客户端渲染线程调用 (Minecraft.renderTick)
 */
public class FrameTimeSampler {

    private final int windowSize;
    private final long[] frameTimes;     // 纳秒
    private int head = 0;
    private int size = 0;

    /** EMA 平滑后的平均帧时间 (纳秒) */
    private double emaFrameTime = -1;
    private static final double EMA_RATIO = 0.05;  // 5% 新样本权重

    /** 排序后的副本, 用于 P99 计算 (每次 getP99 时排序) */
    private final long[] sortedCopy;

    public FrameTimeSampler(int windowSize) {
        this.windowSize = Math.max(10, windowSize);
        this.frameTimes = new long[this.windowSize];
        this.sortedCopy = new long[this.windowSize];
    }

    /**
     * 每帧开始时调用, 记录上一帧耗时.
     *
     * @param frameNanos 上一帧总耗时 (纳秒), 由 Minecraft 提供
     * @param renderNanos 上一帧 render-only 耗时 (纳秒, 扣除 entities/UI)
     */
    public void recordFrame(long frameNanos, long renderNanos) {
        frameTimes[head] = frameNanos;
        head = (head + 1) % windowSize;
        if (size < windowSize) size++;

        // EMA 更新
        if (emaFrameTime < 0) {
            emaFrameTime = frameNanos;
        } else {
            emaFrameTime = emaFrameTime * (1.0 - EMA_RATIO) + frameNanos * EMA_RATIO;
        }
    }

    /** 简化版: 只传总帧时间, render-only 估算为 70% */
    public void recordFrame(long frameNanos) {
        recordFrame(frameNanos, (long) (frameNanos * 0.7));
    }

    /** EMA 平均帧时间 (毫秒) */
    public double getEmaFrameTimeMs() {
        return emaFrameTime / 1_000_000.0;
    }

    /**
     * P99 帧时间 (纳秒): 窗口内 99% 分位数.
     * 用于检测尖刺: P99 >> EMA 说明有 GC 暂停等异常.
     */
    public long getP99FrameTimeNanos() {
        if (size == 0) return 0;
        // 复制到排序数组
        for (int i = 0; i < size; i++) {
            int idx = (head - size + i + windowSize) % windowSize;
            sortedCopy[i] = frameTimes[idx];
        }
        // 部分排序找 P99
        int p99Index = (int) Math.ceil(size * 0.99) - 1;
        if (p99Index < 0) p99Index = 0;
        if (p99Index >= size) p99Index = size - 1;
        // 简单排序 (窗口不大, 用插入排序)
        for (int i = 1; i < size; i++) {
            long key = sortedCopy[i];
            int j = i - 1;
            while (j >= 0 && sortedCopy[j] > key) {
                sortedCopy[j + 1] = sortedCopy[j];
                j--;
            }
            sortedCopy[j + 1] = key;
        }
        return sortedCopy[p99Index];
    }

    /**
     * 是否处于抖动状态: P99 远超 EMA (×1.5 以上).
     * 抖动时应该用保守预算.
     */
    public boolean isJittery() {
        if (size < 10) return false;
        long p99 = getP99FrameTimeNanos();
        return p99 > emaFrameTime * 1.5;
    }

    /** 是否有足够样本 (至少 windowSize 的一半) */
    public boolean hasEnoughData() {
        return size >= Math.max(10, windowSize / 2);
    }

    /** 重置 (维度切换/传送后) */
    public void reset() {
        head = 0;
        size = 0;
        emaFrameTime = -1;
    }
}