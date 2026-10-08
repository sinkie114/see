package com.sinkie114.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Quick entity edits for the integrated server. The client sends a vanilla container button click with
 * {@link #button}; only the server menu applies it, so no custom packets are needed.
 */
public enum EntityAction {
    NO_AI(10, "无AI", "切换生物 AI（NoAI）", true),
    INVULNERABLE(11, "无敌", "切换无敌（Invulnerable）", true),
    GLOWING(12, "发光", "切换发光轮廓（Glowing）", true),
    SILENT(13, "静音", "切换静音（Silent）", true),
    NO_GRAVITY(14, "无重力", "切换无重力（NoGravity）", true),
    HEAL(15, "回满血", "将生命值恢复到最大值", false),
    EXTINGUISH(16, "灭火", "熄灭实体身上的火焰", false),
    CLEAR_EFFECTS(17, "清除效果", "移除全部状态效果", false);

    public final int button;
    public final String label, help;
    public final boolean toggle;

    EntityAction(int button, String label, String help, boolean toggle) {
        this.button = button; this.label = label; this.help = help; this.toggle = toggle;
    }

    public static EntityAction byButton(int button) {
        for (EntityAction action : values()) if (action.button == button) return action;
        return null;
    }

    /** Invulnerability is not synchronized, so remote viewers cannot show a truthful state for it. */
    public boolean supports(Entity entity, boolean server) {
        return switch (this) {
            case NO_AI -> entity instanceof Mob;
            case INVULNERABLE -> server;
            case HEAL, CLEAR_EFFECTS -> entity instanceof LivingEntity;
            default -> true;
        };
    }

    public boolean state(Entity entity) {
        return switch (this) {
            case NO_AI -> entity instanceof Mob mob && mob.isNoAi();
            case INVULNERABLE -> entity.isInvulnerable();
            case GLOWING -> entity.hasGlowingTag();
            case SILENT -> entity.isSilent();
            case NO_GRAVITY -> entity.isNoGravity();
            default -> false;
        };
    }

    /** Server thread only. */
    public void apply(Entity entity) {
        switch (this) {
            case NO_AI -> { if (entity instanceof Mob mob) mob.setNoAi(!mob.isNoAi()); }
            case INVULNERABLE -> entity.setInvulnerable(!entity.isInvulnerable());
            case GLOWING -> entity.setGlowingTag(!entity.hasGlowingTag());
            case SILENT -> entity.setSilent(!entity.isSilent());
            case NO_GRAVITY -> entity.setNoGravity(!entity.isNoGravity());
            case HEAL -> { if (entity instanceof LivingEntity living) living.setHealth(living.getMaxHealth()); }
            case EXTINGUISH -> entity.clearFire();
            case CLEAR_EFFECTS -> { if (entity instanceof LivingEntity living) living.removeAllEffects(); }
        }
    }
}
