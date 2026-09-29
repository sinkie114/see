package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

/** Searchable picker for registry entries; search never replaces the focused input. */
final class NbtOptionScreen extends Screen implements EntityNbtLayer {
    record Option(String id, String name) {}
    private final Screen parent;
    private final EntityNbtSession session;
    private final List<Option> options;
    private final Consumer<String> select;
    private final List<Button> results = new ArrayList<>();
    private String query = "";
    private int x, y, w, h, offset, pageSize, matches;
    private Button previous, next;

    NbtOptionScreen(Screen parent, EntityNbtSession session, String title, List<Option> options, Consumer<String> select) {
        super(Component.literal(title)); this.parent = parent; this.session = session; this.options = options; this.select = select;
    }
    @Override public EntityNbtSession session() { return session; }
    @Override protected void init() {
        w = Math.min(640, width - 12); h = Math.min(430, height - 12); x = (width - w) / 2; y = (height - h) / 2;
        pageSize = Math.max(1, (h - 100) / 24); results.clear();
        EditBox search = addRenderableWidget(new EditBox(font, x + 8, y + 29, w - 16, 20, Component.literal("搜索选项")));
        search.setMaxLength(256); search.setHint(Component.literal("搜索名称、ID 或 mod 命名空间")); search.setValue(query);
        search.setResponder(value -> { query = value; offset = 0; refresh(); });
        previous = addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { offset -= pageSize; refresh(); })
                .bounds(x + 8, y + h - 28, 62, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { offset += pageSize; refresh(); })
                .bounds(x + 76, y + h - 28, 62, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(x + w - 70, y + h - 28, 62, 20).build());
        refresh(); setInitialFocus(search);
    }
    private void refresh() {
        if (previous == null) return;
        results.forEach(this::removeWidget); results.clear();
        String needle = query.toLowerCase(Locale.ROOT);
        List<Option> filtered = options.stream().filter(o -> (o.name() + " " + o.id()).toLowerCase(Locale.ROOT).contains(needle)).toList();
        matches = filtered.size(); offset = Math.max(0, Math.min(offset, Math.max(0, (matches - 1) / pageSize * pageSize)));
        for (int i = offset; i < Math.min(matches, offset + pageSize); i++) {
            Option option = filtered.get(i);
            Button b = addRenderableWidget(Button.builder(Component.literal(option.name() + " · " + option.id()), button -> {
                if (session.writable()) select.accept(option.id());
            }).bounds(x + 8, y + 57 + (i - offset) * 24, w - 16, 20).build());
            b.active = session.writable(); results.add(b);
        }
        previous.active = offset > 0; next.active = offset + pageSize < matches;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(KeyEvent key) { if (key.isEscape()) { onClose(); return true; } return super.keyPressed(key); }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        EntityEditorBase.panel(g, x, y, w, h);
        g.drawString(font, title, x + 8, y + 10, 0xFF303030, false);
        g.drawString(font, matches == 0 ? "没有匹配项" : "共 " + matches + " 项", x + 8, y + h - 44, 0xFF555555, false);
        super.render(g, mx, my, delta);
    }
}
