package com.chunkpilot.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家速度追踪器
 *
 * 设计：滑动窗口平均 + 近期样本加权
 *  - 窗口大小 windowTicks（默认 20 = 1 秒）
 *  - 窗口内的样本按 tick 顺序保留为环形缓冲
 *  - 平均速度 = (最新样本 - 最老样本) / tickDelta     ← 这就是"这一秒内平均速度"
 *  - 同时给"最近 recentTicks"的样本 50% 权重，让档位响应更快
 *
 * 成本：每个玩家 1 个 20 长度的环形 buffer (~200B) + 每 tick 一次写入
 *
 * 关闭优化：当 config.speedWindowTicks = 0 时退回瞬时（兼容旧逻辑/调试）
 */
public class SpeedTracker {

    /** 1 秒窗口 = 20 ticks */
    public static final int DEFAULT_WINDOW_TICKS = 20;
    /** 最近 5 ticks 加权 50% */
    public static final int DEFAULT_RECENT_TICKS = 5;

    private final int windowTicks;
    private final int recentTicks;

    /** 默认值（可被构造覆盖） */
    public SpeedTracker() {
        this(DEFAULT_WINDOW_TICKS, DEFAULT_RECENT_TICKS);
    }

    public SpeedTracker(int windowTicks, int recentTicks) {
        this.windowTicks = Math.max(2, windowTicks);
        this.recentTicks = Math.max(1, Math.min(recentTicks, windowTicks - 1));
    }

    private final Map<UUID, RingBuffer> buffers = new ConcurrentHashMap<>();

    /**
     * 每 tick 调用一次
     * @param playerId 玩家 UUID
     * @param x 当前方块 X（玩家块坐标用 chunkX<<4 也行，但用方块坐标精度更高）
     * @param z 当前方块 Z
     * @param currentTick 当前服务端 tick
     */
    /** v0.11.4: 一步位移超过该距离(方块)就判定为传送/跨维度, 清空窗口. 8 chunks = 128 格. */
    public static final double TELEPORT_DISTANCE = 128.0;

    public void update(UUID playerId, double x, double z, long currentTick) {
        RingBuffer buf = buffers.computeIfAbsent(playerId, k -> new RingBuffer(windowTicks));
        // v0.11.4: 传送/跨维度检测.
        //   旧行为: 窗口里存的是"传送前"的坐标, 下一帧算出的位移是跨维度差值 (几千格) →
        //   速度虚高 20 tick → ChunkLoadOptimizer 直接命中 extreme 档 + 生成侧疯狂预生成.
        //   现在一旦发现单步跳变过大, 直接丢历史样本, 从当前位置重新开始测速.
        Sample newest = buf.newest();
        if (newest != null) {
            double dx = x - newest.x, dz = z - newest.z;
            if (dx * dx + dz * dz > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
                buf.clear();
            }
        }
        buf.add(x, z, currentTick);
    }

    /** v0.11.4: 外部判定为传送/换维度时, 主动清空该玩家的速度窗口. */
    public void resetPlayer(UUID playerId) {
        RingBuffer buf = buffers.get(playerId);
        if (buf != null) buf.clear();
    }

    /**
     * 当前平均速度 (blocks/tick)
     * 公式：v_weighted = 0.5 * v_recent + 0.5 * v_window
     * 其中 v_recent = (recent 段首末距离 / 段 tick 数)
     *       v_window = (window 首末距离 / window tick 数)
     */
    public double getSpeed(UUID playerId) {
        RingBuffer buf = buffers.get(playerId);
        if (buf == null || buf.size() < 2) return 0;

        long firstTick = buf.oldestTick();
        long lastTick = buf.newestTick();
        if (lastTick <= firstTick) return 0;

        // 全窗口平均
        double vWindow = dist(buf.oldest(), buf.newest()) / (lastTick - firstTick);

        // 近期窗口平均（最近 N tick 段）
        long recentStartTick = Math.max(firstTick, lastTick - recentTicks);
        Sample recentStart = buf.sampleAtOrAfter(recentStartTick);
        if (recentStart == null) recentStart = buf.oldest();
        long recentDelta = lastTick - recentStart.tick;
        double vRecent = recentDelta > 0 ? dist(recentStart, buf.newest()) / recentDelta : 0;

        // 0.5 / 0.5 加权
        return 0.5 * vRecent + 0.5 * vWindow;
    }

    /** 移动方向（弧度），使用窗口最老→最新的方向 */
    public double getDirection(UUID playerId) {
        RingBuffer buf = buffers.get(playerId);
        if (buf == null || buf.size() < 2) return 0;
        Sample a = buf.oldest();
        Sample b = buf.newest();
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        if (dx == 0 && dz == 0) {
            // 玩家原地没动，尝试用最近的小位移
            return buf.lastNonZeroDirection();
        }
        return Math.atan2(dz, dx);
    }

