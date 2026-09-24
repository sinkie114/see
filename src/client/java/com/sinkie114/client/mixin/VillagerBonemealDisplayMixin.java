package com.sinkie114.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.sinkie114.client.VillagerHandProtection;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.UseBonemeal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Farming still consumes bone meal from inventory; only the temporary hand icon is skipped. */
@Mixin(UseBonemeal.class)
public class VillagerBonemealDisplayMixin {
    @WrapWithCondition(method = {
            "start(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;J)V",
            "stop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;J)V"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/villager/Villager;setItemSlot(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;)V"))
    private boolean see$preserveHeldItem(Villager villager, EquipmentSlot slot, ItemStack preview) {
        return !VillagerHandProtection.isProtected(villager);
    }
}
