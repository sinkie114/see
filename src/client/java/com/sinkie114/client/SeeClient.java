package com.sinkie114.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Client entrypoint for the entity debug viewer. */
public class SeeClient implements ClientModInitializer {
    public static KeyMapping OPEN_DEBUG;
    /** Inspection reach in blocks; the survival interaction range (3) meant walking right up to every mob. Blocks still stop the ray. */
    public static final double PICK_RANGE = 32.0D;

    @Override
    public void onInitializeClient() {
        EntityEditBridge.initialize();
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("see", "entity_debug"));
        OPEN_DEBUG = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.see.open_debug",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category));
        ClientTickEvents.END_CLIENT_TICK.register(SeeClient::tick);
        ClientTickEvents.END_CLIENT_TICK.register(EntityNbtSession::tickActive);
    }

    private static void tick(Minecraft client) {
        if (client.screen != null || client.player == null || client.level == null) {
            while (OPEN_DEBUG.consumeClick()) { }
            return;
        }
        while (OPEN_DEBUG.consumeClick()) {
            Entity hit = nearestFromCamera(client);
            if (hit != null && hit != client.player && isVanilla(hit)) {
                EntityNbtSession.open(client, hit);
                break;
            }
            // Say why nothing opened instead of ignoring the key.
            client.player.displayClientMessage(Component.literal(hit != null && hit != client.player
                    ? "SEE 只支持原版实体：" + BuiltInRegistries.ENTITY_TYPE.getKey(hit.getType())
                    : "准星没有对准 " + (int) PICK_RANGE + " 格内的实体"), true);
        }
    }

    /** Picks using the active render camera, including detached/free camera. */
    public static Entity pickFromCamera(Minecraft client) {
        Entity entity = nearestFromCamera(client);
        // The player's own body is a hard stop.  This is deliberately checked
        // after sorting, so a closer entity in front of the player can still be
        // selected in third-person view.
        return entity == client.player || entity != null && !isVanilla(entity) ? null : entity;
    }

    private static boolean isVanilla(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals("minecraft");
    }

    /** The first entity the camera ray reaches before any block, or null. */
    private static Entity nearestFromCamera(Minecraft client) {
        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 start = camera.position();
        Vec3 direction = new Vec3(camera.forwardVector());
        double reach = Math.max(client.player.entityInteractionRange(), PICK_RANGE);
        Vec3 end = start.add(direction.scale(reach));

        HitResult blockHit = client.level.clip(new net.minecraft.world.level.ClipContext(
                start, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                camera.entity()));
        double blockDistance = blockHit.getType() == HitResult.Type.MISS
                ? reach : start.distanceTo(blockHit.getLocation());
        AABB search = new AABB(start, end).inflate(1.0D);
        List<Entity> candidates = client.level.getEntities((Entity) null, search,
                entity -> entity.isAlive() && !entity.isSpectator());
        Optional<Hit> nearest = candidates.stream()
                .map(entity -> hit(entity, start, end, blockDistance))
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparingDouble(Hit::distance));
        if (nearest.isEmpty() || nearest.get().distance() > blockDistance) return null;
        return nearest.get().entity();
    }

    private static Hit hit(Entity entity, Vec3 start, Vec3 end, double blockDistance) {
        AABB box = entity.getBoundingBox().inflate(Math.max(0.0D, entity.getPickRadius()));
        // A normal first-person camera starts inside its owner; that is not a ray entering the player's body.
        if (box.contains(start)) {
            if (entity == Minecraft.getInstance().player && entity.getBoundingBox().contains(start)) return null;
            return new Hit(entity, 0);
        }
        Optional<Vec3> point = box.clip(start, end);
        if (point.isEmpty()) return null;
        double distance = start.distanceTo(point.get());
        return distance <= blockDistance ? new Hit(entity, distance) : null;
    }

    private record Hit(Entity entity, double distance) {}
}
