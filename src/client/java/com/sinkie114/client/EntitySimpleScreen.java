package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.*;

/**
 * Form-based editor for the common entity fields. Text boxes are saved into the copy together with "保存到副本";
 * toggles and list changes apply to the copy immediately. Nothing reaches the entity before "同步".
 */
public final class EntitySimpleScreen extends EntityEditorBase {
    private static final int ROW_H = 26;
    private static final String[] AXES = {"X", "Y", "Z"}, ROTATION = {"偏航", "俯仰"};

    private enum Category {
        BASIC("基础"), POSITION("位置与运动"), LIFE("生命"), BEHAVIOR("行为"), EFFECTS("状态效果"), ATTRIBUTES("属性");
        final String title;
        Category(String title) { this.title = title; }
    }
    private enum Kind { BOOL, SHORT, INT, FLOAT, DOUBLE, TEXT, STRINGS, VEC3, VEC2 }
    /** {@code requires}: the field is only shown when the entity's NBT already has this key (e.g. Health means living). */
    private record Field(String key, String label, Kind kind, String requires, double min, double max, String help) {
        int parts() { return kind == Kind.VEC3 ? 3 : kind == Kind.VEC2 ? 2 : 1; }
    }
    private record Label(int x, int y, String text) {}

    private static Field bool(String key, String label, String requires, String help) { return new Field(key, label, Kind.BOOL, requires, 0, 0, help); }
    private static Field num(String key, String label, Kind kind, String requires, double min, double max, String help) { return new Field(key, label, kind, requires, min, max, help); }

    private static final Map<Category, List<Field>> FIELDS = Map.of(
            Category.BASIC, List.of(
                    new Field("CustomName", "自定义名称", Kind.TEXT, null, 0, 0, "纯文本，留空则删除；带样式的名称请在高级模式编辑"),
                    bool("CustomNameVisible", "名称始终可见", null, ""),
                    new Field("Tags", "标签", Kind.STRINGS, null, 0, 0, "用英文逗号分隔，供命令选择器使用"),
                    bool("Silent", "静音", null, ""), bool("NoGravity", "无重力", null, ""),
                    bool("Invulnerable", "无敌", null, ""), bool("Glowing", "发光", null, ""),
                    bool("HasVisualFire", "显示着火外观", null, ""),
                    num("Fire", "着火剩余刻数", Kind.SHORT, null, -32768, 32767, "-1 表示没有着火，20 刻 = 1 秒"),
                    num("Air", "空气值", Kind.SHORT, null, -32768, 32767, "300 为满，负数会持续溺水"),
                    num("TicksFrozen", "冰冻刻数", Kind.INT, null, 0, Integer.MAX_VALUE, ""),
                    num("PortalCooldown", "传送门冷却", Kind.INT, null, 0, Integer.MAX_VALUE, "")),
            Category.POSITION, List.of(
                    new Field("Pos", "坐标", Kind.VEC3, null, -1.0E9, 1.0E9, "超出 ±3000 万的坐标会要求确认"),
                    new Field("Motion", "速度", Kind.VEC3, null, -1.0E6, 1.0E6, "每刻移动的方块数"),
                    new Field("Rotation", "朝向", Kind.VEC2, null, -1.0E6, 1.0E6, "偏航（水平）与俯仰（垂直），单位为度"),
                    bool("OnGround", "在地面", null, ""),
                    num("fall_distance", "下落距离", Kind.DOUBLE, null, 0, 1.0E9, "落地时按此距离计算摔落伤害")),
            Category.LIFE, List.of(
                    num("Health", "生命值", Kind.FLOAT, "Health", 0, 1.0E9, "超过最大生命值时会被游戏截断"),
                    num("AbsorptionAmount", "伤害吸收", Kind.FLOAT, "Health", 0, 1.0E9, ""),
                    num("HurtTime", "受伤计时", Kind.SHORT, "Health", -32768, 32767, ""),
                    num("DeathTime", "死亡计时", Kind.SHORT, "Health", -32768, 32767, ""),
                    num("Age", "年龄", Kind.INT, "Age", Integer.MIN_VALUE, Integer.MAX_VALUE, "负数为幼体，数值越小成长越慢"),
                    num("ForcedAge", "强制年龄", Kind.INT, "ForcedAge", Integer.MIN_VALUE, Integer.MAX_VALUE, ""),
                    num("InLove", "繁殖倒计时", Kind.INT, "InLove", 0, Integer.MAX_VALUE, "大于 0 表示处于求偶状态")),
            Category.BEHAVIOR, List.of(
                    bool("NoAI", "关闭 AI", "PersistenceRequired", "关闭后生物不会移动或攻击"),
                    bool("LeftHanded", "左撇子", "PersistenceRequired", ""),
                    bool("PersistenceRequired", "不自然消失", "PersistenceRequired", "开启后不会因距离过远而消失"),
                    bool("CanPickUpLoot", "可拾取物品", "PersistenceRequired", "")));

