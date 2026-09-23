package com.chunkpilot.config;

import com.chunkpilot.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v0.11.10 首次运行释放参考配置.
 *
 * ============================ 为什么需要它 ============================
 * 运行时**只**读文件系统上的 `config/chunkpilot.toml`; 文件不存在时所有字段走代码默认值。
 * 而 jar 里打包的那份 `chunkpilot.toml` (120 个键、带逐键说明, 含 [forward_window] 等整节)
 * 以前**没有任何代码读取** —— 结果新装的服务器根本没有配置文件, 服主也看不到这些键。
 * 本类在启动时把 jar 内那份释放到 `config/chunkpilot.toml`。
 *
 * ============================ 行为约束 ============================
 *  - **已存在就绝不覆盖**: 只要任一候选路径已有配置, 就直接返回 (服主的改动必须保留);
 *  - 纯新增行为: 不改变任何既有默认值, 也不干预 {@link ConfigLoader} 的解析逻辑
 *    (释放出的文件随后由 ConfigLoader 正常解析, 与手抄一份完全等价);
 *  - **失败只告警**: 目录不可写、资源缺失、IO 异常都只打一行 warn,
 *    没有配置文件时一切按代码默认值运行, 绝不影响启动。
 */
public final class ConfigBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    /** jar 内打包的参考配置 (源: common/src/main/resources/chunkpilot.toml). */
    public static final String RESOURCE = "/chunkpilot.toml";

    /** 配置文件候选路径 —— 与 {@code ChunkPilotConfig.locateConfigFile()} 保持一致. */
    public static final String[] CANDIDATES = {
        "config/chunkpilot.toml",
        "chunkpilot.toml",
        "../config/chunkpilot.toml"
    };

    private ConfigBootstrap() {}

    /** 默认释放目标: {@code config/chunkpilot.toml}. */
    public static Path defaultTarget() {
        return Paths.get(CANDIDATES[0]);
    }

    /** 任一候选路径已存在 → 服主已经有配置文件, 什么都不要做. */
    public static boolean anyCandidateExists() {
        for (String c : CANDIDATES) {
            if (Files.exists(Paths.get(c))) return true;
        }
        return false;
    }

    /**
     * 启动时调用: 若一个配置文件都没有, 就把 jar 内的参考配置释放到默认路径.
     *
     * @return 实际写出的路径; 无需释放 / 释放失败时为 {@code null}
     */
    public static Path releaseIfAbsent() {
        if (anyCandidateExists()) return null;
        return releaseIfMissing(defaultTarget());
    }

    /**
     * 把 jar 内的参考配置释放到指定路径. **目标已存在时绝不覆盖**.
     *
     * @return 实际写出的路径; 目标已存在、资源缺失或写入失败时为 {@code null}
     */
    public static Path releaseIfMissing(Path target) {
        try {
            if (target == null || Files.exists(target)) return null;

            byte[] bytes;
            try (InputStream in = ConfigBootstrap.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    LOG.warn("[ChunkPilot] bundled config {} not found in jar, skipping first-run release", RESOURCE);
                    return null;
                }
                bytes = in.readAllBytes();
            }
            if (bytes.length == 0) {
                LOG.warn("[ChunkPilot] bundled config {} is empty, skipping first-run release", RESOURCE);
                return null;
            }

            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(target, bytes);

            LOG.info(I18n.tr("chunkpilot.log.config_released", target.toString()));
            return target;
        } catch (Throwable t) {
            // 释放失败绝不能影响启动: 没配置文件时一切按代码默认值走
            LOG.warn("[ChunkPilot] failed to release default config to {}: {}", target, t.toString());
            return null;
        }
    }
}
