package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Single-chest dimensions and inventory coordinates, with a separate tab strip above. */
public final class EntityDebugScreen extends AbstractContainerScreen<EntityDebugMenu> {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int CONTENT_X = 8, CONTENT_TOP = 18, CONTENT_BOTTOM = 68, CONTENT_RIGHT = 166;
    private static final int PLAYER_TOP = 85, TAB_TOP = -22, TAB_HEIGHT = 22, NBT_X = 143, NBT_WIDTH = 26;
    private final Entity target;
    private final EntityEditBridge.Session session;
    private final List<EntityItems.Entry> remoteEntries;
    private EntitySnapshot snapshot;
    private Page page = Page.ITEMS;
    private int scroll, horizontalScroll, maxScroll, maxHorizontalScroll;
    private boolean tabInput, pageSynced, suspended;
    private final Set<String> expanded = new HashSet<>(Set.of("Entity"));
    private List<RawLine> rawLines = List.of();
    private List<String> lines = List.of();
    private final List<Button> tabs = new ArrayList<>();
    private Button nbtButton;
    private EntityNbtScreen nbtEditor;

    public EntityDebugScreen(Entity target, EntityDebugMenu menu, EntityEditBridge.Session session) {
        super(menu, menu.inventory, Component.literal("实体调试查看器"));
        this.target = target;
        this.session = session;
        this.remoteEntries = session == null ? EntityItems.discover(target, false) : List.of();
        snapshot = session != null && session.snapshot != null ? session.snapshot : EntitySnapshot.capture(target, false, remoteEntries);
        imageWidth = 176;
        imageHeight = 168;
        inventoryLabelY = 74;
        refreshRemoteItems();
    }

    public static EntityDebugScreen readOnly(Entity target, Inventory inventory) {
        var entries = EntityItems.discover(target, false);
        var menu = EntityDebugMenu.client(-114, entries.stream().map(EntityItems.Entry::description).toList(), inventory, false);
        menu.setClientTarget(target);
        return new EntityDebugScreen(target, menu, null);
    }

    @Override protected void init() {
        super.init();
        suspended = tabInput = false;
        menu.layout(CONTENT_X, CONTENT_TOP, PLAYER_TOP);
        menu.setItemsPage(page == Page.ITEMS);
        tabs.clear();
        for (Page candidate : Page.values()) {
            Button button = Button.builder(Component.literal(candidate.shortTitle), b -> selectPage(candidate))
                    .bounds(leftPos + 1 + candidate.ordinal() * 25, topPos + TAB_TOP, 24, TAB_HEIGHT)
                    .tooltip(Tooltip.create(Component.literal(candidate.title))).build();
            tabs.add(addRenderableWidget(button));
        }
        nbtButton = addRenderableWidget(Button.builder(Component.literal("NBT"), b -> openNbtEditor(List.of()))
                .bounds(leftPos + NBT_X, topPos + 3, NBT_WIDTH, 12)
                .tooltip(Tooltip.create(Component.literal(session == null
                        ? "多人模式：客户端没有完整实体 NBT，无法编辑"
                        : "编辑实体 NBT：完整数据树，同步后写入实体"))).build());
        nbtButton.active = canOpenNbt();
        updateTabs();
        rebuildLines();
    }

    private boolean canOpenNbt() {
        return session != null && session.snapshot != null && session.snapshot.nbt() != null;
    }

    /** The vanilla container stays open underneath; the editor returns to this exact screen. */
    private void openNbtEditor(List<Object> focus) {
        if (!canOpenNbt()) return;
        isQuickCrafting = false;
        quickCraftSlots.clear();
        clearDraggingState();
        if (nbtEditor == null) nbtEditor = new EntityNbtScreen(this, target, session, focus);
        else if (!focus.isEmpty()) nbtEditor.focus(focus);
        suspended = true;
        minecraft.setScreen(nbtEditor);
    }

    void resumeFromNbtEditor() {
        if (minecraft.player != null && minecraft.player.containerMenu == menu) minecraft.setScreen(this);
        else minecraft.setScreen(null);
    }

    @Override public void removed() {
        // Suspending for the NBT editor is not closing the container.
        if (!suspended) super.removed();
    }

    private void selectPage(Page selected) {
        if (page == selected) return;
        page = selected;
        scroll = horizontalScroll = 0;
        isQuickCrafting = false;
        quickCraftSlots.clear();
        clearDraggingState();
        hoveredSlot = null;
        menu.setItemsPage(page == Page.ITEMS);
        pageSynced = false;
        syncPage();
        updateTabs();
        rebuildLines();
    }

