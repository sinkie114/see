package com.sinkie114.client.mixin;

import com.sinkie114.client.VillagerHandProtection;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public class VillagerHandProtectionMixin implements VillagerHandProtection {
    @Unique private boolean see$mainHandProtected;

    @Override public boolean see$isMainHandProtected() { return see$mainHandProtected; }
    @Override public void see$setMainHandProtected(boolean value) { see$mainHandProtected = value; }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void see$saveHandProtection(ValueOutput output, CallbackInfo ci) {
        if (VillagerHandProtection.isProtected((Villager) (Object) this)) output.putBoolean("see:mainhand_edited", true);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void see$loadHandProtection(ValueInput input, CallbackInfo ci) {
        see$mainHandProtected = input.getBooleanOr("see:mainhand_edited", false);
    }
}