    private Category category = Category.BASIC;
    private int offset, pageSize, rowsTop, rowsBottom;
    private final Map<String, String> pending = new HashMap<>();
    private final List<Label> labels = new ArrayList<>();
    private String error = "";

    public EntitySimpleScreen(EntityNbtSession session) { super(session, "实体简单模式"); }
    @Override protected EditorMode mode() { return EditorMode.SIMPLE; }
    @Override protected String hint() {
        return session.editable ? "修改文本框后点击“保存到副本”，再点右上角“同步”写入实体；复杂数据请切换到高级模式。" : "只读：只能查看客户端可见的数据。";
    }
    @Override protected String feedback() { return error.isEmpty() ? super.feedback() : error; }
    @Override protected boolean feedbackError() { return !error.isEmpty() || super.feedbackError(); }

    private boolean living() { return session.document.contains("Health"); }
    private List<Field> fields() {
        return FIELDS.getOrDefault(category, List.of()).stream().filter(f -> f.requires() == null || session.document.contains(f.requires())).toList();
    }
    private ListTag list(String key) { return session.document.get(key) instanceof ListTag l ? l : new ListTag(); }
    private int rowCount() {
        return switch (category) {
            case EFFECTS -> living() ? list("active_effects").size() : 0;
            case ATTRIBUTES -> living() ? list("attributes").size() : 0;
            default -> fields().size();
        };
    }
    private int labelWidth() { return Math.min(category == Category.EFFECTS || category == Category.ATTRIBUTES ? 150 : 200, w / 3); }

    @Override protected void initContent(int top) {
        int columns = Category.values().length, cw = (w - 16) / columns;
        for (Category c : Category.values()) {
            button(c.title, x + 8 + c.ordinal() * cw, top, cw - 2, () -> {
                category = c; offset = 0; pending.clear(); error = ""; rebuildWidgets();
            }).active = c != category;
        }
        rowsTop = top + 30; rowsBottom = y + h - 66;
        pageSize = Math.max(1, (rowsBottom - rowsTop) / ROW_H);
        labels.clear();
        int count = rowCount();
        offset = Math.max(0, Math.min(offset, Math.max(0, (count - 1) / pageSize * pageSize)));
        for (int i = offset; i < Math.min(count, offset + pageSize); i++) buildRow(i, rowsTop + (i - offset) * ROW_H);

        int by = y + h - 60;
        button("保存到副本", x + 8, by, 100, this::save).active = session.writable();
        int bx = x + 112;
        if (category == Category.EFFECTS || category == Category.ATTRIBUTES) {
            button(category == Category.EFFECTS ? "新增效果" : "新增属性", bx, by, 100, category == Category.EFFECTS ? this::addEffect : this::addAttribute)
                    .active = session.writable() && living();
            bx += 104;
        }
        Button refresh = button("刷新", bx, by, 62, () -> { pending.clear(); error = ""; session.refresh(); });
        refresh.setTooltip(Tooltip.create(Component.literal("重新读取实体当前数据，保留你尚未同步的修改")));
        button("上一页", x + w - 136, by, 62, () -> { offset -= pageSize; rebuildWidgets(); }).active = offset > 0;
        button("下一页", x + w - 70, by, 62, () -> { offset += pageSize; rebuildWidgets(); }).active = offset + pageSize < count;
    }

