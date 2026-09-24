package com.sinkie114.client.mixin;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(SynchedEntityData.class)
public interface EntityDataAccessor {
    @Accessor("itemsById") SynchedEntityData.DataItem<?>[] see$items();
}
