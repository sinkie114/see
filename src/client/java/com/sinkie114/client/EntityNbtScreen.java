package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Full-screen entity NBT editor. The tree shows live data with the user's pending edits laid over it;
 * sync replays only those edits onto the entity, like /data modify.
 */
public final class EntityNbtScreen extends Screen {
    private static final int ROW = 14;
    private final EntityDebugScreen parent;
    private final Entity target;
    private final EntityEditBridge.Session session;
    private final String readOnlyReason;
    private final Set<List<Object>> expanded = new HashSet<>(), marked = new HashSet<>(), conflicted = new HashSet<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Button> editingButtons = new ArrayList<>();
    private EntitySnapshot seenSnapshot;
    // base → edited is the user's draft; observed is the latest server data; view is the draft replayed onto it.
    private CompoundTag base, edited, observed, view;
    private List<List<Object>> conflicts = List.of();
    private int changes;
    private boolean pending, syncing, draggingBar, revealSelection;
    private List<Object> selected = List.of();
    private EditBox search;
    private String query = "", status = "";
    private int x, y, w, h, treeTop, treeBottom, scroll, horizontal;
    private Button syncButton;

    public EntityNbtScreen(EntityDebugScreen parent, Entity target, EntityEditBridge.Session session, List<Object> focus) {
        super(Component.literal("实体 NBT 编辑器"));
        this.parent = parent; this.target = target; this.session = session;
        seenSnapshot = session.snapshot;
        observed = base = edited = view = seenSnapshot.nbt();
        readOnlyReason = target instanceof Player ? "玩家数据只读：原版 /data 同样不允许修改玩家" : "";
        expanded.add(List.of());
        focus(focus);
    }

    /** Selects the deepest existing node on the path and opens its ancestors. */
    void focus(List<Object> path) {
        List<Object> found = List.of();
        for (int i = 1; i <= path.size(); i++) {
            List<Object> prefix = List.copyOf(path.subList(0, i));
            if (NbtTree.at(view, prefix) == null) break;
            found = prefix;
        }
        for (int i = 0; i < found.size(); i++) expanded.add(List.copyOf(found.subList(0, i)));
        Tag value = NbtTree.at(view, found);
        if (value instanceof CompoundTag || value instanceof CollectionTag) expanded.add(found);
        selected = found;
        revealSelection = true;
    }

    // Package-private state for the client game test.
    boolean editable() { return readOnlyReason.isEmpty(); }
    boolean pending() { return pending; }
    String status() { return status; }
    CompoundTag view() { return view; }
    List<Object> selected() { return selected; }
    List<List<Object>> conflicts() { return conflicts; }
    void select(List<Object> path) { selected = List.copyOf(path); }

    @Override protected void init() {
        w = Math.min(760, width - 8); h = Math.min(490, height - 8); x = (width - w) / 2; y = (height - h) / 2;
        editingButtons.clear(); syncButton = null;
        if (editable()) syncButton = button("同步", x + w - 145, y + 8, 66, this::synchronize);
        button("返回", x + w - 75, y + 8, 67, this::onClose)
                .setTooltip(Tooltip.create(Component.literal("返回 SEE。未同步的修改会保留，关闭 SEE 后丢弃")));
        int searchY = y + 36;
        search = addRenderableWidget(new EditBox(font, x + 8, searchY, w - 136, 18, Component.literal("搜索数据")));
        search.setMaxLength(2048); search.setHint(Component.literal("搜索键名、类型或值")); search.setValue(query);
        search.setResponder(s -> { query = s; scroll = 0; rebuildRows(); });
        button("展开", x + w - 120, searchY - 1, 54, () -> { expandAll(List.of(), view, 0); rebuildRows(); });
        button("折叠", x + w - 62, searchY - 1, 54, () -> { expanded.clear(); expanded.add(List.of()); rebuildRows(); });
        treeTop = searchY + 22; treeBottom = y + h - 65;
        String[] labels = {editable() ? "编辑" : "查看", "新增", "删除", "重命名", "放弃修改", "复制节点", "SNBT"};
        Runnable[] actions = {this::editSelected, this::addSelected, this::deleteSelected, this::renameSelected, this::discard,
                this::copySelected, () -> minecraft.setScreen(new SnbtScreen())};
        int aw = (w - 16) / labels.length;
        for (int i = 0; i < labels.length; i++) {
            Button b = button(labels[i], x + 8 + i * aw, y + h - 60, aw - 2, actions[i]);
            if (i > 0 && i < 5) editingButtons.add(b);
        }
        updateButtons();
        rebuildRows();
    }

