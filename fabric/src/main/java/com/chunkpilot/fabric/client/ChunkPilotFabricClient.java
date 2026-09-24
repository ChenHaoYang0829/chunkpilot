package com.chunkpilot.fabric.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 客户端入口 (ClientModInitializer) —— 1.0.0 起只剩一行日志.
 *
 * 1.0.0 之前这里初始化的是"客户端渲染优先级"链路 (ChunkPilotClient /
 * ChunkRenderScheduler / Sodium+vanilla 两个客户端 Mixin). 那条链路从未真正生效:
 * 本类当时只是 {@code new ChunkPilotClient(...)} 造了个局部对象, 从不赋给
 * {@code ChunkPilotClient.instance}, 于是所有查询点拿到的一律是 null 并静默 return.
 * 该链路已整体删除 (见 1.0.0 死代码清理), 本类仅保留:
 *   ① fabric.mod.json 的 client 入口点仍指向一个真实存在、可加载的类;
 *   ② 客户端启动时留一条可核对的日志.
 *
 * 服务端逻辑仍在 ChunkPilotFabric (main 入口) → ServerInitializer.
 */
public class ChunkPilotFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("[ChunkPilot] client side initialized (1.0.0: 客户端无渲染侧功能, 优化均来自服务端)");
    }
}
