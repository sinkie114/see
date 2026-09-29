package com.sinkie114.client;

import net.minecraft.nbt.*;
import java.util.*;

/** Path helpers and three-way patching for editable entity NBT copies. */
public final class NbtTools {
    private NbtTools() {}

    /** One leaf-level replacement, addressed by compound keys only. */
    public record Change(List<String> path, Tag value) {}
    /** Only what the user edited, so volatile live fields such as Pos or Brain are never overwritten by a stale copy. */
    public record Patch(List<Change> changes, List<List<String>> removed) {
        public static final Patch EMPTY = new Patch(List.of(), List.of());
        public boolean isEmpty() { return changes.isEmpty() && removed.isEmpty(); }
        public int size() { return changes.size() + removed.size(); }
    }

    public static Tag at(Tag root, List<Object> path) {
        Tag current = root;
        for (Object key : path) {
            if (current instanceof CompoundTag compound && key instanceof String s) current = compound.get(s);
            else if (current instanceof CollectionTag list && key instanceof Integer i && i >= 0 && i < list.size()) current = list.get(i);
            else return null;
        }
        return current;
    }

    public static Tag atPath(CompoundTag root, List<String> path) {
        Tag current = root;
        for (String key : path) {
            if (current instanceof CompoundTag compound) current = compound.get(key);
            else return null;
        }
        return current;
    }

    public static List<Object> child(List<Object> path, Object key) {
        var result = new ArrayList<Object>(path); result.add(key); return List.copyOf(result);
    }

    public static CompoundTag replace(CompoundTag root, List<Object> path, Tag value) {
        if (path.isEmpty()) {
            if (!(value instanceof CompoundTag compound)) throw new IllegalArgumentException("实体根节点必须为 Compound");
            return compound.copy();
        }
        CompoundTag copy = root.copy();
        Tag parent = at(copy, path.subList(0, path.size() - 1));
        Object key = path.getLast();
        if (parent instanceof CompoundTag compound && key instanceof String s) compound.put(s, value.copy());
        else if (parent instanceof CollectionTag list && key instanceof Integer i) {
            if (!list.setTag(i, value.copy())) throw new IllegalArgumentException("数组元素类型不匹配");
        } else throw new IllegalArgumentException("节点位置已失效");
        return copy;
    }

    public static CompoundTag remove(CompoundTag root, List<Object> path) {
        if (path.isEmpty()) throw new IllegalArgumentException("不能删除实体根节点");
        CompoundTag copy = root.copy();
        Tag parent = at(copy, path.subList(0, path.size() - 1));
        Object key = path.getLast();
        if (parent instanceof CompoundTag compound && key instanceof String s) compound.remove(s);
        else if (parent instanceof CollectionTag list && key instanceof Integer i) list.remove(i);
        else throw new IllegalArgumentException("节点位置已失效");
        return copy;
    }

    public static String summary(Tag value) {
        if (value instanceof CompoundTag compound) return "{" + compound.size() + " 个键}";
        if (value instanceof CollectionTag list) return "[" + list.size() + " 项]";
        return value == null ? "<缺失>" : value.toString();
    }

    public static String brief(Tag value) {
        if (value == null) return "<缺失>";
        String text = value.toString();
        return text.length() > 48 ? text.substring(0, 45) + "…" : text;
    }

    public static String type(Tag value) {
        return value == null ? "Missing" : switch (value.getId()) {
            case 1 -> "Byte/布尔"; case 2 -> "Short"; case 3 -> "Int"; case 4 -> "Long";
            case 5 -> "Float"; case 6 -> "Double"; case 7 -> "Byte[]"; case 8 -> "String";
            case 9 -> "List"; case 10 -> "Compound"; case 11 -> "Int[]"; case 12 -> "Long[]"; default -> "End";
        };
    }

    public static String path(List<String> path) { return String.join(".", path); }

    public static Patch diff(CompoundTag base, CompoundTag document) {
        List<Change> changes = new ArrayList<>();
        List<List<String>> removed = new ArrayList<>();
        diff(base, document, List.of(), changes, removed);
        return changes.isEmpty() && removed.isEmpty() ? Patch.EMPTY : new Patch(List.copyOf(changes), List.copyOf(removed));
    }

