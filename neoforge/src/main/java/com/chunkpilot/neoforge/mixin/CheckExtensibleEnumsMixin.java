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
            // finishCurrentTask 是 private (javap 实证 1.21.10:
            //   ServerConfigurationPacketListenerImpl.finishCurrentTask(ConfigurationTask$Type)V = private),
            // 所以必须用 getDeclaredMethod + setAccessible —— 用 getMethod 会找不到 (NoSuchMethodException,
            // 被下面 catch 吃掉 → 配置阶段任务永不结束 → 客户端卡在 configuration)。
            // 同时向上遍历类层次, 兼容它被挪到父类的版本。
            Method m = null;
            Class<?> cur = listener.getClass();
            Class<?> typeCls = Class.forName("net.minecraft.server.network.ConfigurationTask$Type");
            while (cur != null) {
                try {
                    m = cur.getDeclaredMethod("finishCurrentTask", typeCls);
                    break;
                } catch (NoSuchMethodException ignored) {
                    cur = cur.getSuperclass();
                }
            }
            if (m == null) {
                LOG.error("[ChunkPilot] CheckExtensibleEnumsMixin: finishCurrentTask not found on {}",
                    listener.getClass().getName());
                return;
            }
            m.setAccessible(true);
            m.invoke(listener, type);
        } catch (Exception e) {
            LOG.error("[ChunkPilot] CheckExtensibleEnumsMixin.start failed", e);
        }
    }
}
