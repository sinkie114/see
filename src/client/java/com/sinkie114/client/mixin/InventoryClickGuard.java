package com.sinkie114.client.mixin;

import com.sinkie114.client.EntityDebugScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers inventory mods that call the vanilla click API directly rather than Screen.slotClicked. */
@Mixin(MultiPlayerGameMode.class)
public class InventoryClickGuard {
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), cancellable = true)
    private void see$guardReadOnly(int id, int slot, int button, ClickType type, Player player, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof EntityDebugScreen screen
                && (!screen.getMenu().canEdit() || screen.getMenu() != player.containerMenu)) ci.cancel();
    }
}
