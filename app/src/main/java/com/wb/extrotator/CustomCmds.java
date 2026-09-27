package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「自定义指令」—— 用户自己写的一条 shell 命令。
 *
 * <p>同一条数据有三处要看它：Beta 页的列表（增删改）、{@link QuickActions} 的动作白名单
 * （能被选成侧键动作）、执行时的查表。存在 {@link QuickPrefs} 那个 SharedPreferences 文件里，
 * 将来整块 Beta 功能一起摘掉时删一个文件就干净。
 *
 * <p>格式是 JSON 数组（用 Android 自带 org.json），每项三个字段：id / name / cmd。
 * 动作 key 形如 {@code custom:<id>}，与内置动作共用一套命名空间，见 {@link #KEY_PREFIX}。
 */
public final class CustomCmds {

    /** 自定义指令的动作 key 前缀 */
    public static final String KEY_PREFIX = "custom:";

    private static final String FILE = "extrot_quick";
    private static final String KEY_LIST = "custom_cmds";
    /** 名字太长会把列表行撑爆，落库前先截一刀 */
    private static final int MAX_NAME = 24;

    private CustomCmds() {
    }

    /** 一条自定义指令 */
    public static final class Cmd {
        public String id = "";
        public String name = "";
        public String cmd = "";
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 全部指令，按添加顺序 */
    public static List<Cmd> list(Context c) {
        List<Cmd> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_LIST, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Cmd x = new Cmd();
                x.id = o.optString("id", "");
                x.name = o.optString("name", "");
                x.cmd = o.optString("cmd", "");
                if (!x.id.isEmpty() && !x.cmd.isEmpty()) {
                    out.add(x);
                }
            }
        } catch (Throwable t) {
            // 存坏了就当没有 —— 不让一个坏字符串把整页拖垮
        }
        return out;
    }

    /** 按 id 找一条；没有返回 null */
    public static Cmd find(Context c, String id) {
        if (id == null) {
            return null;
        }
        for (Cmd x : list(c)) {
            if (id.equals(x.id)) {
                return x;
            }
        }
        return null;
    }

    /** 新增或更新（id 为空 = 新增，自动分配一个） */
    public static void put(Context c, Cmd item) {
        if (item == null || item.cmd == null || item.cmd.trim().isEmpty()) {
            return;
        }
        List<Cmd> all = list(c);
        if (item.id == null || item.id.isEmpty()) {
            item.id = Long.toString(System.currentTimeMillis(), 36);
        }
        item.cmd = item.cmd.trim();
        item.name = clip(item.name);
        if (item.name.isEmpty()) {
            // 名字留空就拿命令顶上，列表里总得有个东西可认
            item.name = item.cmd.length() > MAX_NAME ? item.cmd.substring(0, MAX_NAME) : item.cmd;
        }
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(item.id)) {
                all.set(i, item);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(item);
        }
        save(c, all);
    }

    /** 删一条 */
    public static void remove(Context c, String id) {
        List<Cmd> all = list(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        save(c, all);
    }

    private static void save(Context c, List<Cmd> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Cmd x : all) {
                JSONObject o = new JSONObject();
                o.put("id", x.id);
                o.put("name", x.name);
                o.put("cmd", x.cmd);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        sp(c).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > MAX_NAME ? t.substring(0, MAX_NAME) : t;
    }
}
