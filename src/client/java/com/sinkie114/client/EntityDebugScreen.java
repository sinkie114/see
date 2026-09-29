package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/**
 * The inventory tab of the entity editor: single-chest dimensions and inventory coordinates,
 * with the editor's mode tabs above. Leaving the tab closes the container and returns to the editor.
 */
public final class EntityDebugScreen extends AbstractContainerScreen<EntityDebugMenu> implements EntityNbtLayer {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int CONTENT_X = 8, CONTENT_TOP = 18;
    private static final int PLAYER_TOP = 85, TAB_TOP = -22, TAB_HEIGHT = 22;
    private final Entity target;
    private final EntityEditBridge.Session session;
    private final EntityNbtSession nbt;
    private final List<EntityItems.Entry> remoteEntries;
    private boolean tabInput;
    private final List<Button> tabs = new ArrayList<>();

    public EntityDebugScreen(Entity target, EntityDebugMenu menu, EntityEditBridge.Session session, EntityNbtSession nbt) {
        super(menu, menu.inventory, Component.literal("实体调试查看器"));
        this.target = target;
        this.session = session;
        this.nbt = nbt;
        this.remoteEntries = session == null ? EntityItems.discover(target, false) : List.of();
        imageWidth = 176;
        imageHeight = 168;
        inventoryLabelY = 74;
        refreshRemoteItems();
    }

    public static EntityDebugScreen readOnly(Entity target, Inventory inventory) { return readOnly(target, inventory, null); }

    public static EntityDebugScreen readOnly(Entity target, Inventory inventory, EntityNbtSession nbt) {
        var entries = EntityItems.discover(target, false);
        var menu = EntityDebugMenu.client(-114, entries.stream().map(EntityItems.Entry::description).toList(), inventory, false);
        menu.setClientTarget(target);
        return new EntityDebugScreen(target, menu, null, nbt);
    }

    @Override public EntityNbtSession session() { return nbt; }

    @Override protected void init() {
        super.init();
        menu.layout(CONTENT_X, CONTENT_TOP, PLAYER_TOP);
        menu.setItemsPage(true);
        tabs.clear();
        if (nbt != null) {
            EditorMode[] modes = EditorMode.values();
            for (int i = 0; i < modes.length; i++) {
                EditorMode mode = modes[i];
                Button button = Button.builder(Component.literal(mode.shortTitle), b -> navigate(mode))
                        .bounds(leftPos + 1 + i * 44, topPos + TAB_TOP, 43, TAB_HEIGHT)
                        .tooltip(Tooltip.create(Component.literal(mode.title))).build();
                button.active = mode != EditorMode.INVENTORY;
                tabs.add(addRenderableWidget(button));
            }
        }
    }

    /** Closes the container the vanilla way, then shows the requested editor tab. */
    private void navigate(EditorMode mode) {
        if (nbt == null || mode == EditorMode.INVENTORY) return;
        leaveContainer();
        if (minecraft.screen == this || minecraft.screen == null || minecraft.screen instanceof EntityNbtLayer) nbt.go(mode);
    }

    private void leaveContainer() {
        if (session != null) {
            session.closed = true;
            if (minecraft.player != null) minecraft.player.closeContainer();
        }
    }

    /** Back to the editor tab the user came from; also used when the server closes the container. */
    public void showEditor() {
        if (minecraft.screen != this) return;
        if (nbt != null) nbt.showEditor(); else minecraft.setScreen(null);
    }

    private void refreshRemoteItems() {
        if (session == null && !menu.invalid) {
            for (int i = 0; i < remoteEntries.size(); i++) menu.items.getItems().set(i, remoteEntries.get(i).access().get().copy());
        }
    }

    @Override public void containerTick() {
        menu.invalid |= target.isRemoved() || !target.isAlive() || minecraft.level == null
                || target.level() != minecraft.level || minecraft.level.getEntity(target.getId()) != target;
        if (session != null) {
            menu.invalid |= session.invalid;
            if (menu.invalid) session.invalid = true;
        } else if (!menu.invalid) {
            refreshRemoteItems();
        }
    }