    private Button button(String text, int bx, int by, int bw, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(bx, by, bw, 20).build());
    }

    private void updateButtons() {
        boolean can = editable() && !syncing;
        // 新增, 删除, 重命名, 放弃修改
        for (int i = 0; i < editingButtons.size(); i++) editingButtons.get(i).active = can && (i < 3 || pending);
        if (syncButton != null) syncButton.active = can && pending;
    }

    /** Starts a new draft on top of the current live data; earlier edits are already part of the view. */
    void apply(CompoundTag next) {
        if (!editable() || syncing) return;
        base = observed; edited = next.copy(); pending = !base.equals(edited); status = "";
        refreshView(); updateButtons();
    }

    private void refreshView() {
        marked.clear(); conflicted.clear();
        if (pending) {
            NbtTree.Merge merge = NbtTree.merge(base, edited, observed);
            view = merge.result(); conflicts = merge.conflicts(); changes = merge.changed().size();
            for (List<Object> path : merge.changed()) for (int i = 0; i <= path.size(); i++) marked.add(List.copyOf(path.subList(0, i)));
            conflicted.addAll(conflicts);
        } else {
            view = observed; conflicts = List.of(); changes = 0;
        }
        rebuildRows();
    }

    private void rebuildRows() {
        rows.clear();
        append(List.of(), "Entity", view, 0);
        int visible = treeBottom - treeTop;
        if (revealSelection && visible > 0) {
            revealSelection = false;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).path().equals(selected)) { scroll = i * ROW - visible / 3; break; }
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * ROW - visible)));
    }

    private void append(List<Object> path, String name, Tag value, int depth) {
        if (value == null || depth > 48 || rows.size() >= 10000) return;
        boolean branch = value instanceof CompoundTag || value instanceof CollectionTag;
        String needle = query.toLowerCase(Locale.ROOT);
        boolean matches = needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)
                || NbtTree.type(value).toLowerCase(Locale.ROOT).contains(needle)
                || (!branch && value.toString().toLowerCase(Locale.ROOT).contains(needle));
        if (matches) rows.add(new Row(path, name, value, depth, branch));
        if (!expanded.contains(path) && needle.isEmpty()) return;
        if (value instanceof CompoundTag c) c.keySet().stream().sorted().forEach(k -> append(NbtTree.child(path, k), k, c.get(k), depth + 1));
        else if (value instanceof CollectionTag c) for (int i = 0; i < c.size() && rows.size() < 10000; i++) append(NbtTree.child(path, i), "[" + i + "]", c.get(i), depth + 1);
    }

    private void expandAll(List<Object> path, Tag value, int depth) {
        if (depth > 32 || expanded.size() > 10000) return;
        expanded.add(path);
        if (value instanceof CompoundTag c) for (String key : c.keySet()) expandAll(NbtTree.child(path, key), c.get(key), depth + 1);
        else if (value instanceof CollectionTag c) for (int i = 0; i < c.size(); i++) expandAll(NbtTree.child(path, i), c.get(i), depth + 1);
    }

    private static Tag parse(String text) {
        try { return TagParser.create(NbtOps.INSTANCE).parseFully(text); }
        catch (Exception ex) { throw new IllegalArgumentException(ex.getMessage()); }
    }

    private void editSelected() {
        Tag value = NbtTree.at(view, selected);
        if (value == null) return;
        List<Object> path = selected;
        boolean writable = editable() && !syncing;
        minecraft.setScreen(new NbtValueScreen(this, (writable ? "编辑节点 / " : "查看节点 / ") + NbtTree.type(value), NbtTree.format(path),
                value.toString(), false, true, writable, (key, text) -> apply(NbtTree.replace(view, path, parse(text)))));
    }

    private void addSelected() {
        if (!editable() || syncing) return;
        List<Object> destination = selected;
        Tag value = NbtTree.at(view, destination);
        if (!(value instanceof CompoundTag) && !(value instanceof CollectionTag)) {
            destination = NbtTree.parent(selected);
            value = NbtTree.at(view, destination);
        }
        if (!(value instanceof CompoundTag) && !(value instanceof CollectionTag)) { status = "请先选择 Compound、List 或数组"; return; }
        final List<Object> path = List.copyOf(destination); final Tag container = value;
        minecraft.setScreen(new NbtValueScreen(this, "新增数据节点", container instanceof CompoundTag ? "new_key" : "追加到列表末尾", "{}",
                container instanceof CompoundTag, true, true, (key, text) -> {
            Tag copy = container.copy(); Tag added = parse(text);
            if (copy instanceof CompoundTag c) {
                if (key.isEmpty()) throw new IllegalArgumentException("键名不能为空");
                if (c.contains(key)) throw new IllegalArgumentException("该键已存在，请使用编辑");
                c.put(key, added);
            } else if (copy instanceof CollectionTag c && !c.addTag(c.size(), added)) throw new IllegalArgumentException("数组元素类型不匹配");
            apply(NbtTree.replace(view, path, copy)); expanded.add(path);
        }));
    }

    private void deleteSelected() {
        if (!editable() || syncing) return;
        if (selected.isEmpty()) { status = "不能删除实体根节点"; return; }
        try {
            String removed = NbtTree.format(selected);
            apply(NbtTree.remove(view, selected));
            selected = NbtTree.parent(selected);
            status = "已删除 " + removed + "，同步后生效";
        } catch (RuntimeException ex) { status = ex.getMessage(); }
    }

    private void renameSelected() {
        if (!editable() || syncing || selected.isEmpty()) return;
        if (!(selected.getLast() instanceof String old)) { status = "列表和数组元素不能重命名"; return; }
        List<Object> path = selected, parentPath = NbtTree.parent(path);
        Tag value = NbtTree.at(view, path);
        if (value == null) return;
        minecraft.setScreen(new NbtValueScreen(this, "重命名数据键", old, value.toString(), true, true, true, (key, text) -> {
            if (key.isEmpty()) throw new IllegalArgumentException("键名不能为空");
            CompoundTag copy = ((CompoundTag) NbtTree.at(view, parentPath)).copy();
            if (!key.equals(old) && copy.contains(key)) throw new IllegalArgumentException("目标键已存在");
            copy.remove(old); copy.put(key, parse(text));
            apply(NbtTree.replace(view, parentPath, copy));
            selected = NbtTree.child(parentPath, key);
        }));
    }

    private void discard() {
        if (!pending || syncing) return;
        base = edited = observed; pending = false; status = "已放弃未同步的修改";
        refreshView(); updateButtons();
    }

    private void copySelected() { copy(NbtTree.at(view, selected)); }
    private void copy(Tag data) { if (data != null) { minecraft.keyboardHandler.setClipboard(data.toString()); status = "已复制 SNBT"; } }

    private void paste() {
        if (!editable() || syncing) return;
        minecraft.setScreen(new NbtValueScreen(this, "粘贴 SNBT：合并到当前数据（同 /data merge）", "实体 NBT Compound",
                minecraft.keyboardHandler.getClipboard(), false, false, true, (key, text) -> loadSnbt(text)));
    }

    private void loadSnbt(String text) {
        if (!(parse(text) instanceof CompoundTag patch)) throw new IllegalArgumentException("实体数据必须为 Compound");
        apply(NbtTree.mergeInto(view, patch));
        status = "已合并到编辑副本，点击同步写入实体";
    }

    private Path exportDirectory() { return minecraft.gameDirectory.toPath().resolve("see").resolve("exports"); }

    private void importFile() {
        if (!editable() || syncing) return;
        minecraft.setScreen(new NbtValueScreen(this, "导入本地 SNBT 文件（合并）", "填写绝对路径，或相对于游戏目录的路径", "see/exports/entity.snbt", false, false, true,
                (key, text) -> {
                    try {
                        Path file = Path.of(text.trim());
                        if (!file.isAbsolute()) file = minecraft.gameDirectory.toPath().resolve(file);
                        loadSnbt(Files.readString(file, StandardCharsets.UTF_8));
                    } catch (java.io.IOException ex) { throw new IllegalArgumentException("读取失败：" + ex.getMessage()); }
                }));
    }

    private void exportFile() {
        try {
            Files.createDirectories(exportDirectory());
            String type = seenSnapshot.id().replaceAll("[^A-Za-z0-9_.-]", "_");
            Path file = exportDirectory().resolve(type + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".snbt");
            Files.writeString(file, view.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            status = "已导出：" + file;
        } catch (Exception ex) { status = "导出失败：" + ex.getMessage(); }
    }

    private void synchronize() {
        if (!editable() || syncing) return;
        if (!pending) { status = "没有待同步的修改"; return; }
        CompoundTag requested = edited.copy();
        syncing = true; status = "同步中…"; updateButtons();
        EntityEditBridge.writeNbt(minecraft, session, base, requested, (result, failure) -> {
            syncing = false;
            if (result == null) status = "同步失败：" + failure;
            else {
                // Report edits the game dropped or normalized, e.g. UUID or Passengers.
                List<String> ignored = new ArrayList<>();
                for (List<Object> path : result.changed()) {
                    if (!NbtTree.sameValue(NbtTree.at(requested, path), NbtTree.at(result.actual(), path))) ignored.add(NbtTree.format(path));
                }
                base = edited = observed = result.actual(); pending = false; seenSnapshot = session.snapshot;
                status = ignored.isEmpty() ? "同步成功" : "同步成功；以下数据被游戏忽略或调整：" + String.join("、", ignored);
                if (result.layoutChanged()) status += "；物品槽位已变化，请关闭 SEE 后重新打开";
                refreshView();
            }
            updateButtons();
        });
    }

    private final class SnbtScreen extends Screen {
        private int sx, sy, sw, sh;
        SnbtScreen() { super(Component.literal("SNBT 导入与导出")); }
        @Override protected void init() {
            sw = Math.min(320, width - 16); sh = Math.min(214, height - 16); sx = (width - sw) / 2; sy = (height - sh) / 2;
            String[] labels = {"复制编辑后的数据", "复制实时数据", "粘贴 SNBT（合并）", "导入文件（合并）", "导出文件", "返回"};
            Runnable[] tasks = {() -> copy(view), () -> copy(observed), EntityNbtScreen.this::paste,
                    EntityNbtScreen.this::importFile, EntityNbtScreen.this::exportFile, () -> {}};
            for (int i = 0; i < labels.length; i++) {
                Runnable task = tasks[i];
                Button b = addRenderableWidget(Button.builder(Component.literal(labels[i]), button -> {
                    minecraft.setScreen(EntityNbtScreen.this); task.run();
                }).bounds(sx + 8, sy + 28 + i * 26, sw - 16, 20).build());
                if (i == 2 || i == 3) b.active = editable() && !syncing;
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
        EntitySnapshot latest = session.snapshot;
        if (latest != seenSnapshot && latest != null && latest.nbt() != null) {
            seenSnapshot = latest; observed = latest.nbt(); refreshView();
        }
        updateButtons();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || (minecraft.options.keyInventory.matches(event) && !search.isFocused())) { onClose(); return true; }
        if (!search.isFocused()) {
            if (event.isCopy()) { copySelected(); return true; }
            if (event.isConfirmation()) { editSelected(); return true; }
            if (event.key() == 261 && editable()) { deleteSelected(); return true; }
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
                Row row = rows.get(i); selected = row.path();
                setFocused(null);
                int arrowX = x + 12 + row.depth() * 12 - horizontal;
                if (row.branch() && event.x() < arrowX + 12) { if (!expanded.remove(row.path())) expanded.add(row.path()); rebuildRows(); }
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

    @Override public void onClose() { parent.resumeFromNbtEditor(); }
    @Override public boolean isPauseScreen() { return false; }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF373737);
        g.fill(x + 1, y + 1, x + w - 2, y + h - 2, 0xFFFFFFFF);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        panel(g, x, y, w, h);
        int headerWidth = w - 170;
        g.drawString(font, font.plainSubstrByWidth(seenSnapshot.name().getString() + "  " + seenSnapshot.id(), headerWidth), x + 8, y + 9, 0xFF303030, false);
        String mode = !editable() ? readOnlyReason : pending ? "有 " + changes + " 处未同步的修改（黄色标记）" : "单人 · 可编辑，点击同步写入实体";
        g.drawString(font, font.plainSubstrByWidth(mode, headerWidth), x + 8, y + 21, pending ? 0xFF8A6D00 : 0xFF555555, false);
        g.fill(x + 8, treeTop, x + w - 8, treeBottom, 0xFF8B8B8B);
        g.enableScissor(x + 8, treeTop, x + w - 12, treeBottom);
        for (int i = scroll / ROW; i < rows.size() && treeTop + i * ROW - scroll < treeBottom; i++) {
            Row row = rows.get(i); int ry = treeTop + i * ROW - scroll;
            if (row.path().equals(selected)) g.fill(x + 9, ry, x + w - 12, ry + ROW, 0xFF5C6786);
            String label = (row.branch() ? expanded.contains(row.path()) ? "− " : "+ " : "  ") + row.name() + "  [" + NbtTree.type(row.value()) + "]  " + NbtTree.summary(row.value());
            int color = conflicted.contains(row.path()) ? 0xFFFF8080 : marked.contains(row.path()) ? 0xFFFFE070 : 0xFFFFFFFF;
            g.drawString(font, label, x + 12 + row.depth() * 12 - horizontal, ry + 3, color, true);
        }
        g.disableScissor();
        int contentHeight = treeBottom - treeTop;
        if (rows.size() * ROW > contentHeight) {
            int bar = Math.max(8, contentHeight * contentHeight / (rows.size() * ROW));
            int by = treeTop + (contentHeight - bar) * scroll / (rows.size() * ROW - contentHeight);
            g.fill(x + w - 12, treeTop, x + w - 8, treeBottom, 0xFF444444);
            g.fill(x + w - 12, by, x + w - 8, by + bar, 0xFFCCCCCC);
        }
        boolean gone = session.invalid || target.isRemoved();
        String warning = gone ? "目标实体已失效：副本保留，同步会失败"
                : !conflicts.isEmpty() ? "注意：" + String.join("、", conflicts.stream().map(NbtTree::format).toList()) + " 在你修改后又被游戏改变（红色），同步将以你的值覆盖"
                : "实时数据每 tick 刷新；同步只写入你修改过的字段（同 /data modify），UUID 保持不变";
        String feedback = status.isEmpty() ? "单击选择，点击 +/− 展开，双击或右键编辑；Ctrl+C 复制节点，Delete 删除" : status;
        g.drawString(font, font.plainSubstrByWidth(warning, w - 16), x + 8, y + h - 33, gone || !conflicts.isEmpty() ? 0xFFAA2222 : 0xFF555555, false);
        g.drawString(font, font.plainSubstrByWidth(feedback, w - 16), x + 8, y + h - 18,
                feedback.startsWith("同步失败") || feedback.startsWith("导出失败") ? 0xFFAA2222 : 0xFF303030, false);
        super.render(g, mx, my, delta);
        if (my >= y + h - 36 && my < y + h - 6) {
            g.setTooltipForNextFrame(font, font.split(Component.literal(my < y + h - 23 ? warning : feedback), Math.min(400, width - 24)), mx, my);
        } else if (mx >= x + 8 && mx < x + 8 + headerWidth && my >= y + 6 && my < y + 31) {
            g.setComponentTooltipForNextFrame(font, List.of(seenSnapshot.name(), Component.literal(seenSnapshot.id()),
                    Component.literal("UUID: " + seenSnapshot.uuid())), mx, my);
        }
    }

    private record Row(List<Object> path, String name, Tag value, int depth, boolean branch) {}
}
