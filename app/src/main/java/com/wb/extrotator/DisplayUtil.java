package com.wb.extrotator;

import android.content.ContentResolver;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/** 显示屏相关的小工具方法 */
public final class DisplayUtil {

    private DisplayUtil() {
    }

    /**
     * 判断某块屏是不是「设备自带屏」（内屏 / 折叠机的外屏），这类屏绝对不能去旋转。
     *
     * 依据（android.view.Display 没有公开的 getType()，只能靠这几个信号）：
     *  1. 有挖孔/刘海的 DisplayCutout —— 只有机身自带屏才会有，外接屏永远是 null
     *  2. 名字和默认屏一样 —— 三星折叠机（Z Flip 系列）的内屏和外屏名字相同（"内置屏幕"）
     */
    public static boolean looksInternal(Display d, String defaultName) {
        if (d == null) {
            return true;
        }
        try {
            DisplayCutout cutout = d.getCutout();
            if (cutout != null) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            String name = String.valueOf(d.getName());
            if (defaultName != null && defaultName.equals(name)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 找出所有真正的外接显示屏。
     *
     * <p>排除规则：默认屏（手机内屏）、私有显示屏（虚拟屏 / 投屏覆盖层）、非 STATE_ON 的屏幕
     * （息屏 / AOD 状态下不去动它）、名字里明显是虚拟屏的、看起来像机身自带屏的
     * （见 {@link #looksInternal}）。
     *
     * <p>最后一条很关键：Galaxy Z Flip 的外屏（displayId=1）是一个带 FLAG_PRESENTATION 的内部
     * 显示屏，折叠使用时它是 STATE_ON，很容易被当成外接屏 —— 一旦误旋它，会连带把整机的显示几何
     * 搅乱，镜像到便携屏上就是画面错位。
     */
    public static List<Display> externalDisplays(DisplayManager dm) {
        List<Display> out = new ArrayList<>();
        if (dm == null) {
            return out;
        }
        Display[] all;
        try {
            all = dm.getDisplays();
        } catch (Throwable t) {
            return out;
        }

        String defaultName = null;
        try {
            Display def = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (def != null) {
                defaultName = String.valueOf(def.getName());
            }
        } catch (Throwable ignored) {
        }

        for (Display d : all) {
            if (d == null) {
                continue;
            }
            try {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    continue;
                }
                if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) {
                    continue;
                }
                // 只接受"完全点亮"的屏幕：外接屏正在被手机输出时必然是 STATE_ON
                if (d.getState() != Display.STATE_ON) {
                    continue;
                }
                String name = String.valueOf(d.getName());
                if (name.contains("Virtual") || name.contains("Overlay") || name.contains("Emulator")) {
                    continue;
                }
                if (looksInternal(d, defaultName)) {
                    continue;
                }
                out.add(d);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static Display defaultDisplay(DisplayManager dm) {
        if (dm == null) {
            return null;
        }
        try {
            return dm.getDisplay(Display.DEFAULT_DISPLAY);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 逐个 id 探测到第几个为止（正常设备不会超过 8 块屏） */
    private static final int MAX_PROBE_ID = 12;

    /**
     * 列出设备上所有能拿到的显示屏 —— <b>不要用 {@code dm.getDisplays()} 代替</b>。
     *
     * <p>实测（Galaxy Z Flip5 / Android 14）：封面屏（displayId=1）在内外屏并发下 state 已经是 ON、
     * {@code dumpsys display} 里明明白白列着，但应用进程调 {@code dm.getDisplays()} 只返回内屏一块，
     * 封面屏整个消失；而 {@code dm.getDisplay(1)} 是能正常拿到的。
     *
     * <p>后果很隐蔽：用 {@code getDisplays()} 找封面屏永远找不到（返回 null），界面就会把它标成
     * 「外接屏」，「投副屏桌面」的按钮也会一直说「没找到目标屏」。
     *
     * <p>所以改成按 id 从 0 到 {@link #MAX_PROBE_ID} 逐个 {@code getDisplay()} 探测；探测不到返回
     * null，不影响正确性。
     */
    public static List<Display> allDisplays(DisplayManager dm) {
        List<Display> out = new ArrayList<>();
        if (dm == null) {
            return out;
        }
        for (int id = 0; id <= MAX_PROBE_ID; id++) {
            Display d = null;
            try {
                d = dm.getDisplay(id);
            } catch (Throwable ignored) {
            }
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 找出「封面屏」—— 即折叠机机身上那块<b>不是默认屏</b>的内置屏（Galaxy Z Flip 的外屏）。
     *
     * <p>判据与 {@link #externalDisplays} 正好相反：外接屏的判据它一条都不满足，而
     * {@link #looksInternal} 那条（有挖孔 或 与默认屏同名）它满足。这台 Z Flip5 上封面屏没有
     * cutout（逻辑 DisplayInfo 里压根没有 cutout 字段），靠的是「名字和内屏一样都叫内置屏幕」。
     *
     * <p>这里<b>不看 state</b>：展开时外屏是 STATE_OFF / 甚至被系统从 {@code getDisplays()} 里藏
     * 起来，但依然要能定位到它，好在并发模式下把内容投上去 —— 所以走 {@link #allDisplays}。
     *
     * @return 找不到时返回 null（普通直板机就是 null）
     */
    public static Display coverDisplay(DisplayManager dm) {
        if (dm == null) {
            return null;
        }
        String defaultName = null;
        Display def = defaultDisplay(dm);
        if (def != null) {
            defaultName = String.valueOf(def.getName());
        }
        for (Display d : allDisplays(dm)) {
            if (d == null) {
                continue;
            }
            try {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    continue;
                }
                if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) {
                    continue;
                }
                if (looksInternal(d, defaultName)) {
                    return d;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 除默认屏之外、**可以往里投内容**的所有屏：封面屏 + 外接屏。
     * 与 {@link #externalDisplays} 的区别：不要求 STATE_ON，也不排除封面屏。
     * 同样走 {@link #allDisplays}，否则封面屏会被系统藏掉。
     */
    public static List<Display> secondaryDisplays(DisplayManager dm) {
        List<Display> out = new ArrayList<>();
        for (Display d : allDisplays(dm)) {
            if (d == null) {
                continue;
            }
            try {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    continue;
                }
                if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) {
                    continue;
                }
                String name = String.valueOf(d.getName());
                if (name.contains("Virtual") || name.contains("Overlay")
                        || name.contains("Emulator") || name.contains("叠加")) {
                    continue;
                }
                out.add(d);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 这块屏是不是封面屏（机身自带但不是默认屏） */
    public static boolean isCover(DisplayManager dm, int displayId) {
        Display c = coverDisplay(dm);
        return c != null && c.getDisplayId() == displayId;
    }

    /** 屏幕当前是否点亮 */
    public static boolean isOn(Display d) {
        try {
            return d != null && d.getState() == Display.STATE_ON;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一句话描述一块屏的类型，用于界面显示 */
    public static String kindOf(DisplayManager dm, Display d) {
        if (d == null) {
            return "未知";
        }
        if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return "手机内屏";
        }
        if (isCover(dm, d.getDisplayId())) {
            return "封面屏（外屏）";
        }
        return "外接屏";
    }

    /**
     * 某块屏现在是不是「竖着」的。
     *
     * <p><b>不能只看 rotation 是不是 0/180</b> —— 那等于假设「面板天生就是竖的」，而折叠机的
     * 外屏（Z Flip5 封面屏 748×720）面板天生是<b>横</b>的：对它来说 0°/180° 才是横屏、
     * 90°/270° 才是竖屏，映射正好反过来，拿这一条去问外屏得到的永远是反的答案。
     *
     * <p>所以先用面板原生宽高比判断「天生往哪边躺」，再决定 90/270 代表哪一边 —— 这一步是拿
     * {@link #nativeSize}（Display.Mode 的物理像素）算的，不受 wm size 覆盖和旋转影响。
     */
    public static boolean isPhonePortrait(Display d) {
        if (d == null) {
            return false;
        }
        return isPortraitAt(d, d.getRotation());
    }

    /**
     * 指定 rotation 下这块屏是不是竖着的（{@link #isPhonePortrait} 的显式版本）。
     *
     * <p>单独抽出来是为了能拿<b>别的来源</b>的 rotation 来问同一块屏 ——
     * 比如系统旋转策略里的 {@code user_rotation}，从而绕过"屏上读到的 rotation
     * 被系统复位/覆盖"这一类问题。
     */
    public static boolean isPortraitAt(Display d, int rotation) {
        if (d == null) {
            return false;
        }
        boolean quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        int[] n = nativeSize(d);
        boolean naturalLandscape = n[0] > 0 && n[1] > 0 && n[0] > n[1];
        boolean landscape = naturalLandscape ? !quarterTurn : quarterTurn;
        return !landscape;
    }

    /**
     * 「系统旋转策略」里默认屏当前被钉在哪个角度。
     *
     * <p>自动旋转关着的时候（{@code accelerometer_rotation == 0}），默认屏的内容方向<b>由
     * {@code user_rotation} 决定</b>，不再跟传感器走 —— 这时它是权威值，比去问某块屏的
     * {@code getRotation()} 可靠：实测（Z Flip5 / Android 14）内屏被关掉再点亮（例如切并发双屏
     * {@code device_state state 4}）之后，它的 rotation 会被系统复位成 0，而 {@code user_rotation}
     * 仍然是 1（横屏）→ 光看屏就判成「竖屏」，外接屏被锁到 270°。
     *
     * <p>自动旋转开着时方向由传感器实时决定，返回 -1 表示「没有策略」。
     *
     * @return 0..3（Surface.ROTATION_*）；不适用或读不到时返回 -1
     */
    public static int policyRotation(ContentResolver cr) {
        if (cr == null) {
            return -1;
        }
        try {
            if (Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) != 0) {
                return -1;
            }
            return Settings.System.getInt(cr, Settings.System.USER_ROTATION, 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 「手机当前真正在用的那块内置屏」—— 用来判断手机是竖着还是横着。
     *
     * <p><b>不要直接用 {@code dm.getDisplay(DEFAULT_DISPLAY)}</b>：折叠机合上以后默认屏（内屏）是
     * {@code STATE_OFF}，一块灭着的屏 {@code getRotation()} 会停在旧值（这台 Z Flip5 实测恒为 0），
     * 并不跟着手机实际朝向走 —— 用它当输入源就会永远以为「手机是竖屏」，外接屏被锁到竖屏角度，
     * 这正是「明明是横屏，外接显示器却当场转了 270°」的成因。
     *
     * <p>所以：默认屏亮着就用默认屏；默认屏灭着就在所有内置屏里找真正亮着的那块（合盖时是封面屏）。
     * 一块内置屏都没亮（息屏 / 锁屏）时返回默认屏，调用方必须用 {@link #isOn} 复核后再决定要不要
     * 据此下发命令。
     */
    public static Display activePhoneDisplay(DisplayManager dm) {
        Display def = defaultDisplay(dm);
        if (isOn(def)) {
            return def;
        }
        String defaultName = def == null ? null : String.valueOf(def.getName());
        for (Display d : allDisplays(dm)) {
            if (d == null) {
                continue;
            }
            try {
                if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) {
                    continue;
                }
                if (!looksInternal(d, defaultName)) {
                    continue;
                }
                if (isOn(d)) {
                    return d;
                }
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    /**
     * 一行诊断串：把"判断手机横竖"用到的全部原始输入摊开，方便对着日志查为什么判错。
     *
     * <p>曾经两次栽在"输入本身不对"上（灭屏的 rotation 停在旧值 / 尺寸覆盖把面板
     * 原生宽高比带偏），所以直接把 rotation、Mode 原生尺寸、当前逻辑尺寸、结论全打出来。
     */
    public static String orientationDebug(Display d) {
        if (d == null) {
            return "（无显示屏）";
        }
        int[] n = nativeSize(d);
        return "rot=" + d.getRotation()
                + " mode=" + n[0] + "x" + n[1]
                + " real=" + sizeOf(d)
                + " state=" + stateName(d)
                + " 屏上读数=" + (isPhonePortrait(d) ? "竖屏" : "横屏");
    }

    /** 屏幕状态可读名 */
    public static String stateName(Display d) {
        try {
            switch (d.getState()) {
                case Display.STATE_ON:
                    return "ON";
                case Display.STATE_OFF:
                    return "OFF";
                case Display.STATE_DOZE:
                    return "DOZE";
                case Display.STATE_DOZE_SUSPEND:
                    return "DOZE_SUSPEND";
                case Display.STATE_UNKNOWN:
                    return "UNKNOWN";
                default:
                    return String.valueOf(d.getState());
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    public static int degrees(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    public static String sizeOf(Display d) {
        try {
            Point p = new Point();
            d.getRealSize(p);
            return p.x + "×" + p.y;
        } catch (Throwable t) {
            return "?×?";
        }
    }

    /**
     * 读取一块屏的「面板原生分辨率」，即它物理上就是多少像素。
     *
     * <p>和 {@link #sizeOf} 的区别：{@code getRealSize} 返回的是<b>当前逻辑</b>尺寸，会被 wm size
     * 覆盖和旋转影响；{@code Display.Mode} 的 physicalWidth/Height 才是面板自己的像素矩阵。
     * 判断便携屏「该给内屏配多少分辨率」必须用后者，否则读到的是已经被改过的值，越读越乱。
     *
     * @return {宽, 高}；读不到时返回 {0, 0}
     */
    public static int[] nativeSize(Display d) {
        if (d == null) {
            return new int[]{0, 0};
        }
        try {
            Display.Mode m = d.getMode();
            if (m != null && m.getPhysicalWidth() > 0 && m.getPhysicalHeight() > 0) {
                return new int[]{m.getPhysicalWidth(), m.getPhysicalHeight()};
            }
        } catch (Throwable ignored) {
        }
        try {
            Point p = new Point();
            d.getRealSize(p);
            return new int[]{p.x, p.y};
        } catch (Throwable ignored) {
        }
        return new int[]{0, 0};
    }

    /**
     * 这块外接屏适合让手机内屏用多少分辨率。
     *
     * <p>取它的面板原生像素数，再规范成竖屏 —— 面板是 1920×1080（横向）时
     * 得到 1080×1920，这样内屏镜像过去、外接屏再转 90° 之后正好铺满。
     */
    public static int[] suggestedInternalSize(Display ext) {
        int[] n = nativeSize(ext);
        if (n[0] <= 0 || n[1] <= 0) {
            return new int[]{0, 0};
        }
        return ScreenSizeUtil.portrait(n[0], n[1]);
    }

    /**
     * 从一堆外接屏里挑一块「代表屏」—— 面板像素最多的那块；没有可用的就返回 null。
     *
     * <p>界面上只留一栏、档案也只存一份，多屏时总得挑一块，所以口径必须统一：
     * 「按外接屏保存设置」的界面、接屏时套用档案、以及自动读面板分辨率（{@code syncResFromExt}）
     * 三处都走这个方法。以前这段逻辑在三个地方各写了一遍 —— 只要有一处改成「挑第一块」，
     * 用户就会看到「界面显示的屏和实际套用的屏不是同一块」。
     */
    public static Display largestByPanel(List<Display> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        Display best = null;
        long bestPx = -1;
        for (Display d : list) {
            int[] n = nativeSize(d);
            long px = (long) n[0] * n[1];
            if (px > bestPx) {
                bestPx = px;
                best = d;
            }
        }
        return best;
    }

    public static String describe(Display d) {
        return "ID " + d.getDisplayId()
                + " · " + sizeOf(d)
                + " · 旋转 " + degrees(d.getRotation()) + "°";
    }

    /** 带面板原生分辨率的描述，例如 "ID 5 · 原生 1920×1080 · 当前 1080×1920 · 旋转 90°" */
    public static String describeFull(Display d) {
        int[] n = nativeSize(d);
        return "ID " + d.getDisplayId()
                + " · 原生 " + n[0] + "×" + n[1]
                + " · 当前 " + sizeOf(d)
                + " · 旋转 " + degrees(d.getRotation()) + "°";
    }

    /**
     * 检测这块屏是否正被一个「分辨率覆盖」压着（即有人执行过 wm size）。
     *
     * <p>原理：{@code Display.getMode()} 报的是面板的物理分辨率，{@code Display.getRealSize()}
     * 报的是被覆盖后的真实逻辑分辨率，两者不一致 ⇒ 存在覆盖。
     *
     * <p>为什么关心它：有覆盖存在时一旦再发生旋转，不同组件对「宽高要不要交换」的理解可能不一致，
     * 典型症状就是镜像画面整体偏移、只留四分之一在屏内。
     *
     * @return 例如 1080×2640 → 1080×1920，正常时返回 null
     */
    public static String sizeOverrideInfo(Display d) {
        if (d == null) {
            return null;
        }
        try {
            Point real = new Point();
            d.getRealSize(real);
            Display.Mode mode = d.getMode();
            if (mode == null) {
                return null;
            }
            int pw = mode.getPhysicalWidth();
            int ph = mode.getPhysicalHeight();
            int rMin = Math.min(real.x, real.y);
            int rMax = Math.max(real.x, real.y);
            int pMin = Math.min(pw, ph);
            int pMax = Math.max(pw, ph);
            if (rMin == pMin && rMax == pMax) {
                return null;
            }
            return pMin + "×" + pMax + " → " + rMin + "×" + rMax;
        } catch (Throwable t) {
            return null;
        }
    }
}
