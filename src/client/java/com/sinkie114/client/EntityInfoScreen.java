package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import java.util.ArrayList;
import java.util.List;

/** Read-only overview of what the game computes from the NBT: identity, attribute totals, status and motion. */
public final class EntityInfoScreen extends EntityEditorBase {
    private static final String[][] SECTIONS = {{"overview", "概览"}, {"attributes", "属性"}, {"status", "状态"}, {"motion", "位置与运动"}};
    private record Line(String text, boolean header) {}
    private List<Line> lines = List.of();
    private int top, bottom, scroll, horizontal, maxScroll, maxHorizontal;

    public EntityInfoScreen(EntityNbtSession session) { super(session, "实体信息"); }
    @Override protected EditorMode mode() { return EditorMode.INFO; }
    @Override protected String hint() { return "只读信息，由游戏根据实体数据计算；滚轮滚动，左右方向键横向滚动。"; }
    @Override protected void onRevision() {}

    @Override protected void initContent(int contentTop) {
        top = contentTop; bottom = y + h - 40;
        rebuild();
    }

    private void rebuild() {
        List<Line> list = new ArrayList<>();
        for (String[] section : SECTIONS) {
            List<String> content = session.pages.getOrDefault(section[0], List.of());
            if (content.isEmpty()) continue;
            list.add(new Line("▍" + section[1], true));
            for (String text : content) list.add(new Line(text, false));
        }
        if (list.isEmpty()) list.add(new Line("没有可显示的数据", false));
        lines = list;
        maxScroll = Math.max(0, lines.size() * 10 - (bottom - top));
        maxHorizontal = Math.max(0, lines.stream().mapToInt(l -> font.width(l.text())).max().orElse(0) - (w - 24));
        scroll = Math.min(scroll, maxScroll); horizontal = Math.min(horizontal, maxHorizontal);
    }

    @Override public void tick() { super.tick(); if (font != null) rebuild(); }

    @Override protected void renderContent(GuiGraphics g, int mx, int my) {
        g.fill(x + 8, top, x + w - 8, bottom, 0xFFB0B0B0);
        g.enableScissor(x + 8, top, x + w - 12, bottom);
        int first = scroll / 10, last = Math.min(lines.size(), (scroll + bottom - top) / 10 + 1);
        for (int i = first; i < last; i++) {
            Line line = lines.get(i);
            g.drawString(font, line.text(), x + 12 - horizontal, top + 2 + i * 10 - scroll, line.header() ? 0xFF1A3A7A : 0xFF303030, false);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int height = bottom - top, bar = Math.max(8, height * height / (lines.size() * 10));
            int by = top + (height - bar) * scroll / maxScroll;
            g.fill(x + w - 12, top, x + w - 8, bottom, 0xFF444444);
            g.fill(x + w - 12, by, x + w - 8, by + bar, 0xFFCCCCCC);
        }
    }

    @Override public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        if (my >= top && my < bottom) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (vy * 30)));
            horizontal = Math.max(0, Math.min(maxHorizontal, horizontal - (int) (hx * 24)));
            return true;
        }
        return super.mouseScrolled(mx, my, hx, vy);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isLeft() || event.isRight()) { horizontal = Math.max(0, Math.min(maxHorizontal, horizontal + (event.isRight() ? 30 : -30))); return true; }
        return super.keyPressed(event);
    }
}
