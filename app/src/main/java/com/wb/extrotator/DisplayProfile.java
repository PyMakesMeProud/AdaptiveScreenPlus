package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * 一块外接屏的专属配置档案 —— 按屏幕身份存档，下次它接上来自动还原。
 *
 * <p><b>为什么不能直接用 displayId 当 key</b>：displayId 是系统在运行时分配的，拔掉再插上
 * 大概率会变（中间插过别的东西、或系统重启过就更容易变），拿它当 key 用户会遇到
 * 「我明明存过，插回来又不生效」。所以这里用的是「屏幕身份」：优先
 * {@link Display#getUniqueId()}（Android 13+）—— 由 EDID 等信息生成，同一块屏在同一台机器上
 * 稳定不变；拿不到就退回「屏名字 + 面板原生尺寸」。displayId 仍然会存一份，
 * 但只给界面显示用，不参与匹配。
 *
 * <p><b>存的是「这块屏想要的整套设置」</b>，不只是用户点名的那三项 —— 漏项的后果很荒谬：
 * 拿同一块屏在两个场景下用（桌上横放 / 支起来竖放），改完同步模式、锁定方式、内屏分辨率
 * 和 DPI，存进档案的却只有那三项，套用回来当然不对。旋转侧记竖屏角度 · 横屏角度 · 强制忽略
 * 应用申请的方向；分辨率侧记接入后自动套用面板分辨率 · 内屏分辨率接管开关 · 分辨率宽高 · DPI。
 *
 * <p>「方向同步」与「手机方向锁定」只<b>记录</b>（表格里能对照），不参与 {@link Item#applyTo}：
 * 照档案写回全局开关的话，一插屏就会把用户手动打开的开关悄悄关掉，表现正是「两个开关只能
 * 单独开」「自适应旋转一开就失效」。存的是「你上次为这块屏保存时开关是什么状态」，
 * 不是「接屏就该切成什么」。
 *
 * <p>刻意<b>不存</b>「插屏时弹窗询问」和「拔屏后自动还原」—— 它们是全局的交互习惯
 * （要不要每次都问我），跟「哪块屏」没关系，按屏存反而会让人困惑。
 */
public final class DisplayProfile {

    /** 单独一个 prefs 文件，免得和主设置互相干扰，也方便日后整体清掉 */
    private static final String FILE = "extrot_profiles";

    private DisplayProfile() {
    }

    // 各字段的兜底默认值，必须和 {@link Prefs} 里的默认值一致 ——
    // 否则旧版本存下的档案（只有前 5 项）读出来会和用户的预期对不上。
    private static final boolean DEF_SYNC = false;
    private static final int DEF_PORTRAIT = 1;
    private static final int DEF_LANDSCAPE = 0;
    private static final boolean DEF_FIX_TO_USER = true;
    private static final boolean DEF_LOCK_PHONE = true;
    private static final int DEF_PHONE_ROT = 1;
    private static final boolean DEF_RES_AUTO = true;
    private static final boolean DEF_RES_ENABLED = false;
    private static final int DEF_RES_W = 1080;
    private static final int DEF_RES_H = 1920;
    private static final int DEF_RES_DPI = 0;

    /** 一块屏名下存着的整套设置 */
    public static final class Item {
        /** 方向同步总开关（只记录，不参与 {@link #applyTo}） */
        public final boolean sync;
        public final int portrait;
        public final int landscape;
        /** 强制忽略应用自己申请的方向（wm fixed-to-user-rotation） */
        public final boolean fixToUser;
        /** 手机自动旋转关掉、锁定方向（只记录，不参与 {@link #applyTo}） */
        public final boolean lockPhone;
        /** 锁到哪个角度（Surface.ROTATION_*，只记录） */
        public final int phoneRot;
        /** 接入时自动读面板分辨率并套用 */
        public final boolean resAuto;
        /** 内屏分辨率接管开关 */
        public final boolean resEnabled;
        public final int resW;
        public final int resH;
        /** 0 = 不改 DPI */
        public final int resDensity;
        /** 存档那一次的 displayId / 屏名，仅用于界面显示 */
        public final int displayId;
        public final String label;

        Item(boolean sync, int portrait, int landscape,
             boolean fixToUser, boolean lockPhone, int phoneRot,
             boolean resAuto, boolean resEnabled, int resW, int resH, int resDensity,
             int displayId, String label) {
            this.sync = sync;
            this.portrait = portrait;
            this.landscape = landscape;
            this.fixToUser = fixToUser;
            this.lockPhone = lockPhone;
            this.phoneRot = phoneRot;
            this.resAuto = resAuto;
            this.resEnabled = resEnabled;
            this.resW = resW;
            this.resH = resH;
            this.resDensity = resDensity;
            this.displayId = displayId;
            this.label = label == null ? "" : label;
        }

        /**
         * 把**当前**的全局设置打包成一份 Item（不落盘）。
         *
         * <p>用途是让界面能把「这台屏存过的那份」和「现在这套」摆成同一个形状来逐项对比 ——
         * 两边格式化成同一张表，用户一眼就能看出哪几项对不上、要不要重新保存。
         */
        public static Item from(Prefs p) {
            return new Item(p.isEnabled(),
                    p.getPortraitAngle(), p.getLandscapeAngle(),
                    p.isFixToUserRotation(), p.isLockPhoneRotation(), p.getPhoneRotation(),
                    p.isAutoMatchExt(), p.isResolutionEnabled(),
                    p.getResWidth(), p.getResHeight(), p.getResDensity(),
                    -1, "");
        }

        /**
         * 把这份档案写回全局设置。
         *
         * @return 是否真的改动了什么 —— 没改动就不必提示用户"已套用"
         */
        public boolean applyTo(Prefs p) {
            boolean changed = false;
            // ⚠ v2.14 起这里刻意不写「方向同步」与「手机方向锁定」，v2.17 补回字段后
            // 仍然不写。它们是你此刻的开关意图，不是"这块屏的属性"；照档案写回去，
            // 一插屏就会把你手动打开的开关悄悄关掉（v2.9–v2.15 那个 bug）。
            // 档案里留着这两个值，只是为了表格「已保存」列能对照。
            if (p.getPortraitAngle() != portrait) {
                p.setPortraitAngle(portrait);
                changed = true;
            }
            if (p.getLandscapeAngle() != landscape) {
                p.setLandscapeAngle(landscape);
                changed = true;
            }
            if (p.isFixToUserRotation() != fixToUser) {
                p.setFixToUserRotation(fixToUser);
                changed = true;
            }
            if (p.isAutoMatchExt() != resAuto) {
                p.setAutoMatchExt(resAuto);
                changed = true;
            }
            if (p.isResolutionEnabled() != resEnabled) {
                p.setResolutionEnabled(resEnabled);
                changed = true;
            }
            if (p.getResWidth() != resW || p.getResHeight() != resH) {
                p.setResSize(resW, resH);
                changed = true;
            }
            if (p.getResDensity() != resDensity) {
                p.setResDensity(resDensity);
                changed = true;
            }
            return changed;
        }
    }

    /**
     * 一块屏的稳定身份串。
     *
     * <p>优先用系统内部的 {@code Display.getUniqueId()} —— 由 EDID 等信息生成，同一块屏在这台
     * 机器上稳定不变。⚠ 它<b>不在公开 SDK 里</b>（android.jar 里没有，编译期引用会直接报
     * 「找不到符号」），所以只能走反射；拿到了就用，拿不到（方法不存在、被挡）就安静地退回下一档。
     *
     * <p>退路是「屏名 + 面板原生尺寸」：便携屏基本都够用，唯一的漏洞是两块<b>完全同型号</b>的
     * 便携屏会撞在一起 —— 这时它们共用一份设置，属于可接受的结果。
     *
     * <p>⚠ 读不到任何信息时返回空串 —— 调用方必须把它当成「无法归档」处理，绝不能退化成
     * 「用空 key 存一份」，那会变成所有屏共用一份设置。
     */
    public static String signature(Display d) {
        if (d == null) {
            return "";
        }
        try {
            Method m = Display.class.getMethod("getUniqueId");
            Object uid = m.invoke(d);
            if (uid instanceof String && !((String) uid).isEmpty()) {
                return "u:" + uid;
            }
        } catch (Throwable ignored) {
        }
        int[] n = DisplayUtil.nativeSize(d);
        if (n[0] <= 0 || n[1] <= 0) {
            return "";
        }
        return "n:" + d.getName() + "#" + n[0] + "x" + n[1];
    }

    /** 界面上给这块屏的描述，例如 "display 5 · 面板 1920×1080" */
    public static String label(Display d) {
        int[] n = DisplayUtil.nativeSize(d);
        return "display " + d.getDisplayId() + " · 面板 " + n[0] + "×" + n[1];
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String prefix(String sig) {
        return "p:" + sig + ":";
    }

    public static boolean exists(Context c, String sig) {
        return sig != null && !sig.isEmpty()
                && sp(c).contains(prefix(sig) + "land");
    }

    /** 读档案；这块屏没存过时返回 null */
    public static Item load(Context c, String sig) {
        if (sig == null || sig.isEmpty()) {
            return null;
        }
        SharedPreferences p = sp(c);
        String k = prefix(sig);
        // 存在性判据用 "land"：它从最早那版起就在存（老档案也认）；
        // 原先用的 "sync" 键已不再写入，再拿它当判据会让所有老档案变成"没存过"。
        if (!p.contains(k + "land")) {
            return null;
        }
        return new Item(
                p.getBoolean(k + "sync", DEF_SYNC),
                p.getInt(k + "port", DEF_PORTRAIT),
                p.getInt(k + "land", DEF_LANDSCAPE),
                p.getBoolean(k + "fixtouser", DEF_FIX_TO_USER),
                p.getBoolean(k + "lockphone", DEF_LOCK_PHONE),
                p.getInt(k + "phonerot", DEF_PHONE_ROT),
                p.getBoolean(k + "resauto", DEF_RES_AUTO),
                p.getBoolean(k + "resenabled", DEF_RES_ENABLED),
                p.getInt(k + "resw", DEF_RES_W),
                p.getInt(k + "resh", DEF_RES_H),
                p.getInt(k + "resdpi", DEF_RES_DPI),
                p.getInt(k + "id", -1),
                p.getString(k + "label", ""));
    }

    /**
     * 把**当前**的整套设置存到这面屏名下。
     *
     * <p>只传一个 {@link Prefs}，不逐个传参 —— 字段有十几个，散着传迟早漏一个，
     * 而且以后再加设置项时不用同时改三个地方。
     */
    public static void saveFrom(Context c, String sig, String label, int displayId, Prefs p) {
        if (sig == null || sig.isEmpty()) {
            return;
        }
        String k = prefix(sig);
        sp(c).edit()
                .putBoolean(k + "sync", p.isEnabled())
                .putInt(k + "port", p.getPortraitAngle())
                .putInt(k + "land", p.getLandscapeAngle())
                .putBoolean(k + "fixtouser", p.isFixToUserRotation())
                .putBoolean(k + "lockphone", p.isLockPhoneRotation())
                .putInt(k + "phonerot", p.getPhoneRotation())
                .putBoolean(k + "resauto", p.isAutoMatchExt())
                .putBoolean(k + "resenabled", p.isResolutionEnabled())
                .putInt(k + "resw", p.getResWidth())
                .putInt(k + "resh", p.getResHeight())
                .putInt(k + "resdpi", p.getResDensity())
                .putInt(k + "id", displayId)
                .putString(k + "label", label == null ? "" : label)
                .apply();
    }

    public static void clear(Context c, String sig) {
        if (sig == null || sig.isEmpty()) {
            return;
        }
        String k = prefix(sig);
        sp(c).edit()
                .remove(k + "sync")
                // mode / fixed 已不再写入，但还是照清 —— 老档案里留着它们
                .remove(k + "mode")
                .remove(k + "port")
                .remove(k + "land")
                .remove(k + "fixed")
                .remove(k + "fixtouser")
                .remove(k + "lockphone")
                .remove(k + "phonerot")
                .remove(k + "resauto")
                .remove(k + "resenabled")
                .remove(k + "resw")
                .remove(k + "resh")
                .remove(k + "resdpi")
                .remove(k + "id")
                .remove(k + "label")
                .apply();
    }
}