    private void buildRow(int index, int ry) {
        switch (category) {
            case EFFECTS -> buildEffect(index, ry);
            case ATTRIBUTES -> buildAttribute(index, ry);
            default -> buildField(fields().get(index), ry);
        }
    }

    // ---- plain fields -------------------------------------------------------------------------------------------

    private void buildField(Field f, int ry) {
        labels.add(new Label(x + 10, ry + 6, f.label()));
        int cx = x + 10 + labelWidth();
        switch (f.kind()) {
            case BOOL -> {
                Button b = button(session.document.getBooleanOr(f.key(), false) ? "开启" : "关闭", cx, ry, 80, () -> toggle(f));
                b.active = session.writable();
                if (!f.help().isEmpty()) b.setTooltip(Tooltip.create(Component.literal(f.help())));
            }
            case VEC3, VEC2 -> {
                for (int i = 0; i < f.parts(); i++) box(id(f, i), current(f, i), cx + i * 90, ry, 86, (f.kind() == Kind.VEC3 ? AXES : ROTATION)[i], f.help());
            }
            case TEXT, STRINGS -> box(id(f, 0), current(f, 0), cx, ry, x + w - 16 - cx, "", f.help());
            default -> box(id(f, 0), current(f, 0), cx, ry, 130, "", f.help());
        }
    }

    private static String id(Field f, int part) { return f.key() + "#" + part; }
    private String current(Field f, int part) { return pending.getOrDefault(id(f, part), docString(f, part)); }

    private EditBox box(String id, String value, int bx, int by, int bw, String hint, String help) {
        EditBox box = addRenderableWidget(new EditBox(font, bx, by, Math.max(30, bw), 20, Component.literal(id)));
        box.setMaxLength(32767);
        box.setValue(value);
        box.setEditable(session.writable());
        box.setResponder(s -> pending.put(id, s));
        if (!hint.isEmpty()) box.setHint(Component.literal(hint));
        if (!help.isEmpty()) box.setTooltip(Tooltip.create(Component.literal(help)));
        return box;
    }

    private String docString(Field f, int part) {
        Tag t = session.document.get(f.key());
        return switch (f.kind()) {
            case TEXT -> text(t);
            case STRINGS -> strings(t);
            case VEC3, VEC2 -> t instanceof ListTag l && part < l.size() ? number(l.get(part), f.kind() == Kind.VEC2 ? Kind.FLOAT : Kind.DOUBLE) : "";
            default -> t instanceof NumericTag ? number(t, f.kind()) : "";
        };
    }
    private static String number(Tag t, Kind kind) {
        NumericTag n = (NumericTag) t;
        return switch (kind) {
            case FLOAT -> Float.toString(n.floatValue());
            case DOUBLE -> Double.toString(n.doubleValue());
            default -> Long.toString(n.longValue());
        };
    }
    private static String text(Tag t) {
        if (t == null) return "";
        if (t instanceof StringTag s) return s.value();
        if (t instanceof CompoundTag c && c.contains("text")) return c.getStringOr("text", "");
        return t.toString();
    }
    private static String strings(Tag t) {
        if (!(t instanceof ListTag list)) return "";
        List<String> items = new ArrayList<>();
        for (Tag e : list) items.add(e instanceof StringTag s ? s.value() : e.toString());
        return String.join(",", items);
    }

    private void toggle(Field f) {
        if (!session.writable()) return;
        CompoundTag root = session.document.copy();
        root.putBoolean(f.key(), !root.getBooleanOr(f.key(), false));
        session.edit(root);
    }

