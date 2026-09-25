package com.sinkie114.client;

import com.sinkie114.See;
import com.sinkie114.client.mixin.SlotAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/** A vanilla menu on both sides; only the server commits changed entity slots. */
public final class EntityDebugMenu extends AbstractContainerMenu {
    public final List<EntityItems.Description> descriptions;
    final List<EntityItems.Entry> entries;
    public final SimpleContainer items;
    public final Inventory inventory;
    public final boolean integrated;
    public boolean ready;
    public boolean invalid;
    public int otherPage;
    private final int[] entityPositions;
    private final int otherSlotCount;
    private boolean itemsPage = true;
    private Entity target;
    private final EntityEditBridge.Session session;

    private EntityDebugMenu(int id, List<EntityItems.Description> descriptions, List<EntityItems.Entry> entries,
                            Inventory inventory, boolean integrated, Entity target, EntityEditBridge.Session session) {
        super(MenuType.GENERIC_9x3, id);
        this.descriptions = descriptions;
        this.entries = entries;
        this.inventory = inventory;
        this.integrated = integrated;
        this.target = target;
        this.session = session;
        entityPositions = new int[descriptions.size()];
        int otherCount = 0;
        for (int i = 0; i < descriptions.size(); i++) {
            int fixed = switch (descriptions.get(i).key()) {
                case "equipment.head" -> 0;
                case "equipment.chest" -> 1;
                case "equipment.legs" -> 2;
                case "equipment.feet" -> 3;
                case "equipment.mainhand" -> 9;
                case "equipment.offhand" -> 10;
                default -> -1;
            };
            entityPositions[i] = fixed >= 0 ? fixed : 18 + otherCount++;
        }
        otherSlotCount = otherCount;
        items = new SimpleContainer(descriptions.size()) {
            @Override public void setItem(int index, ItemStack stack) {
                // Real slot interactions enforce their limits; network/display copies must remain exact.
                getItems().set(index, stack);
                setChanged();
            }
        };
        for (int i = 0; i < descriptions.size(); i++) {
            final int index = i;
            addSlot(new Slot(items, i, 0, 0) {
                @Override public boolean mayPlace(ItemStack stack) { return canEdit() && itemsPage && descriptions.get(index).accepts(stack); }
                @Override public boolean mayPickup(Player player) { return canEdit() && itemsPage; }
                @Override public int getMaxStackSize() { return descriptions.get(index).limit(); }
                @Override public int getMaxStackSize(ItemStack stack) { return Math.min(getMaxStackSize(), stack.getMaxStackSize()); }
                @Override public boolean isActive() { return itemsPage && (session != null || entityPositions[index] < 18
                        || (entityPositions[index] - 18) / 9 == otherPage); }
            });
        }
        for (int i = 0; i < 36; i++) {
            int inventoryIndex = i < 27 ? i + 9 : i - 27;
            addSlot(new Slot(inventory, inventoryIndex, 0, 0) {
                @Override public boolean mayPlace(ItemStack stack) { return canEdit(); }
                @Override public boolean mayPickup(Player player) { return canEdit(); }
            });
        }
        addDataSlot(new DataSlot() {
            @Override public int get() { return invalid ? 2 : ready ? 1 : 0; }
            @Override public void set(int value) { invalid |= value == 2; ready = value == 1; }
        });
        refreshFromEntity();
    }

    public static EntityDebugMenu server(int id, Entity target, ServerPlayer player, EntityEditBridge.Session session) {
        List<EntityItems.Entry> entries = EntityItems.discover(target, true);
        return new EntityDebugMenu(id, entries.stream().map(EntityItems.Entry::description).toList(), entries,
                player.getInventory(), true, target, session);
    }
    public static EntityDebugMenu client(int id, List<EntityItems.Description> slots, Inventory inventory, boolean integrated) {
        return new EntityDebugMenu(id, slots, List.of(), inventory, integrated, null, null);
    }
    public void setClientTarget(Entity entity) { target = entity; }
    public boolean isItemsPage() { return itemsPage; }
    public void setItemsPage(boolean itemsPage) {
        this.itemsPage = itemsPage;
        resetQuickCraft();
    }
    @Override public boolean clickMenuButton(Player player, int button) {
        if (button != 0 && button != 1) return false;
        setItemsPage(button == 0);
        return true;
    }
    public boolean canEdit() {
        return integrated && ready && !invalid && (target == null || target.isAlive() && !target.isRemoved()
                && inventory.player.level() == target.level() && target.level().getEntity(target.getId()) == target);
    }
    public int otherPages() { return Math.max(1, (otherSlotCount + 8) / 9); }
    public void refreshFromEntity() {
        if (invalid) return;
        // This is a display cache, so it must not truncate stacks edited by other tools.
        for (int i = 0; i < entries.size(); i++) items.getItems().set(i, entries.get(i).access().get().copy());
    }

