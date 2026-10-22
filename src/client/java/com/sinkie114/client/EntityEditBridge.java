package com.sinkie114.client;

import com.sinkie114.See;
import com.sinkie114.client.mixin.ServerPlayerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.List;
import java.util.UUID;

/** Only immutable display data crosses threads. Item actions use vanilla container packets. */
public final class EntityEditBridge {
    private EntityEditBridge() {}
    private static boolean opening;

    public static final class Session {
        volatile EntitySnapshot snapshot;
        volatile boolean invalid;
        volatile boolean closed;
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.containerMenu instanceof EntityDebugMenu menu) menu.tickServer(player);
            }
        });
    }

    public static void open(Minecraft client, Entity target) { open(client, target, null); }

    /** Opens the inventory container; {@code nbt} is the editor session it is a tab of (null when used standalone). */
    public static void open(Minecraft client, Entity target, EntityNbtSession nbt) {
        if (opening || client.player == null) return;
        var server = client.getSingleplayerServer();
        if (server == null) {
            client.setScreen(EntityDebugScreen.readOnly(target, client.player.getInventory(), nbt));
            return;
        }
        opening = true;
        var from = client.screen;
        var connection = client.getConnection();
        var clientLevel = client.level;
        UUID playerId = client.player.getUUID();
        UUID targetId = target.getUUID();
        var dimension = target.level().dimension();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            ServerLevel level = server.getLevel(dimension);
            Entity entity = level == null ? null : level.getEntity(targetId);
            if (player == null || entity == null || !entity.isAlive() || entity == player
                    || player.containerMenu != player.inventoryMenu || player.level() != level) {
                client.execute(() -> opening = false);
                return;
            }
            try {
                ServerPlayerAccessor access = (ServerPlayerAccessor) player;
                access.see$nextContainerCounter();
                int id = access.see$containerCounter();
                Session session = new Session();
                EntityDebugMenu serverMenu = EntityDebugMenu.server(id, entity, player, session);
                List<EntityItems.Description> descriptions = serverMenu.descriptions;
                serverMenu.tickServer(player);
                client.execute(() -> {
                    opening = false;
                    if (client.getConnection() != connection || client.level != clientLevel || client.player == null
                            || client.screen != from || target.isRemoved()) return;
                    EntityDebugMenu clientMenu = EntityDebugMenu.client(id, descriptions, client.player.getInventory(), true);
                    clientMenu.setClientTarget(target);
                    client.player.containerMenu = clientMenu;
                    client.setScreen(new EntityDebugScreen(target, clientMenu, session, nbt));
                    // Install the client menu BEFORE sending the initial vanilla slot packets.
                    server.execute(() -> {
                        if (session.closed || player.hasDisconnected() || player.containerMenu != player.inventoryMenu) {
                            session.invalid = true;
                            return;
                        }
                        player.containerMenu = serverMenu;
                        access.see$initMenu(serverMenu);
                        serverMenu.tickServer(player);
                        serverMenu.ready = !serverMenu.invalid;
                        serverMenu.broadcastChanges();
                    });
                });
            } catch (RuntimeException ex) {
                See.LOGGER.error("Unable to open entity inspector", ex);
                client.execute(() -> opening = false);
            }
        });
    }
}
