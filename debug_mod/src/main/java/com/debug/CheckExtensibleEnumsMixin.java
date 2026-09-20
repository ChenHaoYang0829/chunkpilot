package com.debug;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 重写 CheckExtensibleEnums.start(), 对非 NEOFORGE 客户端直接跳过
 * 因为 bench bot 不是真正的 NeoForge 客户端，无法处理 ExtensibleEnumDataPayload
 */
@Mixin(targets = "net.neoforged.neoforge.network.configuration.CheckExtensibleEnums")
public class CheckExtensibleEnumsMixin {
    /**
     * @author DebugMod
     * @reason 跳过 extensible enum check 让 vanilla 客户端能连入 NeoForge 服务器
     */
    @Overwrite(remap = false)
    public void start(java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> consumer,
                     Operation<Void> original) {
        try {
            java.lang.reflect.Field listenerField = Class.forName("net.neoforged.neoforge.network.configuration.CheckExtensibleEnums")
                    .getDeclaredField("listener");
            listenerField.setAccessible(true);
            ServerConfigurationPacketListener listener = (ServerConfigurationPacketListener) listenerField.get(this);
            
            java.lang.reflect.Field typeField = Class.forName("net.neoforged.neoforge.network.configuration.CheckExtensibleEnums")
                    .getDeclaredField("TYPE");
            typeField.setAccessible(true);
            ConfigurationTask.Type type = (ConfigurationTask.Type) typeField.get(null);
            
            // 直接 finish task，让 vanilla 客户端跳过 extensible enum 检查
            listener.finishCurrentTask(type);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("DebugMod").error("Mixin failed", e);
        }
    }
}
