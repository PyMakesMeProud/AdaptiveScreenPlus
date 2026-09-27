package com.wb.extrotator;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * 「按键快捷指令」那一页自己的设置。
 *
 * <p>单独一个 SharedPreferences 文件，不和 {@link Prefs}（主功能的设置）混 ——
 * 这整块是 Beta 功能，将来整体摘掉时删一个文件就干净了，不用去主设置里翻哪些 key 是它的。
 */
public final class QuickPrefs {

    private static final String FILE = "extrot_quick";

    private static final String KEY_ASSIST = "assist_action";
    private static final String KEY_VOL_UP_ACTION = "volume_up_action";
    /** 长按音量下键的动作 */
    private static final String KEY_VOL_DOWN_ACTION = "volume_down_action";
    private static final String KEY_VOL_ENABLED = "volume_key_enabled";

    /** 「侧键操作」当前在配哪个键 —— 电源键 */
    public static final String SIDE_ASSIST = "assist";
    /** 「侧键操作」当前在配哪个键 —— 长按音量上键 */
    public static final String SIDE_VOL_UP = "vol_up";
    /** 「侧键操作」当前在配哪个键 —— 长按音量下键 */
    public static final String SIDE_VOL_DOWN = "vol_down";

    private QuickPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 长按电源键（数字助手）

    /** 长按电源键唤起本应用时执行哪个动作，默认「回到外屏主界面」 */
    public static String assistAction(Context c) {
        return sp(c).getString(KEY_ASSIST, QuickActions.KEY_COVER_HOME);
    }

    public static void setAssistAction(Context c, String key) {
        sp(c).edit().putString(KEY_ASSIST, key).apply();
    }

    // ------------------------------------------------------------ 长按音量上键 / 下键

    /** 长按音量上键执行哪个动作，默认「外屏旋转 90°」 */
    public static String volumeUpAction(Context c) {
        return sp(c).getString(KEY_VOL_UP_ACTION, QuickActions.KEY_COVER_ROTATE);
    }

    public static void setVolumeUpAction(Context c, String key) {
        sp(c).edit().putString(KEY_VOL_UP_ACTION, key).apply();
    }

    /**
     * 长按音量下键执行哪个动作。
     *
     * <p>默认 {@link QuickActions#KEY_NONE}（不绑）—— 下键在系统里本来就有"快速静音"之类的
     * 用途，没道理装上新版就把它占掉。要用的人自己去选。
     */
    public static String volumeDownAction(Context c) {
        return sp(c).getString(KEY_VOL_DOWN_ACTION, QuickActions.KEY_NONE);
    }

    public static void setVolumeDownAction(Context c, String key) {
        sp(c).edit().putString(KEY_VOL_DOWN_ACTION, key).apply();
    }

    /**
     * 音量键拦截的总开关。关掉之后**两个**音量键都完全回归系统。
     *
     * <p>⚠️ 单个键想"不绑动作"用动作里的「无」（{@link QuickActions#KEY_NONE}），
     * 不要用这个开关 —— 它是一刀切两个键。
     */
    public static boolean isVolumeKeyEnabled(Context c) {
        return sp(c).getBoolean(KEY_VOL_ENABLED, true);
    }

    public static void setVolumeKeyEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_VOL_ENABLED, v).apply();
    }

    // ------------------------------------------------------------ 侧键操作：当前看哪个按键

    /** v2.19.2 起存三选一的字符串 */
    private static final String KEY_SIDE_TARGET = "side_key_target";
    /** v2.19.2 之前的旧值（boolean）：true=电源键，false=音量上键。只为了迁移一次 */
    private static final String KEY_SIDE_TARGET_LEGACY = "side_key_assist";

    /**
     * 「侧键操作」里当前配的是哪个键（{@link #SIDE_ASSIST} / {@link #SIDE_VOL_UP} /
     * {@link #SIDE_VOL_DOWN}），默认电源键。
     *
     * <p>⚠ 换了 key 名、没原地改类型：同一个 key 从 boolean 变成 String 会让 SharedPreferences
     * 读取直接抛 ClassCastException。旧值在这里读一次做迁移，用户上次的选择不白丢。
     */
    public static String sideKey(Context c) {
        String v = sp(c).getString(KEY_SIDE_TARGET, null);
        if (v != null) {
            return v;
        }
        String migrated = SIDE_ASSIST;
        try {
            // try 是必要的：万一这里还是旧 boolean，getBoolean 在某些实现上会抛
            boolean wasAssist = sp(c).getBoolean(KEY_SIDE_TARGET_LEGACY, true);
            migrated = wasAssist ? SIDE_ASSIST : SIDE_VOL_UP;
        } catch (Throwable ignored) {
        }
        return migrated;
    }

    public static void setSideKey(Context c, String v) {
        sp(c).edit().putString(KEY_SIDE_TARGET,
                v == null ? SIDE_ASSIST : v).apply();
    }

    // ------------------------------------------------------------ 无障碍服务状态

    /**
     * 本应用的无障碍服务此刻是否已在系统里开启。
     *
     * <p>直接读 {@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} 这个扁平的字符串，
     * 里面的分隔符是冒号、每项形如 {@code pkg/cls} —— 用 ComponentName 的两种写法都去比一遍：
     * 系统存进去的可能是全名也可能是短名，只比一种会漏判。
     */
    public static boolean isAccessibilityEnabled(Context c) {
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(flat)) {
                return false;
            }
            ComponentName me = new ComponentName(c, VolumeKeyService.class);
            String full = me.flattenToString();
            String shortName = me.flattenToShortString();
            for (String item : flat.split(":")) {
                if (item.equalsIgnoreCase(full) || item.equalsIgnoreCase(shortName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
