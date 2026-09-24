package com.chunkpilot.forge.client;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 客户端 tick 事件监听器 —— **只在 Dist.CLIENT 下被实例化** (见 ChunkPilotForge 构造器里的
 * `FMLEnvironment.dist.isClient()` 判断)。
 *
 * 这类里没有任何客户端专用 MC 类型: `TickEvent.ClientTickEvent` 属于 Forge 的通用事件类,
 * 在专用服务端上引用它也不会加载客户端类。
 */
public class ChunkPilotForgeClientEvents {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try {
            ChunkPilotForgeClient.onClientTick();
        } catch (Throwable t) {
            // 客户端侧异常绝不上抛
        }
    }
}
