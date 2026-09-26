package com.sinkie114.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Mod Menu config page: rebinds this mod's keys without scrolling through vanilla Controls. */
public final class SeeKeysScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private final Screen parent;
    private final List<KeyMapping> keys;
    private final List<Button> bindButtons = new ArrayList<>();
    private final List<Button> resetButtons = new ArrayList<>();
    private KeyMapping listening;

    public SeeKeysScreen(Screen parent) {
        super(Component.literal("实体调试查看器 · 按键设置"));
        this.parent = parent;
        this.keys = List.of(SeeClient.OPEN_DEBUG);
    }

    @Override protected void init() {
        bindButtons.clear(); resetButtons.clear();
        for (int i = 0; i < keys.size(); i++) {
            KeyMapping key = keys.get(i);
            int y = top() + i * ROW_HEIGHT;
            bindButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> { listening = key; refreshLabels(); })
                    .bounds(width / 2 + 10, y, 100, 20).build()));
            resetButtons.add(addRenderableWidget(Button.builder(Component.literal("重置"), b -> bind(key, key.getDefaultKey()))
                    .bounds(width / 2 + 114, y, 50, 20).build()));
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
        refreshLabels();
    }

    private int top() { return height / 4; }

    private void bind(KeyMapping key, InputConstants.Key input) {
        key.setKey(input);
        minecraft.options.save();
        KeyMapping.resetMapping();
        listening = null;
        refreshLabels();
    }

    private void refreshLabels() {
        for (int i = 0; i < keys.size(); i++) {
            KeyMapping key = keys.get(i);
            Component label = key.getTranslatedKeyMessage();
            if (key == listening) {
                label = Component.literal("> ").append(label.copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE)).append(" <")
                        .withStyle(ChatFormatting.YELLOW);
            } else if (conflicts(key)) {
                label = label.copy().withStyle(ChatFormatting.RED);
            }
            bindButtons.get(i).setMessage(label);
            resetButtons.get(i).active = !key.isDefault();
        }
    }

    private boolean conflicts(KeyMapping key) {
        if (key.isUnbound()) return false;
        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != key && key.same(other)) return true;
        }
        return false;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (listening != null) {
            // Same as vanilla Controls: Esc clears the binding instead of closing the page.
            bind(listening, event.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(event));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFFFF);
        for (int i = 0; i < keys.size(); i++) {
            g.drawString(font, Component.translatable(keys.get(i).getName()), width / 2 - 160, top() + i * ROW_HEIGHT + 6, 0xFFFFFFFF);
        }
        g.drawCenteredString(font, listening != null ? "按下新按键，Esc 解除绑定" : "点击右侧按钮后按下新按键；红色表示与其他按键冲突",
                width / 2, height - 44, 0xFFA0A0A0);
    }
}
