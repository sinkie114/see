package com.sinkie114.client;

import com.sinkie114.See;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Entity NBT editing copy. No custom packets: every server access runs on the integrated server executor.
 * Sync applies only the fields the user changed on top of the live entity, then reads the entity back.
 * The session outlives the individual editor screens and the inventory container screen.
 */
public final class EntityNbtSession {
    private static final int POLL_INTERVAL = 4;
    public static EntityNbtSession active;
    private static EditorMode lastMode = EditorMode.SIMPLE;

    public final Minecraft client;
    public final Entity target;
    public final boolean editable;
    public final String location;
    private final ClientLevel level;
    private final IntegratedServer server;
    private final ResourceKey<Level> dimension;
    private final UUID targetId;
    private boolean pollPending;
    private int ticks;
    private EditorMode mode = lastMode;
    private EntitySimpleScreen simple;
    private EntityNbtScreen advanced;
    private EntityInfoScreen info;
    public boolean syncing, valid = true;
    public CompoundTag document, baseline, observed;
    public NbtTools.Patch patch = NbtTools.Patch.EMPTY;
    /** Edited fields that the game changed after the copy was taken. */
    public List<String> conflicts = List.of();
    /** Read-only overview text (overview, attributes, status, motion) from the latest poll. */
    public Map<String, List<String>> pages = Map.of();
    public String status = "";
    public int revision;

    private record Read(CompoundTag data, Map<String, List<String>> pages) {}
    private record Result(CompoundTag data, List<String> warnings) {}

    private EntityNbtSession(Minecraft c, Entity target, boolean editable, Read read) {
        this.client = c; this.target = target; this.editable = editable;
        this.level = c.level; this.server = c.getSingleplayerServer();
        this.dimension = target.level().dimension(); this.targetId = target.getUUID();
        location = "SEE / " + target.getName().getString() + " (" + targetId + ")"
                + (target instanceof Player ? " · 玩家数据只读" : "");
        document = read.data().copy(); baseline = read.data().copy(); observed = read.data().copy();
        pages = read.pages();
    }

    /**
     * Opens the editor. Editable sessions start from the real server entity;
     * read-only ones (remote servers, players) from what the client can see.
     */
    public static void open(Minecraft client, Entity target) {
        if (client.player == null) return;
        var server = client.getSingleplayerServer();
        if (server == null) {
            present(client, target, false, readClient(target));
            return;
        }
        boolean editable = !(target instanceof Player);
        var dimension = target.level().dimension();
        UUID id = target.getUUID();
        Screen before = client.screen;
        server.execute(() -> {
            Read data = null; String failure = null;
            try { data = readServer(resolve(server, dimension, id)); }
            catch (RuntimeException ex) { failure = message(ex); }
            Read result = data; String reason = failure;
            client.execute(() -> {
                if (client.screen != before || client.player == null) return;
                if (result != null) present(client, target, editable, result);
                else client.player.displayClientMessage(net.minecraft.network.chat.Component.literal("无法打开实体数据：" + reason), true);
            });
        });
    }

    private static void present(Minecraft client, Entity target, boolean editable, Read read) {
        var session = new EntityNbtSession(client, target, editable, read);
        active = session;
        session.go(lastMode == EditorMode.INVENTORY ? EditorMode.SIMPLE : lastMode);
    }

    public static void tickActive(Minecraft client) {
        if (active == null) return;
        if (client.screen instanceof EntityNbtLayer layer && layer.session() == active) active.tick();
        else active = null;
    }

    public EditorMode mode() { return mode; }

    /** Switches tab. The inventory tab opens the vanilla container screen instead of an editor screen. */
    public void go(EditorMode next) {
        if (next == EditorMode.INVENTORY) { EntityEditBridge.open(client, target, this); return; }
        mode = next; lastMode = next;
        client.setScreen(editorScreen(next));
    }

