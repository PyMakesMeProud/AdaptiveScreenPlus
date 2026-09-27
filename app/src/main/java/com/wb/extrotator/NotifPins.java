package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 外屏通知面板<b>左栏</b>那一条「常用应用」的存盘（v4.22 起）。
 *
 * <p>以前面板底下那排「快捷启动」拿的是<b>刚发过通知的应用</b> —— 那跟上面那排应用筛选项
 * 本来就是同一批东西，而且点下去经常起不来（那些应用不一定有 launcher 入口）。现在换成
 * 用户自己挑的常用应用，点一下就是真正启动它。
 *
 * <p>存成一串逗号分隔的包名。包名里不可能出现逗号，所以不担心分隔符打架；
 * 解析时顺带去重、丢空段 —— 手工改坏了也只是少几个图标，不会崩。
 */
public final class NotifPins {

    private static final String SP = "cover_notif_panel";
    private static final String KEY_PINS = "notif_pins";

    /** 最多存几个。外屏那一列一屏也就看得见六七个，多了只能滚，没必要 */
    public static final int MAX = 12;

    private NotifPins() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 读物：按存的先后给（外屏那一列就按这个次序从上往下摆） */
    public static List<String> list(Context c) {
        List<String> out = new ArrayList<>();
        String s = sp(c).getString(KEY_PINS, "");
        if (TextUtils.isEmpty(s)) {
            return out;
        }
        for (String p : s.split(",")) {
            String q = p.trim();
            if (!q.isEmpty() && !out.contains(q)) {
                out.add(q);
            }
        }
        return out;
    }

    private static void save(Context c, List<String> v) {
        sp(c).edit().putString(KEY_PINS, TextUtils.join(",", v)).apply();
    }

    public static boolean has(Context c, String pkg) {
        return pkg != null && !pkg.isEmpty() && list(c).contains(pkg);
    }

    /**
     * 加一个。已经在里面就直接算成功（幂等）；满了返回 false，界面那边提示一句。
     */
    public static boolean add(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        List<String> v = list(c);
        if (v.contains(pkg)) {
            return true;
        }
        if (v.size() >= MAX) {
            return false;
        }
        v.add(pkg);
        save(c, v);
        return true;
    }

    public static void remove(Context c, String pkg) {
        List<String> v = list(c);
        if (v.remove(pkg)) {
            save(c, v);
        }
    }

    /** 选应用那一页用：点一下加入、再点一下拿掉 */
    public static void toggle(Context c, String pkg) {
        if (has(c, pkg)) {
            remove(c, pkg);
        } else {
            add(c, pkg);
        }
    }
}
