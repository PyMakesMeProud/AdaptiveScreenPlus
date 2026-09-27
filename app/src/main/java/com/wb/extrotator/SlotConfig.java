package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 「老虎机」的两个状态：现在是摇奖还是快捷按钮，以及三个格子上各挂了什么动作。
 *
 * <p><b>为什么单独一个类</b>：这份状态有三处要读 —— 组件自己（{@link SlotWidgetProvider}）、
 * 配置页（{@link SlotConfigActivity}）、以及以后要加的任何入口。存法一旦分散，读出来的
 * 默认值迟早不一致（一个当成"没配"、一个当成"配了空字符串"）。
 *
 * <p>⚠ 刻意<b>不</b>把模式塞进 {@code SlotWidgetProvider} 的静态字段：组件进程随时可能
 * 被回收重建，静态字段一定丢，用户会看到"明明切过模式，回来又变回摇奖了"。
 *
 * <p>⚠ 所有读写都吞异常：这里碰的是组件那条链路，任何一次抛都会让桌面把整块换成
 * 「无法显示微件」，比"读不出状态"严重得多。
 */
public final class SlotConfig {

    /** 摇奖模式：三格是滚筒、摇杆是「摇一次」 */
    public static final int MODE_SPIN = 0;
    /** 快捷按钮模式：三格各挂一个功能、摇杆是「设置那三个位」 */
    public static final int MODE_BTN = 1;

    /** 三个格子 */
    public static final int SLOTS = 3;

    private static final String SP = "slot_custom";
    private static final String K_MODE = "mode";

    /**
     * 能挂到格子上的内置动作，按希望出现在列表里的顺序。
     *
     * <p>只收「开关类」：点一下就是切换或执行，没有二级参数。分辨率 / 刷新率那类要选值、
     * 还要区分内屏外屏的动作不在其列 —— 塞进来会让"点一下到底发生什么"变成开盲盒。
     */
    private static final String[] BASE = {
            QuickActions.KEY_ADB_WIFI,
            QuickActions.KEY_TOGGLE_FOLD,
            QuickActions.KEY_SYNC,
            QuickActions.KEY_DUAL,
            QuickActions.KEY_COVER_DESK,
            QuickActions.KEY_LOCK,
            QuickActions.KEY_CAST,
            QuickActions.KEY_OPEN_APP,
            QuickActions.KEY_COVER_ROTATE,
            QuickActions.KEY_COVER_ROTATE_RESET,
            QuickActions.KEY_COVER_AUTOROT,
            QuickActions.KEY_COVER_HOME,
    };

    private SlotConfig() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static int mode(Context c) {
        try {
            return sp(c).getInt(K_MODE, MODE_SPIN);
        } catch (Throwable t) {
            return MODE_SPIN;
        }
    }

    public static void setMode(Context c, int m) {
        try {
            sp(c).edit().putInt(K_MODE, m).apply();
        } catch (Throwable ignored) {
        }
    }

    public static int toggleMode(Context c) {
        int m = mode(c) == MODE_SPIN ? MODE_BTN : MODE_SPIN;
        setMode(c, m);
        return m;
    }

    /** 第 i 格挂的动作 key；没配就是空串（不是 null —— 省得每个调用点都判两次） */
    public static String slot(Context c, int i) {
        try {
            String s = sp(c).getString("b" + i, "");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    public static void setSlot(Context c, int i, String key) {
        try {
            sp(c).edit().putString("b" + i, key == null ? "" : key).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 格子上显示的短名（动作名本身就是给用户看的，不用另起一套） */
    public static String label(Context c, int i) {
        String k = slot(c, i);
        if (k.isEmpty()) {
            return c.getString(R.string.widget_slot_idle);
        }
        return QuickActions.label(c, k);
    }

    /**
     * 这一格现在能不能点。
     *
     * <p>专门挡住一种情况：挂的是自定义指令、而那条指令已经被删了。
     * 这时格子上要显示"已失效"，也不能给它绑点击 —— 不然用户点了毫无反应，只会以为组件坏了。
     */
    public static boolean slotAlive(Context c, int i) {
        String k = slot(c, i);
        return !k.isEmpty() && QuickActions.isValidKey(c, k);
    }

    /** 全可选动作 = 内置那批 + 用户自建的自定义指令 */
    public static String[] choices(Context c) {
        List<String> out = new ArrayList<>();
        for (String k : BASE) {
            out.add(k);
        }
        try {
            for (CustomCmds.Cmd cmd : CustomCmds.list(c)) {
                out.add(CustomCmds.KEY_PREFIX + cmd.id);
            }
        } catch (Throwable ignored) {
        }
        return out.toArray(new String[0]);
    }
}
