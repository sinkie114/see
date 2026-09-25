package com.sinkie114.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.UUID;

/** Entity NBT editing through the real SEE container and integrated-server write path. */
public final class EntityNbtEditorTest implements FabricClientGameTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        UUID villagerId;
        try (var world = context.worldBuilder().create()) {
            save = world.getWorldSave();
            TestServerContext server = world.getServer();
            villagerId = server.computeOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                var villager = EntityType.VILLAGER.create(player.level(), EntitySpawnReason.COMMAND);
                villager.setPos(player.getX() + 2, player.getY(), player.getZ());
                villager.setNoAi(true);
                villager.setInvulnerable(true);
                player.level().addFreshEntity(villager);
                return villager.getUUID();
            });
            int id = server.computeOnServer(s -> s.overworld().getEntity(villagerId).getId());
            context.waitFor(c -> c.level != null && c.level.getEntity(id) != null);
            context.runOnClient(c -> EntityEditBridge.open(c, c.level.getEntity(id)));
            context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
            pressButton(context, "NBT");
            context.waitFor(c -> c.screen instanceof EntityNbtScreen);
            context.runOnClient(c -> {
                var editor = (EntityNbtScreen) c.screen;
                check(editor.editable(), "Singleplayer entities must be editable");
                check(editor.view().contains("Health") && editor.view().contains("Pos") && editor.view().contains("UUID"),
                        "The editor must show the server's saved entity NBT: " + editor.view());
                check(!editor.view().contains("SyncedData") && !editor.view().contains("VisibleItems"), "Display-only keys are not entity NBT");
                check(c.player.containerMenu instanceof EntityDebugMenu, "The SEE container must stay open under the editor");
            });
            context.takeScreenshot("entity-nbt-editor");

            // Edit through the dialog; nothing is written before sync.
            context.runOnClient(c -> ((EntityNbtScreen) c.screen).select(List.of("Health")));
            pressButton(context, "编辑");
            context.waitFor(c -> c.screen instanceof NbtValueScreen);
            context.takeScreenshot("entity-nbt-value");
            context.runOnClient(c -> ((NbtValueScreen) c.screen).setText("7.5f"));
            pressButton(context, "载入编辑副本");
            context.waitFor(c -> c.screen instanceof EntityNbtScreen);
            context.runOnClient(c -> {
                var editor = (EntityNbtScreen) c.screen;
                check(editor.pending(), "An edit must stay a pending draft");
                check(NbtTree.sameValue(editor.view().get("Health"), FloatTag.valueOf(7.5F)), "The draft must show the edited value");
            });
            server.runOnServer(s -> check(living(s, villagerId).getHealth() == 20F, "Editing must not write before sync"));

            // The game changes other fields and the edited one; sync keeps the former and overrides the latter.
            server.runOnServer(s -> {
                var villager = living(s, villagerId);
                villager.addTag("external");
                villager.setCustomName(Component.literal("External"));
                villager.setHealth(3F);
            });
            context.waitFor(c -> ((EntityNbtScreen) c.screen).view().contains("Tags"));
            context.runOnClient(c -> {
                var editor = (EntityNbtScreen) c.screen;
                check(editor.conflicts().contains(List.of("Health")), "A field the game changed after the edit must be flagged: " + editor.conflicts());
                check(NbtTree.sameValue(editor.view().get("Health"), FloatTag.valueOf(7.5F)), "The user's value must win in the draft view");
                check(editor.view().contains("CustomName"), "Untouched fields must show live data");
            });
            context.takeScreenshot("entity-nbt-conflict");
            sync(context);
            context.runOnClient(c -> check(((EntityNbtScreen) c.screen).status().equals("同步成功"), "Sync must succeed: " + ((EntityNbtScreen) c.screen).status()));
            server.runOnServer(s -> {
                var villager = living(s, villagerId);
                check(villager.getHealth() == 7.5F, "Sync must write the edited field");
                check(villager.getTags().contains("external") && villager.getCustomName() != null
                        && villager.getCustomName().getString().equals("External"), "Sync must keep live changes the editor did not touch");
            });
            context.runOnClient(c -> check(!((EntityNbtScreen) c.screen).pending(), "Sync must clear the draft"));

            // Like /data, the UUID is kept; the editor names the ignored field while other edits apply.
            context.runOnClient(c -> {
                var editor = (EntityNbtScreen) c.screen;
                editor.apply(NbtTree.replace(editor.view(), List.of("UUID"), snbt("[I;1,2,3,4]")));
                editor.apply(NbtTree.replace(editor.view(), List.of("Silent"), ByteTag.valueOf(true)));
            });
            sync(context);
            context.runOnClient(c -> {
                String status = ((EntityNbtScreen) c.screen).status();
                check(status.startsWith("同步成功") && status.contains("UUID") && !status.contains("Silent"), "UUID edits must be reported as ignored: " + status);
            });
            server.runOnServer(s -> check(s.overworld().getEntity(villagerId) != null && s.overworld().getEntity(villagerId).isSilent(),
                    "The UUID must be kept while other fields apply"));

            // A non-finite position is refused before the entity is touched, and the draft survives.
            Vec3 before = server.computeOnServer(s -> s.overworld().getEntity(villagerId).position());
            context.runOnClient(c -> {
                var editor = (EntityNbtScreen) c.screen;
                ListTag pos = new ListTag();
                pos.add(DoubleTag.valueOf(Double.NaN)); pos.add(DoubleTag.valueOf(0)); pos.add(DoubleTag.valueOf(0));
                editor.apply(NbtTree.replace(editor.view(), List.of("Pos"), pos));
            });
            sync(context);
            context.runOnClient(c -> {
                var editor = (EntityNbtScreen) c.screen;
                check(editor.status().startsWith("同步失败") && editor.pending(), "Invalid positions must be rejected with the draft kept: " + editor.status());
            });
            server.runOnServer(s -> check(s.overworld().getEntity(villagerId).position().equals(before), "A rejected sync must not move the entity"));
            pressButton(context, "放弃修改");
            context.runOnClient(c -> check(!((EntityNbtScreen) c.screen).pending(), "Discard must drop the draft"));

            // Deleting a key removes it from the entity.
            context.runOnClient(c -> ((EntityNbtScreen) c.screen).select(List.of("CustomName")));
            pressButton(context, "删除");
            sync(context);
            server.runOnServer(s -> check(s.overworld().getEntity(villagerId).getCustomName() == null, "Deleting a key must remove it from the entity"));

            // Equipment written as NBT shows up in SEE's live item slots.
            context.runOnClient(c -> {
                var editor = (EntityNbtScreen) c.screen;
                editor.apply(NbtTree.mergeInto(editor.view(), (CompoundTag) snbt("{equipment:{head:{id:\"minecraft:diamond_helmet\",count:1}}}")));
            });
            sync(context);
            context.runOnClient(c -> check(((EntityNbtScreen) c.screen).status().startsWith("同步成功"), "Equipment sync: " + ((EntityNbtScreen) c.screen).status()));
            server.runOnServer(s -> check(living(s, villagerId).getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "Equipment NBT must reach the entity"));

            // Return to SEE, then reopen at a node from the raw data tab.
            context.runOnClient(c -> c.screen.onClose());
            context.runOnClient(c -> check(c.screen instanceof EntityDebugScreen screen && screen.getMenu() == c.player.containerMenu
                    && screen.getMenu().ready && !screen.getMenu().invalid, "Closing the editor must return to the same live SEE screen"));
            selectTab(context, "原始");
            context.runOnClient(c -> {
                var screen = (EntityDebugScreen) c.screen;
                // Row 0 is the root; row 1 is the first entity key.
                var event = new MouseButtonEvent((screen.width - 176) / 2 + 20, (screen.height - 168) / 2 + 18 + 15, new MouseButtonInfo(1, 0));
                screen.mouseClicked(event, false);
                screen.mouseReleased(event);
            });
            context.waitFor(c -> c.screen instanceof EntityNbtScreen);
            context.runOnClient(c -> {
                var editor = (EntityNbtScreen) c.screen;
                check(editor.selected().size() == 1 && NbtTree.at(editor.view(), editor.selected()) != null,
                        "Right-clicking a raw row must open the editor at that node: " + editor.selected());
                editor.onClose();
            });
            selectTab(context, "物品");
            context.waitFor(c -> findSlot(((EntityDebugScreen) c.screen).getMenu(), "equipment.head").getItem().is(Items.DIAMOND_HELMET));
            click(context, "equipment.head", ClickType.PICKUP);
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                check(player.containerMenu.getCarried().is(Items.DIAMOND_HELMET) && living(s, villagerId).getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                        "SEE item editing must keep working after the NBT editor");
            });
            click(context, "equipment.head", ClickType.PICKUP);
            server.runOnServer(s -> check(living(s, villagerId).getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "The helmet must go back"));
            context.runOnClient(c -> c.screen.onClose());
            context.waitTicks(3);

            // Remote viewers have no authoritative NBT, so the editor stays unavailable.
            context.runOnClient(c -> {
                c.setScreen(EntityDebugScreen.readOnly(c.level.getEntity(id), c.player.getInventory()));
                check(!findButton(c.screen, "NBT").active, "Read-only SEE must not offer NBT editing");
                c.screen.onClose();
            });
            context.waitTick();
        }
        try (var world = save.open()) {
            var server = world.getServer();
            context.waitTicks(10);
            server.runOnServer(s -> {
                var villager = living(s, villagerId);
                check(villager.getHealth() == 7.5F && villager.isSilent() && villager.getTags().contains("external"), "NBT edits must survive a world reload");
                check(villager.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "Equipment written through NBT must persist");
            });
        }
    }

    private static LivingEntity living(net.minecraft.server.MinecraftServer server, UUID id) {
        return (LivingEntity) server.overworld().getEntity(id);
    }

    private static Tag snbt(String text) {
        try { return TagParser.create(NbtOps.INSTANCE).parseFully(text); }
        catch (Exception ex) { throw new AssertionError("Invalid test SNBT: " + text, ex); }
    }

    private static void sync(ClientGameTestContext context) {
        pressButton(context, "同步");
        context.waitFor(c -> !((EntityNbtScreen) c.screen).status().equals("同步中…"));
    }

    private static Button findButton(Screen screen, String label) {
        return screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().equals(label))
                .map(child -> (Button) child).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label));
    }

    private static void pressButton(ClientGameTestContext context, String label) {
        context.runOnClient(c -> {
            Button button = findButton(c.screen, label);
            check(button.active, "Button must be active: " + label);
            var event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0));
            Screen screen = c.screen;
            screen.mouseClicked(event, false);
            screen.mouseReleased(event);
        });
        context.waitTicks(2);
    }

    private static void selectTab(ClientGameTestContext context, String label) {
        pressButton(context, label);
    }

    private static Slot findSlot(EntityDebugMenu menu, String key) {
        for (int i = 0; i < menu.descriptions.size(); i++) if (menu.descriptions.get(i).key().equals(key)) return menu.slots.get(i);
        throw new AssertionError("Missing slot: " + key);
    }

    private static void click(ClientGameTestContext context, String key, ClickType type) {
        context.runOnClient(c -> {
            var screen = (EntityDebugScreen) c.screen;
            Slot slot = findSlot(screen.getMenu(), key);
            screen.slotClicked(slot, slot.index, 0, type);
        });
        context.waitTicks(2);
    }
}
