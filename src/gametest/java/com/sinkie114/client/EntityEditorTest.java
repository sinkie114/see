package com.sinkie114.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NumericTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.npc.villager.Villager;
import java.util.List;
import java.util.UUID;

/**
 * The F8 entity editor: simple-mode edits and one-click edits reach the entity only on 同步; the info,
 * advanced and inventory tabs; and the session keeps ticking after another mod's screen was on top.
 */
final class EntityEditorTest {
    private EntityEditorTest() {}
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void exercise(ClientGameTestContext ctx, TestServerContext server, UUID villagerId) {
        int entityId = server.computeOnServer(s -> s.overworld().getEntity(villagerId).getId());
        int scale = ctx.computeOnClient(c -> c.options.guiScale().get());
        ctx.runOnClient(c -> {
            if (c.screen != null) c.screen.onClose();
            // The editor wants a wide window; scale 1 on the 854×480 test window gives it full width.
            c.options.guiScale().set(1);
            c.resizeDisplay();
        });
        ctx.waitTicks(3);
        ctx.runOnClient(c -> EntityNbtSession.open(c, c.level.getEntity(entityId)));
        ctx.waitFor(c -> c.screen instanceof EntitySimpleScreen);
        ctx.runOnClient(c -> check(session(c).editable && session(c).document.contains("Health"), "A singleplayer villager opens an editable copy"));
        ctx.takeScreenshot("entity-editor-simple");

        // A text field changes only the copy until 同步.
        ctx.runOnClient(c -> field(c, "CustomName#0").setValue("测试村民"));
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> check(!session(c).patch.isEmpty(), "Saving puts the name into the copy"));
        server.runOnServer(s -> check(name(s, villagerId) == null, "Nothing reaches the entity before sync"));
        sync(ctx);
        server.runOnServer(s -> check("测试村民".equals(name(s, villagerId)), "Sync writes the custom name"));

        // 回满血 after the game lowered health.
        server.runOnServer(s -> villager(s, villagerId).setHealth(1));
        ctx.waitFor(c -> session(c).observed.getFloatOr("Health", 0) == 1);
        click(ctx, "刷新");
        click(ctx, "生命");
        click(ctx, "回满血");
        ctx.runOnClient(c -> check(session(c).document.getFloatOr("Health", 0) == 20, "回满血 writes max health into the copy"));
        server.runOnServer(s -> check(villager(s, villagerId).getHealth() == 1, "回满血 waits for sync"));
        sync(ctx);
        server.runOnServer(s -> check(villager(s, villagerId).getHealth() == villager(s, villagerId).getMaxHealth(), "回满血 reaches the entity"));

        // 清除全部 status effects.
        server.runOnServer(s -> villager(s, villagerId).addEffect(new MobEffectInstance(
                BuiltInRegistries.MOB_EFFECT.get(Identifier.withDefaultNamespace("speed")).orElseThrow(), 600)));
        ctx.waitFor(c -> !session(c).observed.getListOrEmpty("active_effects").isEmpty());
        click(ctx, "刷新");
        click(ctx, "状态效果");
        click(ctx, "清除全部");
        sync(ctx);
        server.runOnServer(s -> check(villager(s, villagerId).getActiveEffects().isEmpty(), "清除全部 removes every effect"));

        // 灭火
        server.runOnServer(s -> villager(s, villagerId).setRemainingFireTicks(400));
        ctx.waitFor(c -> session(c).observed.get("Fire") instanceof NumericTag fire && fire.intValue() > 0);
        click(ctx, "刷新");
        click(ctx, "基础");
        click(ctx, "灭火");
        sync(ctx);
        server.runOnServer(s -> check(villager(s, villagerId).getRemainingFireTicks() <= 0, "灭火 puts the fire out"));

