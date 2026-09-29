package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Complete entity NBT tree editor; the layout and write-back flow follow NBT Maker's item editor. */
public final class EntityNbtScreen extends EntityEditorBase {
    private static final int ROW = 14;
    private static final List<Object> ROOT = List.of();
    private final Set<List<Object>> expanded = new HashSet<>(Set.of(ROOT));
    private final List<Row> rows = new ArrayList<>();
    private final List<Button> editingButtons = new ArrayList<>();
    private Page page = Page.ALL;
    private List<Object> selected = ROOT;
    private EditBox search;
    private String query = "";
    private int treeTop, treeBottom, scroll, horizontal;
    private boolean draggingBar;

    public EntityNbtScreen(EntityNbtSession session) { super(session, "实体 NBT 编辑器"); }
    @Override protected EditorMode mode() { return EditorMode.ADVANCED; }
    @Override protected void onRevision() { rebuildRows(); }
    @Override protected String hint() { return page.hint; }

    @Override protected void initContent(int top) {
        editingButtons.clear();
        int columns = w >= 400 ? Page.values().length : 4;
        for (Page candidate : Page.values()) {
            int i = candidate.ordinal(), bw = (w - 16) / columns;
            var b = button(columns == Page.values().length ? candidate.shortTitle : candidate.title, x + 8 + i % columns * bw, top + i / columns * 21, bw - 2, () -> {
                page = candidate; selected = ROOT; scroll = horizontal = 0; rebuildWidgets();
            }); b.active = candidate != page;
            b.setTooltip(Tooltip.create(Component.literal(candidate.title + "：" + candidate.hint)));
        }
        int searchY = top + (columns == Page.values().length ? 24 : 45);
        search = addRenderableWidget(new EditBox(font, x + 8, searchY, w - 130, 18, Component.literal("搜索数据")));
        search.setMaxLength(2048); search.setHint(Component.literal("搜索节点名、类型或值")); search.setValue(query);
        search.setResponder(s -> { query = s; scroll = 0; rebuildRows(); });
        button("展开", x + w - 120, searchY - 1, 54, () -> { expandAll(ROOT, 0); rebuildRows(); });
        button("折叠", x + w - 62, searchY - 1, 54, () -> { expanded.clear(); expanded.add(ROOT); rebuildRows(); });
        treeTop = searchY + 22; treeBottom = y + h - 65;
        String[] operations = {"编辑", "新增", "删除", "重命名", "复制节点", "刷新", "SNBT"};
        Runnable[] actions = {this::editSelected, this::addSelected, this::deleteSelected, this::renameSelected, this::copySelected,
                () -> { session.refresh(); rebuildRows(); }, () -> minecraft.setScreen(new SnbtScreen())};
        int aw = (w - 16) / operations.length;
        for (int i = 0; i < operations.length; i++) {
            Button b = button(operations[i], x + 8 + i * aw, y + h - 60, aw - 2, actions[i]);
            if (i < 4) { b.active = session.editable && !session.syncing; editingButtons.add(b); }
            if (i == 5) b.setTooltip(Tooltip.create(Component.literal("重新读取实体当前数据，保留你尚未同步的修改")));
        }
        rebuildRows();
    }

    private void replace(List<Object> path, Tag value) { session.edit(NbtTools.replace(session.document, path, value)); rebuildRows(); }