    /** Optional NBT Maker API. Stable method names intentionally avoid a dependency on that mod. */
    public boolean see$nbtEditable() { return integrated; }

    public String see$nbtSourceLabel(int index) {
        checkDebugIndex(index);
        String name = target == null ? "实体" : target.getName().getString() + " (" + target.getUUID() + ")";
        return "SEE / " + name + " / " + (index < descriptions.size()
                ? descriptions.get(index).label() + " [" + descriptions.get(index).key() + "]"
                : "玩家背包 " + getSlot(index).getContainerSlot());
    }

    private void checkDebugIndex(int index) {
        if (index < 0 || index >= slots.size()) throw new IllegalStateException("SEE 槽位已失效");
    }

    private void checkDebugServer(ServerPlayer player, int index) {
        checkDebugIndex(index);
        if (session == null || !integrated || !ready || session.closed || player.containerMenu != this
                || inventory.player != player || !validate(player)) throw new IllegalStateException("SEE 目标实体或容器已失效");
    }

    /** Call only on the integrated server thread; read the actual entity rather than the display cache. */
    public ItemStack see$nbtReadServer(ServerPlayer player, int index) {
        checkDebugServer(player, index);
        return (index < entries.size() ? entries.get(index).access().get() : getSlot(index).getItem()).copy();
    }

    /** Keep a remote viewer live while its SEE screen is suspended underneath the item editor. */
    public ItemStack see$nbtReadClient(int index) {
        checkDebugIndex(index);
        if (invalid || target == null || !target.isAlive() || target.isRemoved()
                || target.level() != inventory.player.level() || target.level().getEntity(target.getId()) != target)
            throw new IllegalStateException("SEE 目标实体已失效");
        if (index >= descriptions.size()) return getSlot(index).getItem().copy();
        if (integrated) return items.getItem(index).copy();
        String key = descriptions.get(index).key();
        return EntityItems.discover(target, false).stream().filter(entry -> entry.key().equals(key))
                .findFirst().orElseThrow(() -> new IllegalStateException("SEE 物品槽位已失效")).access().get().copy();
    }

    /** Explicit external editor write: preserve SEE's entity-specific setters and verify actual storage. */
    public ItemStack see$nbtWriteServer(ServerPlayer player, int index, ItemStack requested) {
        checkDebugServer(player, index);
        if (index >= entries.size()) {
            getSlot(index).set(requested.copy());
            player.getInventory().setChanged();
            return getSlot(index).getItem().copy();
        }
        EntityItems.Entry entry = entries.get(index);
        if (!entry.description().accepts(requested) || entry.key().equals("carried_block") && !requested.isEmpty() && requested.getCount() != 1)
            throw new IllegalStateException("该槽位保存的是方块状态，无法保存物品数量或自定义组件");
        ItemStack before = entry.access().get().copy();
        boolean accepted = entry.access().set(requested.copy());
        ItemStack actual = entry.access().get().copy();
        if (!accepted || !ItemStack.matches(requested, actual)) {
            // For example, item frames normalize the count to 1. Reject without losing the previous item.
            if (!target.isRemoved()) entry.access().set(before);
            refreshFromEntity(); broadcastChanges();
            throw new IllegalStateException("SEE 实体槽位不能完整保存这些数据，原物品已保留");
        }
        refreshFromEntity();
        return actual;
    }
    /**
     * Integrated server thread only. Replays the editor's changes (base → edited) onto the entity's current data,
     * so fields that changed in the meantime keep their live values. A rejected load restores the previous data.
     */
    public EntityEditBridge.NbtResult writeEntityNbt(ServerPlayer player, EntityEditBridge.Session owner,
                                                     CompoundTag base, CompoundTag edited) {
        if (session == null || session != owner || !integrated || !ready || session.closed || player.containerMenu != this
                || inventory.player != player || !validate(player)) throw new IllegalStateException("SEE 目标实体或容器已失效");
        if (target instanceof Player) throw new IllegalStateException("原版不允许修改玩家数据，玩家 NBT 只能查看");
        CompoundTag before = EntitySnapshot.saveNbt(target);
        NbtTree.Merge merge = NbtTree.merge(base, edited, before);
        if (merge.changed().isEmpty()) throw new IllegalStateException("没有待同步的修改");
        String rejected = NbtTree.invalidVectors(merge.result());
        if (!rejected.isEmpty()) throw new IllegalStateException(rejected);
        try {
            EntitySnapshot.loadNbt(target, merge.result());
        } catch (RuntimeException ex) {
            try {
                EntitySnapshot.loadNbt(target, before);
            } catch (RuntimeException restore) {
                invalid = true;
                ready = false;
                session.invalid = true;
                See.LOGGER.error("Unable to restore entity {} after a rejected NBT edit", target.getUUID(), restore);
            }
            throw new IllegalStateException("游戏无法载入这些数据，实体已恢复原状：" + EntityEditBridge.rootMessage(ex));
        }
        CompoundTag actual = EntitySnapshot.saveNbt(target);
        boolean layoutChanged = !EntityItems.discover(target, true).stream().map(EntityItems.Entry::description).toList().equals(descriptions);
        // Publish the new data before the reply, so the editor never sees an older snapshot afterwards.
        tickServer(player);
        return new EntityEditBridge.NbtResult(actual, merge.changed(), layoutChanged);
    }

