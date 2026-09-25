package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import java.util.function.BiConsumer;

/** A typed value dialog for the entity NBT editor, also used for whole SNBT documents and file paths. */
public final class NbtValueScreen extends Screen {
    private static final String[] TYPES = {"SNBT", "String", "布尔", "Byte", "Short", "Int", "Long", "Float", "Double", "List", "Compound", "Byte[]", "Int[]", "Long[]"};
    private final EntityNbtScreen parent;
    private final boolean writable, named, typed;
    private final BiConsumer<String, String> commit;
    private String keyText, valueText, message = "";
    private int type;
    private EditBox key;
    private MultiLineEditBox value;
    private int x, y, w, h;

    public NbtValueScreen(EntityNbtScreen parent, String title, String key, String value, boolean named, boolean typed,
                          boolean writable, BiConsumer<String, String> commit) {
        super(Component.literal(title)); this.parent = parent; this.keyText = key; this.valueText = value;
        this.named = named; this.typed = typed; this.writable = writable; this.commit = commit;
    }

    @Override protected void init() {
        w = Math.min(680, width - 16); h = Math.min(400, height - 16); x = (width - w) / 2; y = (height - h) / 2;
        key = addRenderableWidget(new EditBox(font, x + 8, y + 25, w - (typed ? 144 : 16), 20, Component.literal("节点名")));
        key.setMaxLength(32767); key.setValue(keyText); key.setEditable(writable && named);
        key.setResponder(s -> keyText = s);
        if (typed) addRenderableWidget(Button.builder(Component.literal("类型：" + TYPES[type]), b -> {
            type = (type + 1) % TYPES.length; b.setMessage(Component.literal("类型：" + TYPES[type]));
        }).bounds(x + w - 130, y + 25, 122, 20).build()).active = writable;
        value = addRenderableWidget(MultiLineEditBox.builder().setX(x + 8).setY(y + 51).build(font, w - 16, h - 112, Component.literal("数据值")));
        value.setCharacterLimit(Integer.MAX_VALUE); value.setValue(valueText); value.setValueListener(s -> valueText = s);
        if (writable) addRenderableWidget(Button.builder(Component.literal("载入编辑副本"), b -> submit()).bounds(x + 8, y + h - 28, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("复制全文"), b -> minecraft.keyboardHandler.setClipboard(value.getValue()))
                .bounds(x + (writable ? 124 : 8), y + h - 28, 82, 20).build());
        addRenderableWidget(Button.builder(Component.literal(writable ? "取消" : "返回"), b -> onClose()).bounds(x + w - 78, y + h - 28, 70, 20).build());
        setInitialFocus(value);
    }

    /** Package-private for the client game test. */
    void setText(String text) { valueText = text; if (value != null) value.setValue(text); }

    private void submit() {
        try {
            String text = value.getValue();
            if (typed && type != 0) text = parseTyped(TYPES[type], text).toString();
            commit.accept(key.getValue(), text);
            minecraft.setScreen(parent);
        } catch (Exception ex) { message = ex.getMessage() == null ? ex.toString() : ex.getMessage(); }
    }

    private static Tag parseTyped(String type, String text) throws Exception {
        return switch (type) {
            case "String" -> StringTag.valueOf(text);
            case "布尔" -> {
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false") && !text.equals("1") && !text.equals("0"))
                    throw new IllegalArgumentException("布尔值请填 true、false、1 或 0");
                yield ByteTag.valueOf(text.equalsIgnoreCase("true") || text.equals("1"));
            }
            case "Byte" -> ByteTag.valueOf(Byte.parseByte(text.trim()));
            case "Short" -> ShortTag.valueOf(Short.parseShort(text.trim()));
            case "Int" -> IntTag.valueOf(Integer.parseInt(text.trim()));
            case "Long" -> LongTag.valueOf(Long.parseLong(text.trim()));
            case "Float" -> FloatTag.valueOf(Float.parseFloat(text.trim()));
            case "Double" -> DoubleTag.valueOf(Double.parseDouble(text.trim()));
            default -> {
                Tag parsed = TagParser.create(NbtOps.INSTANCE).parseFully(text);
                String actual = NbtTree.type(parsed);
                if (!actual.equals(type)) throw new IllegalArgumentException("需要 " + type + "，实际为 " + actual);
                yield parsed;
            }
        };
    }

    @Override public boolean charTyped(CharacterEvent event) { return writable && super.charTyped(event); }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || (!writable && minecraft.options.keyInventory.matches(event))) { onClose(); return true; }
        if (!writable && !(event.isCopy() || event.isSelectAll() || event.isLeft() || event.isRight() || event.isUp() || event.isDown()
                || event.key() == 268 || event.key() == 269 || event.key() == 266 || event.key() == 267 || event.isCycleFocus())) return true;
        if (event.hasControlDown() && event.isConfirmation() && writable) { submit(); return true; }
        return super.keyPressed(event);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        EntityNbtScreen.panel(g, x, y, w, h);
        g.drawString(font, title, x + 8, y + 9, 0xFF303030, false);
        g.drawString(font, font.plainSubstrByWidth(message.isEmpty() ? "SNBT 字符串需引号；选择 String 类型可直接填写文字。Ctrl+Enter 载入。" : message, w - 16),
                x + 8, y + h - 52, message.isEmpty() ? 0xFF555555 : 0xFFAA2222, false);
        if (!message.isEmpty() && my >= y + h - 56 && my <= y + h - 32) g.setTooltipForNextFrame(font, Component.literal(message), mx, my);
        super.render(g, mx, my, delta);
    }
}
