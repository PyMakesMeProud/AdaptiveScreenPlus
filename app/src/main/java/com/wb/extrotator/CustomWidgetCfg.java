package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「自定义操作」小组件那四个格子上各挂了什么动作。
 *
 * <p><b>为什么单独一个类</b>：这份状态有三处要读 —— 组件自己（{@link CustomCoverWidgetProvider}）、
 * 配置页（{@link CustomCfgActivity}）、以及以后要加的任何入口。跟 {@link SlotConfig} 是一个路子：
 * 存法一旦分散，各处读出来的默认值迟早不一致（一个当成"没配"、一个当成"配了空字符串"）。
 *
 * <p>⚠ 刻意<b>不</b>把状态塞进 provider 的静态字段：组件进程随时可能被回收重建，
 * 静态字段一定丢，用户会看到"明明设过，回来又变空白了"。
 *
 * <p>⚠ 所有读写都吞异常：这里碰的是组件那条链路，任何一次抛都会让桌面把整块换成
 * 「无法显示微件」，比"读不出状态"严重得多。
 *
 * <p>可选动作<b>与老虎机完全相同</b>（直接借 {@link SlotConfig#choices}），
 * 免得两处清单各自演化、日子一长就对不上。
 */
public final class CustomWidgetCfg {

    /** 格子数：2×2 四格 */
    public static final int SLOTS = 4;

    private static final String SP = "custom_widget";

    private CustomWidgetCfg() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 第 i 格挂的动作 key；没配就是空串（不是 null —— 省得每个调用点都判两次） */
    public static String slot(Context c, int i) {
        try {
            String s = sp(c).getString("s" + i, "");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    public static void setSlot(Context c, int i, String key) {
        try {
            sp(c).edit().putString("s" + i, key == null ? "" : key).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这一格现在能不能点。
     *
     * <p>专门挡住一种情况：挂的是自定义指令、而那条指令已经被删了。
     * 这时格子上要显示成"待设置"、点击回到设置页 —— 不然用户点了毫无反应，只会以为组件坏了。
     */
    public static boolean slotAlive(Context c, int i) {
        String k = slot(c, i);
        return !k.isEmpty() && QuickActions.isValidKey(c, k);
    }

    /** 格子上显示的字：配好了显示动作名，没配（或动作已失效）显示加号 */
    public static String label(Context c, int i) {
        if (!slotAlive(c, i)) {
            return c.getString(R.string.widget_custom_empty);
        }
        return QuickActions.label(c, slot(c, i));
    }
}
