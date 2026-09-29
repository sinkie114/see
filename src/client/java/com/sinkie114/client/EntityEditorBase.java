package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Shared chrome of the editor tabs: mode tabs, sync and close buttons, header and footer. */
abstract class EntityEditorBase extends Screen implements EntityNbtLayer {
    protected final EntityNbtSession session;
    protected int x, y, w, h;
    private int seenRevision = -1;
    private Button syncButton;

    protected EntityEditorBase(EntityNbtSession session, String title) {
        super(Component.literal(title)); this.session = session;
    }
    @Override public EntityNbtSession session() { return session; }

    protected abstract EditorMode mode();
    /** Adds the tab's own widgets; {@code top} is the first free y below the mode tabs. */
    protected abstract void initContent(int top);
    /** Called after the session document changed. */
    protected void onRevision() { rebuildWidgets(); }
    protected void renderContent(GuiGraphics g, int mx, int my) {}
    protected String hint() { return ""; }
    protected String feedback() { return session.status.isEmpty() ? hint() : session.status; }
    protected boolean feedbackError() { return feedback().startsWith("同步失败"); }

    @Override protected void init() {
        w = Math.min(760, width - 8); h = Math.min(490, height - 8); x = (width - w) / 2; y = (height - h) / 2;
        EditorMode[] modes = EditorMode.values();
        int tabWidth = Math.min(90, (w - 170) / modes.length);
        for (int i = 0; i < modes.length; i++) {
            EditorMode m = modes[i];
            button(m.title, x + 8 + i * (tabWidth + 2), y + 30, tabWidth, () -> session.go(m)).active = m != mode();
        }
        syncButton = null;
        if (session.editable) syncButton = button("同步", x + w - 145, y + 8, 66, this::synchronize);
        button("关闭", x + w - 75, y + 8, 67, this::onClose);
        seenRevision = session.revision;
        initContent(y + 56);
    }

    protected Button button(String text, int bx, int by, int bw, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(bx, by, bw, 20).build());
    }

    public void synchronize() {
        if (!session.editable || session.syncing) return;
        String risk = NbtTools.riskReason(session.patch);
        if (!risk.isEmpty()) minecraft.setScreen(new RiskScreen(this, risk));
        else session.sync(success -> onRevision());
    }

    private static final class RiskScreen extends ConfirmScreen implements EntityNbtLayer {
        private final EntityEditorBase parent;
        RiskScreen(EntityEditorBase parent, String reason) {
            super(answer -> { parent.minecraft.setScreen(parent); if (answer) parent.session.sync(success -> parent.onRevision()); },
                    Component.literal("高风险数据"), Component.literal(reason + "。当前数据可能导致游戏异常或崩溃，是否继续同步？"),
                    Component.literal("确定同步"), Component.literal("取消"));
            this.parent = parent;
        }
        @Override public EntityNbtSession session() { return parent.session; }
        @Override public boolean isPauseScreen() { return false; }
    }

    /** True while a text box has focus, so letter keys are typed instead of closing the editor. */
    protected boolean typing() { return getFocused() instanceof EditBox; }

    @Override public void tick() {
        if (seenRevision != session.revision) { seenRevision = session.revision; onRevision(); }
        if (syncButton != null) syncButton.active = !session.syncing && !session.patch.isEmpty();
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || (!typing() && minecraft.options.keyInventory.matches(event))) { onClose(); return true; }
        return super.keyPressed(event);
    }
    @Override public void onClose() { session.close(); }
    @Override public boolean isPauseScreen() { return false; }

    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF373737);
        g.fill(x + 1, y + 1, x + w - 2, y + h - 2, 0xFFFFFFFF);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        panel(g, x, y, w, h);
        String name = session.target.getDisplayName().getString();
        String mode = !session.editable ? (session.target instanceof net.minecraft.world.entity.player.Player ? "玩家数据 · 只读" : "多人 · 严格只读（仅客户端可见数据）")
                : "本地 · 可编辑" + (session.patch.isEmpty() ? "" : " · " + session.patch.size() + " 处未同步修改");
        g.drawString(font, font.plainSubstrByWidth(name, w - 185), x + 8, y + 6, 0xFF303030, false);
        g.drawString(font, font.plainSubstrByWidth(mode, w - 185), x + 8, y + 18, 0xFF555555, false);
        renderContent(g, mx, my);
        String warning = !session.valid ? "目标实体已失效；仍可保留副本，同步会失败"
                : !session.conflicts.isEmpty() ? "警告：你修改的字段已被游戏改变（" + String.join("、", session.conflicts.subList(0, Math.min(3, session.conflicts.size())))
                        + (session.conflicts.size() > 3 ? "…" : "") + "），同步将覆盖"
                : session.location;
        String feedback = feedback();
        boolean alarm = !session.valid || !session.conflicts.isEmpty();
        g.drawString(font, font.plainSubstrByWidth(warning, w - 16), x + 8, y + h - 33, alarm ? 0xFFAA2222 : 0xFF555555, false);
        g.drawString(font, font.plainSubstrByWidth(feedback, w - 16), x + 8, y + h - 18, feedbackError() ? 0xFFAA2222 : 0xFF303030, false);
        super.render(g, mx, my, delta);
        if (my >= y + h - 36 && my < y + h - 6) g.setTooltipForNextFrame(font, font.split(Component.literal(my < y + h - 23 ? warning : feedback), Math.min(360, width - 24)), mx, my);
    }
}
