package com.chunkpilot.fabric;

import com.chunkpilot.ChunkPilot;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChunkPilot Fabric 入口
 *
 * 注意: 此类在客户端和服务端都会被 Class.forName 加载.
 * 不能直接 import 服务端专属类 (ServerPlayer, PlayerList, ServerLevel 等),
 * 否则客户端启动时会 ClassNotFoundException.
 *
 * 服务端逻辑放在 ServerInitializer 内部类中, 延迟加载.
 */
public class ChunkPilotFabric implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ChunkPilot");

    @Override
    public void onInitialize() {
        LOGGER.info("ChunkPilot initializing on Fabric...");

        // 检测环境: 只在服务端初始化核心逻辑
        String envType = System.getProperty("fabric.game.version", "");
        // fabric loader 设置的环境类型
        boolean isClient = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;

        if (isClient) {
            // 客户端: 不初始化服务端逻辑 (客户端入口由 ChunkPilotFabricClient 处理)
            LOGGER.info("ChunkPilot: client environment, skipping server initialization");
            return;
        }

        // 服务端: 延迟加载服务端逻辑 (避免客户端类加载时触发服务端类依赖)
        ServerInitializer.initialize();
    }
}