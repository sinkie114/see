package com.sinkie114.client;

import com.sinkie114.client.mixin.ArrowAccessor;
import com.sinkie114.client.mixin.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.Container;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.monster.EnderMan;
import java.util.ArrayList;
import java.util.List;

public final class EntityItems {
    private EntityItems() {}
    public record Description(String key, String label, int limit) {
        public boolean accepts(ItemStack stack) {
            // A BlockState cannot store non-block items or their custom item components.
            return !key.equals("carried_block") || stack.isEmpty()
                    || stack.getItem() instanceof net.minecraft.world.item.BlockItem
                    && ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()));
        }
    }
    public record Entry(String key, String label, int limit, SlotAccess access) {
        public Description description() { return new Description(key, label, limit); }
    }
    public static List<Entry> discover(Entity entity, boolean server) {
        List<Entry> out = new ArrayList<>();
        if (entity instanceof LivingEntity living) {
            // Register slots in visual order so vanilla quick move visits them in that same order.
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.BODY, EquipmentSlot.SADDLE)) {
                // The selected hotbar stack is already represented by the player's inventory.
                if (server && entity instanceof Player && slot == EquipmentSlot.MAINHAND) continue;
                if (slot == EquipmentSlot.BODY && !(entity instanceof AbstractHorse || entity instanceof AbstractNautilus
                        || entity.getType() == EntityType.WOLF)) continue;
                if (slot == EquipmentSlot.SADDLE && !(entity instanceof AbstractHorse || entity instanceof AbstractNautilus
                        || entity.getType() == EntityType.PIG || entity.getType() == EntityType.STRIDER)) continue;
                if (!living.canUseSlot(slot)) continue;
                String label = switch (slot) {
                    case MAINHAND -> "主手"; case OFFHAND -> "副手"; case HEAD -> "头盔";
                    case CHEST -> "胸甲"; case LEGS -> "护腿"; case FEET -> "靴子";
                    case BODY -> "身体"; case SADDLE -> "鞍";
                };
                out.add(new Entry("equipment." + slot.getName(), label, 64,
                        SlotAccess.of(() -> living.getItemBySlot(slot), stack -> {
                            living.setItemSlot(slot, stack);
                            if (living instanceof VillagerHandProtection protection && slot == EquipmentSlot.MAINHAND) {
                                protection.see$setMainHandProtected(!stack.isEmpty());
                            }
                        })));
            }
        }
        if (entity instanceof EnderMan enderman) {
            // Endermen carry an optional BlockState, separate from every equipment slot.
            out.add(new Entry("carried_block", "搬运方块", 1, new SlotAccess() {
                @Override public ItemStack get() {
                    BlockState state = enderman.getCarriedBlock();
                    if (state == null || state.isAir() || state.getBlock().asItem() == net.minecraft.world.item.Items.AIR) {
                        return ItemStack.EMPTY;
                    }
                    return new ItemStack(state.getBlock().asItem());
                }

                @Override public boolean set(ItemStack stack) {
                    if (stack.isEmpty()) {
                        enderman.setCarriedBlock(null);
                        return true;
                    }
                    Block block = Block.byItem(stack.getItem());
                    if (block == Blocks.AIR || !new Description("carried_block", "搬运方块", 1).accepts(stack)) return false;
                    enderman.setCarriedBlock(block.defaultBlockState());
                    return true;
                }
            }));
        }
        if (entity instanceof ItemEntity item) {
            out.add(new Entry("item", "掉落物", 64, SlotAccess.of(item::getItem, item::setItem)));
        } else if (entity instanceof ItemFrame frame) {
            out.add(new Entry("item", "展示物品", 1, SlotAccess.of(frame::getItem, frame::setItem)));
        } else if (entity instanceof Display.ItemDisplay display) {
            out.add(new Entry("item", "展示物品", 64, display.getSlot(0)));
        } else if (entity instanceof ThrowableItemProjectile projectile) {
            out.add(new Entry("item", "投射物", 1, SlotAccess.of(projectile::getItem, projectile::setItem)));
        } else if (server && entity instanceof AbstractArrow arrow) {
            ArrowAccessor access = (ArrowAccessor) arrow;
            out.add(new Entry("item", "投射物", 1, SlotAccess.of(arrow::getPickupItemStackOrigin,
                    stack -> { if (stack.isEmpty()) { access.see$clearPickupItemStack(stack); arrow.discard(); }
                               else access.see$setPickupItemStack(stack); })));
        } else if (entity instanceof EyeOfEnder || entity instanceof FireworkRocketEntity
                || entity instanceof Fireball || entity instanceof OminousItemSpawner) {
            // These entities store their real item in a synchronized item-stack field.
            // No reflection, no fabricated/default inventory slots, and no client registry objects cross threads.
            for (var data : ((EntityDataAccessor) entity.getEntityData()).see$items()) {
                if (data == null || data.getAccessor().serializer() != EntityDataSerializers.ITEM_STACK) continue;
                var accessor = new net.minecraft.network.syncher.EntityDataAccessor<ItemStack>(data.getAccessor().id(), EntityDataSerializers.ITEM_STACK);
                out.add(new Entry("item", "携带物品", entity instanceof OminousItemSpawner ? 64 : 1,
                        SlotAccess.of(() -> entity.getEntityData().get(accessor), stack -> {
                            entity.getEntityData().set(accessor, stack);
                            if (stack.isEmpty() && !(entity instanceof OminousItemSpawner)) entity.discard();
                        })));
            }
        }
        // Remote clients do not receive village, mount or container inventories.
        if (server) {
            if (entity instanceof Player player) {
                for (int i = 0; i < 36; i++) addContainer(out, player.getInventory(), i, "物品栏 " + i);
            } else if (entity instanceof Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) addContainer(out, container, i, "物品栏 " + i);
            } else if (entity instanceof InventoryCarrier carrier) {
                for (int i = 0; i < carrier.getInventory().getContainerSize(); i++) addContainer(out, carrier.getInventory(), i, "物品栏 " + i);
            } else if (entity instanceof AbstractHorse || entity instanceof AbstractNautilus) {
                for (int i = 0; i < 100; i++) {
                    SlotAccess access = entity.getSlot(500 + i);
                    if (access != null) out.add(new Entry("inventory." + i, "物品栏 " + i, 64, access));
                }
            }
        }
        return List.copyOf(out);
    }
    private static void addContainer(List<Entry> out, Container container, int index, String label) {
        out.add(new Entry("inventory." + index, label, 64, SlotAccess.of(() -> container.getItem(index), stack -> {
            container.setItem(index, stack); container.setChanged();
        })));
    }
}
