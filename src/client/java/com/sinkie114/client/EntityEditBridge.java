package com.sinkie114.client;

import com.sinkie114.See;
import com.sinkie114.client.mixin.ServerPlayerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Only immutable display data crosses threads. Item actions use vanilla container packets. */
public final class EntityEditBridge {
    private EntityEditBridge() {}
    private static boolean opening;

    public static final class Session {
        volatile EntitySnapshot snapshot;
        volatile boolean invalid;
        volatile boolean closed;
    }

    /** The entity's data as saved right after a write, and the paths the write tried to change. */
    public record NbtResult(CompoundTag actual, List<List<Object>> changed, boolean layoutChanged) {}

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.containerMenu instanceof EntityDebugMenu menu) menu.tickServer(player);
            }
        });
    }

    public static void open(Minecraft client, Entity target) {
        if (opening || client.player == null) return;
        var server = client.getSingleplayerServer();
        if (server == null) {
            client.setScreen(EntityDebugScreen.readOnly(target, client.player.getInventory()));
            return;
        }
        opening = true;
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
                            || client.screen != null || target.isRemoved()) return;
                    EntityDebugMenu clientMenu = EntityDebugMenu.client(id, descriptions, client.player.getInventory(), true);
                    clientMenu.setClientTarget(target);
                    client.player.containerMenu = clientMenu;
                    client.setScreen(new EntityDebugScreen(target, clientMenu, session));
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

    /** Writes on the integrated server thread; done runs on the render thread with a result or a failure message. */
    public static void writeNbt(Minecraft client, Session session, CompoundTag base, CompoundTag edited,
                                BiConsumer<NbtResult, String> done) {
        var server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            done.accept(null, "多人模式无法修改服务端实体数据");
            return;
        }
        UUID playerId = client.player.getUUID();
        CompoundTag requestedBase = base.copy(), requested = edited.copy();
        server.execute(() -> {
            NbtResult result = null;
            String failure = null;
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.containerMenu instanceof EntityDebugMenu menu)) throw new IllegalStateException("SEE 容器已关闭");
                result = menu.writeEntityNbt(player, session, requestedBase, requested);
            } catch (RuntimeException ex) {
                failure = rootMessage(ex);
            }
            NbtResult written = result;
            String reason = failure;
            client.execute(() -> done.accept(written, reason));
        });
    }

    /** Entity.load wraps its real failure in a crash report; show the underlying reason. */
    static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