    private boolean validate(ServerPlayer player) {
        if (invalid) return false;
        boolean accessible = !session.invalid && target != null && target.isAlive() && !target.isRemoved() && target.level() == player.level()
                && player.level().getEntity(target.getUUID()) == target;
        if (accessible) accessible = EntityItems.discover(target, true).stream().map(EntityItems.Entry::description).toList().equals(descriptions);
        if (!accessible) {
            invalid = true;
            ready = false;
            session.invalid = true;
        }
        return accessible;
    }
    public void tickServer(ServerPlayer player) {
        if (session == null) return;
        if (session.closed) {
            if (player.containerMenu == this) player.closeContainer();
            return;
        }
        if (!validate(player)) return;
        refreshFromEntity();
        try {
            session.snapshot = EntitySnapshot.capture(target, true, entries);
        } catch (RuntimeException ex) {
            invalid = true;
            session.invalid = true;
            See.LOGGER.error("Unable to refresh entity inspector for {}", target.getUUID(), ex);
        }
    }
    public void layout(int x, int entityY, int playerY) {
        otherPage = Math.max(0, Math.min(otherPage, otherPages() - 1));
        for (int i = 0; i < slots.size(); i++) {
            int local = i < descriptions.size() ? entityPositions[i] : i - descriptions.size();
            int sx = x + local % 9 * 18;
            int sy = i < descriptions.size() ? entityY + Math.min(local / 9, 2) * 18
                    : playerY + local / 9 * 18 + (local >= 27 ? 4 : 0);
            ((SlotAccessor) slots.get(i)).see$setX(sx);
            ((SlotAccessor) slots.get(i)).see$setY(sy);
        }
    }
    @Override public boolean stillValid(Player player) { return true; }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (session != null && !validate((ServerPlayer) player)) return;
        if (!canEdit() || !isValidSlotIndex(slot)) return;
        if (slot >= 0 && slot < descriptions.size() && !itemsPage) return;
        // Vanilla checks creative abilities for CLONE and creative quick craft on both sides.
        if (session != null) refreshFromEntity();
        List<ItemStack> before = session == null ? List.of() : entries.stream().map(e -> e.access().get().copy()).toList();
        super.clicked(slot, button, type, player);
        if (session != null) {
            for (int i = 0; i < entries.size(); i++) {
                ItemStack updated = items.getItem(i);
                if (!ItemStack.matches(before.get(i), updated)) entries.get(i).access().set(updated.copy());
            }
            player.getInventory().setChanged();
        }
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!canEdit() || index < 0 || index >= slots.size() || index < descriptions.size() && !itemsPage) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack moving = slot.getItem();
        ItemStack original = moving.copy();
        int count = descriptions.size();
        boolean moved = index < count ? moveItemStackTo(moving, count, slots.size(), true)
                : itemsPage && moveItemStackTo(moving, 0, count, false);
        if (!moved && index >= count) moved = index < count + 27 ? moveItemStackTo(moving, count + 27, count + 36, false)
                    : moveItemStackTo(moving, count, count + 27, false);
        if (!moved) return ItemStack.EMPTY;
        slot.set(moving.isEmpty() ? ItemStack.EMPTY : moving);
        slot.setChanged(); slot.onTake(player, moving);
        return original;
    }
}