    private Tag scalar(Field f, int part) {
        String text = current(f, part).strip();
        Kind kind = f.kind() == Kind.VEC3 ? Kind.DOUBLE : f.kind() == Kind.VEC2 ? Kind.FLOAT : f.kind();
        double v;
        try { v = Double.parseDouble(text); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(f.label() + "请输入数字"); }
        if (!Double.isFinite(v)) throw new IllegalArgumentException(f.label() + "必须是有限数字");
        if (v < f.min() || v > f.max()) throw new IllegalArgumentException(f.label() + "范围：" + f.min() + " 至 " + f.max());
        if ((kind == Kind.SHORT || kind == Kind.INT) && v != Math.rint(v)) throw new IllegalArgumentException(f.label() + "需要整数");
        return switch (kind) {
            case SHORT -> ShortTag.valueOf((short) v);
            case INT -> IntTag.valueOf((int) v);
            case FLOAT -> FloatTag.valueOf((float) v);
            default -> DoubleTag.valueOf(v);
        };
    }

    private int saveFields(CompoundTag root) {
        int changed = 0;
        for (Field f : fields()) {
            if (f.kind() == Kind.BOOL) continue;
            boolean dirty = false;
            for (int i = 0; i < f.parts(); i++) if (!current(f, i).equals(docString(f, i))) dirty = true;
            if (!dirty) continue;
            switch (f.kind()) {
                case TEXT -> {
                    String value = current(f, 0).strip();
                    if (value.isEmpty()) root.remove(f.key());
                    else { CompoundTag component = new CompoundTag(); component.putString("text", value); root.put(f.key(), component); }
                }
                case STRINGS -> {
                    ListTag items = new ListTag();
                    for (String item : current(f, 0).split(",")) if (!item.isBlank()) items.add(StringTag.valueOf(item.strip()));
                    if (items.isEmpty()) root.remove(f.key()); else root.put(f.key(), items);
                }
                case VEC3, VEC2 -> {
                    ListTag values = new ListTag();
                    for (int i = 0; i < f.parts(); i++) values.add(scalar(f, i));
                    root.put(f.key(), values);
                }
                default -> {
                    if (current(f, 0).isBlank()) root.remove(f.key()); else root.put(f.key(), scalar(f, 0));
                }
            }
            changed++;
        }
        return changed;
    }

    // ---- status effects ------------------------------------------------------------------------------------------

    private static String effectName(String id) {
        Identifier key = Identifier.tryParse(id);
        var holder = key == null ? null : BuiltInRegistries.MOB_EFFECT.get(key).orElse(null);
        return holder == null ? id : Component.translatable(holder.value().getDescriptionId()).getString() + " · " + id;
    }
    private static String duration(int ticks) {
        if (ticks < 0) return "无限";
        double seconds = ticks / 20.0;
        return seconds == Math.rint(seconds) ? Long.toString((long) seconds) : Double.toString(seconds);
    }

    private void buildEffect(int i, int ry) {
        if (!(list("active_effects").get(i) instanceof CompoundTag e)) return;
        labels.add(new Label(x + 10, ry + 6, effectName(e.getStringOr("id", "?"))));
        int cx = x + 10 + labelWidth();
        box("eff#" + i + "#amp", pending.getOrDefault("eff#" + i + "#amp", Integer.toString((e.getIntOr("amplifier", 0) & 0xFF) + 1)),
                cx, ry, 40, "等级", "效果等级，1 表示 I 级");
        box("eff#" + i + "#dur", pending.getOrDefault("eff#" + i + "#dur", duration(e.getIntOr("duration", 0))),
                cx + 44, ry, 64, "秒", "持续秒数，填“无限”表示无限持续");
        String[][] flags = {{"show_particles", "粒子"}, {"show_icon", "图标"}, {"ambient", "信标"}};
        for (int k = 0; k < flags.length; k++) {
            String key = flags[k][0];
            boolean on = e.getBooleanOr(key, !key.equals("ambient"));
            button(flags[k][1] + (on ? "开" : "关"), cx + 112 + k * 54, ry, 52, () -> toggleEffect(i, key)).active = session.writable();
        }
        button("删除", cx + 276, ry, 44, () -> removeFrom("active_effects", i)).active = session.writable();
    }

