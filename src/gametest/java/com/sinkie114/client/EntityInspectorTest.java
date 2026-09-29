package com.sinkie114.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer;
import net.minecraft.world.entity.ai.behavior.UseBonemeal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerLevel;
import java.util.UUID;

/** Runs in a separate disposable world and uses the actual client/server packet path. */
public final class EntityInspectorTest implements FabricClientGameTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    @Override public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        UUID villagerId;
        UUID endermanId;
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
                player.getInventory().clearContent();
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.enchant(s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LOOTING), 3);
                sword.set(DataComponents.CUSTOM_NAME, Component.literal("Registry regression sword"));
                sword.setDamageValue(17);
                player.getInventory().setItem(0, sword);
                player.getInventory().setItem(1, new ItemStack(Items.STONE, 16));
                player.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 1));
                return villager.getUUID();
            });
            endermanId = server.computeOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                EnderMan enderman = EntityType.ENDERMAN.create(player.level(), EntitySpawnReason.COMMAND);
                enderman.setPos(player.getX() + 4, player.getY(), player.getZ());
                enderman.setNoAi(true);
                enderman.setInvulnerable(true);
                enderman.setCarriedBlock(net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                player.level().addFreshEntity(enderman);
                return enderman.getUUID();
            });
            int entityId = server.computeOnServer(s -> s.overworld().getEntity(villagerId).getId());
            context.waitFor(c -> c.level != null && c.level.getEntity(entityId) != null);
            context.runOnClient(c -> { c.player.setYRot(-90); c.player.setXRot(0); });
            context.waitTicks(3);
            context.runOnClient(c -> check(SeeClient.pickFromCamera(c) == c.level.getEntity(entityId), "First-person camera must select the visible villager"));
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                p.level().setBlockAndUpdate(p.blockPosition().east().above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            });
            context.waitTicks(2);
            context.runOnClient(c -> check(SeeClient.pickFromCamera(c) == null, "A block must occlude the target"));
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                p.level().setBlockAndUpdate(p.blockPosition().east().above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            });
            context.waitTicks(2);
            context.runOnClient(c -> EntityEditBridge.open(c, c.level.getEntity(entityId)));
            context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
            context.runOnClient(c -> {
                var screen = (EntityDebugScreen)c.screen;
                check(screen.getMenu() == c.player.containerMenu, "Inventory mods must see the active menu");
                check(screen.getMenu().containerId > 0, "Vanilla menu id must be valid");
                check(screen.getMenu().descriptions.stream().filter(d -> d.key().startsWith("inventory.")).count() == 8, "Villager must expose 8 real inventory slots");
                c.options.guiScale().set(4);
                c.resizeDisplay();
            });
            context.waitTick();
            context.takeScreenshot("inspector-small-gui");
            // Hotbar -> real villager equipment. This previously mixed registry holders and disconnected.
            click(context, "player.0", 0, ClickType.PICKUP);
            click(context, "equipment.mainhand", 0, ClickType.PICKUP);
            server.runOnServer(s -> {
                var villager = (Villager)s.overworld().getEntity(villagerId);
                ItemStack stack = villager.getMainHandItem();
                check(stack.is(Items.DIAMOND_SWORD) && stack.getDamageValue() == 17, "Equipment must change immediately with durability intact");
                check(stack.getEnchantments().getLevel(s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LOOTING)) == 3, "Enchantment must belong to the server registry");
                ItemStack.CODEC.encodeStart(s.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
            });
            testVillagerAi(context, server, villagerId);
            testChestTabsAndCloning(context, server, villagerId);
            // Right-click half, place one; remaining carried stack returns on close.
            click(context, "player.1", 1, ClickType.PICKUP);
            click(context, "inventory.0", 1, ClickType.PICKUP);
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                var villager = (Villager)s.overworld().getEntity(villagerId);
                check(villager.getInventory().getItem(0).getCount() == 1, "Right click must place one");
                check(player.containerMenu.getCarried().getCount() == 7, "Right click must pick up half");
            });
            context.runOnClient(c -> c.screen.onClose());
            context.waitTicks(3);
            int endermanEntityId = server.computeOnServer(s -> s.overworld().getEntity(endermanId).getId());
            context.runOnClient(c -> EntityEditBridge.open(c, c.level.getEntity(endermanEntityId)));
            context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
            context.runOnClient(c -> {
                var menu = ((EntityDebugScreen)c.screen).getMenu();
                int slot = -1;
                for (int i = 0; i < menu.descriptions.size(); i++) {
                    if (menu.descriptions.get(i).key().equals("carried_block")) slot = i;
                }
                check(slot >= 0, "Enderman carried block must be a separate visible slot");
                check(menu.items.getItem(slot).is(Items.DIRT), "Enderman carried block must render as its block item");
                check(menu.slots.get(slot).x == 8 && menu.slots.get(slot).y == 54, "Enderman carried block belongs in the third row");
            });
            click(context, "carried_block", 0, ClickType.PICKUP);
            click(context, "player.2", 0, ClickType.PICKUP);
            click(context, "carried_block", 0, ClickType.PICKUP);
            server.runOnServer(s -> {
                EnderMan enderman = (EnderMan)s.overworld().getEntity(endermanId);
                check(enderman.getCarriedBlock() != null && enderman.getCarriedBlock().is(net.minecraft.world.level.block.Blocks.COBBLESTONE), "Block item must update Enderman carried state");
            });
            server.runOnServer(s -> {
                var inventory = s.getPlayerList().getPlayers().getFirst().getInventory();
                ItemStack namedDirt = new ItemStack(Items.DIRT);
                namedDirt.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this component"));
                inventory.setItem(8, namedDirt);
                inventory.setItem(9, new ItemStack(Items.STICK, 2));
            });
            context.waitTicks(2);
            for (int inventorySlot : new int[]{8, 9}) {
                click(context, "player." + inventorySlot, 0, ClickType.PICKUP);
                click(context, "carried_block", 0, ClickType.PICKUP);
                server.runOnServer(s -> {
                    ItemStack carried = s.getPlayerList().getPlayers().getFirst().containerMenu.getCarried();
                    check(inventorySlot == 8 ? carried.is(Items.DIRT) && carried.has(DataComponents.CUSTOM_NAME)
                            : carried.is(Items.STICK) && carried.getCount() == 2, "Rejected Enderman items must stay on cursor intact");
                    check(((EnderMan)s.overworld().getEntity(endermanId)).getCarriedBlock().is(net.minecraft.world.level.block.Blocks.COBBLESTONE), "Rejected item must not replace the carried block");
                });
                click(context, "player." + inventorySlot, 0, ClickType.PICKUP);
            }
            server.runOnServer(s -> {
                var inventory = s.getPlayerList().getPlayers().getFirst().getInventory();
                inventory.setItem(8, ItemStack.EMPTY);
                inventory.setItem(9, ItemStack.EMPTY);
            });
            context.runOnClient(c -> c.screen.onClose());
            context.waitTicks(3);
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                check(player.containerMenu == player.inventoryMenu, "Close must restore inventory menu");
                check(player.getInventory().countItem(Items.STONE) == 15, "Close must return carried stack without duplication/loss");
            });
            // Vanilla quick craft uses a slot of -999 at both ends and a packed button parameter.
            context.runOnClient(c -> EntityEditBridge.open(c, c.level.getEntity(entityId)));
            context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
            click(context, "player.1", 0, ClickType.PICKUP);
            click(context, null, AbstractContainerMenu.getQuickcraftMask(0, 0), ClickType.QUICK_CRAFT);
            click(context, "inventory.1", AbstractContainerMenu.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT);
            click(context, "inventory.2", AbstractContainerMenu.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT);
            click(context, null, AbstractContainerMenu.getQuickcraftMask(2, 0), ClickType.QUICK_CRAFT);
            server.runOnServer(s -> {
                var villager = (Villager)s.overworld().getEntity(villagerId);
                check(villager.getInventory().getItem(1).getCount() == 7 && villager.getInventory().getItem(2).getCount() == 7, "Drag must distribute evenly");
            });
            selectTab(context, "概览");
            click(context, "player.1", 0, ClickType.PICKUP_ALL);
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                check(player.containerMenu.getCarried().getCount() == 1, "Double-click collection must not take matching items from hidden entity slots");
                check(((Villager)s.overworld().getEntity(villagerId)).getInventory().countItem(Items.STONE) == 15, "Hidden entity contents must stay intact");
            });
            context.runOnClient(c -> c.screen.onClose());
            context.waitTicks(3);
            // Verify read-only guards even when an inventory mod calls the game-mode click API directly.
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.CREATIVE));
            context.waitFor(c -> c.player.hasInfiniteMaterials());
            context.runOnClient(c -> c.setScreen(EntityDebugScreen.readOnly(c.level.getEntity(entityId), c.player.getInventory())));
            context.runOnClient(c -> {
                var menu = ((EntityDebugScreen)c.screen).getMenu();
                check(menu.descriptions.stream().noneMatch(d -> d.key().startsWith("inventory.")), "Remote mode must hide unsynchronized inventories");
                ItemStack before = c.player.getInventory().getItem(0).copy();
                check(!before.isEmpty(), "Read-only regression requires a nonempty slot");
                c.gameMode.handleInventoryMouseClick(c.player.containerMenu.containerId, 36, 0, ClickType.THROW, c.player);
                check(ItemStack.matches(before, c.player.getInventory().getItem(0)), "Read-only guard must block direct API edits");
                c.gameMode.handleInventoryMouseClick(c.player.containerMenu.containerId, 36, 2, ClickType.CLONE, c.player);
                check(menu.getCarried().isEmpty(), "Read-only must not acquire a carried stack");
                c.screen.onClose();
            });
            context.waitTick();
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SURVIVAL));
            context.waitFor(c -> !c.player.hasInfiniteMaterials());
            testOtherInventories(context, server);
            testShiftVisualOrder(context, server);
            // Protection must also work while the inspector is closed.
            testVillagerAi(context, server, villagerId);
        }
        // Reload from disk to verify the actual entity state and component persistence.
        try (var world = save.open()) {
            var server = world.getServer();
            context.waitTicks(10);
            testVillagerAi(context, server, villagerId);
            server.runOnServer(s -> {
                var villager = (Villager)s.overworld().getEntity(villagerId);
                check(villager != null && villager.getMainHandItem().is(Items.DIAMOND_SWORD), "Equipment must survive a world reload");
                check(villager.getMainHandItem().getDamageValue() == 17 && villager.getMainHandItem().isEnchanted(), "Reload must retain item components");
                // Vanilla InventoryCarrier reloads through SimpleContainer.addItem, merging equal stacks.
                check(villager.getInventory().countItem(Items.STONE) == 15, "Internal inventory contents must survive reload");
            });
            int id = server.computeOnServer(s -> s.overworld().getEntity(villagerId).getId());
            context.waitFor(c -> c.level.getEntity(id) != null);
            context.runOnClient(c -> EntityEditBridge.open(c, c.level.getEntity(id)));
            context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
            click(context, "equipment.mainhand", 0, ClickType.PICKUP);
            server.runOnServer(s -> {
                var villager = (Villager)s.overworld().getEntity(villagerId);
                check(!((VillagerHandProtection) villager).see$isMainHandProtected(), "Taking the item must release the manual hand protection");
                var player = s.getPlayerList().getPlayers().getFirst();
                ItemStack before = player.getMainHandItem().copy();
                player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD));
                MerchantOffers offers = new MerchantOffers();
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 10, 1, 0F));
                villager.setOffers(offers);
                villager.getBrain().setMemory(MemoryModuleType.INTERACTION_TARGET, player);
                var display = new ShowTradesToPlayer(400, 1600);
                display.start(s.overworld(), villager, 0);
                display.tick(s.overworld(), villager, 0);
                check(villager.getMainHandItem().is(Items.BREAD), "An unprotected villager must still display trade previews normally");
                display.stop(s.overworld(), villager, 0);
                check(villager.getMainHandItem().isEmpty(), "Normal trade preview cleanup must still work");
                player.setItemSlot(EquipmentSlot.MAINHAND, before);
            });
            server.runOnServer(s -> s.overworld().getEntity(villagerId).discard());
            context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().invalid);
            context.takeScreenshot("inspector-invalid-target");
            click(context, "player.0", 0, ClickType.PICKUP);
            click(context, "inventory.0", 2, ClickType.CLONE);
            server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().containerMenu.getCarried().is(Items.DIAMOND_SWORD), "Target invalidation must freeze all editing"));
            context.runOnClient(c -> c.screen.onClose());
            context.waitTicks(3);
            server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().countItem(Items.DIAMOND_SWORD) == 1, "Invalid target close must still return the carried sword exactly once"));
        }
    }

    private static void testChestTabsAndCloning(ClientGameTestContext context, TestServerContext server, UUID villagerId) {
        context.runOnClient(c -> {
            var screen = (EntityDebugScreen)c.screen;
            var menu = screen.getMenu();
            check(menu.isItemsPage(), "Inspector must default to the inventory tab");
            String[] armor = {"head", "chest", "legs", "feet"};
            for (int i = 0; i < armor.length; i++) {
                Slot slot = findSlot(menu, "equipment." + armor[i]);
                check(slot.x == 8 + i * 18 && slot.y == 18, "Armor row must be head, chest, legs, feet");
            }
            check(findSlot(menu, "equipment.mainhand").x == 8 && findSlot(menu, "equipment.mainhand").y == 36, "Main hand must start the second row");
            check(findSlot(menu, "equipment.offhand").x == 26 && findSlot(menu, "equipment.offhand").y == 36, "Offhand must follow main hand");
            for (int i = 0; i < 8; i++) {
                Slot slot = findSlot(menu, "inventory." + i);
                check(slot.x == 8 + i * 18 && slot.y == 54, "Villager inventory must occupy the third row");
            }
            var vanilla = ChestMenu.threeRows(0, c.player.getInventory());
            for (int i = 0; i < 36; i++) {
                Slot actual = menu.slots.get(menu.descriptions.size() + i);
                Slot expected = vanilla.slots.get(27 + i);
                check(actual.x == expected.x && actual.y == expected.y, "Player slots must match a single chest exactly");
            }
            for (var child : screen.children()) if (child instanceof Button button) {
                check(button.getY() >= 0 && button.getX() >= 0 && button.getRight() <= screen.width, "Tabs must fit the scaled window");
                check(c.font.width(button.getMessage()) <= button.getWidth() - 4, "Tab text must fit without truncation");
            }
        });
        server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.CREATIVE));
        context.waitFor(c -> c.player.hasInfiniteMaterials());
        mouseClick(context, "equipment.mainhand", 2);
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            ItemStack source = ((Villager)s.overworld().getEntity(villagerId)).getMainHandItem();
            check(!player.containerMenu.getCarried().isEmpty() && ItemStack.matches(source, player.containerMenu.getCarried()), "Middle click must clone enchanted items with all components intact");
        });
        selectTab(context, "概览");
        server.runOnServer(s -> {
            var menu = (EntityDebugMenu)s.getPlayerList().getPlayers().getFirst().containerMenu;
            check(!menu.isItemsPage(), "Tab selection must reach the server before inventory actions");
            check(menu.getCarried().is(Items.DIAMOND_SWORD), "Changing tabs must not drop the carried item");
        });
        context.runOnClient(c -> {
            var menu = ((EntityDebugScreen)c.screen).getMenu();
            check(menu.slots.subList(0, menu.descriptions.size()).stream().noneMatch(Slot::isActive), "Information tabs must hide all entity slots");
            check(menu.slots.get(menu.descriptions.size()).y == 85 && findSlot(menu, "player.0").y == 143, "Switching tabs must not move the player inventory");
        });
        mouseClick(context, "player.8", 0);
        context.runOnClient(c -> {
            var menu = ((EntityDebugScreen)c.screen).getMenu();
            check(menu.getCarried().isEmpty(), "Client placement after tab change: cursor=" + menu.getCarried()
                    + ", slot=" + findSlot(menu, "player.8").getItem() + ", editable=" + menu.canEdit());
        });
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            check(player.containerMenu.getCarried().isEmpty() && player.getInventory().getItem(8).is(Items.DIAMOND_SWORD), "Player inventory must remain usable on information tabs: cursor=" + player.containerMenu.getCarried() + ", slot=" + player.getInventory().getItem(8));
            check(((Villager)s.overworld().getEntity(villagerId)).getMainHandItem().is(Items.DIAMOND_SWORD), "Cloning must leave the source equipped");
            player.getInventory().setItem(8, ItemStack.EMPTY);
        });
        context.waitTicks(2);
        context.runOnClient(c -> c.getToastManager().clear());
        context.takeScreenshot("inspector-chest-overview");
        context.runOnClient(c -> {
            var menu = ((EntityDebugScreen)c.screen).getMenu();
            c.gameMode.handleInventoryMouseClick(menu.containerId, findSlot(menu, "equipment.mainhand").index, 2, ClickType.CLONE, c.player);
        });
        context.waitTicks(2);
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().containerMenu.getCarried().isEmpty(), "A direct clone call must not access hidden entity slots"));
        click(context, "player.1", 0, ClickType.QUICK_MOVE);
        server.runOnServer(s -> {
            var inventory = s.getPlayerList().getPlayers().getFirst().getInventory();
            check(inventory.getItem(1).isEmpty() && inventory.getItem(9).getCount() == 16, "Shift click on information tabs must move only within player inventory");
            check(((Villager)s.overworld().getEntity(villagerId)).getInventory().isEmpty(), "Shift click must not insert into hidden entity slots");
        });
        click(context, "player.9", 0, ClickType.QUICK_MOVE);
        // The first empty hotbar slot is 0, so restore the original test setup.
        click(context, "player.0", 0, ClickType.PICKUP);
        click(context, "player.1", 0, ClickType.PICKUP);
        selectTab(context, "原始");
        context.runOnClient(c -> c.getToastManager().clear());
        context.takeScreenshot("inspector-chest-raw");
        context.runOnClient(c -> {
            c.options.guiScale().set(1);
            c.resizeDisplay();
        });
        context.waitTick();
        context.takeScreenshot("inspector-chest-scale-one");
        context.runOnClient(c -> {
            c.options.guiScale().set(4);
            c.resizeDisplay();
        });
        context.waitTick();
        selectTab(context, "物品");
        mouseClick(context, "player.1", 2);
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            check(player.containerMenu.getCarried().is(Items.STONE) && player.containerMenu.getCarried().getCount() == 64, "Middle click must clone a full stack");
            check(player.getInventory().getItem(1).getCount() == 16, "Cloning must not consume the source stack");
        });
        mouseClick(context, "player.8", 0);
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(8, ItemStack.EMPTY);
            player.setGameMode(GameType.SURVIVAL);
        });
        context.waitFor(c -> !c.player.hasInfiniteMaterials());
        mouseClick(context, "player.1", 2);
        click(context, "equipment.mainhand", 2, ClickType.CLONE);
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().containerMenu.getCarried().isEmpty(), "Survival must reject cloning, including direct container clicks"));
        context.runOnClient(c -> c.getToastManager().clear());
        context.takeScreenshot("inspector-chest-items");
    }

    private static void testVillagerAi(ClientGameTestContext context, TestServerContext server, UUID villagerId) {
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            var villager = (Villager)s.overworld().getEntity(villagerId);
            ItemStack original = villager.getMainHandItem().copy();
            check(original.is(Items.DIAMOND_SWORD), "AI regression requires the manually equipped sword");
            ItemStack playerHand = player.getMainHandItem().copy();
            MerchantOffers oldOffers = villager.getOffers();
            try {
                MerchantOffers offers = new MerchantOffers();
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 10, 1, 0F));
                villager.setOffers(offers);
                for (ItemStack hand : new ItemStack[]{ItemStack.EMPTY, new ItemStack(Items.EMERALD)}) {
                    player.setItemSlot(EquipmentSlot.MAINHAND, hand);
                    villager.getBrain().setMemory(MemoryModuleType.INTERACTION_TARGET, player);
                    var display = new ShowTradesToPlayer(400, 1600);
                    display.start(s.overworld(), villager, 0);
                    display.tick(s.overworld(), villager, 0);
                    check(ItemStack.matches(original, villager.getMainHandItem()), "Trade display AI must not erase or replace manually equipped items");
                    display.stop(s.overworld(), villager, 0);
                    check(ItemStack.matches(original, villager.getMainHandItem()), "Trade display stop must retain manual equipment");
                }
                var bonemeal = new BonemealDisplayProbe();
                bonemeal.startDisplay(s.overworld(), villager);
                check(ItemStack.matches(original, villager.getMainHandItem()), "Farming display start must preserve the main-hand item");
                bonemeal.stopDisplay(s.overworld(), villager);
                check(ItemStack.matches(original, villager.getMainHandItem()), "Farming display stop must preserve the main-hand item");
            } finally {
                player.setItemSlot(EquipmentSlot.MAINHAND, playerHand);
                villager.setOffers(oldOffers);
            }
            villager.setNoAi(false);
            villager.getBrain().setMemory(MemoryModuleType.INTERACTION_TARGET, player);
        });
        context.waitTicks(60);
        server.runOnServer(s -> {
            var villager = (Villager)s.overworld().getEntity(villagerId);
            check(!villager.isNoAi(), "The fix must not disable villager AI");
            check(villager.getMainHandItem().is(Items.DIAMOND_SWORD) && villager.getMainHandItem().isEnchanted()
                    && villager.getMainHandItem().getDamageValue() == 17, "Active villager AI must retain manually equipped items and components");
            villager.setNoAi(true);
        });
    }

    private static final class BonemealDisplayProbe extends UseBonemeal {
        void startDisplay(ServerLevel level, Villager villager) { super.start(level, villager, level.getGameTime()); }
        void stopDisplay(ServerLevel level, Villager villager) { super.stop(level, villager, level.getGameTime()); }
    }

    private static void testOtherInventories(ClientGameTestContext context, TestServerContext server) {
        int allayId = server.computeOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            var allay = EntityType.ALLAY.create(s.overworld(), EntitySpawnReason.COMMAND);
            allay.setPos(player.getX() + 2, player.getY(), player.getZ());
            allay.setNoAi(true);
            allay.getInventory().setItem(0, new ItemStack(Items.AMETHYST_SHARD, 3));
            s.overworld().addFreshEntity(allay);
            return allay.getId();
        });
        context.waitFor(c -> c.level.getEntity(allayId) != null);
        context.runOnClient(c -> EntityEditBridge.open(c, c.level.getEntity(allayId)));
        context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
        context.runOnClient(c -> {
            var menu = ((EntityDebugScreen)c.screen).getMenu();
            Slot slot = findSlot(menu, "inventory.0");
            check(slot.x == 8 && slot.y == 54 && slot.getItem().is(Items.AMETHYST_SHARD), "Allay inventory must appear in the third row");
        });
        mouseClick(context, "inventory.0", 0);
        mouseClick(context, "equipment.mainhand", 0);
        server.runOnServer(s -> {
            var allay = (Allay)s.overworld().getEntity(allayId);
            check(allay.getInventory().isEmpty() && allay.getMainHandItem().getCount() == 3, "Allay inventory and main hand must be distinct real slots");
        });
        context.runOnClient(c -> c.getToastManager().clear());
        context.takeScreenshot("inspector-allay-rows");
        context.runOnClient(c -> c.screen.onClose());
        context.waitTicks(3);
        int cartId = server.computeOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            var cart = EntityType.CHEST_MINECART.create(s.overworld(), EntitySpawnReason.COMMAND);
            cart.setPos(player.getX() + 2, player.getY(), player.getZ());
            cart.setItem(0, new ItemStack(Items.DIRT, 5));
            cart.setItem(26, new ItemStack(Items.DIAMOND, 3));
            s.overworld().addFreshEntity(cart);
            return cart.getId();
        });
        context.waitFor(c -> c.level.getEntity(cartId) != null);
        context.runOnClient(c -> EntityEditBridge.open(c, c.level.getEntity(cartId)));
        context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
        context.runOnClient(c -> {
            var screen = (EntityDebugScreen)c.screen;
            var menu = screen.getMenu();
            check(menu.otherPages() == 3, "A chest minecart needs three pages in the third row");
            check(menu.slots.subList(0, 27).stream().filter(Slot::isActive).count() == 9, "Only nine other slots should be visible");
            check(findSlot(menu, "inventory.0").isActive() && !findSlot(menu, "inventory.26").isActive(), "First page visibility must match actual indices");
            double x = (screen.width - 176) / 2 + 16;
            double y = (screen.height - 168) / 2 + 62;
            screen.mouseScrolled(x, y, 0, -1);
            screen.mouseScrolled(x, y, 0, -1);
            check(!findSlot(menu, "inventory.0").isActive() && findSlot(menu, "inventory.26").isActive(), "Scrolling must reveal the last inventory slots");
            check(findSlot(menu, "inventory.26").y == 54 && findSlot(menu, "player.0").y == 143, "Inventory paging must not move any rows");
        });
        mouseClick(context, "inventory.26", 0);
        context.runOnClient(c -> {
            var screen = (EntityDebugScreen)c.screen;
            screen.mouseScrolled((screen.width - 176) / 2 + 16, (screen.height - 168) / 2 + 62, 0, 1);
            screen.mouseScrolled((screen.width - 176) / 2 + 16, (screen.height - 168) / 2 + 62, 0, 1);
        });
        mouseClick(context, "inventory.1", 0);
        server.runOnServer(s -> {
            var cart = (MinecartChest)s.overworld().getEntity(cartId);
            check(cart.getItem(26).isEmpty() && cart.getItem(1).is(Items.DIAMOND) && cart.getItem(1).getCount() == 3
                    && cart.getItem(0).getCount() == 5, "Cross-page editing must target the correct slots without loss");
        });
        context.runOnClient(c -> c.getToastManager().clear());
        context.takeScreenshot("inspector-container-third-row");
        selectTab(context, "概览");
        context.runOnClient(c -> check(((EntityDebugScreen)c.screen).getMenu().slots.subList(0, 27).stream().noneMatch(Slot::isActive), "Information tabs must hide all inventory pages"));
        context.runOnClient(c -> c.screen.onClose());
        context.waitTicks(3);
    }

    private static void testShiftVisualOrder(ClientGameTestContext context, TestServerContext server) {
        int id = server.computeOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            check(player.getInventory().getItem(8).isEmpty(), "Shift-order test needs an empty hotbar slot");
            var villager = EntityType.VILLAGER.create(s.overworld(), EntitySpawnReason.COMMAND);
            villager.setPos(player.getX() + 2, player.getY(), player.getZ());
            villager.setNoAi(true);
            s.overworld().addFreshEntity(villager);
            return villager.getId();
        });
        context.waitFor(c -> c.level.getEntity(id) != null);
        context.runOnClient(c -> EntityEditBridge.open(c, c.level.getEntity(id)));
        context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
        String[] order = {"equipment.head", "equipment.chest", "equipment.legs", "equipment.feet",
                "equipment.mainhand", "equipment.offhand", "inventory.0", "inventory.1"};
        for (int i = 0; i < order.length; i++) {
            String expected = order[i];
            int expectedTotal = (i + 1) * 64;
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().setItem(8, new ItemStack(Items.STONE, 64)));
            context.waitTicks(2);
            mouseClick(context, "player.8", 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
            context.runOnClient(c -> check(findSlot(((EntityDebugScreen)c.screen).getMenu(), expected).getItem().getCount() == 64,
                    "Shift-click client prediction must fill " + expected + " in visual order"));
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                var menu = (EntityDebugMenu)player.containerMenu;
                check(findSlot(menu, expected).getItem().is(Items.STONE) && findSlot(menu, expected).getItem().getCount() == 64,
                        "Shift-click server result must fill " + expected + " in visual order");
                check(menu.entries.stream().mapToInt(entry -> entry.access().get().getCount()).sum() == expectedTotal,
                        "Shift-click must preserve the total item count across all three rows");
                check(player.getInventory().getItem(8).isEmpty() && menu.getCarried().isEmpty(), "Shift-click must move the entire stack without leaving ghost items");
            });
        }
        // Keep vanilla merging and overflow behavior when a matching stack is almost full.
        server.runOnServer(s -> {
            ((Villager)s.overworld().getEntity(id)).setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.STONE, 60));
            s.getPlayerList().getPlayers().getFirst().getInventory().setItem(8, new ItemStack(Items.STONE, 10));
        });
        context.waitTicks(2);
        mouseClick(context, "player.8", 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
        server.runOnServer(s -> {
            var villager = (Villager)s.overworld().getEntity(id);
            check(villager.getItemBySlot(EquipmentSlot.HEAD).getCount() == 64 && villager.getInventory().getItem(2).getCount() == 6,
                    "Shift-click must merge matching stacks then place the remainder in the next available slot");
        });
        context.runOnClient(c -> c.screen.onClose());
        context.waitTicks(3);
    }

    /**
     * The inventory screen only has the items page now (the other tabs moved into the NBT editor),
     * but the menu still hides its entity slots when the page is off; drive that state directly.
     */
    private static void selectTab(ClientGameTestContext context, String label) {
        context.runOnClient(c -> {
            var menu = ((EntityDebugScreen)c.screen).getMenu();
            boolean items = label.equals("物品");
            menu.setItemsPage(items);
            if (c.player.containerMenu == menu && menu.ready) c.gameMode.handleInventoryButtonClick(menu.containerId, items ? 0 : 1);
        });
        context.waitTicks(2);
    }

    private static void mouseClick(ClientGameTestContext context, String key, int button) {
        mouseClick(context, key, button, 0);
    }

    private static void mouseClick(ClientGameTestContext context, String key, int button, int modifiers) {
        context.runOnClient(c -> {
            var screen = (EntityDebugScreen)c.screen;
            Slot slot = findSlot(screen.getMenu(), key);
            var event = new MouseButtonEvent((screen.width - 176) / 2 + slot.x + 8, (screen.height - 168) / 2 + slot.y + 8, new MouseButtonInfo(button, modifiers));
            screen.mouseClicked(event, false);
            screen.mouseReleased(event);
        });
        context.waitTicks(2);
    }

    private static Slot findSlot(EntityDebugMenu menu, String key) {
        if (key.startsWith("player.")) {
            int inventorySlot = Integer.parseInt(key.substring(7));
            for (int i = menu.descriptions.size(); i < menu.slots.size(); i++) if (menu.slots.get(i).getContainerSlot() == inventorySlot) return menu.slots.get(i);
        } else {
            for (int i = 0; i < menu.descriptions.size(); i++) if (menu.descriptions.get(i).key().equals(key)) return menu.slots.get(i);
        }
        throw new AssertionError("Missing slot: " + key);
    }

    private static void click(ClientGameTestContext context, String key, int button, ClickType type) {
        context.runOnClient(c -> {
            var screen = (EntityDebugScreen)c.screen;
            Slot slot = key == null ? null : findSlot(screen.getMenu(), key);
            screen.slotClicked(slot, slot == null ? -999 : slot.index, button, type);
        });
        context.waitTicks(2);
    }
}
