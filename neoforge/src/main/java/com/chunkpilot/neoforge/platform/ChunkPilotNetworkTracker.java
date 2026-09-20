package com.chunkpilot.neoforge.platform;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 累计每个玩家发送/接收字节数（被 mixin 调用）
 *
 * Mixin 拦截 Connection.channelRead0 (rx) 和 send (tx) 累加
 * 之后 Optimizer/command 通过 getDelta() 取累计值
 *
 * 设计：tracker 是服务端进程级别，跨命令调用
 * - getDelta() 返回当前累计 - 上次查询时的累计（即"自上次查询以来新增"）
 * - 用于 benchmark.py 每秒采样并计算 bps
 */
public class ChunkPilotNetworkTracker {

    /** playerId -> {rxBytes, txBytes} (累计) */
    private static final Map<UUID, long[]> BYTES = new ConcurrentHashMap<>();

    /** 上次查询的累计值（用于计算 delta） */
    private static final Map<UUID, long[]> LAST = new ConcurrentHashMap<>();

    public static void addRx(UUID playerId, int n) {
        if (n <= 0) return;
        BYTES.computeIfAbsent(playerId, k -> new long[2])[0] += n;
    }

    public static void addTx(UUID playerId, int n) {
        if (n <= 0) return;
        BYTES.computeIfAbsent(playerId, k -> new long[2])[1] += n;
    }

    public static void onPlayerJoin(UUID playerId) {
        BYTES.put(playerId, new long[2]);
        LAST.put(playerId, new long[2]);
    }

    public static void onPlayerLeave(UUID playerId) {
        BYTES.remove(playerId);
        LAST.remove(playerId);
    }

    /** 取当前累计 */
    public static long[] getCurrent(UUID playerId) {
        long[] cur = BYTES.get(playerId);
        return cur == null ? new long[]{0, 0} : new long[]{cur[0], cur[1]};
    }

    /** 取自上次查询以来的 delta，同时更新基线 */
    public static long[] getDelta(UUID playerId) {
        long[] cur = BYTES.get(playerId);
        long[] last = LAST.get(playerId);
        if (cur == null) {
            return null;
        }
        if (last == null) {
            long[] newLast = new long[]{cur[0], cur[1]};
            LAST.put(playerId, newLast);
            return new long[]{0, 0};
        }
        long[] delta = new long[]{cur[0] - last[0], cur[1] - last[1]};
        last[0] = cur[0];
        last[1] = cur[1];
        return delta;
    }

    /** 重置基线到当前累计（用于开始新的测量窗口） */
    public static void reset(UUID playerId) {
        long[] cur = BYTES.get(playerId);
        if (cur != null) {
            LAST.put(playerId, new long[]{cur[0], cur[1]});
        } else {
            LAST.remove(playerId);
        }
    }
}
