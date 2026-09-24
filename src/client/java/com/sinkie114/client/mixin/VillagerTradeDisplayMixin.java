package com.sinkie114.client.mixin;

import com.sinkie114.client.VillagerHandProtection;
import net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Trade previews must not overwrite real items placed by the player. */
@Mixin(ShowTradesToPlayer.class)
public class VillagerTradeDisplayMixin {
    @Inject(method = "clearHeldItem", at = @At("HEAD"), cancellable = true)
    private static void see$preserveHeldItem(Villager villager, CallbackInfo ci) {
        if (VillagerHandProtection.isProtected(villager)) ci.cancel();
    }

    @Inject(method = "displayAsHeldItem", at = @At("HEAD"), cancellable = true)
    private static void see$preserveHeldItem(Villager villager, ItemStack preview, CallbackInfo ci) {
        if (VillagerHandProtection.isProtected(villager)) ci.cancel();
    }
}
