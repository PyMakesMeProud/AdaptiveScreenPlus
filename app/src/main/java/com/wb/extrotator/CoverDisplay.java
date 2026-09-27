package com.wb.extrotator;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「封面屏」（折叠机手机背面那块小屏，Galaxy Z Flip 上 displayId=1）的强制覆盖操作。
 *
 * <p>与 {@link DisplayProfile} 那条线完全无关 —— 那个是「接便携屏时套用档案」，这里是把封面屏的
 * 参数按用户手动输入强行改掉，纯 Beta 功能。
 *
 * <p><b>命令是实测出来的，参数顺序不能改</b>：
 * <pre>
 *   wm size &lt;W&gt;x&lt;H&gt; -d &lt;id&gt;        ✅ 生效（base/cur 都变）
 *   wm size -d &lt;id&gt; &lt;W&gt;x&lt;H&gt;        ❌ 静默无效（读回来还是 Physical size）
 *   wm density &lt;dpi&gt; -d &lt;id&gt;       ✅ 生效（出现 Override density）
 *   wm size reset -d &lt;id&gt;          ✅ 恢复
 *   wm density reset -d &lt;id&gt;       ✅ 恢复
 *   wm fixed-to-user-rotation -d &lt;id&gt; enabled   ← 旋转前必须先开这条，否则 lock 不落地
 *   cmd window user-rotation -d &lt;id&gt; lock &lt;0..3&gt;
 *   cmd window user-rotation -d &lt;id&gt; free        ← 交还传感器
 * </pre>
 *
 * <p>⚠ 走的是 {@code cmd window user-rotation}，<b>不是</b> {@code wm user-rotation} —— 后者对
 * 非默认屏不生效。
 *
 * <p>本类所有方法都会真的走 binder，<b>必须在后台线程调用</b>。
 */
public final class CoverDisplay {

    private static final String TAG = "CoverDisplay";

    private static final Pattern P_SIZE = Pattern.compile("(\\d+)\\s*x\\s*(\\d+)");
    private static final Pattern P_LOCK = Pattern.compile("lock\\s+(\\d)");

    /** 旋转被钉住时的角度值（0..3），自由旋转返回 -1 */
    public static final int ROT_FREE = -1;

    private CoverDisplay() {
    }

