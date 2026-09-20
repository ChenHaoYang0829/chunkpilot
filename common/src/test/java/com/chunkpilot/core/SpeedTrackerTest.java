package com.chunkpilot.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpeedTrackerTest — 验证速度检测
 *
 * 挡: oldestTick 滚动不更新的 bug (firstTick 只在 size==1 设置, buffer 满后不更新
 *      => lastTick-firstTick 膨胀为总时长, vWindow 被稀释, getSpeed 减半)
 *
 * 修复前: 匀速 1.64 b/t 飞行, 持续 >20 tick 后 getSpeed ≈ 0.82 (一半)
 * 修复后: getSpeed ≈ 1.64 (正确)
 */
class SpeedTrackerTest {

    @Test
    void 匀速飞行速度检测不衰减() {
        SpeedTracker st = new SpeedTracker(20, 5);  // 20 tick 窗口, 5 tick 近期
        UUID pid = UUID.randomUUID();

        // 模拟: 每 tick 沿 +X 移动 1.64 blocks (真实鞘翅滑翔水平速度)
        double x = 0, z = 0;
        long ticks = 100;  // 飞 100 tick (5 秒), 足够让 buffer 满并滚动
        for (long t = 1; t <= ticks; t++) {
            x += 1.64;
            st.update(pid, x, z, t);
        }

        double speed = st.getSpeed(pid);
        // 修复后应接近 1.64, 允许小误差
        assertEquals(1.64, speed, 0.1,
            "匀速飞行 100 tick 后速度应保持 1.64 b/t (修复前会衰减到 ~0.82)");
    }

    @Test
    void 短窗口内速度正确() {
        SpeedTracker st = new SpeedTracker(20, 5);
        UUID pid = UUID.randomUUID();

        // 只飞 10 tick, buffer 未满
        double x = 0;
        for (long t = 1; t <= 10; t++) {
            x += 2.0;
            st.update(pid, x, 0, t);
        }
        assertEquals(2.0, st.getSpeed(pid), 0.1,
            "短窗口(未满)速度应正确 = 2.0 b/t");
    }

    @Test
    void 静止玩家速度为零() {
        SpeedTracker st = new SpeedTracker(20, 5);
        UUID pid = UUID.randomUUID();
        for (long t = 1; t <= 50; t++) {
            st.update(pid, 100.0, 100.0, t);  // 原地不动
        }
        assertEquals(0.0, st.getSpeed(pid), 0.01, "静止玩家速度应为 0");
    }

    @Test
    void 方向检测正确() {
        SpeedTracker st = new SpeedTracker(20, 5);
        UUID pid = UUID.randomUUID();
        // 沿 +X 飞行
        double x = 0, z = 0;
        for (long t = 1; t <= 30; t++) {
            x += 1.0;
            st.update(pid, x, z, t);
        }
        double dir = st.getDirection(pid);
        // +X 方向 => atan2(0, 1) = 0
        assertEquals(0.0, dir, 0.01, "沿 +X 飞行方向应为 0 rad");
    }
}
