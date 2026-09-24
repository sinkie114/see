package com.sinkie114.client.mixin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ServerPlayer.class)
public interface ServerPlayerAccessor {
    @Invoker("nextContainerCounter") void see$nextContainerCounter();
    @Accessor("containerCounter") int see$containerCounter();
    @Invoker("initMenu") void see$initMenu(AbstractContainerMenu menu);
}