    /** 封面屏的 display id；找不到（普通直板机）返回 -1 */
    public static int id(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getApplicationContext()
                    .getSystemService(Context.DISPLAY_SERVICE);
            Display c = DisplayUtil.coverDisplay(dm);
            return c == null ? -1 : c.getDisplayId();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Shizuku 就绪 + 拿得到封面屏，才算能用 */
    public static boolean available(Context ctx) {
        return ShellRunner.isReady() && id(ctx) > 0;
    }

    // ------------------------------------------------------------------ 读

    /**
     * 封面屏此刻的一整组参数（物理值、覆盖值、旋转），供界面显示。
     * <b>阻塞</b>（三条 shell 命令）
     */
    public static State read(Context ctx) {
        State s = new State();
        int did = id(ctx);
        s.displayId = did;
        if (did <= 0) {
            return s;
        }

        String sizeOut = ShellRunner.run("wm size -d " + did);
        s.physSize = firstMatch(P_SIZE, section(sizeOut, "Physical"));
        String ov = section(sizeOut, "Override");
        s.overrideSize = ov == null ? null : firstMatch(P_SIZE, ov);

        String dpiOut = ShellRunner.run("wm density -d " + did);
        s.physDpi = parseInt(section(dpiOut, "Physical"));
        s.overrideDpi = parseInt(section(dpiOut, "Override"));

        s.rotation = currentRotation(ctx, did);
        return s;
    }

    /**
     * 封面屏当前被钉在哪个角度：0..3 是被 lock 住的值；
     * {@link #ROT_FREE} 表示自由（跟传感器走）。
     */
    public static int currentRotation(Context ctx, int did) {
        try {
            String out = ShellRunner.run("cmd window user-rotation -d " + did);
            if (out != null) {
                if (out.contains("free")) {
                    return ROT_FREE;
                }
                Matcher m = P_LOCK.matcher(out);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        } catch (Throwable ignored) {
        }
        // 兜底：直接问屏。⚠️ 屏灭时这个值会停在旧值，只当参考。
        try {
            DisplayManager dm = (DisplayManager) ctx.getApplicationContext()
                    .getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(did);
            return d == null ? ROT_FREE : d.getRotation();
        } catch (Throwable t) {
            return ROT_FREE;
        }
    }

    // ------------------------------------------------------------------ 改分辨率 / DPI

    /**
     * 强制覆盖封面屏的分辨率与 DPI。<b>阻塞</b>。
     *
     * @param dpi 传 0 表示不动 DPI（只改分辨率）
     * @return null 表示成功，否则是给用户看的错误说明
     */
    public static String applySize(Context ctx, int w, int h, int dpi) {
        int did = id(ctx);
        if (did <= 0) {
            return "找不到封面屏";
        }
        if (!ShellRunner.isReady()) {
            return "Shizuku 未就绪";
        }
        if (w < 200 || h < 200) {
            return "分辨率太小（宽高都需 ≥ 200）";
        }
        // 参数顺序是实测结论：size 在前、-d id 在后才会生效
        ShellRunner.run("wm size " + w + "x" + h + " -d " + did);
        if (dpi > 0) {
            ShellRunner.run("wm density " + dpi + " -d " + did);
        }
        return null;
    }

    /**
     * 把封面屏的分辨率 / DPI 恢复成面板原生值。<b>阻塞</b>。
     *
     * @param withDpi 是否连 DPI 一起恢复
     */
    public static String resetSize(Context ctx, boolean withDpi) {
        int did = id(ctx);
        if (did <= 0) {
            return "找不到封面屏";
        }
        if (!ShellRunner.isReady()) {
            return "Shizuku 未就绪";
        }
        ShellRunner.run("wm size reset -d " + did);
        if (withDpi) {
            ShellRunner.run("wm density reset -d " + did);
        }
        return null;
    }

    // ------------------------------------------------------------------ 旋转

    /**
     * 把封面屏顺时针转 90°（0→1→2→3→0）。
     *
     * <p>每次调用都先开一遍 {@code fixed-to-user-rotation}：它是"允许外部钉住这块屏"的
     * 开关，系统没有对外提供读取接口，幂等地写一遍最省事。
     *
     * <b>阻塞</b>
     *
     * @return 新的角度值（0..3）；-1 表示失败
     */
    public static int rotate(Context ctx) {
        int did = id(ctx);
        if (did <= 0 || !ShellRunner.isReady()) {
            return -1;
        }
        int cur = currentRotation(ctx, did);
        // 还没锁过（free，读回 -1）时第一刀**直接给 90°**，别给 0°：
        // 0° 就是封面屏本来的姿态，按下去画面纹丝不动，用户会以为按钮坏了。
        // （这条是从「阿田自用」的命令里对出来的，它 free 分支给的就是 1。）
        int next = (cur < 0 ? 1 : (cur + 1) % 4);
        ShellRunner.run("wm fixed-to-user-rotation -d " + did + " enabled");
        ShellRunner.run("cmd window user-rotation -d " + did + " lock " + next);
        Log.i(TAG, "封面屏旋转 " + cur + " -> " + next);
        return next;
    }

    /** 交还传感器，封面屏恢复自动旋转。<b>阻塞</b> */
    public static boolean resetRotation(Context ctx) {
        int did = id(ctx);
        if (did <= 0 || !ShellRunner.isReady()) {
            return false;
        }
        ShellRunner.run("cmd window user-rotation -d " + did + " free");
        return true;
    }

    // ------------------------------------------------------------------ 刘海（挖孔）避让

    /** 封面屏的原生姿态：面板倒装，所以"用户正着看"就是 180° */
    public static final int ROT_NATIVE = 2;

    /**
     * 让封面屏的内容避开左上角的挖孔（刘海）。<b>阻塞</b>（约 0.3 秒）。
     *
     * <p>两个条件必须<b>同时</b>成立，缺一不可：折叠状态 = {@link DualScreen#TENT}
     * （{@code CLOSED} 下不避让，实测过）；封面屏被钉在 180°（{@link #ROT_NATIVE}）——
     * 0° / 90° / 270° 都不避让。
     *
     * <p>生效时窗口重算把 {@code mAppBounds.top} 从 0 改成 66（= 挖孔带高度），也就是内容
     * <b>真的被排版躲开</b>；不是在上面盖一条黑边（那个方案已被用户否掉）。
     *
     * <p>⚠️ 两条都是<b>覆盖</b>，会一直保持到下次「切换折叠 / 展开」或重启手机。想撤销要连着一起撤
     * （{@code device_state state reset} + lock 收回）—— 单独 reset 状态的话避让会当场消失
     * （窗口重算回 top=0）。
     *
     * @return true = 两条都已下发
     */
    public static boolean avoidCutout(Context ctx) {
        int did = id(ctx);
        if (did <= 0 || !ShellRunner.isReady()) {
            return false;
        }
        DualScreen.setState(DualScreen.TENT);
        // 状态那一刀要让窗口先跟上，紧随其后的 lock 才会被算在 TENT 上
        sleep(250);
        // 顺序不能反：先允许钉住，再钉角度，否则 lock 不落地
        ShellRunner.run("wm fixed-to-user-rotation -d " + did + " enabled");
        ShellRunner.run("cmd window user-rotation -d " + did + " lock " + ROT_NATIVE);
        Log.i(TAG, "封面屏刘海避让已下发：TENT + lock " + ROT_NATIVE);
        return true;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 取出 "Physical size: 748x720" 这类行里的冒号后面那段 */
    private static String section(String out, String key) {
        if (out == null) {
            return null;
        }
        for (String line : out.split("\n")) {
            if (line.trim().startsWith(key)) {
                int i = line.indexOf(':');
                return i < 0 ? null : line.substring(i + 1).trim();
            }
        }
        return null;
    }

    private static String firstMatch(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.find() ? (m.group(1) + "x" + m.group(2)) : null;
    }

    private static int parseInt(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 角度值 → 度数文字 */
    public static String degreeText(int rot) {
        switch (rot) {
            case 0:
                return "0°";
            case 1:
                return "90°";
            case 2:
                return "180°";
            case 3:
                return "270°";
            default:
                return "自动";
        }
    }

    /** 封面屏参数快照 */
    public static final class State {
        public int displayId = -1;
        /** 面板原生分辨率，例如 748x720 */
        public String physSize;
        /** 被覆盖后的分辨率；没有覆盖时为 null */
        public String overrideSize;
        /** 面板原生 DPI */
        public int physDpi;
        /** 被覆盖后的 DPI；0 = 没有覆盖 */
        public int overrideDpi;
        /** 0..3 = 被钉住的角度；{@link #ROT_FREE} = 自由 */
        public int rotation = ROT_FREE;

        public boolean hasSizeOverride() {
            return overrideSize != null;
        }

        public boolean hasDpiOverride() {
            return overrideDpi > 0;
        }

        /** 有没有被改过（分辨率或 DPI 任一） */
        public boolean overridden() {
            return hasSizeOverride() || hasDpiOverride();
        }
    }
}
