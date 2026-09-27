package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置存储。
 * 角度统一使用 Android Surface.ROTATION_* 常量：0=0°, 1=90°, 2=180°, 3=270°。
 */
public final class Prefs {

    public static final String NAME = "extrot";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return sp.getBoolean("enabled", false);
    }

    public void setEnabled(boolean v) {
        sp.edit().putBoolean("enabled", v).apply();
    }

    /** 手机竖屏时外接屏该锁到哪个角度（默认 90°） */
    public int getPortraitAngle() {
        return sp.getInt("portrait_angle", 1);
    }

    public void setPortraitAngle(int v) {
        sp.edit().putInt("portrait_angle", v).apply();
    }

    /** 手机横屏时外接屏该锁到哪个角度（默认 0°） */
    public int getLandscapeAngle() {
        return sp.getInt("landscape_angle", 0);
    }

    public void setLandscapeAngle(int v) {
        sp.edit().putInt("landscape_angle", v).apply();
    }

    /**
     * 是否同时下发 wm fixed-to-user-rotation，强制忽略应用自己申请的方向。
     *
     * <p>⚠ 默认「关」（v4.26 用户点名改的）：它会在<b>插屏那一刻</b>就把画面钉到某个角度，
     * 外接显示器第一次插进来的人看到的就是「歪脖子」。要钉的自己去打开。
     */
    public boolean isFixToUserRotation() {
        return sp.getBoolean("fix_to_user", false);
    }

    public void setFixToUserRotation(boolean v) {
        sp.edit().putBoolean("fix_to_user", v).apply();
    }

    // ---------------------------------------------------------------- 手机自身旋转

    /**
     * 启用同步时，顺手把「手机自动旋转」关掉并锁定方向。
     * 目的：手机方向不再被重力感应来回翻转，外接屏也就不会被反复重排，
     * 从源头减少显示几何被频繁改写导致的画面错位。
     */
    public boolean isLockPhoneRotation() {
        return sp.getBoolean("lock_phone_rotation", true);
    }

    public void setLockPhoneRotation(boolean v) {
        sp.edit().putBoolean("lock_phone_rotation", v).apply();
    }

    /** 锁定到哪个角度（1 = 90° = 横向，2 = 180°，3 = 270°，0 = 0°）。默认 1（横向）。 */
    public int getPhoneRotation() {
        return sp.getInt("phone_rotation", 1);
    }

    public void setPhoneRotation(int v) {
        sp.edit().putInt("phone_rotation", v).apply();
    }

    // ---------------------------------------------------------------- 内屏分辨率（替代 SecondScreen）

    /**
     * 独立开关：连接便携显示屏时，是否把手机内屏改成分辨率覆盖。
     * 与「自动方向同步」互不影响 —— 关掉旋转同步，分辨率接管依然可以单独工作。
     */
    public boolean isResolutionEnabled() {
        return sp.getBoolean("res_enabled", false);
    }

    public void setResolutionEnabled(boolean v) {
        sp.edit().putBoolean("res_enabled", v).apply();
    }

    public int getResWidth() {
        return sp.getInt("res_w", 1080);
    }

    public int getResHeight() {
        return sp.getInt("res_h", 1920);
    }

    public void setResSize(int w, int h) {
        sp.edit().putInt("res_w", w).putInt("res_h", h).apply();
    }

    /** 同时修改 DPI；0 = 不修改（默认） */
    public int getResDensity() {
        return sp.getInt("res_density", 0);
    }

    public void setResDensity(int v) {
        sp.edit().putInt("res_density", v).apply();
    }

    /** 检测到便携屏插入时弹窗询问（默认开） */
    public boolean isAskOnConnect() {
        return sp.getBoolean("res_ask", true);
    }

    public void setAskOnConnect(boolean v) {
        sp.edit().putBoolean("res_ask", v).apply();
    }

    /** 便携屏拔掉后自动把内屏分辨率还原回原生（默认开） */
    public boolean isAutoResetSize() {
        return sp.getBoolean("res_auto_reset", true);
    }

    public void setAutoResetSize(boolean v) {
        sp.edit().putBoolean("res_auto_reset", v).apply();
    }

    /**
     * 接入便携屏时，自动读取它的面板原生分辨率并套用（默认开）。
     * <p>面板多是横向的（1920×1080），会自动转成竖屏 1080×1920 再写入内屏，
     * 这样就不用手动去查、去填了。
     */
    public boolean isAutoMatchExt() {
        return sp.getBoolean("res_auto_match", true);
    }

    public void setAutoMatchExt(boolean v) {
        sp.edit().putBoolean("res_auto_match", v).apply();
    }

    /** 本应用当前是否已给内屏加上了分辨率覆盖（用于判断拔屏后要不要还原） */
    public boolean isSizeApplied() {
        return sp.getBoolean("res_applied", false);
    }

    public void setSizeApplied(boolean v) {
        sp.edit().putBoolean("res_applied", v).apply();
    }

    /**
     * 这个覆盖是不是「因为接了便携屏」才加上的。
     * <p>true  → 拔掉便携屏 / 应用重启时若发现没接屏，自动还原（这是常规用法）
     * <p>false → 用户在界面上点「立即应用」手动加的，跟便携屏无关，不动它
     */
    public boolean isResBoundToExt() {
        return sp.getBoolean("res_bound", false);
    }

    public void setResBoundToExt(boolean v) {
        sp.edit().putBoolean("res_bound", v).apply();
    }

    /** DPI 覆盖是否由本应用下发过（只有下发过才去 reset，避免误清用户自己的设置） */
    public boolean isDensityApplied() {
        return sp.getBoolean("res_density_applied", false);
    }

    public void setDensityApplied(boolean v) {
        sp.edit().putBoolean("res_density_applied", v).apply();
    }

    /**
     * 服务是否需要常驻。
     *
     * <p>三个功能各自独立，任何一个开着就都得让服务活着：
     * 旋转同步、分辨率接管、以及<b>手机方向锁定</b>。
     * <p>最后一项是后来补上的 —— 以前它只跟着旋转同步走，导致"只想锁横向、
     * 不想做方向同步"这种用法根本没法用（开关点了没反应）。
     */
    public boolean shouldKeepRunning() {
        return isEnabled() || isResolutionEnabled() || isLockPhoneRotation();
    }

    // ---------------------------------------------------------------- 开机选项

    /** 开机后不干预，交给折叠传感器（默认） */
    public static final int BOOT_NONE = 0;
    /** 关机重开之后切到折叠模式，进外屏桌面 */
    public static final int BOOT_FOLDED = 1;
    /** 关机重开之后切到展开模式，进内屏桌面 */
    public static final int BOOT_UNFOLDED = 2;

    /**
     * 开机选项。
     *
     * <p>跟「折叠模式」那两条按钮用的是同一套 {@code cmd device_state state N}，
     * 区别只是执行的时机从"你按下去"挪到了"开机之后"。所以它同样需要 Shizuku 可用 ——
     * 靠无线调试启动的 Shizuku 重启后要手动拉一次，那种情况下这一档会安静地跳过。
     */
    public int getBootMode() {
        return sp.getInt("boot_mode", BOOT_NONE);
    }

    public void setBootMode(int v) {
        sp.edit().putInt("boot_mode", v).apply();
    }

    /** 上次停留的选项卡（0=自动旋转，1=内屏分辨率，2=授权与状态） */
    public int getLastTab() {
        return sp.getInt("last_tab", 0);
    }

    public void setLastTab(int v) {
        sp.edit().putInt("last_tab", v).apply();
    }
}