    @Override protected void renderBg(GuiGraphics g, float delta, int mouseX, int mouseY) {
        g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos, 0, 0, imageWidth, 71, 256, 256);
        g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos + 71, 0, 126, imageWidth, 96, 256, 256);
        // Remove the texture's placeholder slots: display only actual entity slots.
        g.fill(leftPos + 7, topPos + 17, leftPos + 169, topPos + 71, 0xFFC6C6C6);
        for (int i = 0; i < menu.descriptions.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive()) continue;
            g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos + slot.x - 1, topPos + slot.y - 1, 7, 17, 18, 18, 256, 256);
        }
    }

    private String modeLabel() {
        return menu.invalid ? "已失效" : session == null ? "只读" : !menu.ready ? "同步中" : "可编辑";
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        String title = target.getDisplayName().getString();
        String rows = menu.otherPages() > 1 ? (menu.otherPage + 1) + "/" + menu.otherPages() : "";
        g.drawString(font, font.plainSubstrByWidth(title, 160 - font.width(rows)), 8, 6, 0xFF404040, false);
        if (!rows.isEmpty()) g.drawString(font, rows, 168 - font.width(rows), 6, 0xFF404040, false);
        g.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF404040, false);
        String mode = modeLabel();
        g.drawString(font, mode, 168 - font.width(mode), inventoryLabelY, menu.invalid ? 0xFFAA2222 : 0xFF404040, false);
        if (menu.descriptions.isEmpty()) g.drawCenteredString(font, "此实体没有物品槽位", 88, 38, 0xFF555555);
        if (menu.otherPages() > 1) g.drawString(font, "滚轮翻页", 124, 40, 0xFF555555, false);
        for (int i = 0; i < menu.descriptions.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive() || slot.hasItem()) continue;
            var description = menu.descriptions.get(i);
            String label = description.key().startsWith("inventory.") ? description.key().substring(10) : description.label();
            g.pose().pushMatrix();
            g.pose().translate(slot.x + 8, slot.y + 6);
            g.pose().scale(0.5F, 0.5F);
            String shortLabel = font.plainSubstrByWidth(label, 32);
            g.drawString(font, shortLabel, -font.width(shortLabel) / 2, 0, 0xFFD0D0D0, false);
            g.pose().popMatrix();
        }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        renderTooltip(g, mouseX, mouseY);
        if (hoveredSlot != null && hoveredSlot.index < menu.descriptions.size() && !hoveredSlot.hasItem()) {
            g.setTooltipForNextFrame(Component.literal(menu.descriptions.get(hoveredSlot.index).label()), mouseX, mouseY);
        }
        if (isHovering(8, 4, 160, 12, mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(font, List.of(target.getDisplayName(),
                    Component.literal(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString()),
                    Component.literal("UUID: " + target.getUUID())), mouseX, mouseY);
        } else if (isHovering(117, 72, 52, 10, mouseX, mouseY)) {
            String detail = menu.invalid ? "目标已失效，保留最后数据，编辑已禁用"
                    : session == null ? "多人模式：实体和玩家物品栏均只读" : "集成服务器：修改立即生效；创造模式支持中键复制";
            g.setTooltipForNextFrame(font, font.split(Component.literal(detail), 230), mouseX, mouseY);
        }
    }

    @Override protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> tooltip = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("nbt-maker")) {
            var key = net.minecraft.client.KeyMapping.get("key.nbt-maker.open");
            if (key != null) tooltip.add(Component.literal("按 ").append(key.getTranslatedKeyMessage())
                    .append(" 查看物品数据（NBT Maker）").withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        if (hoveredSlot != null && hoveredSlot.index < menu.descriptions.size()) {
            var description = menu.descriptions.get(hoveredSlot.index);
            tooltip.add(Component.literal("槽位: " + description.label()));
            if (description.key().equals("carried_block")) tooltip.add(Component.literal("搬运方块仅接收无自定义组件的方块物品"));
        }
        return tooltip;
    }

    @Override protected void slotClicked(Slot slot, int slotId, int button, ClickType type) {
        if (menu.canEdit() && (slot == null || slot.isActive())) super.slotClicked(slot, slotId, button, type);
    }

    private boolean inContent(double x, double y) {
        return x >= leftPos + CONTENT_X && x < leftPos + 169 && y >= topPos + 17 && y < topPos + 71;
    }
    private boolean inTabs(double x, double y) {
        return x >= leftPos && x < leftPos + imageWidth && y >= topPos + TAB_TOP && y < topPos;
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (inTabs(event.x(), event.y())) {
            tabInput = true;
            super.mouseClicked(event, doubleClick);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (tabInput || inTabs(event.x(), event.y())) {
            // Fabric forwards releases to the focused widget while Screen.isDragging remains true.
            setDragging(false);
            if (tabInput && getFocused() != null) getFocused().mouseReleased(event);
            tabInput = false;
            isQuickCrafting = false;
            quickCraftSlots.clear();
            clearDraggingState();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override protected boolean hasClickedOutside(double x, double y, int left, int top) {
        return !inTabs(x, y) && super.hasClickedOutside(x, y, left, top);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (inContent(x, y)) {
            if (isQuickCrafting) return true;
            menu.otherPage = Math.max(0, Math.min(menu.otherPages() - 1, menu.otherPage - (int) Math.signum(vertical)));
            menu.layout(CONTENT_X, CONTENT_TOP, PLAYER_TOP);
            hoveredSlot = null;
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    /** Esc returns to the editor; the container is closed first so carried items go back to the player. */
    @Override public void onClose() {
        leaveContainer();
        showEditor();
    }
}
