package com.wb.extrotator;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 「内屏手势」：在内屏自己认全面屏手势，不依赖系统那套。
 *
 * <p>三条<b>隐形</b>热区（没有任何可见元素）：左右边缘往里划 ⇒ 返回；底部上滑 ⇒ 回主界面；
 * 底部上滑并停住 ⇒ 后台。
 *
 * <p><b>为什么能贴上去</b>：应用拿不到别的窗口的触摸，只能用自己的无障碍服务加
 * {@link WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY} 窗口自己收。代价是那一条上的
 * 触摸被吃掉了 —— 所以三条都做得很窄，而且<b>不成手势的触摸会原样重放给下面的应用</b>
 * （见 {@link Band#passThrough()}），否则内屏边缘的按钮和列表侧滑就全废了。
 *
 * <p><b>动作用无障碍的全局动作发</b>（{@link AccessibilityService#performGlobalAction}），
 * 不走 shell、也不用 Shizuku —— 它本来就作用于默认屏（内屏），比 {@code input keyevent} 快得多。
 * 这跟遥控板那条被否掉的路不是一回事：那条是往<b>封面屏</b>注 DPAD 键、还要跟触摸模式斗。
 *
 * <p>画成什么样是另一件事，交给 {@link GestureAnim}（两套风格，见 {@link ExtPrefs#gestureAnimStyle}）：
 * 这里只在手势过门槛 / 走动 / 松手时通知它一声。
 *
 * <p>⚠ 前提是系统导航条已经被「隐藏三键导航」压进沉浸态。导航条还在时，内屏底部那一条会被
 * 系统自己的手势区抢走，底部上滑会时灵时不灵。
 *
 * <p>⚠ 「停住」的判据是 {@code now - lastMoveAt >= HOLD_MS}（手指真的停过），<b>不是</b>
 * {@code now - downAt >= HOLD_MS} —— 后者会把「慢慢划上去回主界面」也判成停滞。
 * 这一条与外屏手势条同源（见 {@link CoverRecentsGesture}）。
 */
public final class InnerGesture {

    private static final String TAG = "InnerGesture";

    /** 左右侧滑热区宽（dp）。窄一点：这一条上的触摸被我们收走，越窄越不碍事 */
    private static final int EDGE_W_DP = 20;
    /** 底部上滑热区高（dp） */
    private static final int BOTTOM_H_DP = 20;
    /**
     * 横屏时热区怎么避开手掌：<b>只让两个下角</b>，上角照常吃手势。
     *
     * <p>⚠ 搭上来的是<b>下角</b>：横屏打游戏时手掌压在左下角，会被底部那条当成「上滑」
     * 直接回主界面（真机日志里一局回了 3 次）。左上的角（返回）和右上的角（返回）
     * 反而常用，那两处不能再留空 —— 四个角全留空之后，横屏手势明显变难触发。
     *
     * <p>底部两端各让掉屏宽的 1/{@link #HOT_BOT_DEN}（中段 3/4 生效）；
     * 左右两条上端顶到屏边、只让掉下端屏高的 1/{@link #HOT_SIDE_BOT_DEN}（占屏高 5/6）。
     * ⭐ 竖屏不避任何角，整条边全范围生效（见 {@code install()} 里那个 {@code landscape} 分支）。
     */
    private static final int HOT_BOT_DEN = 8;
    /** 横屏时左右两条热区只让掉<b>下端</b>：屏高的 1/6，上端留给左上 / 右上的角 */
    private static final int HOT_SIDE_BOT_DEN = 6;
    /** 侧滑往里划够这么多（dp）才认返回。松手才结算，划出去又划回来不算 */
    private static final int BACK_DP = 34;
    /** 底部上滑够这么多（dp）才算数 */
    private static final int SWIPE_DP = 24;
    /** 上滑之后手指真的停住这么久（ms）⇒ 松手开后台 */
    private static final long HOLD_MS = 140L;
    /** 离"上一次认定它还在动"的位置超过这么多 px，才算"还在划" */
    private static final float MOVE_TOL_PX = 8f;
    /** 往下超过这么多（dp）就认定不是上滑（列表回弹） */
    private static final int DOWN_TOL_DP = 8;
    /** 横移是主方向 ⇒ 不是底部上滑，让开 */
    private static final int SIDE_TOL_DP = 12;
    /** 判「点了一下」还是「拖了一下」，决定重放成点击还是拖动 */
    private static final int TAP_SLOP_DP = 8;
    /**
     * 按下之后这么久还没往主方向划（侧滑是往里、底部是往上），就认定不是我们的手势，
     * 当场把窗口让开、把这一次触摸还给下面的应用。
     *
     * <p>⚠ 游戏摇杆就是这么用的：按住不动、或者小幅晃。等松手再重放会把「按住 6 秒」
     * 压成「点一下」，摇杆就断了 —— 所以必须在这里就让开，不能等 UP。
     */
    private static final long LIVE_DECIDE_MS = 70L;
    /** 往主方向划够这么多（dp）就认定是我们的手势，不再考虑让开 */
    private static final int LIVE_ARM_DP = 3;
    /** 让开之后迟迟收不到 CANCEL/UP 时的兜底复位（ms），免得热区永久不吃触摸 */
    private static final long LIVE_GUARD_MS = 3000L;
    /**
     * 底部上滑的动画走到"完全模糊"要划到屏高的这个比例。
     *
     * <p>⚠ 这是<b>观感</b>参数，跟触发门槛（{@link #SWIPE_DP}）没关系：门槛一够动画就起步，
     * 之后按这个比例慢慢加深，划到 70% 屏高才最糊。
     * 外屏手势条是 40%（{@code CoverBlur.FULL_AT_H}）—— 用户说"内屏和外屏不一样"，
     * 这边刻意做得更慢，不是对齐外屏。
     * ⚠ 原来按 dp 写死（130dp = 内屏高 20%）在别的屏上会走样，所以改成按屏高折算。
     */
    private static final float HOME_FULL_AT = 0.70f;
    /** 侧滑里划出这么多（dp）才开始画动画 —— 手指刚碰上去不该弹箭头 */
    private static final int ANIM_ARM_DP = 6;
    /** 触发时的震动（与外屏手势条同一套手感） */
    private static final long BUZZ_MS = 22L;
    private static final int BUZZ_AMP = 140;
    /** 一次触发之后的冷却：这段时间里的触摸不判、也不穿透 */
    private static final long COOLDOWN_MS = 650L;
    /** 穿透重放：先让窗口不吃触摸、等它传下去再发第一个事件 */
    private static final long REPLAY_ARM_MS = 60L;
    private static final long REPLAY_STEP_MS = 24L;
    private static final long REPLAY_RESTORE_MS = 90L;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static WindowManager wm;
    /** 触发时要拿它发全局动作，顺便当"服务还在不在"的判据 */
    private static AccessibilityService svc;
    private static float density = 3f;
    private static int screenW;
    private static int screenH;

    /** 底部 + 左 + 右三条。顺序即加窗顺序，互不重叠（边缘避开底部那一段） */
    private static Band[] bands;

    private InnerGesture() {
    }

    /** 有没有装上（界面拿它显示状态） */
    public static boolean installed() {
        return wm != null && bands != null;
    }

    /**
     * 按当前设置对齐：开关开着 ⇒ 装上；否则摘掉。
     *
     * <p>服务连上、开关被拨动，都调这一个。
     */
    public static void sync(Context ctx) {
        boolean want = ExtPrefs.innerGesture(ctx);
        Log.d(TAG, "sync want=" + want + " 当前已装=" + installed());
        if (!want) {
            remove();
            return;
        }
        if (installed()) {
            return;
        }
        install(ctx);
    }

    /** 参数变了 ⇒ 拆了重装（三条带的尺寸都写死在安装那一刻） */
    public static void refresh(Context ctx) {
        remove();
        sync(ctx);
    }

    /** 屏幕转了要重建（三条带的 x/y、动画层的宽高都是装载那一刻算死的） */
    private static DisplayManager.DisplayListener displayListener;

    /** 旋转稳下来之后的重建。延迟一点是因为旋转中间态会连着报好几次 */
    private static final Runnable REBUILD = () -> {
        AccessibilityService s = svc;
        if (s != null) {
            Log.i(TAG, "整屏尺寸变了，按新尺寸重建三条带与动画层");
            refresh(s);
        }
    };

    /**
     * 盯住整屏尺寸。
     *
     * <p>⚠ 三条带的位置是装载那一刻按整屏算死的（右边缘那条贴在 {@code screenW - edgeW}）。
     * 屏幕一转，竖屏算出来的坐标就全错位：右边缘那条浮到屏幕中间去 —— 真正的右边缘没有热区、
     * 反过来划不触发，动画也从中间冒出来。所以尺寸一变就拆了重装。
     */
    private static void watchDisplay(final AccessibilityService s) {
        if (displayListener != null) {
            return;
        }
        DisplayManager dm = (DisplayManager) s.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return;
        }
        DisplayManager.DisplayListener l = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY) {
                    return;
                }
                // 亮度、刷新率变化也走这个回调 —— 尺寸没变就什么都别做
                if (!sizeChanged(s)) {
                    return;
                }
                // ⚠ 不能在回调里同步摘掉自己，攒到主线程里延迟执行
                UI.removeCallbacks(REBUILD);
                UI.postDelayed(REBUILD, 350L);
            }
        };
        try {
            dm.registerDisplayListener(l, UI);
            displayListener = l;
        } catch (Throwable t) {
            Log.w(TAG, "盯屏幕尺寸失败（转了不会自动重建）", t);
        }
    }

    /** 整屏尺寸跟装载时对不上了？ */
    private static boolean sizeChanged(Context ctx) {
        try {
            DisplayManager dm =
                    (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) {
                return false;
            }
            Point pt = new Point();
            d.getRealSize(pt);
            return pt.x > 0 && pt.y > 0 && (pt.x != screenW || pt.y != screenH);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 不盯了。服务断开时必须调，否则 listener 一直挂着 */
    private static void unwatchDisplay(Context ctx) {
        DisplayManager.DisplayListener l = displayListener;
        displayListener = null;
        if (l == null || ctx == null) {
            return;
        }
        try {
            DisplayManager dm =
                    (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                dm.unregisterDisplayListener(l);
            }
        } catch (Throwable t) {
            Log.w(TAG, "取消盯屏幕尺寸失败", t);
        }
    }

    private static void install(Context ctx) {
        if (!(ctx instanceof AccessibilityService)) {
            // TYPE_ACCESSIBILITY_OVERLAY 这一层只有无障碍服务自己能加
            Log.w(TAG, "不是无障碍服务上下文，装不了内屏手势");
            return;
        }
        try {
            final AccessibilityService s = (AccessibilityService) ctx;
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) {
                Log.w(TAG, "拿不到默认屏");
                return;
            }
            Context displayCtx = ctx.createDisplayContext(d);
            WindowManager w = (WindowManager) displayCtx.getSystemService(Context.WINDOW_SERVICE);
            if (w == null) {
                return;
            }
            density = displayCtx.getResources().getDisplayMetrics().density;

            // ⚠ 尺寸要按「整块屏」算，不是 app 区 —— 空白屏的资源度量少了系统栏那几十 px
            //（RULES §64.1 那条铁律，外屏上差 66px）。窗口用 FLAG_LAYOUT_IN_SCREEN 落在显示空间里，
            // 坐标也照整屏给，否则底部那一条会浮在导航区上方一截。
            DisplayMetrics met = displayCtx.getResources().getDisplayMetrics();
            screenW = met.widthPixels;
            screenH = met.heightPixels;
            try {
                Point pt = new Point();
                d.getRealSize(pt);
                if (pt.x > 0 && pt.y > 0) {
                    screenW = pt.x;
                    screenH = pt.y;
                }
            } catch (Throwable t) {
                Log.w(TAG, "取整屏尺寸失败，退回资源度量", t);
            }

            final int edgeW = Math.max(1, dp(EDGE_W_DP));
            final int botH = Math.max(1, dp(BOTTOM_H_DP));
            // 竖屏：一条边都不让，整条边全范围生效。
            // 横屏：只让开两个下角 —— 手掌搭上来的就是那儿（真机日志里一局误触了 3 次回主界面）。
            //   左上的角是「返回」、右上的角也是「返回」，那两处照常吃手势，所以左右两条上端顶到屏边。
            //   ⭐ 取代了「每条边只占中段 2/3」：那版四个角全留空，横屏时手势明显变难触发。
            final boolean landscape = screenW > screenH;
            final int botInset = landscape ? Math.max(1, screenW / HOT_BOT_DEN) : 0;
            final int sideBottomInset = landscape ? Math.max(1, screenH / HOT_SIDE_BOT_DEN) : 0;
            final int edgeH = Math.max(1, screenH - sideBottomInset);
            final int botW = Math.max(1, screenW - botInset * 2);

            bands = new Band[]{
                    new Band(true, false),      // 底部：上滑 / 上滑停顿
                    new Band(false, true),      // 左边：往里划返回
                    new Band(false, false),     // 右边：往里划返回
            };
            final int[] xs = {botInset, 0, Math.max(0, screenW - edgeW)};
            final int[] ys = {Math.max(0, screenH - botH), 0, 0};
            final int[] ws = {botW, edgeW, edgeW};
            final int[] hs = {botH, edgeH, edgeH};

            wm = w;
            svc = s;
            for (int i = 0; i < bands.length; i++) {
                bands[i].attach(displayCtx, w, xs[i], ys[i], ws[i], hs[i]);
            }
            // ⚠ 动画层<b>最后</b>加：同类窗口后加的在上，它才压在三条带上面（它自己不吃触摸）
            GestureAnim.install(displayCtx, w, s, screenW, screenH, density);
            Log.i(TAG, "内屏手势已装：屏 " + screenW + "x" + screenH + "px，边缘 " + edgeW
                    + "px 宽、底部 " + botH + "px 高；热区 "
                    + (landscape ? ("横屏只避两个下角（底部两端各让 " + botInset
                    + "px、左右下端让 " + sideBottomInset + "px）") : "竖屏全范围")
                    + "，density " + density);
            watchDisplay(s);
        } catch (Throwable t) {
            Log.e(TAG, "装内屏手势失败", t);
            remove();
        }
    }

    /**
     * 把三条带全摘掉。
     *
     * <p>public 是因为无障碍服务断开时要调 —— 服务没了它就没有主人，留在屏上只会白白吃掉
     * 内屏边缘和底部的触摸。
     */
    public static void remove() {
        WindowManager w = wm;
        Band[] bs = bands;
        AccessibilityService s = svc;
        wm = null;
        bands = null;
        svc = null;
        unwatchDisplay(s);
        UI.removeCallbacksAndMessages(null);
        // ⚠ 要放在上面那个 return <b>之前</b>：bands 是空的时候动画层也可能在（顺序反了就漏摘）
        GestureAnim.remove();
        if (bs == null) {
            return;
        }
        for (Band b : bs) {
            b.detach(w);
        }
        Log.i(TAG, "内屏手势已摘掉");
    }

    /** 发一个无障碍全局动作。比 shell 快，也不用 Shizuku */
    private static void go(int action, String what) {
        AccessibilityService s = svc;
        if (s == null) {
            Log.w(TAG, what + "：无障碍服务不在了");
            return;
        }
        try {
            boolean ok = s.performGlobalAction(action);
            Log.i(TAG, what + " => performGlobalAction(" + action + ") 返回 " + ok);
        } catch (Throwable t) {
            Log.w(TAG, what + "：发全局动作报错", t);
        }
    }

    /**
     * 触发的那一下震一下。用系统默认振动器，带幅度的那版在有些机器上会抛异常。
     * 跟外屏手势条同一份做法（{@link CoverRecentsGesture}）。
     */
    private static void buzz() {
        Context c = svc;
        if (c == null) {
            return;
        }
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
            Log.w(TAG, "震动失败（不影响手势）", t);
        }
    }

    private static int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------ 一条带

    /**
     * 一条捕获带。底部那条认上滑 / 上滑停顿，左右两条认往里划。
     *
     * <p>每条带自己一套状态：一次触摸只会落在一条带上（三条互不重叠），
     * 但多点触控可能同时在两条带上，所以状态不能共用。
     */
    private static final class Band {

        /** 底部带（认上滑） */
        final boolean bottom;
        /** 左边带（认"往右划"）；底部带时无意义 */
        final boolean left;

        View view;
        WindowManager.LayoutParams lp;
        /** 窗口左上角（重放要把窗口内坐标换成屏幕坐标） */
        int winX;
        int winY;

        float downX;
        float downY;
        float stillRefX;
        float stillRefY;
        /** 主方向的走量：底部 = 往上多少 px；两侧 = 往里多少 px */
        float peak;
        /** 次方向（底部看横移、两侧看纵移），只用来判"是不是我们的手势" */
        float cross;
        float maxDisp;
        long downAt;
        long lastMoveAt;
        long firedAt;

        boolean reached;
        /** 这一手的动画起了没有（手指没走够 ANIM_ARM_DP 之前不画） */
        boolean armed;
        /**
         * 这一次手势里真的停够过没有。
         *
         * <p>⚠ 置上就<b>不再抹掉</b>：抬手指那一下接触面在变、坐标会跳，
         * 拿它去清这个标记，真停顿就全白停了。
         */
        boolean holdOk;
        /** 中途被判成"不是我们的手势"了，后面一律不管 */
        boolean cancelled;
        boolean fired;
        /** 这一次触摸不许穿透（刚触发完的冷却期里别替用户点到下面的东西） */
        boolean passOff;
        /** 正在把这一次触摸重放给下面的应用 ⇒ 期间收到的一律当回放副本，不判手势 */
        boolean replaying;
        /**
         * 已经把窗口让开、这一次触摸交给下面的应用了。
         *
         * <p>⚠ 跟 {@link #passThrough()} 是两条路：那个是松手<b>之后</b>一次性重放，
         * 会把「按住」压成「点一下」；这个是在判定「不是我们的手势」的<b>那一刻</b>就让开，
         * 让系统把触摸原生转给下面的应用 —— 不重放、不注入，手势流是连续的。
         */
        boolean live;

        final float[] px = new float[9];
        final float[] py = new float[9];
        int n;

        Band(boolean bottom, boolean left) {
            this.bottom = bottom;
            this.left = left;
        }

        /**
         * 按下之后一直没动（连 MOVE 都不来）时的兜底判定：到点还留着就让开。
         *
         * <p>⚠ 只靠 MOVE 里那次判定有个洞：手掌按住完全不动时系统<b>不发 MOVE</b>，
         * 这一次触摸会被我们一直捏到抬手 —— 抬手的重放还会在手掌位置补一次点击。
         */
        final Runnable decideFired = () -> {
            if (fired || cancelled || live || replaying) {
                return;
            }
            // ⚠ 判据与 MOVE 里那一条完全一致 —— 两处都走 mainDispAt()，同一份实现。
            //  别图省事去看 armed —— 那是"起动画"的档（侧滑 18px、底部 72px），严得多，
            //  正常慢划会被这里提前判掉。
            final float lx = n > 0 ? px[n - 1] : downX;
            final float ly = n > 0 ? py[n - 1] : downY;
            final float mainDisp = mainDispAt(lx, ly);
            if (mainDisp >= dp(LIVE_ARM_DP)) {
                return;
            }
            cancelled = true;
            GestureAnim.cancel();
            Log.d(TAG, side() + "：按住没动（" + LIVE_DECIDE_MS + "ms，" + (int) mainDisp
                    + "px）⇒ 让开还给应用");
            beginLive();
        };

        /** 底部带停够了：只记下来 + 震一下，命令等松手再发 */
        final Runnable holdFired = () -> {
            if (fired || cancelled || !reached || holdOk) {
                return;
            }
            holdOk = true;
            Log.i(TAG, "底部：停顿达成（" + HOLD_MS + "ms 没动）=> 松手开后台");
            // 停住了 ⇒ 卡片停在"后台"那一档
            GestureAnim.progress(1f);
            buzz();
        };

        void attach(Context displayCtx, WindowManager w, int x, int y, int width, int height) {
            FrameLayout v = new FrameLayout(displayCtx);
            v.setOnTouchListener((vv, e) -> onTouch(e));

            // ⚠⚠ 必须给 FLAG_SPLIT_TOUCH，否则这一条会吞掉整串手势。
            //  官方原文：「未设置此标志时，第一个按下的指针决定了之后所有触摸都去哪个窗口，
            //  直到所有指针抬起」。手掌先落在带上 ⇒ 搓摇杆的那根手指也被一起判给我们 ⇒
            //  摇杆直到全部抬手都收不到事件，这就是用户说的"断触"（MyGesture 不断触正因它不吞整串）。
            //  加上之后每个指针各按自己落下的位置定窗口，我们只收落在自己身上的那根，互不干扰。
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    width,
                    height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // 不吃焦点（不抢键盘）＋ 带外触摸一律放行
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            // ⚠⚠ 必须先给 gravity：不给的话系统按"居中"摆放，{@code x / y} 会变成
            // **相对屏幕中心的偏移**，而不是左上角坐标 —— 贴在左边那条会被摆到屏幕正中
            //（真左边缘反倒没有热区，怎么划都不返回），右边那条只是被夹回边界才"看起来对"。
            //  实证：左带 (0,0,60x1860) 实测落在 frame=[510,94]，510 = 屏幕正中。
            //  另加 FLAG_LAYOUT_NO_LIMITS 抵掉系统条的 inset（上面那个 94 就是顶部 inset）。
            p.gravity = Gravity.TOP | Gravity.START;
            // 有了 gravity，x / y 才是整屏左上角坐标（左 / 右 / 底三条各自贴自己那一侧）
            p.x = x;
            p.y = y;

            try {
                w.addView(v, p);
            } catch (Throwable t) {
                Log.e(TAG, "加带失败（" + side() + "）", t);
                return;
            }
            view = v;
            lp = p;
            winX = x;
            winY = y;
            // 直注这条路第一次要探（会碰 Shizuku 与几个 @hide 反射），先丢后台线程探好
            new Thread(() -> TouchInject.ready(), "inner-passthru-warm").start();
        }

        void detach(WindowManager w) {
            View v = view;
            view = null;
            lp = null;
            replaying = false;
            passOff = false;
            if (v != null && w != null) {
                try {
                    w.removeViewImmediate(v);
                } catch (Throwable t) {
                    Log.w(TAG, "摘带失败（" + side() + "）", t);
                }
            }
        }

        String side() {
            return bottom ? "底部" : (left ? "左边" : "右边");
        }

        boolean onTouch(MotionEvent e) {
            final long now = System.currentTimeMillis();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (replaying || live) {
                        return true;
                    }
                    downX = e.getX();
                    downY = e.getY();
                    stillRefX = downX;
                    stillRefY = downY;
                    peak = 0;
                    cross = 0;
                    maxDisp = 0;
                    live = false;
                    n = 0;
                    record(downX, downY);
                    downAt = now;
                    lastMoveAt = now;
                    reached = false;
                    armed = false;
                    holdOk = false;
                    fired = false;
                    UI.removeCallbacks(holdFired);
                    UI.removeCallbacks(decideFired);
                    // 上一手的动画多半还在淡出，先清干净
                    GestureAnim.cancel();
                    // 冷却期里的触摸既不判手势、也不穿透 —— 画面还在切，别替用户点到底下的东西
                    passOff = now - firedAt < COOLDOWN_MS;
                    if (passOff) {
                        cancelled = true;
                        Log.d(TAG, side() + "：冷却中（距上次 " + (now - firedAt) + "ms），不判、直接让开");
                        beginLive();
                        return true;
                    }
                    cancelled = false;
                    // 到点还没动就让开（手指完全静止时不会来 MOVE，光靠 MOVE 里那次判定会漏）
                    UI.postDelayed(decideFired, LIVE_DECIDE_MS);
                    // 带内必须先收下，否则拿不到后面的 MOVE
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (replaying) {
                        return true;
                    }
                    // 已经让开了：这一串触摸归下面的应用，我们只看不动
                    if (live) {
                        return true;
                    }
                    if (cancelled) {
                        // 已经判成"不是我们的手势"了，但轨迹还得继续记 —— 松手时要原样重放
                        record(e.getX(), e.getY());
                        return true;
                    }
                    // 动画层要知道手指现在在哪：侧滑那条弧是贴着手指的高度画的
                    GestureAnim.at(winX + e.getX(), winY + e.getY());
                    final float dx = e.getX() - downX;
                    final float dy = e.getY() - downY;
                    final float disp = (float) Math.hypot(dx, dy);
                    if (disp > maxDisp) {
                        maxDisp = disp;
                    }
                    record(e.getX(), e.getY());

                    // 按下之后一直没往主方向划 ⇒ 不是我们的手势，当场让开还给下面的应用。
                    // ⚠ 必须在这里就让开：等松手再重放，游戏摇杆那种「按住不动」
                    // 会被压成一次点击（打王者断触的根因就是这个）。
                    final float mainDisp = mainDispAt(e.getX(), e.getY());
                    if (now - downAt >= LIVE_DECIDE_MS && mainDisp < dp(LIVE_ARM_DP)) {
                        cancelled = true;
                        GestureAnim.cancel();
                        Log.d(TAG, side() + "：按住没往主方向划（" + (int) mainDisp + "px，"
                                + (now - downAt) + "ms）⇒ 让开还给应用");
                        beginLive();
                        return true;
                    }

                    if (bottom) {
                        final float up = -dy;
                        if (up > peak) {
                            peak = up;
                        }
                        if (Math.abs(dx) > cross) {
                            cross = Math.abs(dx);
                        }
                        // ① 往下拖（列表回弹）或横着划 ⇒ 不是我们的手势，让开
                        if (up < -dp(DOWN_TOL_DP) || (cross > dp(SIDE_TOL_DP) && cross > peak)) {
                            cancelled = true;
                            UI.removeCallbacks(holdFired);
                            GestureAnim.cancel();
                            Log.d(TAG, "底部：放弃（上滑 " + (int) up + "px 横移 " + (int) cross + "px）⇒ 让开");
                            beginLive();
                            return true;
                        }
                        // ② 还没够高度：什么都不做（尤其别起停滞计时）
                        if (peak < dp(SWIPE_DP)) {
                            return true;
                        }
                        if (!reached) {
                            // 刚够格：起停滞计时，位置基准重设成此刻
                            reached = true;
                            holdOk = false;
                            stillRefX = e.getX();
                            stillRefY = e.getY();
                            lastMoveAt = now;
                            UI.removeCallbacks(holdFired);
                            UI.postDelayed(holdFired, HOLD_MS);
                            // 动画从这一刻开始（原生档那条要趁手指还没走远抓一帧）
                            armed = true;
                            GestureAnim.begin(GestureAnim.KIND_HOME);
                            GestureAnim.progress(up / (screenH * HOME_FULL_AT));
                            return true;
                        }
                        // ③ 离"上次认定它动了"的位置够远 ⇒ 算还在划，停滞计时往后推
                        if (Math.abs(e.getY() - stillRefY) > MOVE_TOL_PX
                                || Math.abs(e.getX() - stillRefX) > MOVE_TOL_PX) {
                            stillRefX = e.getX();
                            stillRefY = e.getY();
                            lastMoveAt = now;
                            UI.removeCallbacks(holdFired);
                            UI.postDelayed(holdFired, HOLD_MS);
                        }
                        GestureAnim.progress(up / (screenH * HOME_FULL_AT));
                        return true;
                    }

                    // 两侧：往里划才算（左带 = 往右，右带 = 往左）
                    final float inward = left ? dx : -dx;
                    if (inward > peak) {
                        peak = inward;
                    }
                    if (Math.abs(dy) > cross) {
                        cross = Math.abs(dy);
                    }
                    // 纵向是主方向 ⇒ 用户是在边缘上下滑列表，让开
                    if (cross > peak) {
                        cancelled = true;
                        GestureAnim.cancel();
                        Log.d(TAG, side() + "：放弃（纵向 " + (int) cross + "px 往里 " + (int) peak + "px）⇒ 让开");
                        beginLive();
                        return true;
                    }
                    // 往里划出一点才起动画：手指刚碰上去那一下不该弹箭头
                    if (inward >= dp(ANIM_ARM_DP)) {
                        if (!armed) {
                            armed = true;
                            GestureAnim.begin(left ? GestureAnim.KIND_BACK_L : GestureAnim.KIND_BACK_R);
                        }
                        GestureAnim.progress(inward / dp(BACK_DP));
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    UI.removeCallbacks(holdFired);
                    UI.removeCallbacks(decideFired);
                    if (live) {
                        unhookLive();
                        reset();
                        return true;
                    }
                    if (bottom) {
                        if (!cancelled && reached && holdOk) {
                            fired = true;
                            firedAt = now;
                            Log.i(TAG, "底部上滑停顿（上滑 " + (int) peak + "px，共 "
                                    + (now - downAt) + "ms）=> 后台");
                            GestureAnim.end(true);
                            buzz();
                            go(AccessibilityService.GLOBAL_ACTION_RECENTS, "后台");
                            reset();
                            return true;
                        }
                        if (!cancelled && reached && (-(e.getY() - downY)) >= dp(SWIPE_DP) * 0.5f) {
                            fired = true;
                            firedAt = now;
                            Log.i(TAG, "底部上滑未见停顿（上滑 " + (int) peak + "px，共 "
                                    + (now - downAt) + "ms）=> 回主界面");
                            GestureAnim.end(true);
                            buzz();
                            go(AccessibilityService.GLOBAL_ACTION_HOME, "回主界面");
                            reset();
                            return true;
                        }
                        Log.d(TAG, "底部不触发：够格=" + reached + " 停过=" + holdOk
                                + " 放弃=" + cancelled + " 上滑 " + (int) peak + "px 位移 "
                                + (int) maxDisp + "px 共 " + (now - downAt) + "ms");
                    } else if (!cancelled && peak >= dp(BACK_DP)) {
                        // 松手才结算：划出去又划回来不算，误触比"跟手"更要紧
                        fired = true;
                        firedAt = now;
                        Log.i(TAG, side() + "侧滑 " + (int) peak + "px（门槛 " + dp(BACK_DP)
                                + "px，共 " + (now - downAt) + "ms）=> 返回");
                        GestureAnim.end(true);
                        buzz();
                        go(AccessibilityService.GLOBAL_ACTION_BACK, "返回");
                        reset();
                        return true;
                    } else {
                        Log.d(TAG, side() + "不触发：往里 " + (int) peak + "px（门槛 "
                                + dp(BACK_DP) + "px）放弃=" + cancelled + " 共 "
                                + (now - downAt) + "ms");
                    }
                    // 不是我们的手势 ⇒ 动画收掉，再原样重放给下面的应用，等于我们当没看见
                    GestureAnim.end(false);
                    if (!passOff) {
                        passThrough();
                    }
                    reset();
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    if (live) {
                        unhookLive();
                        reset();
                        return true;
                    }
                    if (replaying) {
                        return true;
                    }
                    UI.removeCallbacks(holdFired);
                    UI.removeCallbacks(decideFired);
                    GestureAnim.cancel();
                    Log.d(TAG, side() + "：手势被系统收走，不触发");
                    reset();
                    return true;

                default:
                    return true;
            }
        }

        /** 一次手势结束后的收尾（下一次按下会重新初始化，这里只是别让状态悬着） */
        void reset() {
            reached = false;
            cancelled = false;
            live = false;
        }

        /** 记一个轨迹采样点。离上一个太近就不记 —— 重放的点要稀疏一点 */
        void record(float x, float y) {
            if (n > 0) {
                float dx = x - px[n - 1];
                float dy = y - py[n - 1];
                if (dx * dx + dy * dy < 36f) {      // 6px 以内不算新点
                    return;
                }
            }
            if (n >= px.length) {
                return;
            }
            px[n] = x;
            py[n] = y;
            n++;
        }

        /**
         * 把这一条带让开：窗口设成不吃触摸，让系统把这一次触摸转给下面的应用。
         *
         * <p>为什么不是重放：窗口属性一变，输入派发会重算触摸目标 —— 给我们发 CANCEL、
         * 同时给下面的窗口补一个 DOWN，手势流从头到尾是连续的，手指按多久应用就按多久。
         * 重放做不到这一点（点数、时序都会走样，还会被 tap 判定压成单击）。
         */
        /**
         * 主方向位移：侧滑带 = 往里划了多少 px，底部带 = 往上划了多少 px。
         *
         * <p>⚠⚠ 判据<b>只此一处</b>：MOVE 里的提前判定与 {@link #decideFired} 兜底都调它。
         * 分成两份写迟早会漂（第二份容易顺手写成更严的"起动画"档），那就会把正常慢划判掉。
         * 另外它是方法而不是内联表达式，字段初始化器里的 lambda 才敢引用
         * （直接读 final 里的 {@code bottom / left}，javac 会判"可能尚未初始化"）。
         */
        float mainDispAt(float x, float y) {
            final float dx = x - downX;
            final float dy = y - downY;
            return bottom ? -dy : (left ? dx : -dx);
        }

        void beginLive() {
            if (live) {
                return;
            }
            live = true;
            WindowManager w = wm;
            WindowManager.LayoutParams p = lp;
            View v = view;
            if (w == null || p == null || v == null) {
                return;
            }
            p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                w.updateViewLayout(v, p);
            } catch (Throwable t) {
                Log.w(TAG, "让开失败（" + side() + "）", t);
                return;
            }
            Log.i(TAG, side() + "让开：这次触摸还给下面的应用");
            UI.removeCallbacks(liveGuard);
            UI.postDelayed(liveGuard, LIVE_GUARD_MS);
        }

        /** 让开之后收尾：把窗口恢复成正常吃触摸 */
        void unhookLive() {
            live = false;
            UI.removeCallbacks(liveGuard);
            restoreTouchable();
        }

        /** 兜底：让开之后一直收不到 CANCEL/UP（系统把这一串丢了），也得把窗口恢复回来 */
        final Runnable liveGuard = () -> {
            if (live) {
                Log.d(TAG, side() + "让开后超时复位");
                unhookLive();
            }
        };

        /**
         * 把这一次触摸原样重放给下面的应用。
         *
         * <p>覆盖窗口吃掉的触摸，框架<b>退不回去</b>给下面的应用 —— 这是唯一一条补回来的路。
         * 边缘上的按钮、列表侧滑都靠它。
         *
         * <p>⚠ 注入的事件照常按坐标命中最上面的窗口（也就是我们自己），所以先把窗口临时设成
         * {@code FLAG_NOT_TOUCHABLE}，等这个改动传下去再发第一个事件，重放完恢复。
         *
         * <p>注入不通（没连 Shizuku）时<b>什么窗口参数都别改</b>，让这次触摸照旧消失 ——
         * 至少不会把捕获带留在坏状态里。
         */
        void passThrough() {
            final WindowManager w = wm;
            final WindowManager.LayoutParams p = lp;
            final View v = view;
            if (w == null || p == null || v == null || replaying) {
                return;
            }
            final int cnt = Math.max(1, n);
            final int[] xs = new int[cnt];
            final int[] ys = new int[cnt];
            for (int i = 0; i < cnt; i++) {
                xs[i] = Math.round(winX + px[i]);
                ys[i] = Math.round(winY + py[i]);
            }
            final boolean drag = maxDisp >= dp(TAP_SLOP_DP) && cnt > 1;
            replaying = true;
            p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                w.updateViewLayout(v, p);
            } catch (Throwable t) {
                replaying = false;
                Log.w(TAG, "穿透失败：改窗口参数报错", t);
                return;
            }
            new Thread(() -> {
                try {
                    if (!TouchInject.ready()) {
                        Log.w(TAG, "穿透失败：注入不通（" + TouchInject.why() + "），这次触摸认了");
                        return;
                    }
                    Thread.sleep(REPLAY_ARM_MS);
                    long dt = SystemClock.uptimeMillis();
                    boolean ok = TouchInject.send(Display.DEFAULT_DISPLAY,
                            MotionEvent.ACTION_DOWN, xs[0], ys[0], dt);
                    for (int i = 1; i < cnt; i++) {
                        Thread.sleep(REPLAY_STEP_MS);
                        TouchInject.send(Display.DEFAULT_DISPLAY, MotionEvent.ACTION_MOVE,
                                xs[i], ys[i], dt);
                    }
                    Thread.sleep(REPLAY_STEP_MS);
                    TouchInject.send(Display.DEFAULT_DISPLAY, MotionEvent.ACTION_UP,
                            xs[cnt - 1], ys[cnt - 1], dt);
                    if (ok) {
                        Log.i(TAG, side() + "穿透" + (drag ? "（拖动重放 " + cnt + " 点）" : "（点击重放）")
                                + "：屏坐标 (" + xs[0] + "," + ys[0] + ") 位移 " + (int) maxDisp + "px");
                    } else {
                        Log.w(TAG, side() + "穿透失败：直注被拒（" + TouchInject.why() + "）");
                    }
                    Thread.sleep(REPLAY_RESTORE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    Log.w(TAG, "穿透重放报错", t);
                } finally {
                    UI.post(this::restoreTouchable);
                }
            }, "inner-passthru").start();
        }

        /** 穿透重放完了，把窗口恢复成正常吃触摸。⚠ 不管成功失败都要恢复 */
        void restoreTouchable() {
            replaying = false;
            WindowManager w = wm;
            WindowManager.LayoutParams p = lp;
            View v = view;
            if (w == null || p == null || v == null) {
                return;
            }
            p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                w.updateViewLayout(v, p);
            } catch (Throwable t) {
                Log.w(TAG, "恢复捕获带失败（" + side() + "）", t);
            }
        }
    }
}