    /**
     * 是否存在任一在线玩家速度 >= 给定阈值 (chunks/s).
     * 用于 exclusive-generation 判断"是否该由 CP 接管生成".
     * 单位换算: v(chunks/s) × 16(blocks/chunk) / 20(ticks/s) = b/t.
     */
    public boolean hasPlayerAboveSpeedChunksPerSecond(double vMinChunksPerS) {
        double vMinBpt = vMinChunksPerS * 16.0 / 20.0;
        for (UUID id : buffers.keySet()) {
            if (getSpeed(id) >= vMinBpt) return true;
        }
        return false;
    }

    /** 返回速度最快的玩家 UUID（无则 null） */
    public UUID getFastestPlayerId() {
        UUID best = null;
        double bestSpeed = -1;
        for (UUID id : buffers.keySet()) {
            double s = getSpeed(id);
            if (s > bestSpeed) {
                bestSpeed = s;
                best = id;
            }
        }
        return best;
    }

    /** 移除已离线玩家 */
    public void removePlayer(UUID playerId) {
        buffers.remove(playerId);
    }

    /** 用于 /chunkpilot debug 输出 */
    public String getDebugInfo(UUID playerId) {
        RingBuffer buf = buffers.get(playerId);
        if (buf == null) return "no data";
        return String.format("samples=%d, oldestTick=%d, newestTick=%d, oldest=(%.1f,%.1f), newest=(%.1f,%.1f)",
            buf.size(), buf.oldestTick(), buf.newestTick(),
            buf.oldest().x, buf.oldest().z, buf.newest().x, buf.newest().z);
    }

    // ===== 环形缓冲 =====

    static final class Sample {
        final double x, z;
        final long tick;
        Sample(double x, double z, long tick) { this.x = x; this.z = z; this.tick = tick; }
    }

    static final class RingBuffer {
        private final Sample[] data;
        private int head = 0;   // 写指针
        private int size = 0;  // 当前样本数
        private long firstTick = 0;
        private long lastTick = 0;
        private double lastNonZeroDir = 0;

        RingBuffer(int capacity) {
            this.data = new Sample[capacity];
        }

        void add(double x, double z, long tick) {
            if (size > 0) {
                long lastTickBefore = data[(head - 1 + data.length) % data.length].tick;
                if (tick == lastTickBefore) {
                    // 同 tick 重复写入：覆盖最后一帧（玩家可能完全静止）
                    data[(head - 1 + data.length) % data.length] = new Sample(x, z, tick);
                    return;
                }
            }
            data[head] = new Sample(x, z, tick);
            head = (head + 1) % data.length;
            if (size < data.length) size++;
            if (size == 1) firstTick = tick;
            lastTick = tick;

            // 记录最后一个非零方向
            if (size >= 2) {
                Sample prev = data[(head - 2 + data.length) % data.length];
                double dx = x - prev.x, dz = z - prev.z;
                if (dx != 0 || dz != 0) lastNonZeroDir = Math.atan2(dz, dx);
            }
        }

        int size() { return size; }

        /** v0.11.4: 清空窗口 (传送/换维度), 保留对象复用. */
        void clear() {
            for (int i = 0; i < data.length; i++) data[i] = null;
            head = 0;
            size = 0;
            firstTick = 0;
            lastTick = 0;
            lastNonZeroDir = 0;
        }
        /**
         * 返回当前窗口内真正最老样本的 tick。
         * 修复 bug: 原实现返回固定的 firstTick (只在 size==1 时设置一次),
         * buffer 滚动覆盖后 firstTick 不更新, 导致 lastTick-firstTick 膨胀为
         * "玩家进服以来总时长", vWindow 被稀释成 ~0, getSpeed 只剩 vRecent 撑 0.5 权重
         * => 检测速度只有真实值一半 (实测 0.83 vs 真实 1.64 b/t)。
         */
        long oldestTick() {
            Sample o = oldest();
            return o != null ? o.tick : firstTick;
        }
        long newestTick() { return lastTick; }

        Sample oldest() {
            if (size == 0) return null;
            // 最老是 head - size 位置
            int idx = (head - size + data.length) % data.length;
            return data[idx];
        }

        Sample newest() {
            if (size == 0) return null;
            int idx = (head - 1 + data.length) % data.length;
            return data[idx];
        }

        /** 返回 tick >= targetTick 的第一个样本（找不到返回 null） */
        Sample sampleAtOrAfter(long targetTick) {
            for (int i = 0; i < size; i++) {
                int idx = (head - size + i + data.length) % data.length;
                if (data[idx].tick >= targetTick) return data[idx];
            }
            return null;
        }

        double lastNonZeroDirection() { return lastNonZeroDir; }
    }

    private static double dist(Sample a, Sample b) {
        if (a == null || b == null) return 0;
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
