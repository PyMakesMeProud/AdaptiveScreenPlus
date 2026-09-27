package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 封面屏「分辨率预设」的存取。
 *
 * <p>每组 = 名称 + 分辨率 + DPI。存在独立的 SharedPreferences 文件里，
 * 不和 {@link Prefs}（那套是主功能的设置）混在一起 —— 这整块都是 Beta 功能，
 * 将来要整体摘掉时删一个文件就干净了。
 *
 * <p>存成 JSON 数组而不是用多个 key：预设数量是可变的，
 * 拆成 name_0/size_0/... 这种下标 key 在删中间一条时特别容易漏删。
 */
public final class CoverPresets {

    private static final String FILE = "extrot_cover_presets";
    private static final String KEY_LIST = "list";
    /** 最多留几条：再多列表就长到没法用了 */
    public static final int MAX = 12;

    private CoverPresets() {
    }

    /** 一组预设 */
    public static final class Preset {
        public String name;
        public int w;
        public int h;
        public int dpi;

        public Preset(String name, int w, int h, int dpi) {
            this.name = name;
            this.w = w;
            this.h = h;
            this.dpi = dpi;
        }

        /** 列表里显示的一行字，例如 "748×720 · 340dpi" */
        public String summary() {
            return w + "×" + h + (dpi > 0 ? " · " + dpi + "dpi" : " · 原 DPI");
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * 读全部预设。
     *
     * <p>第一次用（还没存过）时，按封面屏的面板原生尺寸生成几条等比的默认值 ——
     * 写死 748×720 只对 Z Flip5 成立，用面板实测值算才换台机器也说得过去。
     *
     * @param physW/physH/physDpi 面板原生值；传 0 表示读不到，这时用 Z Flip5 的已知值兜底
     */
    public static List<Preset> load(Context c, int physW, int physH, int physDpi) {
        List<Preset> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_LIST, null);
        if (raw == null) {
            return defaults(physW, physH, physDpi);
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Preset(o.optString("name", "预设 " + (i + 1)),
                        o.optInt("w"), o.optInt("h"), o.optInt("dpi")));
            }
        } catch (Throwable t) {
            return defaults(physW, physH, physDpi);
        }
        return out.isEmpty() ? defaults(physW, physH, physDpi) : out;
    }

    public static void save(Context c, List<Preset> list) {
        JSONArray arr = new JSONArray();
        for (Preset p : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", p.name);
                o.put("w", p.w);
                o.put("h", p.h);
                o.put("dpi", p.dpi);
            } catch (Throwable ignored) {
            }
            arr.put(o);
        }
        sp(c).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public static void add(Context c, List<Preset> list, Preset p) {
        if (list.size() >= MAX) {
            return;
        }
        list.add(p);
        save(c, list);
    }

    public static void removeAt(Context c, List<Preset> list, int index) {
        if (index < 0 || index >= list.size()) {
            return;
        }
        list.remove(index);
        save(c, list);
    }

    /** 恢复成出厂那几条默认预设 */
    public static List<Preset> resetToDefault(Context c, int physW, int physH, int physDpi) {
        List<Preset> d = defaults(physW, physH, physDpi);
        save(c, d);
        return d;
    }

    /**
     * 默认预设：原生 + 三档等比缩小。
     * DPI 跟着分辨率等比缩，这样「一屏能放多少内容」不变，只是画面更省资源 ——
     * 这正是改封面屏分辨率最常见的用法（压掉一点负载、换更宽的视野）。
     */
    private static List<Preset> defaults(int physW, int physH, int physDpi) {
        if (physW <= 0 || physH <= 0) {
            physW = 748;
            physH = 720;
        }
        if (physDpi <= 0) {
            physDpi = 340;
        }
        List<Preset> out = new ArrayList<>();
        out.add(new Preset("原生", physW, physH, physDpi));
        int[] scales = {90, 80, 70};
        for (int s : scales) {
            int w = Math.round(physW * s / 100f);
            int h = Math.round(physH * s / 100f);
            int dpi = Math.round(physDpi * s / 100f);
            out.add(new Preset("缩小 " + s + "%", w, h, dpi));
        }
        return out;
    }
}
