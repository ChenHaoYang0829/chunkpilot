package com.chunkpilot;

import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.core.ChunkLoadOptimizer;
import com.chunkpilot.generation.GenerationScheduler;
import com.chunkpilot.network.ServerNetworkDispatcher;
import com.chunkpilot.platform.PlatformAbstraction;
import com.chunkpilot.send.ChunkSendScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChunkPilot 主入口类
 * 跨平台共享，各平台子类负责初始化
 */
public class ChunkPilot {
    public static final String MOD_ID = "chunkpilot";
    public static final String VERSION = "0.10.10-alpha";

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");
    private static ChunkPilot instance;

    private final PlatformAbstraction platform;
    private ChunkPilotConfig config;
    private ChunkLoadOptimizer optimizer;
    /** v0.3.0 轨道优先生成器. 由 C 子任务提供实现, D 子任务负责平台层接入. */
    private GenerationScheduler generationScheduler;
    /** v0.3.0 区块发送顺序优化器. Mixin 拦截 PlayerChunkSender 时调用. */
    private ChunkSendScheduler chunkSendScheduler;

    /** v0.4.0 服务端网络调度器. 管理握手+优先级下发. */
    private ServerNetworkDispatcher networkDispatcher;

    public ChunkPilot(PlatformAbstraction platform) {
        this.platform = platform;
        this.config = ChunkPilotConfig.load();
        this.optimizer = new ChunkLoadOptimizer(config, platform);
        this.optimizer.getIntegrationManager().init(platform, config);
        // v0.3.0: 初始化生成器. 默认 enabled=false, 不会触发生成;
        // 热重载时由 reloadConfig() 处理 (此处只 init 一次, 调度器自身无 toml 状态).
        this.generationScheduler = new GenerationScheduler();
        this.chunkSendScheduler = new ChunkSendScheduler();
        // v0.11.7 i18n: 先按配置定语言, 再注入"玩家客户端语言"解析器
        //   (玩家消息跟随其客户端语言; 控制台/日志用全局语言)
        com.chunkpilot.i18n.I18n.configure(config.language);
        com.chunkpilot.i18n.I18n.setPlayerLocaleResolver(platform::getPlayerLanguage);
        // v0.4.0: 网络调度器延迟初始化 (需要 PlatformNetworkSender, 由平台层注入)
        instance = this;
        LOG.info("ChunkPilot initialized: enabled={}, speedWindow={}t, lowSpeedThreshold={} b/t, providers={}, genScheduler={}, sendScheduler={}",
            config.enabled, config.speedWindowTicks, config.lowSpeedThreshold,
            this.optimizer.getIntegrationManager().getProviderSummary(),
            this.generationScheduler != null ? "ready" : "null",
            this.chunkSendScheduler != null ? "ready" : "null");
    }

    public static ChunkPilot getInstance() {
        return instance;
    }

    public ChunkPilotConfig getConfig() { return config; }
    public ChunkLoadOptimizer getOptimizer() { return optimizer; }
    public PlatformAbstraction getPlatform() { return platform; }
    /** v0.3.0 生成器. null 仅在 ChunkPilot 构造失败时出现. */
    public GenerationScheduler getGenerationScheduler() { return generationScheduler; }
    /** v0.3.0 发送排序器. null 仅在 ChunkPilot 构造失败时出现. */
    public ChunkSendScheduler getChunkSendScheduler() { return chunkSendScheduler; }

    /** v0.4.0 网络调度器. */
    public ServerNetworkDispatcher getNetworkDispatcher() { return networkDispatcher; }

    /** v0.4.0: 由平台层注入网络发送器, 初始化网络调度器. */
    public void initNetwork(com.chunkpilot.network.PlatformNetworkSender sender) {
        this.networkDispatcher = new ServerNetworkDispatcher(config, sender);
        LOG.info("ChunkPilot network dispatcher initialized");
    }

    /** 热重载配置（由 /chunkpilot reload config 调用） */
    public void reloadConfig() {
        this.config = ChunkPilotConfig.load();
        this.optimizer.updateConfig(config);
        this.optimizer.getIntegrationManager().init(platform, config);
        com.chunkpilot.i18n.I18n.configure(config.language);   // v0.11.7: 热重载 [general] language
        // 生成器自身不持有 toml 字段 (通过 onServerTick 参数传入),
        // 所以 reload 不需要重建调度器, 也不重置 currentChunkCount
        // (让自适应状态在 reload 后继续累积更稳).
        LOG.info("ChunkPilot config reloaded");
    }
}