        // The info tab shows what the game computes, including the villager section of the status page.
        click(ctx, "信息");
        ctx.waitFor(c -> c.screen instanceof EntityInfoScreen);
        ctx.runOnClient(c -> check(session(c).pages.getOrDefault("status", List.of()).stream().anyMatch(line -> line.startsWith("职业: ")),
                "Info shows the villager profession"));
        ctx.takeScreenshot("entity-editor-info");

        // Another mod's screen on top (NBT Maker does this from the inventory tab) pauses the session;
        // showing an editor screen again must resume polling.
        ctx.runOnClient(c -> {
            Screen editor = c.screen;
            c.setScreen(new Screen(Component.literal("other mod")) {});
            ActiveHolder.editor = editor;
        });
        ctx.waitTicks(2);
        ctx.runOnClient(c -> check(EntityNbtSession.active == null, "A foreign screen drops the ticking session"));
        ctx.runOnClient(c -> c.setScreen(ActiveHolder.editor));
        ctx.waitTicks(2);
        ctx.runOnClient(c -> check(EntityNbtSession.active == session(c), "Returning to the editor resumes the session"));
        server.runOnServer(s -> villager(s, villagerId).setHealth(5));
        ctx.waitFor(c -> session(c).observed.getFloatOr("Health", 0) == 5);
        server.runOnServer(s -> { var v = villager(s, villagerId); v.setHealth(v.getMaxHealth()); });

        // The advanced tab holds the full tree.
        click(ctx, "高级模式");
        ctx.waitFor(c -> c.screen instanceof EntityNbtScreen);
        ctx.takeScreenshot("entity-editor-advanced");

        // The inventory tab is the vanilla container; Esc closes it and returns to the editor.
        click(ctx, "物品栏");
        ctx.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
        ctx.runOnClient(c -> c.screen.onClose());
        ctx.waitFor(c -> c.screen instanceof EntityEditorBase);
        ctx.waitTicks(3);
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            check(player.containerMenu == player.inventoryMenu, "Leaving the inventory tab closes the container");
        });

        click(ctx, "关闭");
        ctx.waitFor(c -> c.screen == null);
        ctx.runOnClient(c -> {
            check(EntityNbtSession.active == null, "Closing the editor ends the session");
            c.options.guiScale().set(scale);
            c.resizeDisplay();
        });
        server.runOnServer(s -> villager(s, villagerId).setCustomName(null));
        ctx.waitTicks(2);
    }

    /** Holds a screen across two client tasks. */
    private static final class ActiveHolder { static Screen editor; }

    private static EntityNbtSession session(Minecraft c) { return ((EntityNbtLayer) c.screen).session(); }
    private static Villager villager(MinecraftServer s, UUID id) { return (Villager) s.overworld().getEntity(id); }
    private static String name(MinecraftServer s, UUID id) {
        Component name = s.overworld().getEntity(id).getCustomName();
        return name == null ? null : name.getString();
    }

    private static void sync(ClientGameTestContext ctx) {
        click(ctx, "同步");
        ctx.waitFor(c -> !session(c).syncing);
        ctx.runOnClient(c -> check(session(c).status.equals("同步成功"), "Sync must succeed cleanly: " + session(c).status));
    }

    private static EditBox field(Minecraft c, String id) {
        return c.screen.children().stream().filter(w -> w instanceof EditBox e && e.getMessage().getString().equals(id)).map(w -> (EditBox) w)
                .findFirst().orElseThrow(() -> new AssertionError("Missing input " + id + " on " + c.screen.getClass().getSimpleName()));
    }

    private static void click(ClientGameTestContext ctx, String label) {
        ctx.runOnClient(c -> {
            Button button = c.screen.children().stream().filter(w -> w instanceof Button b && b.visible && b.getMessage().getString().equals(label))
                    .map(w -> (Button) w).findFirst().orElseThrow(() -> new AssertionError("Missing button " + label + " on " + c.screen.getClass().getSimpleName()));
            check(button.active, "Button must be enabled: " + label);
            c.screen.mouseClicked(new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
        });
        ctx.waitTicks(2);
    }
}
