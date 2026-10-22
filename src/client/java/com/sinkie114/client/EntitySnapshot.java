package com.sinkie114.client;

import com.sinkie114.client.mixin.EntityDataAccessor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.TagValueOutput;
import java.util.*;

/** Immutable data transferred between the integrated server and the render thread. */
public record EntitySnapshot(Component name, String id, String uuid, Map<String, List<String>> pages,
                             CompoundTag raw, Map<EntityAction, Boolean> actions) {
    public static EntitySnapshot capture(Entity e, boolean server, List<EntityItems.Entry> slots) {
        Map<String, List<String>> pages = new LinkedHashMap<>();
        List<String> overview = new ArrayList<>();
        overview.add("名称: " + e.getName().getString());
        overview.add("实体 ID: " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
        overview.add("UUID: " + e.getUUID());
        overview.add("网络 ID: " + e.getId());
        overview.add("维度: " + e.level().dimension().identifier());
        overview.add("坐标: " + vec(e.position()));
        overview.add("方块坐标: " + e.blockPosition().toShortString());
        overview.add("尺寸: " + e.getBbWidth() + " × " + e.getBbHeight());
        overview.add("碰撞箱: " + vec(e.getBoundingBox().getMinPosition()) + " → " + vec(e.getBoundingBox().getMaxPosition()));
        overview.add("姿势: " + e.getPose());
        if (e instanceof LivingEntity l) overview.add("生命: " + l.getHealth() + " / " + l.getMaxHealth());
        pages.put("overview", List.copyOf(overview));

        List<String> attributes = new ArrayList<>();
        if (e instanceof LivingEntity living) {
            BuiltInRegistries.ATTRIBUTE.listElements().forEach(holder -> {
                if ((!server && !holder.value().isClientSyncable()) || !living.getAttributes().hasAttribute(holder)) return;
                AttributeInstance instance = living.getAttribute(holder);
                if (instance == null) return;
                attributes.add(Component.translatable(holder.value().getDescriptionId()).getString() + " = " + instance.getValue());
                attributes.add("  " + holder.getRegisteredName() + "  基础值: " + instance.getBaseValue());
                instance.getModifiers().forEach(mod -> attributes.add("  " + mod.id() + " : " + mod.amount() + " (" + mod.operation() + ")"));
            });
        }
        pages.put("attributes", List.copyOf(attributes));
        List<String> state = new ArrayList<>();
        state.add("存活: " + e.isAlive()); state.add("着火: " + e.isOnFire());
        state.add("隐身: " + e.isInvisible()); state.add("发光: " + e.hasGlowingTag());
        state.add("无重力: " + e.isNoGravity()); state.add("静音: " + e.isSilent());
        state.add("空气: " + e.getAirSupply() + " / " + e.getMaxAirSupply());
        state.add("冰冻刻数: " + e.getTicksFrozen()); state.add("存在刻数: " + e.tickCount);
        if (e instanceof LivingEntity l) {
            state.add("吸收生命: " + l.getAbsorptionAmount());
            state.add("嵌入箭: " + l.getArrowCount() + " / 蜂刺: " + l.getStingerCount());
            l.getActiveEffects().forEach(effect -> state.add("效果: " + Component.translatable(effect.getDescriptionId()).getString()
                    + " " + (effect.getAmplifier() + 1) + "  " + (effect.isInfiniteDuration() ? "无限"
                    : effect.getDuration() / 20.0 + " 秒（" + effect.getDuration() + " 刻）")));
        }
        if (e instanceof Mob mob) {
            state.add("AI 启用: " + !mob.isNoAi());
            if (server) {
                state.add("可拾取物品: " + mob.canPickUpLoot());
                if (mob.getTarget() != null) state.add("AI 目标: " + mob.getTarget().getName().getString());
            }
        }
        if (e instanceof TamableAnimal tame) {
            state.add("已驯服: " + tame.isTame());
            state.add("坐下姿势: " + tame.isInSittingPose());
        }
        if (e instanceof Villager villager) {
            var data = villager.getVillagerData();
            String profession = data.profession().unwrapKey().map(key -> key.identifier()).map(id ->
                    Component.translatable("entity." + id.getNamespace() + ".villager." + id.getPath()).getString() + " (" + id + ")").orElse("?");
            state.add("职业: " + profession + " · 等级 " + data.level());
            state.add("村民类型: " + data.type().unwrapKey().map(key -> key.identifier().toString()).orElse("?"));
        }
        pages.put("status", List.copyOf(state));
        pages.put("motion", List.of("坐标: " + vec(e.position()), String.format(Locale.ROOT, "旋转: yaw=%.1f pitch=%.1f", e.getYRot(), e.getXRot()),
                "速度: " + vec(e.getDeltaMovement()), "地面: " + e.onGround(), "水平碰撞: " + e.horizontalCollision,
                "垂直碰撞: " + e.verticalCollision, "下落距离: " + e.fallDistance));

        CompoundTag raw = new CompoundTag();
        if (server) {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.registryAccess());
            e.saveWithoutId(output);
            raw = output.buildResult();
        } else {
            // Never serialize an incomplete remote entity as though it were authoritative NBT.
            raw.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            raw.putString("UUID", e.getUUID().toString());
            raw.putString("Name", e.getName().getString());
            raw.putString("Dimension", e.level().dimension().identifier().toString());
            raw.put("Pos", vector(e.getX(), e.getY(), e.getZ()));
            raw.put("Motion", vector(e.getDeltaMovement().x, e.getDeltaMovement().y, e.getDeltaMovement().z));
            raw.put("Rotation", vector(e.getYRot(), e.getXRot()));
        }
        if (server) {
            List<String> status = new ArrayList<>(pages.get("status"));
            trades(raw, status);
            pages.put("status", List.copyOf(status));
        }
        CompoundTag synced = new CompoundTag();
        for (var data : ((EntityDataAccessor) e.getEntityData()).see$items()) {
            if (data == null) continue;
            var value = data.value();
            CompoundTag field = new CompoundTag();
            field.putString("javaType", value.value().getClass().getSimpleName());
            field.putString("value", String.valueOf(value.value()));
            synced.put(Integer.toString(value.id()), field);
        }
        raw.put("SyncedData", synced);
        List<String> itemLines = new ArrayList<>();
        CompoundTag itemData = new CompoundTag();
        for (var slot : slots) {
            ItemStack stack = slot.access().get().copy();
            itemLines.add(slot.label() + ": " + (stack.isEmpty() ? "空" : stack.getHoverName().getString() + " × " + stack.getCount()));
            if (stack.isEmpty()) continue;
            if (stack.isDamageableItem()) itemLines.add("  耐久: " + (stack.getMaxDamage() - stack.getDamageValue()) + " / " + stack.getMaxDamage());
            stack.getComponents().forEach(component -> itemLines.add("  " + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type()) + " = " + component.value()));
            ItemStack.CODEC.encodeStart(e.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
                    .result().ifPresent(tag -> itemData.put(slot.key(), tag));
        }
        raw.put("VisibleItems", itemData);
        pages.put("items", List.copyOf(itemLines));
        List<String> other = new ArrayList<>();
        for (String key : raw.keySet().stream().sorted().toList()) {
            if (Set.of("Pos", "Motion", "Rotation", "UUID", "id", "SyncedData", "VisibleItems").contains(key)) continue;
            other.add(key + ": " + raw.get(key));
        }
        if (!server) synced.entrySet().forEach(entry -> other.add("同步字段 " + entry.getKey() + ": " + entry.getValue()));
        pages.put("other", List.copyOf(other));
        // Supported quick actions with their current toggle state, for the actions tab.
        Map<EntityAction, Boolean> actions = new EnumMap<>(EntityAction.class);
        for (EntityAction action : EntityAction.values()) if (action.supports(e, server)) actions.put(action, action.state(e));
        return new EntitySnapshot(e.getDisplayName().copy(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                e.getUUID().toString(), Map.copyOf(pages), raw, Collections.unmodifiableMap(actions));
    }
    /** Coordinates to three decimals; the raw page keeps the exact values. */
    private static String vec(Vec3 v) {
        return String.format(Locale.ROOT, "%.3f, %.3f, %.3f", v.x, v.y, v.z);
    }

    /** Trade list and merchant XP, read from the saved data the raw page already holds (server only). */
    private static void trades(CompoundTag raw, List<String> out) {
        raw.getInt("Xp").ifPresent(xp -> out.add("交易经验: " + xp));
        ListTag recipes = raw.get("Offers") instanceof ListTag list ? list : raw.getCompoundOrEmpty("Offers").getListOrEmpty("Recipes");
        if (recipes.isEmpty()) return;
        out.add("交易 " + recipes.size() + " 项:");
        for (int i = 0; i < recipes.size(); i++) {
            CompoundTag offer = recipes.getCompoundOrEmpty(i);
            String cost = item(offer.getCompoundOrEmpty("buy"));
            if (offer.get("buyB") instanceof CompoundTag second && !second.isEmpty()) cost += " + " + item(second);
            out.add("  " + (i + 1) + ". " + cost + " → " + item(offer.getCompoundOrEmpty("sell"))
                    + "  已用 " + offer.getIntOr("uses", 0) + "/" + offer.getIntOr("maxUses", 0));
        }
    }

    private static String item(CompoundTag stack) {
        String id = stack.getStringOr("id", "minecraft:air");
        var parsed = net.minecraft.resources.Identifier.tryParse(id);
        String name = parsed == null ? id : BuiltInRegistries.ITEM.get(parsed).map(h -> h.value().getName().getString()).orElse(id);
        return stack.getIntOr("count", 1) + "×" + name;
    }

    private static ListTag vector(double... values) {
        ListTag list = new ListTag();
        for (double value : values) list.add(DoubleTag.valueOf(value));
        return list;
    }
}