    private void syncPage() {
        if (!pageSynced && session != null && menu.ready && minecraft.player.containerMenu == menu) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, page == Page.ITEMS ? 0 : 1);
            pageSynced = true;
        }
    }

    private void updateTabs() {
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).active = i != page.ordinal();
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
            if (!menu.invalid && session.snapshot != null) snapshot = session.snapshot;
            if (menu.invalid) session.invalid = true;
            syncPage();
            if (nbtButton != null) nbtButton.active = canOpenNbt();
        } else if (!menu.invalid) {
            refreshRemoteItems();
            snapshot = EntitySnapshot.capture(target, false, remoteEntries);
        }
        rebuildLines();
    }

    @Override protected void renderBg(GuiGraphics g, float delta, int mouseX, int mouseY) {
        g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos, 0, 0, imageWidth, 71, 256, 256);
        g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos + 71, 0, 126, imageWidth, 96, 256, 256);
        // Remove the texture's placeholder slots: display only actual entity slots on the inventory tab.
        g.fill(leftPos + 7, topPos + 17, leftPos + 169, topPos + 71, 0xFFC6C6C6);
        if (page == Page.ITEMS) {
            for (int i = 0; i < menu.descriptions.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (!slot.isActive()) continue;
                g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos + slot.x - 1, topPos + slot.y - 1, 7, 17, 18, 18, 256, 256);
            }
        } else {
            g.fill(leftPos + 7, topPos + 17, leftPos + 169, topPos + 71, 0xFFB0B0B0);
        }
    }

    private String modeLabel() {
        return menu.invalid ? "已失效" : session == null ? "只读" : !menu.ready ? "同步中" : "可编辑";
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        String title = snapshot.name().getString();
        String rows = page == Page.ITEMS && menu.otherPages() > 1
                ? (menu.otherPage + 1) + "/" + menu.otherPages() : "";
        int titleRight = NBT_X - 3;
        g.drawString(font, font.plainSubstrByWidth(title, titleRight - 8 - (rows.isEmpty() ? 0 : font.width(rows) + 4)), 8, 6, 0xFF404040, false);
        if (!rows.isEmpty()) g.drawString(font, rows, titleRight - font.width(rows), 6, 0xFF404040, false);
        g.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF404040, false);
        String mode = modeLabel();
        g.drawString(font, mode, 168 - font.width(mode), inventoryLabelY, menu.invalid ? 0xFFAA2222 : 0xFF404040, false);
        if (page == Page.ITEMS) {
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
            return;
        }
        g.enableScissor(CONTENT_X, CONTENT_TOP, CONTENT_RIGHT, CONTENT_BOTTOM);
        int first = scroll / 10;
        int last = Math.min(lines.size(), (scroll + CONTENT_BOTTOM - CONTENT_TOP) / 10 + 1);
        for (int i = first; i < last; i++) g.drawString(font, lines.get(i), CONTENT_X - horizontalScroll, CONTENT_TOP + i * 10 - scroll, 0xFF303030, false);
        g.disableScissor();
        if (maxScroll > 0) {
            int height = CONTENT_BOTTOM - CONTENT_TOP;
            int barHeight = Math.max(6, height * height / (lines.size() * 10));
            int barY = CONTENT_TOP + (height - barHeight) * scroll / maxScroll;
            g.fill(167, barY, 169, barY + barHeight, 0xFF666666);
        }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        renderTooltip(g, mouseX, mouseY);
        if (hoveredSlot != null && hoveredSlot.index < menu.descriptions.size() && !hoveredSlot.hasItem()) {
            g.setTooltipForNextFrame(Component.literal(menu.descriptions.get(hoveredSlot.index).label()), mouseX, mouseY);
        }
        if (page != Page.ITEMS && inContent(mouseX, mouseY)) {
            int row = (mouseY - topPos - CONTENT_TOP + scroll) / 10;
            if (row >= 0 && row < lines.size() && (page == Page.RAW || font.width(lines.get(row)) > CONTENT_RIGHT - CONTENT_X)) {
                String detail = page == Page.RAW ? rawLines.get(row).detail() : lines.get(row);
                g.setTooltipForNextFrame(font, font.split(Component.literal(detail), Math.min(320, width - 24)), mouseX, mouseY);
            }
        }
        if (isHovering(8, 4, NBT_X - 11, 12, mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(font, List.of(snapshot.name(), Component.literal(snapshot.id()), Component.literal("UUID: " + snapshot.uuid())), mouseX, mouseY);
        } else if (isHovering(117, 72, 52, 10, mouseX, mouseY)) {
            String detail = menu.invalid ? "目标已失效，保留最后数据，编辑已禁用"
                    : session == null ? "多人模式：实体和玩家物品栏均只读"
                    : "集成服务器：修改立即生效；创造模式支持中键复制；标题栏 NBT 按钮可编辑实体数据";
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
        if (inTabs(event.x(), event.y()) || nbtButton != null && nbtButton.isMouseOver(event.x(), event.y())) {
            tabInput = true;
            super.mouseClicked(event, doubleClick);
            return true;
        }
        if (page != Page.ITEMS && inContent(event.x(), event.y())) {
            int row = (int) ((event.y() - topPos - CONTENT_TOP + scroll) / 10);
            if (page == Page.RAW && row >= 0 && row < rawLines.size()) {
                RawLine line = rawLines.get(row);
                if (event.button() == 1 && canOpenNbt()) openNbtEditor(line.keys());
                else if (event.button() == 0 && line.branch()) {
                    if (!expanded.remove(line.path())) expanded.add(line.path());
                    rebuildLines();
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (tabInput || inTabs(event.x(), event.y()) || page != Page.ITEMS && inContent(event.x(), event.y())) {
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
            if (page == Page.ITEMS) {
                if (isQuickCrafting) return true;
                menu.otherPage = Math.max(0, Math.min(menu.otherPages() - 1, menu.otherPage - (int) Math.signum(vertical)));
                menu.layout(CONTENT_X, CONTENT_TOP, PLAYER_TOP);
                hoveredSlot = null;
            } else {
                scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (vertical * 20)));
                horizontalScroll = Math.max(0, Math.min(maxHorizontalScroll, horizontalScroll - (int) (horizontal * 20)));
            }
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private void rebuildLines() {
        if (page == Page.ITEMS) { lines = List.of(); return; }
        if (page == Page.RAW) {
            List<RawLine> raw = new ArrayList<>();
            flatten(raw, "Entity", "Entity", List.of(), snapshot.raw(), 0);
            rawLines = List.copyOf(raw);
            lines = rawLines.stream().map(RawLine::text).toList();
        } else {
            lines = snapshot.pages().getOrDefault(page.key, List.of());
            if (lines.isEmpty()) lines = List.of("没有可显示的数据");
        }
        maxScroll = Math.max(0, lines.size() * 10 - (CONTENT_BOTTOM - CONTENT_TOP));
        maxHorizontalScroll = Math.max(0, lines.stream().mapToInt(font::width).max().orElse(0) - (CONTENT_RIGHT - CONTENT_X));
        scroll = Math.min(scroll, maxScroll);
        horizontalScroll = Math.min(horizontalScroll, maxHorizontalScroll);
    }

    private void flatten(List<RawLine> out, String name, String path, List<Object> keys, Tag tag, int depth) {
        boolean branch = tag instanceof CompoundTag || tag instanceof CollectionTag;
        String value = tag instanceof CompoundTag compound ? compound.size() + " 个字段"
                : tag instanceof CollectionTag list ? list.size() + " 项" : tag.toString();
        String type = tag.getClass().getSimpleName().replace("Tag", "");
        String prefix = branch ? expanded.contains(path) ? "▼ " : "▶ " : "  ";
        out.add(new RawLine("  ".repeat(depth) + prefix + name + " [" + type + "] " + value, path, keys, branch,
                "路径: " + path + "\n类型: " + type + "\n值: " + value + (canOpenNbt() ? "\n右键：在 NBT 编辑器中打开" : "")));
        if (!branch || !expanded.contains(path)) return;
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.keySet().stream().sorted().toList()) flatten(out, key, path + "[\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]", NbtTree.child(keys, key), compound.get(key), depth + 1);
        } else if (tag instanceof CollectionTag list) {
            for (int i = 0; i < list.size(); i++) flatten(out, "[" + i + "]", path + "[" + i + "]", NbtTree.child(keys, i), list.get(i), depth + 1);
        }
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (page != Page.ITEMS && (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT)) {
            horizontalScroll = Math.max(0, Math.min(maxHorizontalScroll, horizontalScroll + (event.key() == GLFW.GLFW_KEY_RIGHT ? 30 : -30)));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void onClose() {
        if (session == null) minecraft.setScreen(null);
        else { session.closed = true; super.onClose(); }
    }

    private enum Page {
        ITEMS("物品栏", "物品", "items"), OVERVIEW("概览", "概览", "overview"), ATTRIBUTES("属性", "属性", "attributes"),
        STATUS("状态", "状态", "status"), MOTION("位置与运动", "位置", "motion"), RAW("原始数据", "原始", "raw"), OTHER("其他数据", "其他", "other");
        final String title, shortTitle, key;
        Page(String title, String shortTitle, String key) { this.title = title; this.shortTitle = shortTitle; this.key = key; }
    }
    private record RawLine(String text, String path, List<Object> keys, boolean branch, String detail) {}
}