    private void rebuildRows() {
        rows.clear(); CompoundTag root = session.document;
        if (page == Page.ALL) append(ROOT, "实体", root, 0, true);
        else {
            append(ROOT, "实体", root, 0, false);
            root.keySet().stream().sorted().filter(page::accepts).forEach(k -> append(NbtTools.child(ROOT, k), k, root.get(k), 1, true));
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * ROW - (treeBottom - treeTop))));
    }
    private void append(List<Object> path, String name, Tag value, int depth, boolean recurse) {
        if (value == null || depth > 48 || rows.size() >= 10000) return;
        boolean branch = value instanceof CompoundTag || value instanceof CollectionTag;
        String needle = query.toLowerCase(Locale.ROOT);
        boolean matches = needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)
                || NbtTools.type(value).toLowerCase(Locale.ROOT).contains(needle)
                || (!branch && value.toString().toLowerCase(Locale.ROOT).contains(needle));
        if (matches) rows.add(new Row(path, name, value, depth, branch));
        if (!recurse || (!expanded.contains(path) && needle.isEmpty())) return;
        if (value instanceof CompoundTag c) c.keySet().stream().sorted().forEach(k -> append(NbtTools.child(path, k), k, c.get(k), depth + 1, true));
        else if (value instanceof CollectionTag c) for (int i = 0; i < c.size() && rows.size() < 10000; i++) append(NbtTools.child(path, i), "[" + i + "]", c.get(i), depth + 1, true);
    }
    private void expandAll(List<Object> path, int depth) {
        if (depth > 32 || expanded.size() > 10000) return;
        expanded.add(path); Tag value = NbtTools.at(session.document, path);
        if (value instanceof CompoundTag c) for (String key : c.keySet()) expandAll(NbtTools.child(path, key), depth + 1);
        else if (value instanceof CollectionTag c) for (int i = 0; i < c.size(); i++) expandAll(NbtTools.child(path, i), depth + 1);
    }

    private void editSelected() {
        Tag value = NbtTools.at(session.document, selected);
        if (value == null) return;
        List<Object> path = selected;
        minecraft.setScreen(new NbtValueScreen(this, "编辑节点 / " + NbtTools.type(value), path.toString(), value.toString(), false, true,
                session.editable && !session.syncing, (key, text) -> replace(path, parse(text))));
    }
    private static Tag parse(String text) {
        try { return TagParser.create(NbtOps.INSTANCE).parseFully(text); }
        catch (Exception ex) { throw new IllegalArgumentException(ex.getMessage()); }
    }
    private void addSelected() {
        if (!session.editable || session.syncing) return;
        Tag value = NbtTools.at(session.document, selected);
        List<Object> destination = selected;
        if (!(value instanceof CompoundTag) && !(value instanceof CollectionTag)) {
            destination = selected.isEmpty() ? ROOT : selected.subList(0, selected.size() - 1);
            value = NbtTools.at(session.document, destination);
        }
        final List<Object> path = List.copyOf(destination); final Tag target = value;
        if (!(target instanceof CompoundTag) && !(target instanceof CollectionTag)) { session.status = "请先选择 Compound、List 或数组"; return; }
        minecraft.setScreen(new NbtValueScreen(this, "新增数据节点", target instanceof CompoundTag ? "new_key" : "追加到列表末尾", "{}",
                target instanceof CompoundTag, true, true, (key, text) -> {
            Tag copy = target.copy(); Tag added = parse(text);
            if (copy instanceof CompoundTag c) {
                if (c.contains(key)) throw new IllegalArgumentException("该键已存在，请使用编辑");
                c.put(key, added);
            } else if (copy instanceof CollectionTag c && !c.addTag(c.size(), added)) throw new IllegalArgumentException("数组元素类型不匹配");
            replace(path, copy); expanded.add(path);
        }));
    }
    private void deleteSelected() {
        if (!session.editable || session.syncing) return;
        try {
            session.edit(NbtTools.remove(session.document, selected));
            selected = ROOT; rebuildRows();
        }
        catch (RuntimeException ex) { session.status = ex.getMessage(); }
    }
    private void renameSelected() {
        if (!session.editable || session.syncing || selected.isEmpty() || !(selected.getLast() instanceof String old)) return;
        List<Object> path = selected, parentPath = path.subList(0, path.size() - 1);
        Tag value = NbtTools.at(session.document, path);
        if (value == null) return;
        minecraft.setScreen(new NbtValueScreen(this, "重命名数据键", old, value.toString(), true, true, true, (key, text) -> {
            CompoundTag copy = ((CompoundTag) NbtTools.at(session.document, parentPath)).copy();
            if (!key.equals(old) && copy.contains(key)) throw new IllegalArgumentException("目标键已存在");
            copy.remove(old); copy.put(key, parse(text)); replace(parentPath, copy);
            selected = NbtTools.child(parentPath, key);
        }));
    }
    private void copySelected() { copy(NbtTools.at(session.document, selected)); }
    private void copy(Tag data) { if (data != null) { minecraft.keyboardHandler.setClipboard(data.toString()); session.status = "已复制 SNBT"; } }
    private void paste() {
        if (!session.editable || session.syncing) return;
        minecraft.setScreen(new NbtValueScreen(this, "从剪贴板导入 SNBT", "完整实体数据", minecraft.keyboardHandler.getClipboard(), false, false, true,
                (key, text) -> loadSnbt(text)));
    }
    private void loadSnbt(String text) {
        Tag tag = parse(text);
        if (!(tag instanceof CompoundTag c)) throw new IllegalArgumentException("完整实体数据必须为 Compound");
        // Only the fields that differ from the entity are written, so a stale export cannot clobber live state.
        session.edit(c); rebuildRows();
    }
    private Path exportDirectory() { return minecraft.gameDirectory.toPath().resolve("see").resolve("exports"); }
    private void importFile() {
        if (!session.editable || session.syncing) return;
        minecraft.setScreen(new NbtValueScreen(this, "导入本地 SNBT 文件", "填写绝对路径，或相对于游戏目录的路径", "see/exports/entity.snbt", false, false, true,
                (key, text) -> {
                    try { Path file = Path.of(text.trim()); if (!file.isAbsolute()) file = minecraft.gameDirectory.toPath().resolve(file); loadSnbt(Files.readString(file, StandardCharsets.UTF_8)); }
                    catch (java.io.IOException ex) { throw new IllegalArgumentException("读取失败：" + ex.getMessage()); }
                }));
    }
    private void exportFile() {
        try {
            Files.createDirectories(exportDirectory());
            Path file = exportDirectory().resolve("entity-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".snbt");
            Files.writeString(file, session.document.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            session.status = "已导出：" + file;
        } catch (Exception ex) { session.status = "导出失败：" + ex.getMessage(); }
    }

    private final class SnbtScreen extends Screen implements EntityNbtLayer {
        private int sx, sy, sw, sh;
        SnbtScreen() { super(Component.literal("SNBT 导入与导出")); }
        @Override public EntityNbtSession session() { return session; }
        @Override protected void init() {
            sw = Math.min(320, width - 16); sh = Math.min(214, height - 16); sx = (width - sw) / 2; sy = (height - sh) / 2;
            String[] labels = {"复制副本", "复制实体当前数据", "粘贴 SNBT", "导入文件", "导出文件", "返回"};
            Runnable[] tasks = {() -> copy(session.document), () -> copy(session.observed), EntityNbtScreen.this::paste,
                    EntityNbtScreen.this::importFile, EntityNbtScreen.this::exportFile, () -> {}};
            for (int i = 0; i < labels.length; i++) {
                Runnable task = tasks[i];
                Button b = addRenderableWidget(Button.builder(Component.literal(labels[i]), button -> {
                    minecraft.setScreen(EntityNbtScreen.this); task.run();
                }).bounds(sx + 8, sy + 28 + i * 26, sw - 16, 20).build());
                if (i == 2 || i == 3) b.active = session.editable && !session.syncing;
            }
        }
        @Override public void onClose() { minecraft.setScreen(EntityNbtScreen.this); }
        @Override public boolean keyPressed(KeyEvent key) {
            if (minecraft.options.keyInventory.matches(key)) { onClose(); return true; }
            return super.keyPressed(key);
        }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void render(GuiGraphics g, int mx, int my, float delta) {
            panel(g, sx, sy, sw, sh); g.drawString(font, title, sx + 8, sy + 10, 0xFF303030, false);
            super.render(g, mx, my, delta);
        }
    }

    @Override public void tick() {
        super.tick();
        for (Button button : editingButtons) button.active = session.editable && !session.syncing;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (!search.isFocused() && !typing()) {
            if (event.isCopy()) { copySelected(); return true; }
            if (event.isConfirmation()) { editSelected(); return true; }
            if (event.key() == 261 && session.editable) { deleteSelected(); return true; }
            if (event.isLeft() || event.isRight()) { horizontal = Math.max(0, horizontal + (event.isLeft() ? -30 : 30)); return true; }
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.x() >= x + w - 14 && event.x() < x + w - 7 && event.y() >= treeTop && event.y() < treeBottom) {
            draggingBar = true; dragBar(event.y()); return true;
        }
        if (event.x() >= x + 8 && event.x() < x + w - 8 && event.y() >= treeTop && event.y() < treeBottom) {
            int i = ((int) event.y() - treeTop + scroll) / ROW;
            if (i < rows.size()) {
                Row row = rows.get(i); selected = row.path;
                setFocused(null);
                int arrowX = x + 12 + row.depth * 12 - horizontal;
                if (row.branch && event.x() < arrowX + 12) { if (!expanded.remove(row.path)) expanded.add(row.path); rebuildRows(); }
                else if (doubleClick || event.button() == 1) editSelected();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
    private void dragBar(double my) {
        int maximum = Math.max(0, rows.size() * ROW - (treeBottom - treeTop));
        scroll = Math.max(0, Math.min(maximum, (int) ((my - treeTop) / (treeBottom - treeTop) * maximum)));
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingBar) { dragBar(event.y()); return true; }
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        draggingBar = false; return super.mouseReleased(event);
    }
    @Override public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        if (my >= treeTop && my < treeBottom) {
            scroll = Math.max(0, Math.min(Math.max(0, rows.size() * ROW - (treeBottom - treeTop)), scroll - (int) (vy * ROW * 3)));
            horizontal = Math.max(0, horizontal - (int) (hx * 24)); return true;
        }
        return super.mouseScrolled(mx, my, hx, vy);
    }
    @Override protected void renderContent(GuiGraphics g, int mx, int my) {
        g.fill(x + 8, treeTop, x + w - 8, treeBottom, 0xFF8B8B8B);
        g.enableScissor(x + 8, treeTop, x + w - 12, treeBottom);
        for (int i = scroll / ROW; i < rows.size() && treeTop + i * ROW - scroll < treeBottom; i++) {
            Row row = rows.get(i); int ry = treeTop + i * ROW - scroll;
            if (row.path.equals(selected)) g.fill(x + 9, ry, x + w - 12, ry + ROW, 0xFF5C6786);
            String label = (row.branch ? expanded.contains(row.path) ? "− " : "+ " : "  ") + row.name + "  [" + NbtTools.type(row.value) + "]  " + NbtTools.summary(row.value);
            g.drawString(font, label, x + 12 + row.depth * 12 - horizontal, ry + 3, 0xFFFFFFFF, true);
        }
        g.disableScissor();
        int contentHeight = treeBottom - treeTop;
        if (rows.size() * ROW > contentHeight) {
            int bar = Math.max(8, contentHeight * contentHeight / (rows.size() * ROW));
            int by = treeTop + (contentHeight - bar) * scroll / (rows.size() * ROW - contentHeight);
            g.fill(x + w - 12, treeTop, x + w - 8, treeBottom, 0xFF444444);
            g.fill(x + w - 12, by, x + w - 8, by + bar, 0xFFCCCCCC);
        }
    }

    private record Row(List<Object> path, String name, Tag value, int depth, boolean branch) {}

    /** Top-level key groups; anything not listed lands in "其他", so every field stays reachable. */
    private enum Page {
        ALL("全部数据", "全部", "完整实体数据树，包含第三方模组字段", ""),
        BASIC("基础", "基础", "名称、标签、发光、无敌、静音、着火、空气、自定义数据",
                "CustomName,CustomNameVisible,Tags,Silent,NoGravity,Invulnerable,Glowing,HasVisualFire,Fire,Air,TicksFrozen,PortalCooldown,data,Passengers"),
        POSITION("位置与运动", "位置", "坐标、速度、朝向、落地状态、下落距离",
                "Pos,Motion,Rotation,OnGround,fall_distance,FallDistance,Dimension"),
        LIFE("生命与效果", "生命", "生命值、吸收、状态效果、属性、年龄与繁殖",
                "Health,AbsorptionAmount,HurtTime,HurtByTimestamp,DeathTime,active_effects,attributes,sleeping_pos,Age,ForcedAge,InLove,LoveCause"),
        ITEMS("装备与物品", "物品", "装备、掉落概率、物品栏、展示物品、交易",
                "equipment,drop_chances,Inventory,Items,Item,item,ArmorItems,HandItems,ArmorDropChances,HandDropChances,SaddleItem,body_armor_item,body_armor_drop_chance,Offers,Gossips,VillagerData,Xp"),
        AI("AI 与行为", "行为", "AI、惯用手、拾取、拴绳、家、主人、愤怒目标、大脑记忆",
                "NoAI,LeftHanded,PersistenceRequired,CanPickUpLoot,leash,Leash,Brain,home_pos,home_radius,Owner,Sitting,Tame,AngerTime,AngryAt,anger_end_time,Target"),
        OTHER("其他", "其他", "未归入以上分类的字段", "");

        final String title, shortTitle, hint;
        final Set<String> keys;
        Page(String title, String shortTitle, String hint, String keys) {
            this.title = title; this.shortTitle = shortTitle; this.hint = hint;
            this.keys = keys.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(keys.split(",")));
        }
        boolean accepts(String key) {
            if (this == ALL) return true;
            if (this != OTHER) return keys.contains(key);
            for (Page other : values()) if (other != ALL && other != OTHER && other.keys.contains(key)) return false;
            return true;
        }
    }
}
