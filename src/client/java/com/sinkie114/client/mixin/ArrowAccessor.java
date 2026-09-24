package com.sinkie114.client.mixin;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(AbstractArrow.class)
public interface ArrowAccessor {
    @Accessor("pickupItemStack") void see$clearPickupItemStack(ItemStack stack);
    @Invoker("setPickupItemStack") void see$setPickupItemStack(ItemStack stack);
}
