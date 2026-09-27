package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 「副屏桌面」（{@link SecondaryHomeActivity}）自己的设置。
 *
 * <p>与 {@link LauncherPrefs} 的关系：两份顺序各存各的文件，用途也不一样 ——
 * {@code extrot_launcher / launcher_apps} 是外屏启动器（小组件 + 配置页）用户手动排的那份，
 * {@code extrot_home / home_apps} 就是「副屏桌面顺序」。两份<b>互不干扰</b>，唯一的搬运方式是
 * 用户在配置页手动执行「把当前顺序写入副屏桌面」—— 搬<b>一次</b>，之后各走各的。
 *
 * <p>⚠ 唯一写这份表的地方就是 {@link #setApps}。别在 {@link LauncherPrefs#setApps} 里挂自动
 * 同步，那等于把这份表变成手动顺序的副本，两边就分不出来了。
 *
 * <p>{@link LauncherPrefs#SORT_HOME}「副屏桌面顺序」直接读这份表来排，它跟着副屏桌面走。
 *
 * <p>没设过顺序时是什么行为：{@link #apps} 返回空表 ⇒ {@link #sort} 里所有条目同分 ⇒
 * 保持传进来的顺序，也就是 {@link AppRepo#load} 的中文名称序，即「没配过 = 按名称排」。
 *
 * <p>⚠ 顺序只是「排前面」而不是「只显示这些」—— 副屏桌面照旧列出所有应用，表里的排在最前，
 * 其余按名称跟在后面。
 */
public final class HomePrefs {

    private static final String FILE = "extrot_home";
    static final String KEY_APPS = "home_apps";

    /** 包名里不会出现分号，跟 {@link LauncherPrefs} 用同一个分隔符 */
    private static final String SEP = ";";

    private HomePrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 副屏桌面的自定义顺序（有序、去重）；空表 = 还没设过 */
    public static List<String> apps(Context c) {
        String raw = sp(c).getString(KEY_APPS, "");
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        for (String s : raw.split(SEP)) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public static void setApps(Context c, List<String> pkgs) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p != null && !p.trim().isEmpty()) {
                    set.add(p.trim());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(p);
        }
        sp(c).edit().putString(KEY_APPS, sb.toString()).apply();
    }

    public static boolean isConfigured(Context c) {
        return !apps(c).isEmpty();
    }

    /**
     * 按自定义顺序排列（就地）—— 表里的排前面、按表里的先后；
     * 表外的跟在后面，保持传进来的顺序（名称序）。
     */
    public static void sort(Context c, List<AppRepo.Item> items) {
        if (items == null || items.size() < 2) {
            return;
        }
        List<String> order = apps(c);
        if (order.isEmpty()) {
            return;
        }
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            idx.put(order.get(i), i);
        }
        try {
            Collections.sort(items, (a, b) -> Integer.compare(at(idx, a.pkg), at(idx, b.pkg)));
        } catch (Throwable ignored) {
        }
    }

    private static int at(Map<String, Integer> m, String pkg) {
        Integer i = m.get(pkg);
        return i == null ? Integer.MAX_VALUE : i;
    }
}
