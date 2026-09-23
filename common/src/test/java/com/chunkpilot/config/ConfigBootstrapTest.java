package com.chunkpilot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.11.10 首次运行释放配置 —— ConfigBootstrap 测试.
 *
 * 三条核心保证:
 *   ① 目标不存在 → 释放, 且内容与 jar 内资源逐字节一致;
 *   ② 目标已存在 → 绝不覆盖 (服主改动必须保留);
 *   ③ 释放出来的文件能被 ConfigLoader 正常解析 (键真的生效).
 *
 * 全部使用 @TempDir, 不触碰真实 config/ 目录.
 */
@DisplayName("v0.11.10 首次运行释放配置")
class ConfigBootstrapTest {

    private static byte[] bundledResource() throws Exception {
        try (InputStream in = ConfigBootstrapTest.class.getResourceAsStream(ConfigBootstrap.RESOURCE)) {
            assertNotNull(in, "jar 内资源 " + ConfigBootstrap.RESOURCE + " 必须存在");
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("① 目标不存在 → 释放, 内容与 jar 内资源一致")
    void releasesWhenTargetMissing(@TempDir Path tmp) throws Exception {
        boolean existed = false;
        Path target = tmp.resolve("config/chunkpilot.toml");
        assertFalse(Files.exists(target), "前置条件: 目标不存在");

        Path written = ConfigBootstrap.releaseIfMissing(target);

        assertEquals(target, written, "应返回写出的路径");
        assertTrue(Files.exists(target), "目标文件应被创建");
        byte[] expected = bundledResource();
        assertTrue(expected.length > 0, "jar 内资源不能为空");
        assertArrayEquals(expected, Files.readAllBytes(target), "释放内容必须与资源逐字节一致");
    }

    @Test
    @DisplayName("①b 释放出的模板确实包含补全后的整节 (forward_window / speed)")
    void releasedTemplateContainsNewSections(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("nested/deep/chunkpilot.toml");
        assertNotNull(ConfigBootstrap.releaseIfMissing(target), "目录不存在时也应先建目录再释放");

        String text = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(text.contains("[forward_window]"), "模板必须含 [forward_window] 整节");
        assertTrue(text.contains("aheadChunks"), "模板必须含 aheadChunks");
        assertTrue(text.contains("[speed]"), "模板必须含 [speed] 整节");
        assertTrue(text.contains("lowSpeedRevoke"), "模板必须含 lowSpeedRevoke");
    }

    @Test
    @DisplayName("② 目标已存在 (自定义内容) → 不被覆盖")
    void neverOverwritesExistingConfig(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("config/chunkpilot.toml");
        Files.createDirectories(target.getParent());
        String custom = "# 服主自己的配置\n[general]\nenabled = false\nmode = \"none\"\n";
        Files.writeString(target, custom, StandardCharsets.UTF_8);

        Path written = ConfigBootstrap.releaseIfMissing(target);

        assertNull(written, "已存在时不应产生写入");
        assertEquals(custom, Files.readString(target, StandardCharsets.UTF_8),
            "已存在的配置必须一字不改");
    }

    @Test
    @DisplayName("②b 重复调用幂等: 第二次不再写、内容不变")
    void secondReleaseIsNoop(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("config/chunkpilot.toml");
        assertNotNull(ConfigBootstrap.releaseIfMissing(target), "第一次应释放");
        byte[] first = Files.readAllBytes(target);

        assertNull(ConfigBootstrap.releaseIfMissing(target), "第二次应直接跳过");
        assertArrayEquals(first, Files.readAllBytes(target), "内容不应变化");
    }

    @Test
    @DisplayName("③ 释放后的文件能被 ConfigLoader 正常加载, 模板里的键真的生效")
    void releasedFileIsLoadedByConfigLoader(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("config/chunkpilot.toml");
        Path written = ConfigBootstrap.releaseIfMissing(target);
        assertNotNull(written, "应先释放出文件");

        ChunkPilotConfig cfg = new ChunkPilotConfig();
        cfg.initDefaultsIfNeeded();
        ConfigLoader.loadFromToml(cfg, written);

        // 最强证据: 下面每个断言里, "模板里的值" 都与 "代码默认值" 不同
        // (这些正是模板刻意给出的调优值, 见 chunkpilot.toml 文件头说明),
        // 因此它们只能来自"释放出来的文件被真的解析了".
        assertTrue(cfg.generation.enabled, "[generation] enabled 模板 true / 代码默认 false");
        assertEquals(1.0, cfg.chunkSend.k, 1e-9, "[chunk_send] k 模板 1.0 / 代码默认 0.6");
        assertEquals(32, cfg.mtr.lookAheadChunks, "[integration.mtr] lookAheadChunks 模板 32 / 默认 8");
        assertEquals(80.0, cfg.immersiveVehicles.sectorAngle, 1e-9,
            "[integration.immersive_vehicles] sectorAngle 模板 80 / 默认 60");

        // 新增整节也确实生效
        assertEquals("auto", cfg.language, "[general] language 应被解析");
        assertEquals(20, cfg.speedWindowTicks, "[speed] 整节新增, windowTicks 应被解析");
        assertEquals(0.45, cfg.lowSpeedRevoke, 1e-9, "[speed] lowSpeedRevoke 应被解析");
        assertTrue(cfg.forwardWindow.enabled, "[forward_window] 整节新增, enabled 应为 true");
        assertEquals(13, cfg.forwardWindow.aheadChunks, "[forward_window] aheadChunks 应为 13");
        assertEquals(6, cfg.forwardWindow.forwardExtra, "[forward_window] forwardExtra 应为 6");
        assertEquals(6, cfg.forwardWindow.trackingShift, "[forward_window] trackingShift 应为 6");
        assertTrue(cfg.integrationEnabled, "[integration] 整节新增, enabled 应为 true");
        assertFalse(cfg.generation.strictSkipGenerated, "[generation] strictSkipGenerated 应为 false");
        // [speedTiers] 的 4 个档位也应被完整替换 (数组表解析链路)
        assertEquals(4, cfg.speedTiers.size(), "模板的 4 个速度档位应生效");
        assertEquals("extreme_flight", cfg.speedTiers.get(3).name);
    }

    @Test
    @DisplayName("④ 释放失败只返回 null, 不抛异常 (父路径不可建)")
    void releaseFailureIsSwallowed(@TempDir Path tmp) throws Exception {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "I am a file, not a directory\n", StandardCharsets.UTF_8);
        Path target = blocker.resolve("config/chunkpilot.toml");   // 父路径是文件 → createDirectories 必失败

        Path written = assertDoesNotThrow(() -> ConfigBootstrap.releaseIfMissing(target),
            "释放失败绝不能抛异常 (否则会影响启动)");
        assertNull(written, "失败时应返回 null");
    }

    @Test
    @DisplayName("⑤ 候选路径全部不存在时才需要释放 (anyCandidateExists 语义)")
    void anyCandidateExistsReflectsPaths() {
        // 只验证 API 语义: 当前工作目录下不存在这些相对路径中的任意一个时返回 false,
        // 存在任意一个时返回 true —— 两者互补, 不会两个都真/都假地矛盾.
        boolean exists = ConfigBootstrap.anyCandidateExists();
        assertEquals(ConfigBootstrap.defaultTarget(), Path.of(ConfigBootstrap.CANDIDATES[0]));
        assertTrue(exists || !Files.exists(ConfigBootstrap.defaultTarget()),
            "anyCandidateExists()==false 时默认目标必须不存在");
    }
}
