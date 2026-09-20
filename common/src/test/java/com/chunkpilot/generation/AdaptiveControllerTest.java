package com.chunkpilot.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0.5 AdaptiveControllerTest — 自适应负载均衡
 *
 * 挡: MSPT 高→降载失效 / 低→升载失效 / 滞回带失效 / 下限失效 (降载到 0)
 */
class AdaptiveControllerTest {

    // ===== T18: MSPT 高 → 减少生成数 =====

    @Test
    void mspt超目标_降低chunk数() {
        // mspt=50 > target=35+5=40 → 降载
        int next = AdaptiveController.adapt(4, 50.0, 35.0, 4);
        assertEquals(3, next, "MSPT=50 超目标 40 必须降载");
    }

    @Test
    void mspt远超目标_连续降载() {
        int n1 = AdaptiveController.adapt(4, 100.0, 35.0, 4);
        int n2 = AdaptiveController.adapt(n1, 100.0, 35.0, 4);
        int n3 = AdaptiveController.adapt(n2, 100.0, 35.0, 4);
        assertEquals(3, n1);
        assertEquals(2, n2);
        assertEquals(1, n3);
    }

    // ===== T19: MSPT 低 → 保持/增加 (但不超过 max) =====

    @Test
    void mspt低_增加chunk数() {
        // mspt=20 < target=35-5=30 → 升载
        int next = AdaptiveController.adapt(2, 20.0, 35.0, 4);
        assertEquals(3, next, "MSPT=20 低于目标 30 必须升载");
    }

    @Test
    void mspt极低_升载但不超max() {
        int n = 4;
        for (int i = 0; i < 10; i++) {
            n = AdaptiveController.adapt(n, 1.0, 35.0, 4);
        }
        assertEquals(4, n, "多次升载后必须封顶到 max=4");
    }

    // ===== T20: 滞回带 (hysteresis) =====

    @Test
    void mspt在滞回带内_保持() {
        // target=35, 滞回带 ±5 → 30~40 内保持
        assertEquals(2, AdaptiveController.adapt(2, 35.0, 35.0, 4), "mspt=target 保持");
        assertEquals(2, AdaptiveController.adapt(2, 30.0, 35.0, 4), "mspt=30 在带内保持");
        assertEquals(2, AdaptiveController.adapt(2, 40.0, 35.0, 4), "mspt=40 在带内保持");
    }

    @Test
    void mspt刚好在边界_保持() {
        // 边界值 30.01 / 39.99
        assertEquals(2, AdaptiveController.adapt(2, 30.01, 35.0, 4), "30.01 在带内");
        assertEquals(2, AdaptiveController.adapt(2, 39.99, 35.0, 4), "39.99 在带内");
    }

    // ===== 边界 =====

    @Test
    void 降载允许降到0() {
        // v0.6.0: MSPT 严重超标时允许完全停手 (回归 vanilla 节奏)
        // 0804 崩溃教训: 负载高时 CP 必须能"完全停手", 不能降到 1 还继续推
        int next = AdaptiveController.adapt(1, 100.0, 35.0, 4);
        assertEquals(0, next, "降载下限 = 0 (MSPT 超标时完全停手)");
    }

    @Test
    void 升载上限不超max() {
        int next = AdaptiveController.adapt(100, 1.0, 35.0, 4);
        assertEquals(4, next, "升载上限 = max=4");
    }
}
