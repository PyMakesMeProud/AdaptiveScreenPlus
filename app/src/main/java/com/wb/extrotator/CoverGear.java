package com.wb.extrotator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 侧边栏「调速齿轮」（第二套动画）。
 *
 * <p>往里划够距离之后，<b>手指按下那一点</b>（也就是感应带所在的位置）原地蹦出一个蓝色的齿轮，
 * 一直转着；齿轮周围朝屏内张开几块扇形（<b>几块由「面板内容」勾了几项决定，1 ~ 4</b>），
 * 由上到下依次是那几档指令（顺序同 {@link ExtPrefs#sidebarSlots(Context)}，跟圆环共用一份）。手指划进哪一块，那一块
 * 就亮起来、放大一点、轻轻震一下，<b>齿轮的转速也跟着换档</b> —— 从上往下越来越快；<b>松手才执行</b>，
 * 划回齿轮附近就当没选。
 *
 * <p><b>为什么用扇形而不是跟着手指画个指针</b>：圆心落在屏幕边上，三块扇形只朝屏内张开，
 * 判据只看<b>角度</b> —— 斜着划、上下划都算数，手不用去凑某个具体位置。
 *
 * <p><b>转速</b>见 {@link #SPEED_BY_N}：慢档是慢慢转，快档是刷刷转，一眼能看出换档了。
 *
 * <p><b>三个技术点</b>（跟 {@link CoverWheel} 同一套）：
 * <ol>
 *   <li>这一层是全屏但 {@code FLAG_NOT_TOUCHABLE} 的窗口：触摸始终由感应带那层收着，
 *       齿轮只负责画，拖动过程中不需要换触摸接收方；</li>
 *   <li>窗口尺寸与旗标跟感应带保持一致（同一个原生坐标空间），否则会踩到封面屏那 66px 偏移；</li>
 *   <li>{@code TYPE_ACCESSIBILITY_OVERLAY} 只能由无障碍服务上下文加。</li>
 * </ol>
 */
public final class CoverGear {

    private static final String TAG = "CoverGear";

    // ------------------------------------------------------------------ 几何

    /** 扇形张开的总张角（度）：以「朝屏内」为 0°，上下各张开这么多 */
    private static final float FAN_HALF = 72f;
    /**
     * 扇形外沿半径：屏宽 / 屏高两个上限取小者（免得感应带在上下段时扇形冲出屏外）。
     *
     * <p>⚠ 用户嫌"三个扇形区域太小"，要求<b>每个区域的面积都扩大 1/2</b> ——
     * 面积 ∝ 半径²，所以这里乘的是 √1.5（0.40 → 0.4899、0.36 → 0.4409）。
     * 别按"半径 ×1.5"改，那样面积会变成 2.25 倍。
     */
    private static final float RAT_R_W = 0.4899f;
    private static final float RAT_R_H = 0.4409f;
    /** 手指离圆心不到这个半径（占扇形半径）就算"还没选" */
    private static final float RAT_DEAD = 0.19f;
    /** 齿轮本体半径（占扇形半径） */
    private static final float RAT_GEAR = 0.28f;
    /** 扇形内沿 = 齿轮半径 × 这个数，别让扇形盖住齿轮 */
    private static final float SECTOR_INNER = 1.30f;
    /** 判"换到哪一块"时，越过边界这么多度才认（不然压在分界线上会来回跳 + 连震） */
    private static final float HYST_DEG = 7f;
    /** 轮齿数 */
    private static final int TEETH = 10;

    // ------------------------------------------------------------------ 手感

    /**
     * 各档位数的转速（度 / 秒）：下标 = 第几块扇形（0 = 最上面那块），从上往下越来越快。
     *
     * <p>用户点名要的：「<b>从上往下的三个区域对应齿轮从慢到快三种不同的旋转速度</b>」。
     * 慢档约 3.6 秒一圈，快档不到半秒一圈 —— 差得够开，一眼就知道换档了。
     */
    private static final float[][] SPEED_BY_N = {
            {300f},                        // 1 档：只有一档，快慢无所谓，给中间值
            {100f, 800f},                  // 2 档
            {100f, 300f, 800f},            // 3 档 ← 用户实测过的那组，别动
            {100f, 300f, 550f, 800f},      // 4 档
    };
    /** 选中那一块往外长多少 */
    private static final float SEL_GROW = 0.07f;
    /** 放大 / 缩回的动画时长（ms） */
    private static final long SEL_ANIM_MS = 170L;
    /** 换块那一下的震动：短、轻 */
    private static final long BUZZ_MS = 12L;
    private static final int BUZZ_AMP = 110;
    /** 两次震动之间至少隔这么久（ms）—— 分界线上抖一下也别连震 */
    private static final long BUZZ_GAP_MS = 60L;
    private static final long FADE_IN_MS = 90L;
    private static final long FADE_OUT_MS = 130L;
    /** 两帧之间最多按这么久算（切后台回来别一下子转一大圈） */
    private static final float MAX_STEP_S = 0.2f;

    // ------------------------------------------------------------------ 配色

    /** 齿轮本体：亮蓝，够显眼（用户点名"为了显眼要做成蓝色"） */
    private static final int C_GEAR = 0xFF4C9BFF;
    private static final int C_GEAR_DARK = 0xFF1B5FB4;
    /** 齿轮外面那圈柔光，让它从任何底色上跳出来 */
    private static final int C_GEAR_HALO = 0x4D4C9BFF;
    private static final int C_GEAR_HUB = 0x66000000;
    /** 选中那块扇形的底色（六成不透明）与描边 */
    private static final int C_ACCENT_FILL = 0x99518FE6;
    private static final int C_ACCENT = 0xFF5E9CF0;
    private static final int C_ACCENT_GLOW = 0x4D5E9CF0;
    private static final int C_FILL = 0x14FFFFFF;
    private static final int C_FILL_DIM = 0x0AFFFFFF;
    private static final int C_EDGE = 0x26FFFFFF;
    private static final int C_TEXT = 0x8CFFFFFF;
    private static final int C_TEXT_ON = 0xFFFFFFFF;
    private static final int C_SCRIM_IN = 0x73000000;
    private static final int C_SCRIM_OUT = 0x00000000;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ 环境（install 时由 CoverSidebar 交过来）

    private static Context displayCtx;
    private static WindowManager wm;
    private static int screenW;
    private static float density = 2.125f;
    /** 0 = 贴左边，1 = 贴右边（决定"往里"是哪个方向） */
    private static int side = 1;

    // ------------------------------------------------------------------ 状态

    private static FrameLayout root;
    private static GearView view;
    private static float cx;
    private static float cy;
    private static float lastX;
    private static float lastY;
    private static float rFan;
    private static float rInner;
    private static float rGear;
    private static float rDead;
    /** 0 上 / 1 中 / 2 下；-1 = 还在齿轮附近（当作没选） */
    private static int sel = -1;
    /** 上一次选中的是哪一块 —— 只用来做"刚离开那块缩回去"那半段动画 */
    private static int prevSel = -1;
    /** sel 变动的时刻（uptimeMillis），放大动画按它算进度 */
    private static long selAt;
    private static long lastBuzzAt;
    /** 齿轮当前角度（度）与上一帧的时刻，逐帧积分出来 */
    private static float rotDeg;
    private static long lastTick;
    private static boolean shown;
    /** 档位指令的缓存：画的时候每帧都要用，别在 onDraw 里读 prefs */
    private static int[] slots = {ExtPrefs.RING_BACK, ExtPrefs.RING_RECENTS,
            ExtPrefs.RING_NOTIFY};
    /** 每块扇形占多少度（= 总张角 / 档数）—— 档数一变就重算 */
    private static float sectorDeg = FAN_HALF * 2f / 3f;
    /** 各档分界线的角度（从 -FAN_HALF 起算，共 档数-1 条）—— 同上 */
    private static float[] bounds = {-24f, 24f};
    /** 当档的转速表 —— 同上 */
    private static float[] speed = {100f, 300f, 800f};

    private CoverGear() {
    }

    static void env(Context ctx, WindowManager w, int wPx, int hPx, float den, int sd) {
        displayCtx = ctx;
        wm = w;
        screenW = wPx;
        density = den;
        side = sd;
        rFan = Math.min(wPx * RAT_R_W, hPx * RAT_R_H);
        setSlotCount(slots.length);
        rGear = rFan * RAT_GEAR;
        rInner = rGear * SECTOR_INNER;
        rDead = rFan * RAT_DEAD;
        // 窗口在这里就建好（隐形常驻），别等划动时才加 —— 理由同 CoverWheel.ensure()
        ensure();
    }

    /** 按档数换一套扇形张角、分界线与转速表。⚠ 档数一变就得重算，别在 onDraw 里算 */
    private static void setSlotCount(int n) {
        if (n < 1) {
            n = 1;
        }
        if (n > SPEED_BY_N.length) {
            n = SPEED_BY_N.length;
        }
        sectorDeg = FAN_HALF * 2f / n;
        bounds = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            bounds[i] = -FAN_HALF + sectorDeg * (i + 1);
        }
        speed = SPEED_BY_N[n - 1];
    }

    /** 日志里那一串转速 */
    private static String speedText() {
        StringBuilder b = new StringBuilder();
        for (float v : speed) {
            if (b.length() > 0) {
                b.append('/');
            }
            b.append((int) v);
        }
        return b.toString();
    }

    static boolean showing() {
        return root != null && shown;
    }

    /**
     * 把窗口建好，隐形放着。
     *
     * <p>⚠ 必须在 install 时就建，不能等划动时才 {@code addView}：手指已经落在感应带上、
     * 这一串触摸已经被感应带收走之后再加窗口，系统会不会重新派发（发 ACTION_CANCEL 掐掉
     * 这一串）没有把握。这一层是 {@code FLAG_NOT_TOUCHABLE}，按理不影响触摸目标，
     * 但「齿轮能不能跟着手指换档」是这整套的关键路径，不赌这个。
     */
    private static void ensure() {
        if (root != null || displayCtx == null || wm == null) {
            return;
        }
        try {
            FrameLayout r = new FrameLayout(displayCtx);
            GearView v = new GearView(displayCtx);
            v.setVisibility(View.INVISIBLE);
            r.addView(v, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            // 全屏但**不吃触摸**：触摸这一串事件始终留在感应带那层手里
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            r.setAlpha(0f);
            wm.addView(r, lp);
            root = r;
            view = v;
            Log.i(TAG, "齿轮窗口就位（隐形常驻）：屏宽 " + screenW + "px，扇形半径 " + (int) rFan
                    + "px 齿轮 " + (int) rGear + "px 齿 " + TEETH + " 个");
        } catch (Throwable t) {
            Log.e(TAG, "建齿轮窗口失败", t);
            root = null;
            view = null;
        }
    }

    /** 手指按下：把圆心定在那一点上（纯点一下不该冒出齿轮） */
    static void prepare(float x, float y) {
        if (displayCtx != null) {
            int[] got = ExtPrefs.sidebarSlots(displayCtx);
            if (got != null && got.length > 0) {
                slots = got;
            }
        }
        setSlotCount(slots.length);
        cx = x;
        cy = y;
        lastX = x;
        lastY = y;
        sel = -1;
        prevSel = -1;
        selAt = 0L;
        shown = false;
    }

    /** 划够距离了：把现成的窗口点亮（窗口是 install 时就建好的） */
    static void show(float x, float y) {
        ensure();
        if (root == null || slots.length == 0) {
            return;      // 「面板内容」一项都没勾：不出来
        }
        shown = true;
        rotDeg = 0f;
        lastTick = SystemClock.uptimeMillis();
        if (view != null) {
            view.setVisibility(View.VISIBLE);
        }
        move(x, y);
        root.animate().cancel();
        root.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        Log.i(TAG, "齿轮亮了：圆心 " + (int) cx + "," + (int) cy
                + "，扇形半径 " + (int) rFan + "px，" + slots.length + " 档转速 "
                + speedText() + " 度每秒");
    }

    /** 手指动了：更新选中（松手前一直可以改主意） */
    static void move(float x, float y) {
        lastX = x;
        lastY = y;
        int now = sectorAt(x, y);
        if (now != sel) {
            prevSel = sel;
            sel = now;
            selAt = SystemClock.uptimeMillis();
            // 划进某一块（含从齿轮附近第一次划进）才震；划回去是"取消"，不震
            if (now >= 0) {
                buzz();
            }
            Log.i(TAG, "选中第 " + now + " 块扇形（离圆心 " + (int) radius(x, y)
                    + "px，角度 " + (int) angleOf(x, y) + "°）=> 转速 "
                    + (int) (now >= 0 && now < speed.length ? speed[now] : speed[0])
                    + " 度每秒");
        }
        if (view != null) {
            view.invalidate();
        }
    }

    /** 松手：落在哪一档（返回 {@link ExtPrefs#RING_NONE} 表示没选） */
    static int pick() {
        if (root == null || !shown) {
            return ExtPrefs.RING_NONE;
        }
        int now = sectorAt(lastX, lastY);
        if (now < 0) {
            return ExtPrefs.RING_NONE;
        }
        return now >= slots.length ? ExtPrefs.RING_NONE : slots[now];
    }

    /** 淡掉。⚠ 只淡不拆 —— 窗口留着隐形放着，下次划出来是现成的 */
    static void hide() {
        final FrameLayout r = root;
        final GearView v = view;
        shown = false;
        sel = -1;
        prevSel = -1;
        if (r == null) {
            return;
        }
        UI.post(() -> {
            r.animate().cancel();
            r.animate().alpha(0f).setDuration(FADE_OUT_MS)
                    .withEndAction(() -> {
                        if (v != null && !shown) {
                            v.setVisibility(View.INVISIBLE);
                        }
                    })
                    .start();
        });
        Log.i(TAG, "齿轮收起");
    }

    /** 侧边栏整个拆掉时调（立刻拆，不等动画） */
    static void release() {
        FrameLayout r = root;
        root = null;
        view = null;
        shown = false;
        sel = -1;
        prevSel = -1;
        if (r != null && wm != null) {
            try {
                wm.removeViewImmediate(r);
            } catch (Throwable t) {
                Log.w(TAG, "拆齿轮窗口失败", t);
            }
        }
        displayCtx = null;
        wm = null;
    }

    /** 换块那一下轻轻震一下（短到 12ms：这是"手感"不是"通知"） */
    private static void buzz() {
        Context c = displayCtx;
        if (c == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBuzzAt < BUZZ_GAP_MS) {
            return;      // 分界线上抖了一下，别连震
        }
        lastBuzzAt = now;
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager)
                        c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) {
                return;
            }
            int amp = v.hasAmplitudeControl() ? BUZZ_AMP : VibrationEffect.DEFAULT_AMPLITUDE;
            v.vibrate(VibrationEffect.createOneShot(BUZZ_MS, amp));
        } catch (Throwable t) {
            Log.w(TAG, "震动失败（不影响选择）", t);
        }
    }

    // ------------------------------------------------------------------ 几何

    /**
     * 手指落在第几块扇形：0 = 最上面那块，往下递增；-1 = 还在齿轮附近（当作没选）。
     *
     * <p>⚠ 已经有选中的那一块时，判据按<b>当前那一块的分界线</b>算，两侧各留一条
     * {@link #HYST_DEG} 的回滞带 —— 不然手指压在分界线上抖一下就会来回换块。
     */
    private static int sectorAt(float x, float y) {
        int n = slots.length;
        if (rFan <= 0f || n <= 0) {
            return -1;
        }
        float r = radius(x, y);
        if (r < (sel >= 0 ? rDead * 0.8f : rDead)) {
            return -1;
        }
        float a = angleOf(x, y);
        int raw = n - 1;
        for (int k = 0; k < n - 1; k++) {
            if (a < bounds[k]) {
                raw = k;
                break;
            }
        }
        if (sel < 0 || raw == sel) {
            return raw;
        }
        // 换块要真的越过当前那一块的分界线 HYST_DEG 这么多才算（回滞）
        if (raw > sel) {
            return a > bounds[sel] + HYST_DEG ? raw : sel;
        }
        return a < bounds[sel - 1] - HYST_DEG ? raw : sel;
    }

    private static float radius(float x, float y) {
        float dx = x - cx;
        float dy = y - cy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 手指相对"朝屏内"那张开的角（度）：0 = 正对着屏内，负 = 偏上，正 = 偏下。
     */
    private static float angleOf(float x, float y) {
        float dx = side == 1 ? cx - x : x - cx;      // 往屏内为正
        float dy = y - cy;                            // 屏幕向下为正
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /** 画的时候统一用"朝屏内 = +x"的局部角：+ 朝右的带子直接就是它，朝左的要翻过来 */
    private static float canvasDeg(float a) {
        return side == 1 ? 180f - a : a;
    }

    // ------------------------------------------------------------------ 画

    private static final class GearView extends View {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        GearView(Context c) {
            super(c);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.2f * density);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(1.9f * density);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            scrim.setShader(new RadialGradient(cx, cy, rFan <= 0f ? 1f : rFan * 1.1f,
                    C_SCRIM_IN, C_SCRIM_OUT, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (!shown || rFan <= 0f) {
                return;
            }

            // ① 齿轮往前转。转速按选中的那一块换档（上慢下快）
            long now = SystemClock.uptimeMillis();
            float dt = (now - lastTick) / 1000f;
            lastTick = now;
            if (dt < 0f) {
                dt = 0f;
            }
            if (dt > MAX_STEP_S) {
                dt = MAX_STEP_S;
            }
            rotDeg = (rotDeg + dt * speedNow()) % 360f;
            postInvalidateOnAnimation();

            // 选中切换那一下的进度 0..1（缓出）
            float t = 1f;
            if (sel >= 0 || prevSel >= 0) {
                long d = now - selAt;
                t = d >= SEL_ANIM_MS ? 1f : Math.max(0f, d / (float) SEL_ANIM_MS);
            }
            float ease = 1f - (1f - t) * (1f - t);
            boolean shrinking = prevSel >= 0 && prevSel != sel && t < 1f;

            // ② 圆心附近压一层很淡的暗，字压在图标上也读得清
            cv.drawCircle(cx, cy, rFan * 1.1f, scrim);

            // ③ 各块扇形。有选中时没选中的压淡，选中的最后画、压在别的上面
            int idle = sel >= 0 ? C_FILL_DIM : C_FILL;
            for (int i = 0; i < slots.length; i++) {
                if (i == sel || (shrinking && i == prevSel)) {
                    continue;
                }
                sector(cv, i, idle, C_EDGE, 1f);
            }
            if (shrinking) {
                sector(cv, prevSel, C_FILL_DIM, C_EDGE, 1f + SEL_GROW * (1f - ease));
            }
            if (sel >= 0) {
                float grow = 1f + SEL_GROW * ease;
                sector(cv, sel, C_ACCENT_FILL, C_ACCENT, grow);
                // 选中那块的弧线再罩一层柔光
                arcStroke(cv, sel, C_ACCENT_GLOW, 6.5f * density, grow);
            }

            // ④ 齿轮本体（转着的）
            drawGear(cv);

            // ⑤ 各档的字与图，摆在各自扇形的中径上
            float dir = side == 1 ? -1f : 1f;
            for (int i = 0; i < slots.length; i++) {
                int act = slots[i];
                float grow = i == sel ? 1f + SEL_GROW * ease
                        : (shrinking && i == prevSel ? 1f + SEL_GROW * (1f - ease) : 1f);
                float am = (float) Math.toRadians(-FAN_HALF + sectorDeg * (i + 0.5f));
                float rm = (rInner + rFan) * 0.5f * grow;
                float gx = cx + dir * (float) Math.cos(am) * rm;
                float gy = cy + (float) Math.sin(am) * rm;
                boolean on = i == sel;
                float s = (on ? 12.5f : 11f) * density;

                glyph.setColor(on ? C_TEXT_ON : C_TEXT);
                glyph.setStrokeWidth((on ? 2.5f : 1.9f) * density);
                CoverGlyphs.draw(cv, glyph, dot, path, rect, act,
                        gx, gy - 10f * density, s, dir);

                text.setColor(on ? C_TEXT_ON : C_TEXT);
                text.setTextSize((on ? 14.5f : 12.5f) * density);
                cv.drawText(CoverGlyphs.label(act), gx, gy + 23f * density, text);
            }
        }

        /** 当前该转多快（度 / 秒） */
        private static float speedNow() {
            return (sel >= 0 && sel < speed.length) ? speed[sel] : speed[0];
        }

        /** 画第 i 块扇形（i：0 上 / 1 中 / 2 下）；grow 是它当前的放大倍数 */
        private void sector(Canvas cv, int i, int fillColor, int edgeColor, float grow) {
            float a0 = -FAN_HALF + sectorDeg * i;
            float in = rInner * grow;
            float out = rFan * grow;
            float start = canvasDeg(a0);
            float sweep = side == 1 ? -sectorDeg : sectorDeg;

            path.reset();
            rect.set(cx - out, cy - out, cx + out, cy + out);
            path.addArc(rect, start, sweep);
            rect.set(cx - in, cy - in, cx + in, cy + in);
            path.arcTo(rect, start + sweep, -sweep);
            path.close();

            fill.setStyle(Paint.Style.FILL);
            fill.setShader(null);
            fill.setColor(fillColor);
            cv.drawPath(path, fill);
            edge.setColor(edgeColor);
            cv.drawPath(path, edge);
        }

        /** 只描第 i 块扇形那两条弧（用来给选中的那块罩柔光） */
        private void arcStroke(Canvas cv, int i, int color, float width, float grow) {
            float a0 = -FAN_HALF + sectorDeg * i;
            float in = rInner * grow;
            float out = rFan * grow;
            float start = canvasDeg(a0);
            float sweep = side == 1 ? -sectorDeg : sectorDeg;
            edge.setColor(color);
            edge.setStrokeWidth(width);
            rect.set(cx - out, cy - out, cx + out, cy + out);
            cv.drawArc(rect, start, sweep, false, edge);
            rect.set(cx - in, cy - in, cx + in, cy + in);
            cv.drawArc(rect, start, sweep, false, edge);
            edge.setStrokeWidth(1.2f * density);
        }

        /**
         * 齿轮本体：一圈轮齿 + 本体圆盘 + 中心孔，整体转到 {@link #rotDeg}。
         *
         * <p>轮齿用圆角矩形绕一圈摆出来（比抠一条多边形路径稳，也不会在低分辨率下毛边）。
         */
        private void drawGear(Canvas cv) {
            float r = rGear;
            float tw = r * 0.30f;
            float th = r * 0.42f;

            // 外面那圈柔光：让它从任何底色上都跳出来
            fill.setStyle(Paint.Style.FILL);
            fill.setShader(null);
            fill.setColor(C_GEAR_HALO);
            cv.drawCircle(cx, cy, r * 1.45f, fill);

            cv.save();
            cv.rotate(rotDeg, cx, cy);

            fill.setColor(C_GEAR);
            edge.setColor(C_GEAR_DARK);
            edge.setStrokeWidth(1.2f * density);
            for (int k = 0; k < TEETH; k++) {
                cv.save();
                cv.rotate(k * (360f / TEETH), cx, cy);
                rect.set(cx - tw / 2f, cy - r - th * 0.30f, cx + tw / 2f, cy - r + th * 0.70f);
                cv.drawRoundRect(rect, tw * 0.30f, tw * 0.30f, fill);
                cv.drawRoundRect(rect, tw * 0.30f, tw * 0.30f, edge);
                cv.restore();
            }
            // 本体圆盘
            fill.setColor(C_GEAR);
            cv.drawCircle(cx, cy, r, fill);
            cv.drawCircle(cx, cy, r, edge);

            // 中心孔：压暗一圈，看着就是个孔
            fill.setColor(C_GEAR_HUB);
            cv.drawCircle(cx, cy, r * 0.38f, fill);
            edge.setColor(C_GEAR);
            edge.setStrokeWidth(1.6f * density);
            cv.drawCircle(cx, cy, r * 0.38f, edge);
            edge.setStrokeWidth(1.2f * density);

            cv.restore();
        }
    }
}
