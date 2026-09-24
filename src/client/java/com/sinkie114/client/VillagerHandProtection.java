package com.sinkie114.client;

import net.minecraft.world.entity.npc.villager.Villager;

/** Marks real equipment edited through the inspector; no duplicate item storage. */
public interface VillagerHandProtection {
    boolean see$isMainHandProtected();
    void see$setMainHandProtected(boolean value);

    static boolean isProtected(Villager villager) {
        var protection = (VillagerHandProtection) villager;
        // Commands or other game logic can remove equipment without using the inspector.
        if (villager.getMainHandItem().isEmpty()) protection.see$setMainHandProtected(false);
        return protection.see$isMainHandProtected();
    }
}
