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
 * 侧边栏「抽奖轮盘」（第三套动画）。
 *
 * <p>往里划够距离之后，屏上出现一个圆盘（商店里那种抽奖转盘）。<b>圆心就落在侧滑栏那条边线上</b>
 * —— 盘的一半在屏幕外，那半边本来也用不上；功能项因此只铺在<b>朝屏内那半边</b>，
 * 按 {@link ExtPrefs#sidebarSlots(Context)} 有几档均分成 1 ~ 4 格（每格 {@code 180 / 档数} 度）。
 * 盘沿外侧钉着一个<b>不动的水平指针</b>，从侧滑栏另一头指向圆心。手指绕圆心划过多少度，
 * 盘就跟着转多少度；<b>松手时指针指着哪一格，就执行哪一档</b>。指针底下换一格就点亮那一格、
 * 朝外弹出来一点、轻轻震一下。
 *
 * <p>⚠ 别跟 {@link CoverWheel}（动力圆环）搞混：那个画的是三个同心环、按<b>半径</b>判档；
 * 这个画的是一个整圆、按<b>指针压在哪一格</b>判档。
 *
 * <p><b>为什么圆心落在边上</b>：圆心贴在侧滑栏那条边线上，半个盘自然落到屏幕外 —— 换来的是一条
 * <b>固定朝向的水平指针</b>：它不必跟着盘走，落点也永远在盘上，手指在屏内怎么划都绕得动盘。
 *
 * <p><b>为什么按 180° 循环铺格子</b>：功能项只占半圈。要是让盘只在 {@code [-90°, +90°]} 里
 * 转，转到头指针就压着空白了。所以铺格子时按 180° 为一周期铺<b>两遍</b>（整圆 2n 片）——
 * 屏内那半边永远是完整的 n 格，转起来格子从一头滑出去、从另一头滑进来，可以一直转下去。
 *
 * <p><b>得先转起来才算数</b>：刚划出来时盘是静的、指针压着第 0 格，这时候松手什么都不做
 * —— 要累计转过 {@link #SPIN_MIN_DEG} 度以上，"停下来指着哪一格"才认（不然"划进来顺手一松"
 * 就会误触某一档）。
 *
 * <p><b>三个技术点</b>（跟 {@link CoverWheel} / {@link CoverGear} 同一套）：
 * <ol>
 *   <li>这一层是全屏但 {@code FLAG_NOT_TOUCHABLE} 的窗口：触摸始终由感应带那层收着，
 *       轮盘只负责画，拖动过程中不需要换触摸接收方；</li>
 *   <li>窗口尺寸与旗标跟感应带保持一致（同一个原生坐标空间），否则会踩到封面屏那 66px 偏移；</li>
 *   <li>{@code TYPE_ACCESSIBILITY_OVERLAY} 只能由无障碍服务上下文加。</li>
 * </ol>
 */
public final class CoverRoulette {

    private static final String TAG = "CoverRoulette";

    // ------------------------------------------------------------------ 几何

    /** 功能项只铺这半圈（度）：另外半圈在屏外，本来也用不上 */
    private static final float SPAN_DEG = 180f;
    /** 盘面半径 = 屏宽 / 屏高里小那个 × 这个比例（整圆画满，一半落在屏外） */
    private static final float RAT_R = 0.40f;
    /** 指针从盘沿往外伸多长（dp）—— 它沿水平方向摆，不占纵向空间 */
    private static final int ARROW_DP = 18;
    /** 中心轴半径（占盘面半径） */
    private static final float RAT_HUB = 0.15f;
    /** 各档的字与图标摆在盘中径的哪个位置（占盘面半径） */
    private static final float RAT_LABEL = 0.62f;
    /** 手指离圆心不到这个半径（占盘面半径）就不参与转动 —— 太近时极角不可信，会乱转 */
    private static final float RAT_DEAD = 0.16f;
    /** 选中那一格朝外"弹"出来多少（占盘面半径） */
    private static final float SEL_POP = 0.045f;
    /** 至少要累计转过这么多度，"停下来指着哪一格"才算数（防误触） */
    private static final float SPIN_MIN_DEG = 18f;
    /** 外圈那条主描边的宽度（dp） */
    private static final float RIM_DP = 3.2f;
    /** 铆钉半径（dp）—— 每个分界线上钉一颗，盘转起来一眼看得出 */
    private static final float STUD_DP = 3.2f;

    // ------------------------------------------------------------------ 手感

    /** 弹出来 / 缩回去的动画时长（ms） */
    private static final long SEL_ANIM_MS = 170L;
    /** 换格那一下的震动：短、轻 */
    private static final long BUZZ_MS = 12L;
    private static final int BUZZ_AMP = 110;
    /** 两次震动之间至少隔这么久（ms）—— 压在分界线上抖一下也别连震 */
    private static final long BUZZ_GAP_MS = 60L;
    private static final long FADE_IN_MS = 90L;
    private static final long FADE_OUT_MS = 130L;

    // ------------------------------------------------------------------ 配色

    /** 外圈与指针：亮蓝，够显眼 */
    private static final int C_RIM = 0xFF4C9BFF;
    private static final int C_RIM_DARK = 0xFF1B5FB4;
    /** 外圈那层柔光，让它从任何底色上跳出来 */
    private static final int C_HALO = 0x4D4C9BFF;
    private static final int C_HUB_HOLE = 0x66000000;
    /**
     * 各格的底色。
     *
     * <p>要求只有一条：<b>相邻两格颜色不能一样</b>。四档之内，随便取相邻的一对都不撞
     * （档数是 3 时首尾两格也不会连成一片）；档数上限本来就是 4，再多就不保证了。
     */
    private static final int[] FILLS = {0x1FFFFFFF, 0x14A8C8FF, 0x0DFFFFFF, 0x1A7AA2FF};
    /** 选中那格的底色（六成不透明）与描边 */
    private static final int C_ACCENT_FILL = 0x99518FE6;
    private static final int C_ACCENT = 0xFF5E9CF0;
    private static final int C_ACCENT_GLOW = 0x4D5E9CF0;
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
    private static int screenH;
    private static float density = 2.125f;
    /** 0 = 贴左边，1 = 贴右边 */
    private static int side = 1;

    // ------------------------------------------------------------------ 状态

    private static FrameLayout root;
    private static RouletteView view;
    private static float cx;
    private static float cy;
    private static float rWheel;
    private static float rHub;
    private static float rDead;
    /** 手指上一次相对圆心的极角（度）；NaN = 上一刻太靠圆心，角度不可信、断开了 */
    private static float lastFingerDeg = Float.NaN;
    /** 盘面转过的角度（度），从 {@link #rotStart} 起算。⚠ 只有 mod 180 有意义（见类注释） */
    private static float rotDeg;
    /** 起始姿态：让第 0 格正好停在指针底下 */
    private static float rotStart;
    /** 累计转过多少度（取绝对值）—— 够 {@link #SPIN_MIN_DEG} 才认"转过" */
    private static float spunDeg;
    /** 指针底下是第几档；-1 = 还没转够，不算选中 */
    private static int sel = -1;
    /** 上一次是第几档 —— 只用来做"刚离开那格缩回去"那半段动画 */
    private static int prevSel = -1;
    /** sel 变动的时刻（uptimeMillis），弹出动画按它算进度 */
    private static long selAt;
    private static long lastBuzzAt;
    private static boolean shown;
    /** 档位指令的缓存：画的时候每帧都要用，别在 onDraw 里读 prefs */
    private static int[] slots = {ExtPrefs.RING_BACK, ExtPrefs.RING_RECENTS,
            ExtPrefs.RING_NOTIFY};
    /** 每格占多少度（= 180 / 档数）—— 档数一变就重算 */
    private static float stepDeg = 60f;

    private CoverRoulette() {
    }

    static void env(Context ctx, WindowManager w, int wPx, int hPx, float den, int sd) {
        displayCtx = ctx;
        wm = w;
        screenW = wPx;
        screenH = hPx;
        density = den;
        side = sd;
        rWheel = Math.min(wPx, hPx) * RAT_R;
        rHub = rWheel * RAT_HUB;
        rDead = rWheel * RAT_DEAD;
        setSlotCount(slots.length);
        // 窗口在这里就建好（隐形常驻），别等划动时才加 —— 理由同 CoverWheel.ensure()
        ensure();
    }

    /**
     * 指针方向：<b>从圆心指向屏内那条水平线</b>（0 = 3 点方向，正数顺时针）。
     *
     * <p>盘贴左边 ⇒ 指针从右边水平指过来（0°）；盘贴右边 ⇒ 从左边指过来（180°）。
     */
    private static float arrowDeg() {
        return side == 1 ? 180f : 0f;
    }

    /**
     * 按档数换一套每格张角与起始姿态。⚠ 档数一变就得重算，别在 onDraw 里算
     *
     * <p>功能项只铺朝屏内那 {@link #SPAN_DEG} 度，所以每格是 {@code 180 / 档数}
     * —— 不是 360。第 0 格的中线要正好落在指针方向上，盘转 {@code 90 - 每格张角/2} 度即可。
     */
    private static void setSlotCount(int n) {
        if (n < 1) {
            n = 1;
        }
        if (n > ExtPrefs.SIDEBAR_ITEM_IDS.length) {
            n = ExtPrefs.SIDEBAR_ITEM_IDS.length;
        }
        stepDeg = SPAN_DEG / n;
        rotStart = normHalf(SPAN_DEG / 2f - stepDeg / 2f);
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
     * 但"轮盘能不能跟着手指转"是这整套的关键路径，不赌这个。
     */
    private static void ensure() {
        if (root != null || displayCtx == null || wm == null) {
            return;
        }
        try {
            FrameLayout r = new FrameLayout(displayCtx);
            RouletteView v = new RouletteView(displayCtx);
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
            Log.i(TAG, "轮盘窗口就位（隐形常驻）：屏 " + screenW + "x" + screenH
                    + "px，盘半径 " + (int) rWheel + "px 中心轴 " + (int) rHub + "px");
        } catch (Throwable t) {
            Log.e(TAG, "建轮盘窗口失败", t);
            root = null;
            view = null;
        }
    }

    /**
     * 手指按下：定圆心。
     *
     * <p>⚠ 这里跟圆环 / 齿轮都不一样 —— 那两个把圆心放在按下那一点（那一点就在屏幕边上），
     * 只朝屏内张半个身子；这个的圆心<b>就落在侧滑栏那条边线上</b>（横向不再看按下那点，
     * 固定贴边），整圆因此有一半在屏外。纵向只做夹取，让整个圆留在屏内。
     */
    static void prepare(float x, float y) {
        if (displayCtx != null) {
            int[] got = ExtPrefs.sidebarSlots(displayCtx);
            if (got != null && got.length > 0) {
                slots = got;
            }
        }
        setSlotCount(slots.length);
        // 圆心钉在那条边线上：从右边滑出来就贴右边缘，从左边滑出来就贴左边缘
        cx = side == 1 ? screenW : 0f;
        cy = clamp(y, rWheel, screenH - rWheel);
        rotDeg = rotStart;
        spunDeg = 0f;
        lastFingerDeg = Float.NaN;
        sel = -1;
        prevSel = -1;
        selAt = 0L;
        shown = false;
    }

    /** 划够距离了：把盘点亮（窗口是 install 时就建好的） */
    static void show(float x, float y) {
        ensure();
        if (root == null || slots.length == 0) {
            return;      // 「面板内容」一项都没勾：不出来
        }
        shown = true;
        if (view != null) {
            view.setVisibility(View.VISIBLE);
            view.invalidate();
        }
        root.animate().cancel();
        root.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        Log.i(TAG, "轮盘亮了：圆心 " + (int) cx + "," + (int) cy + "（贴"
                + (side == 1 ? "右" : "左") + "边缘），半径 " + (int) rWheel + "px，"
                + slots.length + " 档铺在朝屏内那 " + (int) SPAN_DEG + "° 里，每档 "
                + (int) stepDeg + "°；指针水平指向圆心（" + (int) arrowDeg() + "° 方向）");
    }

    /**
     * 手指在盘上划：<b>手指绕过圆心多少度，盘就跟着转多少度</b>。
     *
     * <p>⚠ 差的角要按 {@link #norm180} 归一 —— 手指跨过 ±180 那条缝时，两个极角的差是
     * 350 多度，直接加会让盘突然倒着转一整圈。
     *
     * <p>⚠ 圆心贴着屏边，手指在屏内 ⇒ 极角天然落在朝屏内那半圈里，不会碰到那条缝；
     * 只有手指几乎压在圆心正上方（横向偏移趋 0）时极角才变得极敏感，那一段由
     * {@link #rDead} 挡掉，等划出去再接上。
     */
    static void move(float x, float y) {
        if (radius(x, y) >= rDead) {
            float a = polarDeg(x, y);
            if (!Float.isNaN(lastFingerDeg)) {
                float d = norm180(a - lastFingerDeg);
                rotDeg = norm360(rotDeg + d);
                spunDeg += Math.abs(d);
            }
            lastFingerDeg = a;
        } else {
            // 太靠圆心：极角一跳就是几十度，这一小段不接上，等划出来再说
            lastFingerDeg = Float.NaN;
        }
        int now = spunDeg >= SPIN_MIN_DEG ? slotUnderArrow() : -1;
        if (now != sel) {
            prevSel = sel;
            sel = now;
            selAt = SystemClock.uptimeMillis();
            // 停到某一格上才震；"还没转够"是没选中，不震
            if (now >= 0) {
                buzz();
            }
            Log.i(TAG, now < 0
                    ? "盘还没转够 " + (int) SPIN_MIN_DEG + "°（现在 " + (int) spunDeg + "°），先不算选中"
                    : "指针落到第 " + now + " 档（盘面转 " + (int) rotDeg + "°，累计 "
                            + (int) spunDeg + "°）");
        }
        if (view != null) {
            view.invalidate();
        }
    }

    /** 松手：指针停在哪一格（返回 {@link ExtPrefs#RING_NONE} = 没转够，当没选） */
    static int pick() {
        if (root == null || !shown || spunDeg < SPIN_MIN_DEG) {
            return ExtPrefs.RING_NONE;
        }
        int now = slotUnderArrow();
        return (now < 0 || now >= slots.length) ? ExtPrefs.RING_NONE : slots[now];
    }

    /** 淡掉。⚠ 只淡不拆 —— 窗口留着隐形放着，下次划出来是现成的 */
    static void hide() {
        final FrameLayout r = root;
        final RouletteView v = view;
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
        Log.i(TAG, "轮盘收起");
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
                Log.w(TAG, "拆轮盘窗口失败", t);
            }
        }
        displayCtx = null;
        wm = null;
    }

    /** 停到某一格那一下轻轻震一下（短到 12ms：这是"手感"不是"通知"） */
    private static void buzz() {
        Context c = displayCtx;
        if (c == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBuzzAt < BUZZ_GAP_MS) {
            return;      // 压在分界线上抖了一下，别连震
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
     * 指针底下压着第几档。
     *
     * <p>第 i 档没转时铺在画布角 {@code [arrowDeg - 90 + i * step, ...]} 上；盘转过 rotDeg
     * 之后它跟着挪。于是"指针底下那一档"= 把 {@code 90 - rotDeg} 归一到 {@code [0, 180)}，
     * 再除一格的张角。
     *
     * <p>⚠ 归的是 <b>180 不是 360</b>（{@link #normHalf}）：功能项只占半圈、格子是按 180°
     * 循环铺的，所以盘转过整 180° 之后指针底下还是同一档 —— 这也是"能一直转"的原因。
     */
    private static int slotUnderArrow() {
        int n = slots.length;
        if (n <= 0) {
            return -1;
        }
        int i = (int) Math.floor(normHalf(SPAN_DEG / 2f - rotDeg) / stepDeg);
        if (i < 0) {
            i = 0;
        }
        return i >= n ? n - 1 : i;
    }

    /** 手指相对圆心的极角（度）：0 = 正右，90 = 正下（屏幕向下），顺时针增大 */
    private static float polarDeg(float x, float y) {
        return (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
    }

    private static float radius(float x, float y) {
        float dx = x - cx;
        float dy = y - cy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 归一到一个 0 ~ 360 的角 */
    private static float norm360(float d) {
        d %= 360f;
        return d < 0f ? d + 360f : d;
    }

    /** 归一到一个 -180 ~ 180 的角差（绕过 ±180 那条缝） */
    private static float norm180(float d) {
        d %= 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /** 归一到一个 0 ~ 180 的角 —— 功能项只占半圈，转起来是按 180° 循环的 */
    private static float normHalf(float d) {
        d %= SPAN_DEG;
        return d < 0f ? d + SPAN_DEG : d;
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) {
            return (lo + hi) / 2f;      // 盘比屏还大（不该发生）：退回居中
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------ 画

    private static final class RouletteView extends View {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        /**
         * 柔光那层渐变现在还挂在哪个圆心上。
         *
         * <p>⚠ 圆心是手指按下那一刻才定的，所以在构造里建它只会建在 (0,0) 上 ——
         * 渐变按"离 (0,0) 多远"取色，而这个圆画在屏幕另一头，一个像素都罩不到。
         * 每帧对一下圆心，变了就重建。
         */
        private float scx = Float.NaN;
        private float scy = Float.NaN;
        private float scr = -1f;

        RouletteView(Context c) {
            super(c);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.2f * density);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(1.9f * density);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (!shown || rWheel <= 0f) {
                return;
            }
            final int n = slots.length;
            if (n <= 0) {
                return;
            }
            final int pieces = n * 2;      // 整圆 2n 片（功能项编号按 180° 循环两遍）
            long now = SystemClock.uptimeMillis();

            // 选中切换那一下的进度 0..1（缓出）
            float t = 1f;
            if (sel >= 0 || prevSel >= 0) {
                long d = now - selAt;
                t = d >= SEL_ANIM_MS ? 1f : Math.max(0f, d / (float) SEL_ANIM_MS);
                if (t < 1f) {
                    postInvalidateOnAnimation();
                }
            }
            final float ease = 1f - (1f - t) * (1f - t);
            final boolean shrinking = prevSel >= 0 && prevSel != sel && t < 1f;
            final float popSel = SEL_POP * ease;
            final float popPrev = SEL_POP * (1f - ease);

            // ① 盘底下压一层很淡的暗，字压在图标上也读得清
            if (cx != scx || cy != scy || rWheel != scr) {
                scx = cx;
                scy = cy;
                scr = rWheel;
                scrim.setShader(new RadialGradient(cx, cy, Math.max(1f, rWheel * 1.15f),
                        C_SCRIM_IN, C_SCRIM_OUT, Shader.TileMode.CLAMP));
            }
            cv.drawCircle(cx, cy, rWheel * 1.15f, scrim);

            // ② 各片。屏外那 n 片画了也看不见（Canvas 自己会裁），不用特意跳过。
            //    有选中时没选中的压淡，选中的最后画、压在别的上面
            for (int k = 0; k < pieces; k++) {
                if (isOn(k) || (shrinking && isPrev(k))) {
                    continue;
                }
                slice(cv, k, FILLS[(k % n) % FILLS.length], C_EDGE, 0f);
            }
            if (shrinking) {
                for (int k = 0; k < pieces; k++) {
                    if (isPrev(k)) {
                        slice(cv, k, FILLS[(k % n) % FILLS.length], C_EDGE, popPrev);
                    }
                }
            }
            if (sel >= 0) {
                for (int k = 0; k < pieces; k++) {
                    if (isOn(k)) {
                        slice(cv, k, C_ACCENT_FILL, C_ACCENT, popSel);
                        if (n > 1) {
                            arcGlow(cv, k, C_ACCENT_GLOW, 6.5f * density, popSel);
                        }
                    }
                }
            }

            // ③ 外圈 + 铆钉 + 中心轴
            drawRim(cv, pieces);

            // ④ 各档的字与图，摆在各自那片中径上（跟着盘一起转）。
            //    整块落在屏外的那几片不用画。
            float dir = side == 1 ? -1f : 1f;
            for (int k = 0; k < pieces; k++) {
                int act = slots[k % n];
                float am = (float) Math.toRadians(startDeg(k) + stepDeg * 0.5f);
                float cp = (float) Math.cos(am);
                if (side == 1 ? cp >= 0f : cp <= 0f) {
                    continue;      // 朝屏外，看不见
                }
                float pop = isOn(k) ? popSel : (shrinking && isPrev(k) ? popPrev : 0f);
                float rm = rWheel * RAT_LABEL + pop * rWheel;
                float px = cx + cp * rm;
                float py = cy + (float) Math.sin(am) * rm;
                boolean on = isOn(k);
                float s = (on ? 12.5f : 11f) * density;

                glyph.setColor(on ? C_TEXT_ON : C_TEXT);
                glyph.setStrokeWidth((on ? 2.5f : 1.9f) * density);
                CoverGlyphs.draw(cv, glyph, dot, path, rect, act,
                        px, py - 10f * density, s, dir);

                text.setColor(on ? C_TEXT_ON : C_TEXT);
                text.setTextSize((on ? 14.5f : 12.5f) * density);
                cv.drawText(CoverGlyphs.label(act), px, py + 23f * density, text);
            }

            // ⑤ 指针：从屏内那头水平指过来，钉在盘外不动，永远压在盘上面
            drawArrow(cv);
        }

        /** 第 k 片装着的是不是当前选中那一档 */
        private static boolean isOn(int k) {
            return sel >= 0 && k % slots.length == sel;
        }

        /** 第 k 片装着的是不是"刚离开"那一档 */
        private static boolean isPrev(int k) {
            return prevSel >= 0 && k % slots.length == prevSel;
        }

        /**
         * 第 k 片起始边在画布上的角（0 = 3 点，正 = 顺时针）。
         *
         * <p>起点是"指针方向往回退半圈"（朝屏外那头），再加盘面转角 —— 转角按 180° 循环
         * （{@link #normHalf}），所以整圆 2n 片总能无缝盖满屏内那半圈。
         */
        private static float startDeg(int k) {
            return arrowDeg() - SPAN_DEG / 2f + normHalf(rotDeg) + k * stepDeg;
        }

        /** 画第 k 片（圆盘的一瓣）；pop = 它当前朝外弹出多少（占盘面半径） */
        private void slice(Canvas cv, int k, int fillColor, int edgeColor, float pop) {
            float start = startDeg(k);
            fill.setStyle(Paint.Style.FILL);
            fill.setShader(null);
            fill.setColor(fillColor);
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(edgeColor);
            edge.setStrokeWidth(1.2f * density);

            cv.save();
            if (pop != 0f) {
                float am = (float) Math.toRadians(start + stepDeg * 0.5f);
                cv.translate((float) Math.cos(am) * pop * rWheel,
                        (float) Math.sin(am) * pop * rWheel);
            }
            path.reset();
            path.moveTo(cx, cy);
            rect.set(cx - rWheel, cy - rWheel, cx + rWheel, cy + rWheel);
            path.arcTo(rect, start, stepDeg);
            path.close();
            cv.drawPath(path, fill);
            cv.drawPath(path, edge);
            cv.restore();
        }

        /** 只描第 k 片那条外弧（用来给选中那格罩柔光） */
        private void arcGlow(Canvas cv, int k, int color, float width, float pop) {
            float start = startDeg(k);
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(color);
            edge.setStrokeWidth(width);
            cv.save();
            if (pop != 0f) {
                float am = (float) Math.toRadians(start + stepDeg * 0.5f);
                cv.translate((float) Math.cos(am) * pop * rWheel,
                        (float) Math.sin(am) * pop * rWheel);
            }
            rect.set(cx - rWheel, cy - rWheel, cx + rWheel, cy + rWheel);
            cv.drawArc(rect, start, stepDeg, false, edge);
            cv.restore();
            edge.setStrokeWidth(1.2f * density);
        }

        /** 外圈柔光 + 双色描边 + 每个分界线上钉一颗铆钉 + 中心轴 */
        private void drawRim(Canvas cv, int pieces) {
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(C_HALO);
            edge.setStrokeWidth(9f * density);
            cv.drawCircle(cx, cy, rWheel * 1.01f, edge);

            edge.setColor(C_RIM);
            edge.setStrokeWidth(RIM_DP * density);
            cv.drawCircle(cx, cy, rWheel, edge);
            edge.setColor(C_RIM_DARK);
            edge.setStrokeWidth(1.2f * density);
            cv.drawCircle(cx, cy, rWheel * 0.982f, edge);

            fill.setStyle(Paint.Style.FILL);
            fill.setShader(null);
            fill.setColor(C_RIM);
            for (int k = 0; k < pieces; k++) {
                double a = Math.toRadians(startDeg(k));
                cv.drawCircle(cx + (float) Math.cos(a) * rWheel * 0.945f,
                        cy + (float) Math.sin(a) * rWheel * 0.945f, STUD_DP * density, fill);
            }
            // 中心轴：蓝盘 + 中间压暗一圈，看着像个轴（圆心在屏边，露出半颗）
            cv.drawCircle(cx, cy, rHub, fill);
            fill.setColor(C_HUB_HOLE);
            cv.drawCircle(cx, cy, rHub * 0.45f, fill);
        }

        /**
         * 指针：沿水平方向从屏内那头指过来，尖头顶着盘沿，不跟着盘转。
         *
         * <p>画法是把画布转到 {@link #arrowDeg()} 方向再画一个朝原点的三角
         * —— 这样左右两边共用一套坐标，不用分情况写。
         */
        private void drawArrow(Canvas cv) {
            float tip = rWheel - 4f * density;
            float back = rWheel + dp(ARROW_DP);
            float half = 13f * density;
            cv.save();
            cv.translate(cx, cy);
            cv.rotate(arrowDeg());
            path.reset();
            path.moveTo(tip, 0f);
            path.lineTo(back, -half);
            path.lineTo(back, half);
            path.close();
            fill.setStyle(Paint.Style.FILL);
            fill.setShader(null);
            fill.setColor(C_RIM);
            cv.drawPath(path, fill);
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(C_RIM_DARK);
            edge.setStrokeWidth(1.2f * density);
            cv.drawPath(path, edge);
            cv.restore();
        }
    }
}