    private void toggleEffect(int i, String key) {
        ListTag list = list("active_effects").copy();
        if (!(list.get(i) instanceof CompoundTag original)) return;
        CompoundTag e = original.copy();
        e.putBoolean(key, !e.getBooleanOr(key, !key.equals("ambient")));
        list.setTag(i, e);
        commitList("active_effects", list);
    }

    private void removeFrom(String key, int i) {
        ListTag list = list(key).copy();
        if (i < list.size()) list.remove(i);
        commitList(key, list);
    }

    private void commitList(String key, ListTag list) {
        if (!session.writable()) return;
        CompoundTag root = session.document.copy();
        if (list.isEmpty()) root.remove(key); else root.put(key, list);
        session.edit(root);
    }

    private int saveEffects(CompoundTag root) {
        ListTag list = list("active_effects").copy();
        int changed = 0;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag original)) continue;
            CompoundTag e = original.copy();
            boolean dirty = false;
            String amp = pending.get("eff#" + i + "#amp"), dur = pending.get("eff#" + i + "#dur");
            if (amp != null && !amp.equals(Integer.toString((e.getIntOr("amplifier", 0) & 0xFF) + 1))) {
                int level;
                try { level = Integer.parseInt(amp.strip()); } catch (NumberFormatException ex) { throw new IllegalArgumentException("等级必须是整数"); }
                if (level < 1 || level > 256) throw new IllegalArgumentException("等级范围：1 至 256");
                e.put("amplifier", ByteTag.valueOf((byte) (level - 1)));
                dirty = true;
            }
            if (dur != null && !dur.equals(duration(e.getIntOr("duration", 0)))) {
                String text = dur.strip();
                int ticks;
                if (text.equals("无限") || text.equals("-1") || text.equalsIgnoreCase("inf")) ticks = -1;
                else {
                    double seconds;
                    try { seconds = Double.parseDouble(text); } catch (NumberFormatException ex) { throw new IllegalArgumentException("持续时间请填秒数或“无限”"); }
                    if (!Double.isFinite(seconds) || seconds < 0 || seconds > 1.0E8) throw new IllegalArgumentException("持续时间范围：0 至 100000000 秒");
                    ticks = (int) Math.round(seconds * 20);
                }
                e.putInt("duration", ticks);
                dirty = true;
            }
            if (dirty) { list.setTag(i, e); changed++; }
        }
        if (changed > 0) root.put("active_effects", list);
        return changed;
    }

    private void addEffect() {
        List<NbtOptionScreen.Option> options = new ArrayList<>();
        BuiltInRegistries.MOB_EFFECT.listElements().forEach(holder ->
                options.add(new NbtOptionScreen.Option(holder.getRegisteredName(), Component.translatable(holder.value().getDescriptionId()).getString())));
        minecraft.setScreen(new NbtOptionScreen(this, session, "选择状态效果", options, id -> {
            ListTag list = list("active_effects").copy();
            for (Tag t : list) if (t instanceof CompoundTag c && id.equals(c.getStringOr("id", ""))) {
                error = "该实体已有此效果"; minecraft.setScreen(this); return;
            }
            CompoundTag e = new CompoundTag();
            e.putString("id", id);
            e.put("amplifier", ByteTag.valueOf((byte) 0));
            e.putInt("duration", 600);
            e.putBoolean("ambient", false); e.putBoolean("show_particles", true); e.putBoolean("show_icon", true);
            list.add(e);
            error = "";
            commitList("active_effects", list);
            minecraft.setScreen(this);
        }));
    }

    // ---- attributes ----------------------------------------------------------------------------------------------

    private static String attributeName(String id) {
        Identifier key = Identifier.tryParse(id);
        var holder = key == null ? null : BuiltInRegistries.ATTRIBUTE.get(key).orElse(null);
        return holder == null ? id : Component.translatable(holder.value().getDescriptionId()).getString() + " · " + id;
    }

    private void buildAttribute(int i, int ry) {
        if (!(list("attributes").get(i) instanceof CompoundTag a)) return;
        labels.add(new Label(x + 10, ry + 6, attributeName(a.getStringOr("id", "?"))));
        int cx = x + 10 + labelWidth();
        box("attr#" + i + "#base", pending.getOrDefault("attr#" + i + "#base", Double.toString(a.getDoubleOr("base", 0))),
                cx, ry, 110, "基础值", "属性基础值，修饰符请在高级模式编辑");
        int modifiers = a.get("modifiers") instanceof ListTag m ? m.size() : 0;
        labels.add(new Label(cx + 118, ry + 6, modifiers + " 个修饰符"));
        button("删除", cx + 200, ry, 44, () -> removeFrom("attributes", i)).active = session.writable();
    }

    private int saveAttributes(CompoundTag root) {
        ListTag list = list("attributes").copy();
        int changed = 0;
        for (int i = 0; i < list.size(); i++) {
            String base = pending.get("attr#" + i + "#base");
            if (base == null || !(list.get(i) instanceof CompoundTag original) || base.equals(Double.toString(original.getDoubleOr("base", 0)))) continue;
            double value;
            try { value = Double.parseDouble(base.strip()); } catch (NumberFormatException ex) { throw new IllegalArgumentException("基础值请输入数字"); }
            if (!Double.isFinite(value)) throw new IllegalArgumentException("基础值必须是有限数字");
            CompoundTag a = original.copy();
            a.putDouble("base", value);
            list.setTag(i, a);
            changed++;
        }
        if (changed > 0) root.put("attributes", list);
        return changed;
    }

    private void addAttribute() {
        List<NbtOptionScreen.Option> options = new ArrayList<>();
        BuiltInRegistries.ATTRIBUTE.listElements().forEach(holder ->
                options.add(new NbtOptionScreen.Option(holder.getRegisteredName(), Component.translatable(holder.value().getDescriptionId()).getString())));
        minecraft.setScreen(new NbtOptionScreen(this, session, "选择属性", options, id -> {
            ListTag list = list("attributes").copy();
            for (Tag t : list) if (t instanceof CompoundTag c && id.equals(c.getStringOr("id", ""))) {
                error = "该实体已有此属性"; minecraft.setScreen(this); return;
            }
            Identifier key = Identifier.tryParse(id);
            var holder = key == null ? null : BuiltInRegistries.ATTRIBUTE.get(key).orElse(null);
            CompoundTag a = new CompoundTag();
            a.putString("id", id);
            a.putDouble("base", holder == null ? 0 : holder.value().getDefaultValue());
            list.add(a);
            error = "";
            commitList("attributes", list);
            minecraft.setScreen(this);
        }));
    }

    // ---- save / render -------------------------------------------------------------------------------------------

    private void save() {
        if (!session.writable()) return;
        try {
            CompoundTag root = session.document.copy();
            int changed = switch (category) {
                case EFFECTS -> saveEffects(root);
                case ATTRIBUTES -> saveAttributes(root);
                default -> saveFields(root);
            };
            error = "";
            if (changed == 0) { session.status = "没有需要保存的修改"; return; }
            pending.clear();
            session.edit(root);
            session.status = "已保存到副本，点击右上角“同步”写入实体";
        } catch (RuntimeException ex) {
            error = ex.getMessage() == null ? "输入无效" : ex.getMessage();
        }
    }

    @Override protected void renderContent(GuiGraphics g, int mx, int my) {
        for (Label label : labels) g.drawString(font, font.plainSubstrByWidth(label.text(), label.x() == x + 10 ? labelWidth() - 6 : 90), label.x(), label.y(), 0xFF303030, false);
        if (rowCount() == 0) {
            String empty = switch (category) {
                case EFFECTS -> living() ? "当前没有状态效果，点击“新增效果”添加" : "此实体不是生物，没有状态效果";
                case ATTRIBUTES -> living() ? "当前没有属性数据" : "此实体不是生物，没有属性";
                case BEHAVIOR -> "此实体不是生物，没有行为字段";
                default -> "此实体没有这类字段，可在高级模式查看完整数据";
            };
            g.drawString(font, empty, x + 12, rowsTop + 8, 0xFF555555, false);
        }
    }
}
