package com.sinkie114.client;

import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import java.util.*;

/** Path-addressed NBT helpers. A path holds String keys for compounds and Integer indices for lists and arrays. */
public final class NbtTree {
    private NbtTree() {}

    /** The user's edits replayed onto live data, like /data modify rather than a full overwrite. */
    public record Merge(CompoundTag result, List<List<Object>> changed, List<List<Object>> conflicts) {}

    public static Tag at(Tag root, List<Object> path) {
        Tag current = root;
        for (Object key : path) {
            if (current instanceof CompoundTag compound && key instanceof String s) current = compound.get(s);
            else if (current instanceof CollectionTag list && key instanceof Integer i && i >= 0 && i < list.size()) current = list.get(i);
            else return null;
        }
        return current;
    }

    public static List<Object> child(List<Object> path, Object key) {
        var result = new ArrayList<Object>(path); result.add(key); return List.copyOf(result);
    }

    public static List<Object> parent(List<Object> path) {
        return path.isEmpty() ? List.of() : List.copyOf(path.subList(0, path.size() - 1));
    }

    public static CompoundTag replace(CompoundTag root, List<Object> path, Tag value) {
        if (path.isEmpty()) {
            if (!(value instanceof CompoundTag compound)) throw new IllegalArgumentException("实体根节点必须为 Compound");
            return compound.copy();
        }
        CompoundTag copy = root.copy();
        Tag parent = at(copy, parent(path));
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
        Tag parent = at(copy, parent(path));
        Object key = path.getLast();
        if (parent instanceof CompoundTag compound && key instanceof String s) compound.remove(s);
        else if (parent instanceof CollectionTag list && key instanceof Integer i) list.remove(i);
        else throw new IllegalArgumentException("节点位置已失效");
        return copy;
    }

    /** Recursive /data merge: nested compounds are combined, every other value is replaced. */
    public static CompoundTag mergeInto(CompoundTag target, CompoundTag patch) {
        CompoundTag result = target.copy();
        for (String key : patch.keySet()) {
            Tag value = patch.get(key);
            if (value instanceof CompoundTag nested && result.get(key) instanceof CompoundTag existing) result.put(key, mergeInto(existing, nested));
            else result.put(key, value.copy());
        }
        return result;
    }

    /**
     * Applies only the paths that differ between base and edited onto current. Untouched fields keep their live
     * values, so a moving mob does not snap back to where it was when the editor opened.
     */
    public static Merge merge(CompoundTag base, CompoundTag edited, CompoundTag current) {
        List<List<Object>> changed = new ArrayList<>(), conflicts = new ArrayList<>();
        Tag result = merge(base, edited, current, List.of(), changed, conflicts);
        return new Merge(result instanceof CompoundTag c ? c : edited.copy(), List.copyOf(changed), List.copyOf(conflicts));
    }

    private static Tag merge(Tag base, Tag edited, Tag current, List<Object> path,
                             List<List<Object>> changed, List<List<Object>> conflicts) {
        if (base instanceof CompoundTag b && edited instanceof CompoundTag d && current instanceof CompoundTag c) {
            CompoundTag result = c.copy();
            Set<String> keys = new TreeSet<>(b.keySet()); keys.addAll(d.keySet());
            for (String key : keys) {
                Tag before = b.get(key), after = d.get(key), live = c.get(key);
                if (Objects.equals(before, after)) continue;
                List<Object> childPath = child(path, key);
                if (after == null) {
                    result.remove(key);
                    changed.add(childPath);
                    if (live != null && !live.equals(before)) conflicts.add(childPath);
                } else result.put(key, merge(before, after, live, childPath, changed, conflicts));
            }
            return result;
        }
        // Equal-length lists (Pos, Motion, Rotation, inventories) are merged per index, like /data modify paths.
        // Arrays such as UUID stay atomic values.
        if (base instanceof ListTag b && edited instanceof ListTag d && current instanceof ListTag c
                && b.size() == d.size() && d.size() == c.size()) {
            ListTag result = c.copy();
            List<List<Object>> subChanged = new ArrayList<>(), subConflicts = new ArrayList<>();
            boolean merged = true;
            for (int i = 0; i < d.size() && merged; i++) {
                if (Objects.equals(b.get(i), d.get(i))) continue;
                merged = result.setTag(i, merge(b.get(i), d.get(i), c.get(i), child(path, i), subChanged, subConflicts));
            }
            if (merged) {
                changed.addAll(subChanged); conflicts.addAll(subConflicts);
                return result;
            }
        }
        changed.add(path);
        if (!Objects.equals(base, current) && !Objects.equals(current, edited)) conflicts.add(path);
        return edited.copy();
    }

    /** Like equals, but 50 and 50.0f are the same number: the game stores numbers with its own types. */
    public static boolean sameValue(Tag a, Tag b) {
        if (Objects.equals(a, b)) return true;
        if (a instanceof NumericTag x && b instanceof NumericTag y) return x.doubleValue() == y.doubleValue();
        if (a instanceof CompoundTag x && b instanceof CompoundTag y) {
            if (!x.keySet().equals(y.keySet())) return false;
            for (String key : x.keySet()) if (!sameValue(x.get(key), y.get(key))) return false;
            return true;
        }
        if (a instanceof CollectionTag x && b instanceof CollectionTag y && x.size() == y.size()) {
            for (int i = 0; i < x.size(); i++) if (!sameValue(x.get(i), y.get(i))) return false;
            return true;
        }
        return false;
    }

    /** Entity.load moves the entity before rejecting these, and a malformed Pos silently becomes 0,0,0. */
    public static String invalidVectors(CompoundTag data) {
        if (data.get("Pos") == null) return "Pos 不能删除，否则实体会被移动到世界原点";
        Map<String, Integer> sizes = Map.of("Pos", 3, "Motion", 3, "Rotation", 2);
        for (String key : List.of("Pos", "Motion", "Rotation")) {
            Tag tag = data.get(key);
            if (tag == null) continue;
            if (!(tag instanceof CollectionTag list) || list.size() != sizes.get(key)) return key + " 必须是 " + sizes.get(key) + " 个数值的列表";
            for (Tag value : list) {
                if (!(value instanceof NumericTag number) || !Double.isFinite(number.doubleValue())) return key + " 包含无效数值：" + value;
            }
        }
        return "";
    }

    public static String format(List<Object> path) {
        StringBuilder out = new StringBuilder();
        for (Object key : path) {
            if (key instanceof Integer i) out.append('[').append(i).append(']');
            else {
                String s = String.valueOf(key);
                if (!out.isEmpty()) out.append('.');
                out.append(s.matches("[A-Za-z0-9_+\\-]+") ? s : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }
        }
        return out.isEmpty() ? "<根>" : out.toString();
    }

    public static String summary(Tag value) {
        if (value instanceof CompoundTag compound) return "{" + compound.size() + " 个键}";
        if (value instanceof CollectionTag list) return "[" + list.size() + " 项]";
        return value == null ? "<缺失>" : value.toString();
    }

    public static String type(Tag value) {
        return value == null ? "Missing" : switch (value.getId()) {
            case 1 -> "Byte/布尔"; case 2 -> "Short"; case 3 -> "Int"; case 4 -> "Long";
            case 5 -> "Float"; case 6 -> "Double"; case 7 -> "Byte[]"; case 8 -> "String";
            case 9 -> "List"; case 10 -> "Compound"; case 11 -> "Int[]"; case 12 -> "Long[]"; default -> "End";
        };
    }
}
