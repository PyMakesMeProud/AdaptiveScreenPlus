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
 * 侧边栏「圆环」（用户点名要的第一套样式）。
 *
 * <p>手指按在感应带上那一刻，以<b>按下的那一点</b>为圆心画出三个嵌套的圆环，由内到外分别是三个
 * 指令；手指划进哪一环，那一环就亮起来、放大一点、再轻轻震一下；<b>松手才执行</b>，划回圆心就
 * 当作没选。三档指令来自 {@link ExtPrefs#sidebarRings(Context)}，顺序就是「外 / 中 / 内」，
 * 缺省 外=返回 / 中=后台 / 内=通知 —— 以后要换指令只改这个偏好，不用动这个类。
 *
 * <p><b>选中反馈</b>：选中那一环连带宽带一起向外长 {@link #SEL_GROW}，走一段
 * {@link #SEL_ANIM_MS} 的缓出动画，刚离开的那一环同时缩回去；选中那一环铺
 * {@link #C_ACCENT_FILL}（六成不透明），没选中的两环压到 {@link #C_FILL_DIM}（约 4%），
 * 亮暗差一眼就看得出；每换到另一环（含从圆心第一次划进某一环）震 {@link #BUZZ_MS}ms，
 * 划回圆心不算选中、不震。环线上还加了 {@link #RAT_HYST} 的回滞，手指压在线上不会来回换环
 * （没有它的话，放大 / 震动叠上「换环」就成了闪烁 + 连震）。
 *
 * <p>⚠ 别再往手指位置画那个小圆点当指针 —— 手明明按在屏上，屏上却有个像鼠标指针的东西跟着走，
 * 用户第一眼就说它「很出戏」。选中本身（放大 + 变亮 + 轻震）已经是最好的位置指示。
 *
 * <p><b>为什么「停在哪一环」用半径判，而不是画扇形</b>：圆心就落在屏幕边上，所以三个环各只露出
 * 朝屏内的那一半；半径判法对手指最宽容 —— 只要离圆心够远就是外环，斜着划、往上划都算数，
 * 不用去校角度。
 *
 * <p><b>三个技术点</b>：
 * <ol>
 *   <li>这一层是<b>全屏但 {@code FLAG_NOT_TOUCHABLE}</b> 的窗口：触摸始终由感应带那层收着，
 *       圆环只负责画，这样拖动过程中不需要切换触摸接收方；</li>
 *   <li>窗口的尺寸与旗标跟感应带<b>保持一致</b>（同一个原生坐标空间），否则会踩到封面屏那 66px
 *       的偏移；</li>
 *   <li>这类窗口都由无障碍服务的上下文加（{@code TYPE_ACCESSIBILITY_OVERLAY}）。</li>
 * </ol>
 */
public final class CoverWheel {

    private static final String TAG = "CoverWheel";

    /**
     * 各档位数的环半径（占屏宽，从内环外沿到外环外沿）。
     *
     * <p>行号 = 档数 - 1（1 ~ 4 档）。<b>3 档那一行是 v4.9 起一直在用的值，别动</b>
     * —— 用户是按那一版的观感验收的；其余几行按"每环尽量等宽"推。
     */
    private static final float[][] RAT_BY_N = {
            {0.450f},                                // 1 档
            {0.240f, 0.450f},                        // 2 档
            {0.150f, 0.320f, 0.450f},                // 3 档 ← 不动
            {0.110f, 0.225f, 0.340f, 0.450f},        // 4 档
    };
    /** 手指还没离开圆心到这个比例，就当没选 */
    private static final float RAT_DEAD = 0.040f;
    /**
     * 环线的<b>回滞</b>（占屏宽的比例，v4.10）。
     *
     * <p>为什么需要它：判"在哪一环"只看半径，手指压在环线上轻微抖动就会在两环之间
     * 来回跳 —— 视觉上是一闪一闪，再叠上震动就成了"突突突"。装完后翻真机日志就看到
     * 过 1 秒里 1↔0 翻了五次。加了回滞之后，已经在某一环里时要越过环线这么远才认换环。
     */
    private static final float RAT_HYST = 0.030f;

    /**
     * 亮起来的那一档。
     *
     * <p>v4.11 按用户要求"选中颜色稍微深一些"往下压了一档（0xFF6FB0FF → 0xFF5E9CF0，
     * 明度掉约一格）。幅度刻意很小：再深就发闷，跟没选中的那两环拉不开反差了。
     */
    private static final int C_ACCENT = 0xFF5E9CF0;
    /** 选中那一环的底色。六成不透明 —— "不透明度更高"就是这个数 */
    private static final int C_ACCENT_FILL = 0x99518FE6;
    private static final int C_ACCENT_GLOW = 0x4D5E9CF0;
    /** 没选中的环（有选择时压得更淡，让选中的更跳） */
    private static final int C_FILL = 0x14FFFFFF;
    private static final int C_FILL_DIM = 0x0AFFFFFF;
    private static final int C_EDGE = 0x26FFFFFF;
    private static final int C_TEXT = 0x8CFFFFFF;
    private static final int C_TEXT_ON = 0xFFFFFFFF;
    /** 圆心那圈压暗，保证任何背景上字都看得清 */
    private static final int C_SCRIM_IN = 0x73000000;
    private static final int C_SCRIM_OUT = 0x00000000;

    /** 选中那一环往外长多少（1.0 = 原样）。用户要的"被选中部分呈现放大效果" */
    private static final float SEL_GROW = 0.10f;
    /** 放大 / 缩回的动画时长（ms） */
    private static final long SEL_ANIM_MS = 170L;
    /** 选中切换那一下的震动：短、轻（对照物也是"很轻地一下"） */
    private static final long BUZZ_MS = 12L;
    private static final int BUZZ_AMP = 110;
    /** 两次"选中震动"之间至少隔这么久（ms）—— 万一环线上还是抖，也别连震 */
    private static final long BUZZ_GAP_MS = 60L;

    private static final long FADE_IN_MS = 90L;
    private static final long FADE_OUT_MS = 130L;

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
    private static WheelView view;
    private static float cx;
    private static float cy;
    private static float lastX;
    private static float lastY;
    /** 各环的外沿半径，从内到外（长度 = 档数）；最外那一环 = bounds[bounds.length - 1] */
    private static float[] bounds = {0.150f, 0.320f, 0.450f};
    private static float rOut;
    private static float rDead;
    /** 环线的回滞宽度（px），见 {@link #RAT_HYST} */
    private static float hyst;
    /** 上一次"选中震动"的时刻（uptimeMillis），做最小间隔用 */
    private static long lastBuzzAt;
    /** 0 = 最外面那一环，往里递增（档数不同、环数就不同）；-1 = 还在圆心附近（当作没选） */
    private static int selRing = -1;
    /** 上一次选中的是哪一环 —— 只用来做"刚离开的那一环缩回去"那半段动画 */
    private static int prevRing = -1;
    /** selRing 变动的时刻（uptimeMillis），放大动画按它算进度 */
    private static long selAt;
    private static boolean shown;
    /** 档位指令的缓存：画的时候每帧都要用，别在 onDraw 里读 prefs */
    private static int[] slots = {ExtPrefs.RING_BACK, ExtPrefs.RING_RECENTS,
            ExtPrefs.RING_NOTIFY};

    private CoverWheel() {
    }

    static void env(Context ctx, WindowManager w, int wPx, float den, int sd) {
        displayCtx = ctx;
        wm = w;
        screenW = wPx;
        density = den;
        side = sd;
        setRingCount(slots.length);
        rDead = screenW * RAT_DEAD;
        hyst = screenW * RAT_HYST;
        // 窗口在这里就建好（隐形常驻），别等划动时才加 —— 理由见 ensure()
        ensure();
    }

    /** 按档数换一套环半径。⚠ 档数一变就得重算，别在 onDraw 里算 */
    private static void setRingCount(int n) {
        if (n < 1) {
            n = 1;
        }
        if (n > RAT_BY_N.length) {
            n = RAT_BY_N.length;
        }
        float[] row = RAT_BY_N[n - 1];
        bounds = new float[row.length];
        for (int i = 0; i < row.length; i++) {
            bounds[i] = screenW * row[i];
        }
        rOut = bounds[bounds.length - 1];
    }

    /** 日志里那一串半径 */
    private static String radiiText() {
        StringBuilder b = new StringBuilder();
        for (float r : bounds) {
            if (b.length() > 0) {
                b.append('/');
            }
            b.append((int) r);
        }
        return b.toString();
    }

    static boolean showing() {
        return root != null && shown;
    }

    /**
     * 把窗口建好，隐形放着。
     *
     * <p>⚠ 必须在 install 时就建，<b>不能等划动时才 addView</b>：手指已经落在感应带上、触摸这
     * 一串事件已经被感应带收走之后再加窗口，系统会不会重新派发（发 ACTION_CANCEL 把这一串掐掉）
     * 没有把握。这层是 {@code FLAG_NOT_TOUCHABLE}，按理不该影响触摸目标，但「圆环能不能跟着
     * 手指走」是这整套的关键路径，不赌这个。
     */
    private static void ensure() {
        if (root != null || displayCtx == null || wm == null) {
            return;
        }
        try {
            FrameLayout r = new FrameLayout(displayCtx);
            WheelView v = new WheelView(displayCtx);
            v.setVisibility(View.INVISIBLE);
            r.addView(v, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            // 全屏但**不吃触摸**：触摸这串事件始终留在感应带那层手里
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
            Log.i(TAG, "圆环窗口就位（隐形常驻）：屏宽 " + screenW + "px，半径 "
                    + radiiText() + "px");
        } catch (Throwable t) {
            Log.e(TAG, "建圆环窗口失败", t);
            root = null;
            view = null;
        }
    }

    /** 手指按下：把圆心定在那一点上，窗口先不建（纯点一下不该冒出一堆环） */
    static void prepare(float x, float y) {
        if (displayCtx != null) {
            int[] got = ExtPrefs.sidebarSlots(displayCtx);
            if (got != null && got.length > 0) {
                slots = got;
            }
        }
        setRingCount(slots.length);
        cx = x;
        cy = y;
        lastX = x;
        lastY = y;
        selRing = -1;
        prevRing = -1;
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
        if (view != null) {
            view.setVisibility(View.VISIBLE);
        }
        move(x, y);
        root.animate().cancel();
        root.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        Log.i(TAG, "圆环亮了：圆心 " + (int) cx + "," + (int) cy
                + "，半径 " + radiiText() + "px，" + slots.length + " 档");
    }

    /** 手指动了：更新选中（松手前一直可以改主意） */
    static void move(float x, float y) {
        lastX = x;
        lastY = y;
        int now = ringAt(x, y);
        if (now != selRing) {
            prevRing = selRing;
            selRing = now;
            selAt = SystemClock.uptimeMillis();
            // 划进某一环（含从圆心第一次划进）才震；划回圆心是"取消"，不震
            if (now >= 0) {
                buzz();
            }
            Log.i(TAG, "选中第 " + now + " 环（离圆心 " + (int) radius(x, y) + "px）");
        }
        if (view != null) {
            view.invalidate();
        }
    }

    /** 松手：这一回落在哪一档（返回 {@link ExtPrefs#RING_NONE} 表示没选） */
    static int pick() {
        if (root == null || !shown) {
            return ExtPrefs.RING_NONE;
        }
        int ring = ringAt(lastX, lastY);
        if (ring < 0) {
            return ExtPrefs.RING_NONE;
        }
        return ring >= slots.length ? ExtPrefs.RING_NONE : slots[ring];
    }

    /** 淡掉。⚠ 只淡不拆 —— 窗口留着隐形放着，下次划出来是现成的 */
    static void hide() {
        final FrameLayout r = root;
        final WheelView v = view;
        shown = false;
        selRing = -1;
        prevRing = -1;
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
        Log.i(TAG, "圆环收起");
    }

    /** 侧边栏整个拆掉时调（立刻拆，不等动画） */
    static void release() {
        FrameLayout r = root;
        root = null;
        view = null;
        shown = false;
        selRing = -1;
        prevRing = -1;
        if (r != null && wm != null) {
            try {
                wm.removeViewImmediate(r);
            } catch (Throwable t) {
                Log.w(TAG, "拆圆环窗口失败", t);
            }
        }
        displayCtx = null;
        wm = null;
    }

    /**
     * 选中换了一环那一下轻轻震一下。
     *
     * <p>用系统默认振动器，别自己挑马达；带幅度的接口不是每台机器都有，
     * 没有就退回系统默认幅度。这是"手感"不是"通知"，所以短到 12ms。
     */
    private static void buzz() {
        Context c = displayCtx;
        if (c == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBuzzAt < BUZZ_GAP_MS) {
            return;      // 环线上抖了一下，别连震
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
     * 第几环：<b>0 = 最外面那一环</b>，往里递增；-1 = 还在圆心附近（当作没选）。
     *
     * <p>⚠ 已经选中某一环时，判据按<b>当前那一环的边界</b>算，两侧各留一条
     * {@link #RAT_HYST} 的回滞带 —— 不然手指压在环线上抖一下就会来回换环。
     * 还没选中时没有"当前环"，就按原始环线判。
     */
    private static int ringAt(float x, float y) {
        int n = bounds.length;
        if (n <= 0) {
            return -1;
        }
        float r = radius(x, y);
        // 圆心那圈：已经有选中的时候也要让一点，不然在圆心边缘又会跳
        if (r < (selRing >= 0 ? rDead - hyst * 0.5f : rDead)) {
            return -1;
        }
        // 从内往外数，落在第几环
        int raw = 0;
        for (int k = 0; k <= n - 2; k++) {
            if (r < bounds[k]) {
                raw = n - 1 - k;
                break;
            }
        }
        if (selRing < 0 || raw == selRing) {
            return raw;
        }
        // 换环要真的越过当前那一环的边界 hyst 这么多才算（回滞）
        if (raw < selRing) {
            return r > bounds[n - 1 - selRing] + hyst ? raw : selRing;
        }
        float lo = selRing == n - 1 ? rDead : bounds[n - 2 - selRing];
        return r < lo - hyst ? raw : selRing;
    }

    private static float radius(float x, float y) {
        float dx = x - cx;
        float dy = y - cy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ------------------------------------------------------------------ 画

    /**
     * 圆环本体。
     *
     * <p>各环各画成一个"圆环带"（外圆顺时针 + 内圆逆时针，走同一条 Path，
     * 默认的 WINDING 填充规则会自己把中间挖空）。选中的那一环最后画、且按
     * {@link #SEL_GROW} 放大，"刚离开的那一环"从放大缩回去，两段共用同一条时间线。
     */
    private static final class WheelView extends View {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        WheelView(Context c) {
            super(c);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.2f * density);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(1.9f * density);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            scrim.setShader(new RadialGradient(cx, cy, rOut <= 0f ? 1f : rOut,
                    C_SCRIM_IN, C_SCRIM_OUT, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (rOut <= 0f) {
                return;
            }
            // 选中切换那一下的进度 0..1（缓出）。动画没走完就继续要下一帧。
            float t = 1f;
            if (selRing >= 0 || prevRing >= 0) {
                long dt = SystemClock.uptimeMillis() - selAt;
                t = dt >= SEL_ANIM_MS ? 1f : Math.max(0f, dt / (float) SEL_ANIM_MS);
                if (t < 1f) {
                    postInvalidateOnAnimation();
                }
            }
            float ease = 1f - (1f - t) * (1f - t);
            boolean shrinking = prevRing >= 0 && prevRing != selRing && t < 1f;

            // ① 圆心周围压一层很淡的暗，字压在图标上也读得清
            cv.drawCircle(cx, cy, rOut, scrim);

            // ② 各环带。有选中时没选中的压淡，选中的最后画、压在别的上面
            int idle = selRing >= 0 ? C_FILL_DIM : C_FILL;
            for (int i = bounds.length - 1; i >= 0; i--) {
                if (i == selRing) {
                    continue;
                }
                if (shrinking && i == prevRing) {
                    continue;
                }
                annulus(cv, i, idle, C_EDGE, 1f);
            }
            // 刚离开的那一环：从"放大"缩回去（跟选中的那环同一段时间线）
            if (shrinking) {
                annulus(cv, prevRing, C_FILL_DIM, C_EDGE, 1f + SEL_GROW * (1f - ease));
            }
            if (selRing >= 0) {
                float grow = 1f + SEL_GROW * ease;
                annulus(cv, selRing, C_ACCENT_FILL, C_ACCENT, grow);
                // 选中那圈再罩一层柔光，边也更粗
                edge.setColor(C_ACCENT_GLOW);
                edge.setStrokeWidth(6.5f * density);
                cv.drawCircle(cx, cy, outerOf(selRing) * grow, edge);
                edge.setStrokeWidth(1.2f * density);
            }

            // ③ 每档的字与图，摆在各自环带的中径上（朝屏内那一侧），选中的跟着往外挪
            for (int i = 0; i < bounds.length; i++) {
                int act = i < slots.length ? slots[i] : ExtPrefs.RING_NONE;
                float grow = i == selRing ? 1f + SEL_GROW * ease
                        : (shrinking && i == prevRing ? 1f + SEL_GROW * (1f - ease) : 1f);
                drawGroup(cv, i, act, i == selRing, grow);
            }
        }

        /** 画第 i 环（0 = 最外）的环带；grow 是这一环当前的放大倍数 */
        private void annulus(Canvas cv, int i, int fillColor, int edgeColor, float grow) {
            float inner = innerOf(i) * grow;
            float outer = outerOf(i) * grow;
            path.reset();
            path.addCircle(cx, cy, outer, Path.Direction.CW);
            if (inner > 0.5f) {
                path.addCircle(cx, cy, inner, Path.Direction.CCW);
            }
            fill.setStyle(Paint.Style.FILL);
            fill.setShader(null);
            fill.setColor(fillColor);
            cv.drawPath(path, fill);
            edge.setColor(edgeColor);
            cv.drawCircle(cx, cy, outer, edge);
            if (inner > 0.5f) {
                cv.drawCircle(cx, cy, inner, edge);
            }
        }

        /** 第 i 环（0 = 最外）的内沿半径；最里面那一环是实心的，内沿是 0 */
        private float innerOf(int i) {
            int k = bounds.length - 2 - i;
            return k < 0 ? 0f : bounds[k];
        }

        /** 第 i 环（0 = 最外）的外沿半径 */
        private float outerOf(int i) {
            return bounds[bounds.length - 1 - i];
        }

        /** 一圈的图标 + 名字，摆在朝屏内的中径处；选中的更大更亮 */
        private void drawGroup(Canvas cv, int i, int act, boolean on, float grow) {
            float inner = innerOf(i) * grow;
            float outer = outerOf(i) * grow;
            float mid = inner <= 0f ? outer * 0.5f : (inner + outer) / 2f;
            float dir = side == 1 ? -1f : 1f;
            float gx = cx + dir * mid;
            float gy = cy;
            float s = (on ? 12.5f : 11f) * density;

            glyph.setColor(on ? C_TEXT_ON : C_TEXT);
            glyph.setStrokeWidth((on ? 2.5f : 1.9f) * density);
            drawGlyph(cv, act, gx, gy - 10f * density, s);

            text.setColor(on ? C_TEXT_ON : C_TEXT);
            text.setTextSize((on ? 14.5f : 12.5f) * density);
            cv.drawText(labelOf(act), gx, gy + 23f * density, text);
        }

        /** 四个小图形（手画的，不引资源） */
        private void drawGlyph(Canvas cv, int act, float x, float y, float s) {
            float dir = side == 1 ? -1f : 1f;
            switch (act) {
                case ExtPrefs.RING_BACK:
                    // 箭头朝着"划出去"的方向，一看就知道往哪划
                    path.reset();
                    path.moveTo(x - dir * s * 0.5f, y - s * 0.55f);
                    path.lineTo(x + dir * s * 0.42f, y);
                    path.lineTo(x - dir * s * 0.5f, y + s * 0.55f);
                    cv.drawPath(path, glyph);
                    break;
                case ExtPrefs.RING_RECENTS: {
                    float w = s * 0.6f;
                    float h = s * 1.0f;
                    for (int k = 0; k < 3; k++) {
                        float ox = x + (k - 1) * s * 0.56f;
                        rect.set(ox - w / 2f, y - h / 2f, ox + w / 2f, y + h / 2f);
                        cv.drawRoundRect(rect, s * 0.22f, s * 0.22f, glyph);
                    }
                    break;
                }
                case ExtPrefs.RING_NOTIFY: {
                    rect.set(x - s * 0.5f, y - s * 0.62f, x + s * 0.5f, y + s * 0.2f);
                    path.reset();
                    path.addArc(rect, 180f, 180f);
                    path.moveTo(x - s * 0.5f, rect.centerY());
                    path.lineTo(x - s * 0.5f, y + s * 0.38f);
                    path.lineTo(x + s * 0.5f, y + s * 0.38f);
                    path.lineTo(x + s * 0.5f, rect.centerY());
                    cv.drawPath(path, glyph);
                    Paint p = dot;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(glyph.getColor());
                    cv.drawCircle(x, y + s * 0.66f, s * 0.14f, p);
                    break;
                }
                case ExtPrefs.RING_HOME:
                    path.reset();
                    path.moveTo(x - s * 0.55f, y + s * 0.05f);
                    path.lineTo(x, y - s * 0.6f);
                    path.lineTo(x + s * 0.55f, y + s * 0.05f);
                    cv.drawPath(path, glyph);
                    rect.set(x - s * 0.34f, y + s * 0.05f, x + s * 0.34f, y + s * 0.6f);
                    cv.drawRoundRect(rect, s * 0.14f, s * 0.14f, glyph);
                    break;
                default:
                    // 这一环没配指令：画个空心圈占位
                    cv.drawCircle(x, y, s * 0.5f, glyph);
                    break;
            }
        }

        private String labelOf(int act) {
            switch (act) {
                case ExtPrefs.RING_BACK:
                    return "返回";
                case ExtPrefs.RING_RECENTS:
                    return "后台";
                case ExtPrefs.RING_NOTIFY:
                    return "通知";
                case ExtPrefs.RING_HOME:
                    return "桌面";
                default:
                    return "空";
            }
        }
    }
}