    /** Back from the inventory container to the tab the user came from. */
    public void showEditor() { client.setScreen(editorScreen(mode)); }

    private Screen editorScreen(EditorMode m) {
        return switch (m) {
            case ADVANCED -> advanced != null ? advanced : (advanced = new EntityNbtScreen(this));
            case INFO -> info != null ? info : (info = new EntityInfoScreen(this));
            default -> simple != null ? simple : (simple = new EntitySimpleScreen(this));
        };
    }

    private static String message(RuntimeException ex) { return ex.getMessage() == null ? ex.toString() : ex.getMessage(); }

    private static Entity resolve(IntegratedServer server, ResourceKey<Level> dimension, UUID id) {
        ServerLevel level = server.getLevel(dimension);
        Entity entity = level == null ? null : level.getEntity(id);
        if (entity == null || entity.isRemoved() || !entity.isAlive()) throw new IllegalStateException("目标实体已死亡、移除或卸载");
        return entity;
    }

    private static CompoundTag save(Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    /** Server thread only. */
    private static Read readServer(Entity entity) {
        EntitySnapshot snapshot = EntitySnapshot.capture(entity, true, EntityItems.discover(entity, true));
        CompoundTag data = snapshot.raw().copy();
        // SEE's own display extras are not part of the entity.
        data.remove("SyncedData"); data.remove("VisibleItems");
        return new Read(data, snapshot.pages());
    }

    private static Read readClient(Entity entity) {
        EntitySnapshot snapshot = EntitySnapshot.capture(entity, false, EntityItems.discover(entity, false));
        return new Read(snapshot.raw().copy(), snapshot.pages());
    }

    private static void load(Entity entity, CompoundTag data, ProblemReporter reporter) {
        UUID uuid = entity.getUUID();
        entity.load(TagValueInput.create(reporter, entity.registryAccess(), data));
        // The same rule as the vanilla /data command: an entity keeps its identity.
        entity.setUUID(uuid);
    }

    public boolean writable() { return editable && !syncing; }

    public void edit(CompoundTag next) {
        if (!editable || syncing) return;
        document = next.copy(); revision++; status = "";
        patch = NbtTools.diff(baseline, document);
        updateConflicts();
    }

    private void updateConflicts() {
        List<String> list = new ArrayList<>();
        for (var change : patch.changes())
            if (!Objects.equals(NbtTools.atPath(baseline, change.path()), NbtTools.atPath(observed, change.path()))) list.add(NbtTools.path(change.path()));
        for (var path : patch.removed())
            if (!Objects.equals(NbtTools.atPath(baseline, path), NbtTools.atPath(observed, path))) list.add(NbtTools.path(path));
        conflicts = List.copyOf(list);
    }

    /** Re-reads the unedited fields from the entity while keeping every unsynced edit. */
    public void refresh() {
        if (syncing) return;
        int kept = patch.size();
        document = NbtTools.apply(observed, patch);
        baseline = observed.copy();
        patch = NbtTools.diff(baseline, document);
        conflicts = List.of(); revision++;
        status = kept == 0 ? "已读取最新实体数据" : "已读取最新实体数据，保留 " + kept + " 处未同步修改";
    }

    public void tick() {
        if (client.player == null || client.level != level) { valid = false; return; }
        if (++ticks % POLL_INTERVAL != 0) return;
        if (server == null || client.getSingleplayerServer() != server) {
            valid = target.isAlive() && !target.isRemoved();
            if (valid) accept(readClient(target));
            return;
        }
        if (pollPending || syncing) return;
        pollPending = true;
        server.execute(() -> {
            Read data = null; String failure = null;
            try { data = readServer(resolve(server, dimension, targetId)); }
            catch (RuntimeException ex) { failure = message(ex); }
            Read result = data; String reason = failure;
            client.execute(() -> {
                pollPending = false; valid = reason == null;
                if (result != null && !syncing) accept(result);
            });
        });
    }

    private void accept(Read read) {
        pages = read.pages();
        if (!read.data().equals(observed)) {
            observed = read.data().copy();
            if (editable) updateConflicts();
            else { document = observed.copy(); baseline = observed.copy(); revision++; }
        }
    }

    public void sync(Consumer<Boolean> done) {
        if (!editable || syncing || client.getSingleplayerServer() != server) return;
        if (patch.isEmpty()) { status = "没有待同步的修改"; done.accept(false); return; }
        if (client.level != level || client.player == null) { status = "同步失败：世界已切换"; done.accept(false); return; }
        NbtTools.Patch requested = patch; syncing = true; status = "同步中…";
        server.execute(() -> {
            Result written = null; String failure = null;
            try { written = write(requested); }
            catch (RuntimeException ex) { failure = message(ex); }
            Result result = written; String reason = failure;
            client.execute(() -> {
                syncing = false;
                if (result != null) {
                    document = result.data().copy(); baseline = result.data().copy(); observed = result.data().copy();
                    patch = NbtTools.Patch.EMPTY; conflicts = List.of(); valid = true; revision++;
                    List<String> warnings = result.warnings();
                    status = warnings.isEmpty() ? "同步成功" : "同步完成，但 " + warnings.size() + " 项未按请求保存："
                            + String.join("；", warnings.subList(0, Math.min(3, warnings.size()))) + (warnings.size() > 3 ? "…" : "");
                    if (!warnings.isEmpty()) See.LOGGER.warn("Entity NBT edit for {} was normalized: {}", targetId, warnings);
                    done.accept(true);
                } else { status = "同步失败：" + reason; done.accept(false); }
            });
        });
    }

    /** Server thread only. */
    private Result write(NbtTools.Patch requested) {
        Entity entity = resolve(server, dimension, targetId);
        if (entity instanceof Player) throw new IllegalStateException("与原版 /data 一致，不能通过 NBT 修改玩家");
        CompoundTag before = save(entity);
        CompoundTag merged = NbtTools.apply(before, requested);
        if (!Objects.equals(before.get("UUID"), merged.get("UUID"))) throw new IllegalStateException("UUID 不能修改");
        ProblemReporter.Collector problems = new ProblemReporter.Collector();
        // Loading only adds effects, so drop the current ones first; the merged data lists every wanted effect.
        boolean effects = requested.changes().stream().anyMatch(c -> c.path().getFirst().equals("active_effects"))
                || requested.removed().stream().anyMatch(p -> p.getFirst().equals("active_effects"));
        try {
            if (effects && entity instanceof LivingEntity living) living.removeAllEffects();
            load(entity, merged, problems);
        } catch (RuntimeException ex) {
            restore(entity, before);
            throw ex;
        }
        if (!problems.isEmpty()) {
            restore(entity, before);
            throw new IllegalStateException("部分字段无法解析，实体已还原：" + problems.getReport());
        }
        CompoundTag actual = save(entity);
        List<String> warnings = new ArrayList<>();
        for (var change : requested.changes()) {
            Tag stored = NbtTools.atPath(actual, change.path());
            if (!NbtTools.similar(change.value(), stored))
                warnings.add(NbtTools.path(change.path()) + " 请求 " + NbtTools.brief(change.value()) + "，实际 " + NbtTools.brief(stored));
        }
        for (var path : requested.removed())
            if (NbtTools.atPath(actual, path) != null) warnings.add(NbtTools.path(path) + " 仍然存在");
        return new Result(actual, List.copyOf(warnings));
    }

    private static void restore(Entity entity, CompoundTag before) {
        try { load(entity, before, ProblemReporter.DISCARDING); }
        catch (RuntimeException ex) { See.LOGGER.error("Unable to restore entity {} after a failed NBT edit", entity.getUUID(), ex); }
    }

    /** Leaves the editor entirely; unsynced edits are discarded. */
    public void close() {
        active = null;
        client.setScreen(null);
    }
}