    private static void diff(CompoundTag base, CompoundTag document, List<String> prefix, List<Change> changes, List<List<String>> removed) {
        for (String key : document.keySet()) {
            Tag next = document.get(key), old = base.get(key);
            List<String> path = append(prefix, key);
            if (old instanceof CompoundTag oldCompound && next instanceof CompoundTag nextCompound) diff(oldCompound, nextCompound, path, changes, removed);
            else if (old == null || !old.equals(next)) changes.add(new Change(path, next.copy()));
        }
        for (String key : base.keySet()) if (!document.contains(key)) removed.add(append(prefix, key));
    }

    private static List<String> append(List<String> prefix, String key) {
        var result = new ArrayList<String>(prefix); result.add(key); return List.copyOf(result);
    }

    /** Returns a copy of {@code target} with the patch applied; removals first, then replacements. */
    public static CompoundTag apply(CompoundTag target, Patch patch) {
        CompoundTag copy = target.copy();
        for (List<String> path : patch.removed()) {
            CompoundTag parent = parent(copy, path, false);
            if (parent != null) parent.remove(path.getLast());
        }
        for (Change change : patch.changes()) parent(copy, change.path(), true).put(change.path().getLast(), change.value().copy());
        return copy;
    }

    private static CompoundTag parent(CompoundTag root, List<String> path, boolean create) {
        CompoundTag current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            Tag next = current.get(path.get(i));
            if (next instanceof CompoundTag compound) current = compound;
            else if (create) {
                CompoundTag created = new CompoundTag();
                current.put(path.get(i), created);
                current = created;
            } else return null;
        }
        return current;
    }

    /** Whether the game stored what was requested: numeric types may be normalized and extra default keys may appear. */
    public static boolean similar(Tag wanted, Tag actual) {
        if (actual == null) return false;
        if (wanted instanceof NumericTag a && actual instanceof NumericTag b) {
            boolean floating = a instanceof FloatTag || a instanceof DoubleTag || b instanceof FloatTag || b instanceof DoubleTag;
            return floating ? a.doubleValue() == b.doubleValue() : a.longValue() == b.longValue();
        }
        if (wanted instanceof CompoundTag a && actual instanceof CompoundTag b) {
            for (String key : a.keySet()) if (!similar(a.get(key), b.get(key))) return false;
            return true;
        }
        if (wanted instanceof ListTag a && actual instanceof ListTag b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!similar(a.get(i), b.get(i))) return false;
            return true;
        }
        return wanted.equals(actual);
    }

    /** Non-empty when writing these edits could plausibly hang, corrupt or lose the entity. */
    public static String riskReason(Patch patch) {
        for (Change change : patch.changes()) {
            String key = change.path().getFirst();
            if (change.path().size() == 1 && (key.equals("Pos") || key.equals("Motion")) && change.value() instanceof CollectionTag list) {
                double limit = key.equals("Pos") ? 3.0E7 : 100;
                for (Tag element : list) if (element instanceof NumericTag n && Math.abs(n.doubleValue()) > limit)
                    return key.equals("Pos") ? "坐标超出世界边界，实体可能丢失或卡死" : "速度过大，实体可能被弹出世界";
            }
            String reason = riskReason(change.value(), change.path().contains("data"));
            if (!reason.isEmpty()) return reason;
        }
        return "";
    }

    private static String riskReason(Tag root, boolean customRoot) {
        record Visit(Tag tag, int depth, boolean custom) {}
        var queue = new ArrayDeque<Visit>();
        queue.add(new Visit(root, 0, customRoot)); int nodes = 0;
        while (!queue.isEmpty()) {
            var e = queue.removeFirst();
            if (++nodes > 12000 || e.depth() > 32) return "节点数量或嵌套层数过多";
            Tag t = e.tag();
            if (t instanceof CompoundTag c) {
                for (String k : c.keySet()) queue.add(new Visit(c.get(k), e.depth() + 1, e.custom() || k.equals("data")));
            } else if (t instanceof CollectionTag c) for (Tag child : c) queue.add(new Visit(child, e.depth() + 1, e.custom()));
            else if (!e.custom() && t instanceof NumericTag n && !Double.isFinite(n.doubleValue())) return "包含非有限数值：" + t;
            else if (t instanceof StringTag s && s.value().length() > 32767) return "字符串长度超过 32767";
        }
        return root.sizeInBytes() > 1024 * 1024 ? "数据大小超过 1 MiB" : "";
    }
}
