package com.sinkie114.client;

/** Top-level tabs of the entity editor; the inventory tab opens the vanilla container screen. */
public enum EditorMode {
    SIMPLE("简单模式", "简单"), ADVANCED("高级模式", "高级"), INFO("信息", "信息"), INVENTORY("物品栏", "物品栏");

    public final String title, shortTitle;
    EditorMode(String title, String shortTitle) { this.title = title; this.shortTitle = shortTitle; }
}
