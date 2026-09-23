package com.chunkpilot.neoforge.mixin;

import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 重写 NeoForge 的 CheckExtensibleEnums.start()
 *
 * 默认逻辑：OTHER 类型客户端 + 有非 optional clientbound extended enums → disconnect
 *
 * 问题：bench bot 是 vanilla-like 客户端，NeoForge 的 extensible enum 检查会断开它。
 *
 * 修复：直接 finishCurrentTask，跳过 extensible enum 检查。
 */
@Mixin(targets = "net.neoforged.neoforge.network.configuration.CheckExtensibleEnums", remap = false)
public class CheckExtensibleEnumsMixin {
    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot/CheckExtensibleEnumsMixin");

    private static Class<?> cls = null;
    private static Field listenerField = null;
    private static Field typeField = null;

    static {
        try {
            cls = Class.forName("net.neoforged.neoforge.network.configuration.CheckExtensibleEnums");
            listenerField = cls.getDeclaredField("listener");
            listenerField.setAccessible(true);
            typeField = cls.getDeclaredField("TYPE");
            typeField.setAccessible(true);
        } catch (Exception e) {
            LOG.error("[ChunkPilot] Failed to setup CheckExtensibleEnums reflection", e);
        }
    }

    @Overwrite
    public void start(Consumer<Packet<?>> consumer) {
        try {
            if (cls == null) return;
            Object listener = listenerField.get(this);
            Object type = typeField.get(null);
            // finishCurrentTask 在 vanilla 里是 **private** (1.21.9 javap 实证:
            //   net.minecraft.server.network.ServerConfigurationPacketListenerImpl
            //     private void finishCurrentTask(ConfigurationTask$Type)),
            // 所以必须 getDeclaredMethod + setAccessible;
            // 旧代码用 getMethod(public only) 在 1.21.9 上必然 NoSuchMethodException
            // → 被 catch 吞掉 → 这个 configuration task 永远不 finish → 客户端卡在配置阶段.
            Class<?> typeCls = Class.forName("net.minecraft.server.network.ConfigurationTask$Type");
            Method m = null;
            for (Class<?> c = listener.getClass(); c != null; c = c.getSuperclass()) {
                try { m = c.getDeclaredMethod("finishCurrentTask", typeCls); break; }
                catch (NoSuchMethodException ignored) { }
            }
            if (m == null) {
                LOG.error("[ChunkPilot] finishCurrentTask not found; cannot skip extensible-enum check");
                return;
            }
            m.setAccessible(true);
            m.invoke(listener, type);
        } catch (Exception e) {
            LOG.error("[ChunkPilot] CheckExtensibleEnumsMixin.start failed", e);
        }
    }
}
