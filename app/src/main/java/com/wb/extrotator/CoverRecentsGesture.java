package com.wb.extrotator;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
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
 * 「外屏手势条」。
 *
 * <p>合盖状态下，封面屏最下沿正中那条窄带上：<b>上滑不停</b> ⇒ 回主界面；<b>上滑并真的停住、
 * 再松手</b> ⇒ 打开最近任务。
 *
 * <p>应用拿不到别的窗口的触摸，只能用自己的无障碍服务往封面屏贴一层
 * {@link WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY} 的透明窗口自己收触摸。
 * 代价是那一条上的触摸被吃掉了（这正是它做成开关的原因）；窗口带 {@code FLAG_NOT_TOUCH_MODAL}，
 * 条外的触摸一个都不碰。条内不成手势的触摸会原样重放给下面的应用（见 {@link #passThrough()}）。
 *
 * <p><b>三条去向</b>（v4.21 定档、v461 修正）：<br>
 * · <b>后台</b>走 shell —— 就是「阿田自用」那条 {@code am start} 命令（见 {@link #shellRecents}）。
 *   这一条要用 Shizuku。<br>
 * · <b>主页 / 返回</b>走 {@link AccessibilityService#performGlobalAction}，
 *   <b>拨一下无障碍开关就能用</b>，不碰 Shizuku、不用电脑、重启也不失效。
 * 条内的重放走 {@link ExtScreen#tap}/{@link ExtScreen#swipe}
 * （底下是无障碍手势 {@link A11yInject}）。

 * <p>⚠ <b>后台这条为什么不能走应用身份</b>：封面屏的最近任务住在
 * {@code com.sec.android.app.launcher} 里，是<b>系统目标</b> —— 应用身份不论怎么发
 * （三星封面屏通道、{@code startActivity + setLaunchDisplayId} 直启）都会被白名单拦成
 * 「打开手机以继续」（v456 实测，process/v456/logcat_cover.txt：
 * {@code Launching from CoverLauncher is blocked, ask to open phone}）。
 * shell 身份不在那条策略的管辖内。⇒ 直启那一半已经删掉（v461）：它开不出后台，
 * 只会把那张弹窗带出来。
 *
 * <p>⚠ {@code performGlobalAction} 没有 display 参数，落点由系统自己挑，合盖时
 * <b>可能落到内屏上</b> —— 照返回 true、封面屏上却什么都不会发生（v455 实测，
 * process/v456/logcat2.txt）。主页 / 返回只剩这一条路，得在真机上再对一次。
 *
 * <p>捕获带高度三档可调（默认 16dp ≈ 34px）；触发区只占屏宽最正中那 1/N。
 *
 * <p>⚠ 下面这条是 shell 那条命令的脾气：
 * 命令与阿田那条逐字一致，开头那句
 * {@code am force-stop com.sec.android.app.launcher} <b>一句都不能省</b>：「最近任务」那份列表是
 * launcher 进程里的一份内存模型，进程活得越久、被开关得越多越容易返回空（界面写「无最近使用的
 * 应用程序」，而 {@code dumpsys activity recents} 里任务一条不少）。列表空的时候命令是成功的、
 * 根本不报错，所以「起不来再 force-stop 重试」那条退路一辈子走不到 —— 唯一判据就是换进程。
 * 代价是内屏桌面重建一次（约 1 秒），封面屏桌面不受影响。
 *
 * <p>⚠ 封面屏底部原本还有系统自己的「上滑回桌面」（{@code dumpsys input} 里 display 1 上挂着
 * {@code swipe-up} 与 {@code extra-swipe-up} 两个 SPY 监视窗口），它跟我们收同一串触摸、
 * 还到松手那一刻才结算，后台会被盖成「刚出来又回桌面」。监视窗口的触摸拦不掉，正解是把系统导航
 * 从「全面屏手势」换成「导航条」（设置页有直达入口）。
 *
 * <p>⚠ 「停住」的判据是 {@code now - lastMoveAt >= HOLD_MS}（手指真的停过），
 * <b>不是</b> {@code now - downAt >= HOLD_MS}（按下到现在够久）—— 后者会把「慢慢划上去回主界面」
 * 也判成停滞。
 */
public final class CoverRecentsGesture {

    private static final String TAG = "CoverRecents";

    /**
     * 捕获带的高度（dp）。⚠ 见类注释「捕获带多高」—— 这一条是死区，越小越不碍事。
     * 三档：小 11dp(≈23px) / 中 16dp(≈34px) / 大 24dp(≈51px)，默认中。
     */
    private static final int[] BAND_DP = {11, 16, 24};
    /** 上滑多少才算"上滑"（dp）。不到这个位移一律不认，交给下面的应用 */
    private static final int SWIPE_DP = 24;     // v4.6：34 -> 24，划一点点就够
    /** 停滞多久算"停一下"（ms）。注意是**真停滞**，不是"划得慢" */
    private static final long HOLD_MS = 140L;    // v4.6：300 -> 140
    /**
     * 离"上一次认定它动了"的那个位置超过这么多 px，才算"还在划"。
     *
     * <p>⚠ 这里判的是<b>这一段的总位移</b>，不是两次采样的差。v4.6 用的是后者（6px），
     * 坏在两处：采样差太小会把"慢慢划上去"也算成停住；而抬手指那一下接触面在变、
     * 坐标会跳，最后那一个 MOVE 就把真停顿的计时清掉了，于是"明明停够了却不触发"。
     * 实测就是这种"时灵时不灵"。
     */
    private static final float MOVE_TOL_PX = 8f;
    /** 往下超过这么多（dp）就认定不是上滑（列表回弹） */
    private static final int DOWN_TOL_DP = 8;
    /** 横移超过这么多（dp）、且比上滑还多，就认定是左右划，让开 */
    private static final int SIDE_TOL_DP = 12;
    /** 触发时的震动时长（ms）。短促一下，别做成连绵的嗡鸣 */
    private static final long BUZZ_MS = 22L;
    /** 震动力度（1~255）。比系统默认略轻一点，够"点一下"的感觉就行 */
    private static final int BUZZ_AMP = 140;
    /** 开完后台的冷却（ms）：这段时间里的按下不判，免得画面还在切又划一下被重复触发 */
    private static final long COOLDOWN_MS = 650L;
    /** 导航条（那条常驻亮杠）的宽 / 高（dp）。它是"底部能划"唯一的视觉线索 */
    private static final int BAR_W_DP = 92;
    private static final int BAR_H_DP = 4;
    /** 导航条的三种亮度（View alpha）：歇着 / 按住 / 划够格了 */
    private static final float BAR_REST = 0.34f;
    private static final float BAR_TOUCH = 0.78f;
    private static final float BAR_FULL = 1f;
    /** 划出去之后"淡掉 → 停一下 → 淡回来"的节奏（ms） */
    private static final long BAR_FADE_OUT_MS = 150L;
    private static final long BAR_FADE_IN_MS = 280L;
    /**
     * 触发区宽度：整块屏宽的 1/N。
     *
     * <p>亮杠本来就只在正中，左右两边视觉上不是手势位置 —— 识别也跟着收进中间那一段。
     * 窗口窄了，被吃掉的触摸自然就少。四档：窄 1/4 / 中 1/3（缺省）/ 宽 1/2 / 满宽。
     */
    private static final int[] ZONE_DIV = {4, 3, 2, 1};
    /**
     * 「外屏 0° 时固定在左下角」里说的那个 0°。
     *
     * <p>就是 {@code Surface.ROTATION_0} —— "这块屏没转"。封面屏平时是倒装的
     * （{@link CoverDisplay#ROT_NATIVE} = 180°），所以 0° 是个特别的档。
     */
    private static final int CORNER_ROTATION = 0;
    /** 位移小于这么多（dp）就算「点了一下」，重放成点击；超过就算拖动，重放成一小段拖动 */
    private static final int TAP_SLOP_DP = 8;
    /** 穿透重放前后各留的余量：先把窗口设成不吃触摸、等它传到输入管线，再发第一个事件 */
    private static final long REPLAY_ARM_MS = 60L;
    /** 重放拖动时相邻采样点的间隔（也决定整段重放有多长） */
    private static final long REPLAY_STEP_MS = 24L;
    /** 重放完最后一个事件、隔多久把窗口恢复成吃触摸 */
    private static final long REPLAY_RESTORE_MS = 90L;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static WindowManager wm;
    private static View strip;
    /** 底部那条常驻亮杠（"导航条"）。画在捕获带窗口里，不额外占触区 */
    private static View pill;
    /** 装条子时留下的应用上下文，只为触发时震一下（不持有 Activity，不会漏） */
    private static Context appCtx;
    private static int shownDisplay = -1;
    /**
     * 封面屏那一侧的密度。
     *
     * <p>⚠ 不能拿 {@code Resources.getSystem()} 换算 dp —— 那是**默认屏（内屏）**的密度（480/160=3.0），
     * 封面屏是 340dpi。差着一半：按内屏算出来的高度在外屏上会翻倍。
     */
    private static float density = 2.125f;
    private static float downX;
    private static float downY;
    private static float lastX;
    private static float lastY;
    private static float maxUp;
    private static long downAt;
    private static long lastMoveAt;
    /** 判"还在划"用的参考位置：手指离开它超过 MOVE_TOL_PX 才算真的在动 */
    private static float stillRefX;
    private static float stillRefY;
    /** 上滑够高度了没有（够格之后才亮灯、才起停滞计时） */
    private static boolean reached;
    /**
     * 这一次手势里，真的停够过没有。
     *
     * <p>⚠ 置上就<b>不再抹掉</b>：手指抬起来那一下坐标会跳、会送来一个很大的 MOVE，
     * 要是拿它去清这个标记，真停顿就全白停了（v4.6 的"时灵时不灵"就是这么来的）。
     */
    private static boolean holdOk;
    /** 中途被判成"不是我们的手势"了（横划 / 下滑），后面一律不管 */
    private static boolean cancelled;
    private static boolean fired;
    /** 上一次真的开了后台的时刻，做冷却用（见 COOLDOWN_MS） */
    private static long firedAt;
    /** 屏幕尺寸与窗口左上角（穿透重放要把窗口内坐标换成屏幕坐标） */
    private static int screenW;
    private static int screenH;
    private static int winLeft;
    private static int winTop;
    /** 窗口参数。穿透时要临时加 / 去 FLAG_NOT_TOUCHABLE，必须留着同一份改 */
    private static WindowManager.LayoutParams bandLp;
    /** 这一次触摸的采样轨迹（窗口内坐标），穿透时按它重放 */
    private static final float[] pathX = new float[9];
    private static final float[] pathY = new float[9];
    private static int pathN;
    /** 离按下点最远走了多少（判「是点了一下还是拖了一下」） */
    private static float maxDisp;
    /** 正在把这一次触摸重放给下面的应用 ⇒ 期间收到的事件一律当回放副本，不判手势 */
    private static boolean replaying;
    /** 这一次触摸不许穿透（刚开完后宫的冷却期里别替用户点到下面的东西） */
    private static boolean passOff;
    /** 这一次装窗口时条子是不是落在左下角（见 {@link #wantCornerLeft}） */
    private static boolean cornerLeft;
    /** 盯着封面屏旋转用的监听：0° ↔ 别的角度之间要跟着挪条子 */
    private static DisplayManager rotationDm;
    private static DisplayManager.DisplayListener rotationListener;

    private CoverRecentsGesture() {
    }

    private static final Runnable holdFired = () -> {
        if (fired || cancelled || !reached || holdOk) {
            return;
        }
        // 停够了：记下来（松手时按它决定是去后台还是回主界面），并亮满告诉用户可以松手了。
        // 开后台挪到松手那一下（ACTION_UP）—— 停顿期间只亮灯，不发任何命令。
        holdOk = true;
        Log.i(TAG, "停顿达成（" + HOLD_MS + "ms 没动）=> 松手打开后台");
        barPress(BAR_FULL, 1.9f, 90);
    };

    /** 导航条淡回来（见 {@link #barFadeOutThenBack}） */
    private static final Runnable barReturn = () -> barPress(BAR_REST, 1f, BAR_FADE_IN_MS);

    /** 有没有装上（界面拿它显示状态） */
    public static boolean installed() {
        return strip != null;
    }

    /**
     * 按当前设置把这一层对齐：开关开着且找得到封面屏 ⇒ 装上；否则摘掉。
     * <p>服务连上、开关被拨动、封面屏拓扑变了，都调这一个。
     */
    public static void sync(Context ctx) {
        boolean want = ExtPrefs.coverRecentsGesture(ctx);
        int display = CoverDisplay.id(ctx);
        Log.d(TAG, "sync want=" + want + " 封面屏=" + display + " 当前已装=" + installed());
        if (!want || display <= 0) {
            remove();
            return;
        }
        if (strip != null && shownDisplay == display) {
            return;
        }
        remove();
        install(ctx, display);
    }

    /**
     * 设置改了（档位 / 开关）⇒ 立刻按新参数重装。
     *
     * <p>{@link #sync} 见到"已经装着同一个屏"会直接返回，所以改高度必须走这里。
     */
    public static void refresh(Context ctx) {
        remove();
        sync(ctx);
    }

    private static void install(Context ctx, int display) {
        if (!(ctx instanceof AccessibilityService)) {
            // TYPE_ACCESSIBILITY_OVERLAY 这一层只有无障碍服务自己能加
            Log.w(TAG, "不是无障碍服务上下文，装不了手势条");
            return;
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(display);
            if (d == null) {
                Log.w(TAG, "拿不到 display " + display);
                return;
            }
            Context displayCtx = ctx.createDisplayContext(d);
            wm = (WindowManager) displayCtx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            density = displayCtx.getResources().getDisplayMetrics().density;
            appCtx = ctx.getApplicationContext();

            final int band = bandDp(ctx);
            final int bandPx = dp(band);
            final int div = zoneDiv(ctx);

            // 窗口本体是全透明的，只在正中放一条亮杠 —— 用户要的那个"导航条"
            FrameLayout v = new FrameLayout(displayCtx);
            View bar = makeBar(displayCtx, dp(BAR_W_DP), dp(BAR_H_DP));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    dp(BAR_W_DP), dp(BAR_H_DP));
            blp.gravity = Gravity.CENTER;
            v.addView(bar, blp);
            v.setOnTouchListener((view, e) -> onTouch(view, e));

            // 触发区只占屏宽最正中那 1/N：亮杠本来就只在中间，左右两边视觉上不是手势位置，
            // 识别也跟着收进去 —— 窗口窄了，被吃掉的触摸自然就少。
            // ⚠ 尺寸要按「整块屏」算，不是 app 区：空白屏的资源度量给的是 748x654（少了 66px 系统条），
            // 而窗口是用 Gravity.BOTTOM 锚在显示空间下沿的。算 winTop 与「左下角」都得用整屏，
            // 跟 CoverBlur 同一条铁律（RULES §64.1）：拿 654 去算，重放的坐标会整整偏 66px。
            DisplayMetrics dmet = displayCtx.getResources().getDisplayMetrics();
            screenW = dmet.widthPixels;
            screenH = dmet.heightPixels;
            try {
                if (dm != null) {
                    Display real = dm.getDisplay(display);
                    if (real != null) {
                        Point pt = new Point();
                        real.getRealSize(pt);
                        if (pt.x > 0 && pt.y > 0) {
                            screenW = pt.x;
                            screenH = pt.y;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "取整屏尺寸失败，退回资源度量", t);
            }
            final int winW = Math.max(1, Math.round(screenW / (float) div));
            // 外屏 0° 且用户勾了那一条 ⇒ 贴左下角，否则照旧贴最下沿正中
            cornerLeft = wantCornerLeft(ctx, display);
            winLeft = cornerLeft ? 0 : (screenW - winW) / 2;
            winTop = screenH - bandPx;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    winW,
                    bandPx,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // 不吃焦点（不抢键盘）＋ 条外触摸一律放行
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            // 贴在最下沿；勾了"0° 时固定在左下角"且外屏此刻真的是 0° 就贴左边，
            // 否则横向居中。封面屏在物理上是横着的，这里跟它当前方向对齐，免得上滑方向被拧。
            lp.gravity = Gravity.BOTTOM
                    | (cornerLeft ? Gravity.START : Gravity.CENTER_HORIZONTAL);

            // 模糊层得先挂：同一类窗口后加的在上，反了的话拖动时糊面会把亮杠盖住
            CoverBlur.attach(displayCtx, wm, (AccessibilityService) ctx, display,
                    screenW, screenH);

            wm.addView(v, lp);
            strip = v;
            pill = bar;
            bandLp = lp;
            shownDisplay = display;
            listenRotation(ctx, display);
            Log.i(TAG, "手势条已装到 display " + display + "，触发区 " + winW + "x"
                    + bandPx + "px（高 " + band + "dp，占屏宽 1/" + div + "），导航条 "
                    + dp(BAR_W_DP) + "x" + dp(BAR_H_DP) + "px / 屏 " + screenW + "x"
                    + screenH + "px" + (cornerLeft ? "，贴左下角（外屏 0°）" : ""));
        } catch (Throwable t) {
            Log.e(TAG, "装手势条失败", t);
            CoverBlur.detach();
            strip = null;
            pill = null;
            wm = null;
            bandLp = null;
            replaying = false;
            shownDisplay = -1;
        }
    }

    /**
     * 把手势条摘掉。
     *
     * <p>public 是因为无障碍服务断开时要从 {@link VolumeKeyService} 那边调 ——
     * 服务没了它就没有主人，留在屏上只会白白吃掉外屏底部那一条的触摸。
     */
    public static void remove() {
        View v = strip;
        WindowManager w = wm;
        strip = null;
        pill = null;
        wm = null;
        bandLp = null;
        shownDisplay = -1;
        appCtx = null;
        replaying = false;
        passOff = false;
        if (rotationDm != null && rotationListener != null) {
            try {
                rotationDm.unregisterDisplayListener(rotationListener);
            } catch (Throwable ignored) {
            }
        }
        rotationDm = null;
        rotationListener = null;
        cornerLeft = false;
        UI.removeCallbacksAndMessages(null);
        CoverBlur.detach();
        if (v != null && w != null) {
            try {
                w.removeViewImmediate(v);
                Log.i(TAG, "手势条已摘掉");
            } catch (Throwable t) {
                Log.w(TAG, "摘手势条失败", t);
            }
        }
    }

    // ------------------------------------------------------------------ 左下角

    /**
     * 现在这一刻，条子该不该贴左下角。
     *
     * <p>两个条件同时成立才挪：用户勾了那一条，<b>并且</b>外屏此刻真的是 0°。
     * 别的角度一律回到正中 —— 用户要的就是"唯独 0° 那一档"。
     */
    private static boolean wantCornerLeft(Context ctx, int display) {
        if (ctx == null || !ExtPrefs.coverRecentsCornerLeft(ctx)) {
            return false;
        }
        return liveRotation(ctx, display) == CORNER_ROTATION;
    }

    /**
     * 外屏此刻的实际旋转（0..3 = {@code Surface.ROTATION_*}；拿不到返回 -1）。
     *
     * <p>走 {@link Display#getRotation()} 而不是 {@link CoverDisplay#currentRotation}：
     * 后者要发一条 shell（两百毫秒上下），而这个方法在服务刚连上时也会被主线程调到。
     * 屏灭的时候系统可能给旧值，但那会儿本来也没人去划它，不碍事。
     */
    private static int liveRotation(Context ctx, int display) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getApplicationContext()
                    .getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(display);
            return d == null ? -1 : d.getRotation();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 盯着封面屏的旋转：0° 与别的角度之间来回切时，把条子在"左下角 / 正中"之间挪过去。
     *
     * <p>只改窗口落点、不拆了重装 —— 尺寸与旗标都没变，{@code updateViewLayout}
     * 就够，不会有重装那一下的闪。
     */
    private static void listenRotation(Context ctx, int display) {
        if (rotationListener != null) {
            return;
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getApplicationContext()
                    .getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return;
            }
            rotationDm = dm;
            rotationListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != shownDisplay) {
                        return;
                    }
                    UI.post(CoverRecentsGesture::applyCorner);
                }
            };
            dm.registerDisplayListener(rotationListener, UI);
        } catch (Throwable t) {
            Log.w(TAG, "注册外屏旋转监听失败（0° 时不会自动挪条子）", t);
        }
    }

    /** 外屏转了 ⇒ 该挪就挪（见 {@link #listenRotation}）；不该挪就一个字都不动 */
    private static void applyCorner() {
        final WindowManager w = wm;
        final WindowManager.LayoutParams p = bandLp;
        final View s = strip;
        if (w == null || p == null || s == null) {
            return;
        }
        boolean want = wantCornerLeft(appCtx, shownDisplay);
        if (want == cornerLeft) {
            return;
        }
        cornerLeft = want;
        p.gravity = Gravity.BOTTOM
                | (cornerLeft ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        winLeft = cornerLeft ? 0 : Math.max(0, (screenW - p.width) / 2);
        winTop = screenH - p.height;
        try {
            w.updateViewLayout(s, p);
            Log.i(TAG, "外屏转到 " + (cornerLeft ? "0°" : "别的角度") + " ⇒ 手势条挪到"
                    + (cornerLeft ? "左下角" : "正中"));
        } catch (Throwable t) {
            Log.w(TAG, "挪手势条失败", t);
        }
    }

    // ------------------------------------------------------------------ 触摸判定

    private static boolean onTouch(View view, MotionEvent e) {
        final long now = System.currentTimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 正在把上一次触摸重放给下面的应用：这期间收到的都是回放副本，不判手势
                if (replaying) {
                    return true;
                }
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                lastY = downY;
                maxUp = 0;
                maxDisp = 0;
                pathN = 0;
                record(downX, downY);
                downAt = now;
                lastMoveAt = now;
                stillRefX = downX;
                stillRefY = downY;
                reached = false;
                holdOk = false;
                fired = false;
                // 冷却期里的触摸既不判手势、也不穿透 —— 画面还在切，别替用户点到下面的东西
                passOff = now - firedAt < COOLDOWN_MS;
                UI.removeCallbacks(holdFired);
                UI.removeCallbacks(barReturn);
                if (passOff) {
                    cancelled = true;
                    Log.d(TAG, "冷却中（距上次开后台 " + (now - firedAt) + "ms），本次不判也不穿透");
                    return true;
                }
                cancelled = false;
                // 条内必须先收下，否则拿不到后面的 MOVE。亮一下表示这一带归我管。
                barPress(BAR_TOUCH, 1.1f, 80);
                // 糊面先隐形并趁这段空档把帧换新：抓帧时不能把自己抓进去
                CoverBlur.prepare();
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (replaying) {
                    return true;
                }
                if (cancelled) {
                    // 已经判成「不是我们的手势」了，但轨迹还得继续记 —— 松手时要原样重放给下面
                    record(e.getX(), e.getY());
                    return true;
                }
                float up = downY - e.getY();
                float side = Math.abs(e.getX() - downX);
                if (up > maxUp) {
                    maxUp = up;
                }
                float disp = (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                if (disp > maxDisp) {
                    maxDisp = disp;
                }
                record(e.getX(), e.getY());
                // ① 往下拖（列表回弹）或横着划（左右返回）⇒ 不是我们的手势，让开
                if (up < -dp(DOWN_TOL_DP) || (side > dp(SIDE_TOL_DP) && side > up)) {
                    cancelled = true;
                    UI.removeCallbacks(holdFired);
                    CoverBlur.hide();
                    barPress(BAR_REST, 1f, 180);
                    Log.d(TAG, "放弃：上滑 " + (int) up + "px 横移 " + (int) side + "px");
                    return true;
                }
                // ② 还没够高度：什么都不做（尤其别起停滞计时）
                if (maxUp < dp(SWIPE_DP)) {
                    return true;
                }
                // 这个手势已经被我们认下了 => 整块外屏跟着糊起来，越划越糊
                CoverBlur.show(maxUp);
                if (!reached) {
                    // 刚够格：全亮、抻长一点 + 起停滞计时
                    reached = true;
                    holdOk = false;
                    stillRefX = e.getX();
                    stillRefY = e.getY();
                    lastX = e.getX();
                    lastY = e.getY();
                    lastMoveAt = now;
                    barPress(BAR_FULL, 1.5f, 110);
                    UI.removeCallbacks(holdFired);
                    UI.postDelayed(holdFired, HOLD_MS);
                    return true;
                }
                // ③ 离"上次认定它动了"的位置超过 MOVE_TOL_PX ⇒ 算还在划：停滞计时往后推。
                //    抖一下（位移不到 MOVE_TOL_PX）不算动，也绝不去清 holdOk。
                if (Math.abs(e.getY() - stillRefY) > MOVE_TOL_PX
                        || Math.abs(e.getX() - stillRefX) > MOVE_TOL_PX) {
                    stillRefX = e.getX();
                    stillRefY = e.getY();
                    lastX = e.getX();
                    lastY = e.getY();
                    lastMoveAt = now;
                    UI.removeCallbacks(holdFired);
                    UI.postDelayed(holdFired, HOLD_MS);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                UI.removeCallbacks(holdFired);
                // 手指离屏：那层糊先收掉（底下三条去向都要收）
                CoverBlur.hide();
                final float upNow = downY - e.getY();
                // 开后台挪到松手这一下：停顿期间只亮灯，命令等手指离开再发
                if (!cancelled && reached && holdOk) {
                    fired = true;
                    firedAt = now;
                    Log.i(TAG, "上滑停滞达成（上滑 " + (int) maxUp + "px，共 "
                            + (now - downAt) + "ms）=> 松手，打开后台");
                    trigger();
                    reset();
                    return true;
                }
                // 上滑了但没停住 ⇒ 回主界面。
                // 用户把系统导航换成了「导航条」，封面屏底部那条系统自己的上滑回桌面就没有了，
                // 这一支正好补上那个位置（松手时手指得真的还在上方，中途拖回来的不算）。
                if (!cancelled && reached && upNow >= dp(SWIPE_DP) * 0.5f) {
                    fired = true;
                    firedAt = now;
                    Log.i(TAG, "上滑未见停顿（上滑 " + (int) maxUp + "px，共 "
                            + (now - downAt) + "ms）=> 回主界面");
                    triggerHome();
                    reset();
                    return true;
                }
                Log.d(TAG, "不触发：够格=" + reached + " 停过=" + holdOk + " 放弃=" + cancelled
                        + " 上滑 " + (int) maxUp + "px 位移 " + (int) maxDisp + "px 共 "
                        + (now - downAt) + "ms");
                if (maxUp >= dp(DOWN_TOL_DP)) {
                    // 手指确实从条上划出去了 => 淡一下再回来（用户要的那个手感）
                    barFadeOutThenBack(200L);
                } else {
                    // 只是点了一下：别闪，直接回到常驻亮度
                    barPress(BAR_REST, 1f, 200);
                }
                // 不是我们的手势 ⇒ 原样重放给下面的应用。
                // 用户点名要的：哪怕按钮就压在导航条那一带上，也得按得到。
                if (!passOff) {
                    passThrough();
                }
                reset();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                if (replaying) {
                    return true;
                }
                UI.removeCallbacks(holdFired);
                CoverBlur.hide();
                Log.d(TAG, "手势被系统收走，不触发");
                barPress(BAR_REST, 1f, 220);
                reset();
                return true;

            default:
                return true;
        }
    }

    /** 一次手势结束后的收尾（下一次按下会重新初始化，这里只是别让状态悬着） */
    private static void reset() {
        reached = false;
        cancelled = false;
    }

    /**
     * 上滑停住：打开<b>系统的</b>最近任务 —— 跟内屏一样，卡片能划掉。
     */
    private static void trigger() {
        // 画面要换走了，手上那帧作废
        CoverBlur.invalidate();
        openRecentsNow(shownDisplay, "上滑停住");
        // 后台要出来了：先把导航条淡掉，等画面换过去再淡回来
        barFadeOutThenBack(620L);
    }

    /**
     * 回主界面。
     *
     * <p>这条位置原本是系统自己的「底部上滑回桌面」。它在封面屏上跟我们收同一串触摸、
     * 还到松手那一刻才结算，所以后台会被它盖成「刚出来又回桌面」。
     * 用户直接把系统导航换成了「导航条」—— 换完封面屏底部那条系统手势区就没了，
     * 上滑完全归我们，于是这里补上那个位置：上滑没停住就回主界面。
     */
    private static void triggerHome() {
        CoverBlur.invalidate();
        // 要切走了：先把亮杠淡掉，等画面换过去再淡回来
        barFadeOutThenBack(420L);
        goHome(shownDisplay);
    }

    // ------------------------------------------------------------------ 三条去向（全程免 Shizuku）

    /** 无障碍服务的三个全局动作码 */
    private static final int A_BACK = AccessibilityService.GLOBAL_ACTION_BACK;
    private static final int A_HOME = AccessibilityService.GLOBAL_ACTION_HOME;
    private static final int A_RECENTS = AccessibilityService.GLOBAL_ACTION_RECENTS;
    /**
     * 全局动作发出去之后，隔多久补一发直启。
     *
     * <p>450ms 是照「阿田自用」的 {@code COVER_LAUNCH_RECENTS_DELAY_MS}（0x1c2）抄的：
     * 全局动作先走一步、直启补在它后面，两条都发、谁也不等谁的结果。
     */
    private static final long FALLBACK_MS = 450L;

    /**
     * 让无障碍服务执行一次全局动作（返回 / 主页 / 最近任务）。
     *
     * <p>这是整套里唯一<b>什么权限都不要</b>的按键路：无障碍服务自带的
     * {@link AccessibilityService#performGlobalAction}，拨一下开关就能用。
     *
     * <p>⚠ 它没有 display 参数，落点由系统自己挑 —— 合盖时不一定落在封面屏上
     * （v455 实测：照返回 true、封面屏上什么都不发生，process/v456/logcat2.txt）。
     * 所以每条去向后面都跟着一发直启，不指望它一条搞定。
     *
     * <p>⚠ 服务实例<b>每次现问</b>：它可能压根没连上，也可能在下一毫秒就断开。
     */
    private static boolean globalAction(int action, String what) {
        AccessibilityService s = A11yInject.service();
        if (s == null) {
            Log.w(TAG, what + "：无障碍服务没连上，全局动作发不出去");
            return false;
        }
        try {
            boolean ok = s.performGlobalAction(action);
            Log.i(TAG, what + " => performGlobalAction(" + action + ") 返回 " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, what + "：performGlobalAction 抛异常", t);
            return false;
        }
    }

    /**
     * 返回（侧滑栏那一档）。
     *
     * <p>⚠ 返回<b>没有可以直启的等价物</b> —— 它不是一个 Activity，是发给前台窗口的一个
     * 事件。所以这条只剩全局动作这一发；日志里记着它的返回值，好不好用一看就知道。
     */
    public static void goBack() {
        buzz();
        globalAction(A_BACK, "返回");
    }

    /**
     * 回主界面（侧滑栏那一档与手势条上滑都走这里）。
     *
     * <p>先发一次全局动作，隔 {@link #FALLBACK_MS} 再补一发「直启桌面」——
     * 全局动作在封面屏上不一定作数（见 {@link #globalAction}）。
     */
    public static void goHome(final int display) {
        buzz();
        CoverPolicy.ensureLauncher(appCtx);
        globalAction(A_HOME, "回主界面");
        if (display <= 0) {
            return;
        }
        UI.postDelayed(() -> {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            if (!launchOnDisplay(display, home, "回主界面（直启桌面）")) {
                shellHome(display);
            }
        }, FALLBACK_MS);
    }

    /**
     * 用<b>无障碍服务自己的上下文</b>把 Intent 摆到第 {@code display} 块屏上。
     *
     * <p>零权限：{@code ActivityOptions.setLaunchDisplayId} 是公开 API，无障碍服务的
     * {@code startActivity} 又比普通应用宽松 —— 这一下就发生在用户刚划完手势的交互里。
     * 「阿田自用」把封面屏最近任务摆上去用的正是这一行。
     *
     * @return true = 调用发出去了（不代表目标真的起来了）
     */
    private static boolean launchOnDisplay(int display, Intent intent, String what) {
        AccessibilityService s = A11yInject.service();
        if (s == null) {
            Log.w(TAG, what + "：无障碍服务没连上，直启发不出去");
            return false;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // 先把目标包放进封面屏白名单 —— 不放的话这一发直启会被拦成
            // 「打开手机以继续」（见 CoverPolicy 类注释）。没写包名的
            // HOME / 最近任务落点就是系统 launcher，按它注册。
            String pkg = intent.getComponent() != null
                    ? intent.getComponent().getPackageName() : intent.getPackage();
            CoverPolicy.ensure(appCtx, pkg != null ? pkg : CoverPolicy.LAUNCHER_PKG);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(display);
            s.startActivity(intent, opts.toBundle());
            Log.i(TAG, what + " => 直启到第 " + display + " 块屏：" + intent.getComponent());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, what + "：直启失败", t);
            return false;
        }
    }

    /** 回主界面的最后一层兜底（只有装了 Shizuku 才走得到） */
    private static void shellHome(int display) {
        new Thread(() -> {
            String out = ShellRunner.run("am start --display " + display
                    + " -a android.intent.action.MAIN -c android.intent.category.HOME"
                    + " -f 0x10800000 2>&1", 12);
            Log.i(TAG, "回主界面（shell 兜底）=> " + out);
        }, "cover-home").start();
    }

    /**
     * 打开<b>系统的</b>最近任务（卡片能划掉那套）。
     *
     * <p>侧滑栏那一档、旧抽屉面板那一行、手势条上滑停住，三处都走这里。
     *
     * <p>排法照「阿田自用」的 {@code triggerCoverRecents}：先发一次全局动作，隔
     * {@link #FALLBACK_MS} 再补一发直启。两条都是无障碍的，装完就能用。
     *
     * <p>⚠ 直启的目标是 {@code com.sec.android.app.launcher}，可能被封面屏策略拦成
     * 「打开手机以继续」（v456 实测 {@code Launching from CoverLauncher is blocked,
     * ask to open phone}）；manifest 里那条 {@code cover_launcher_policy_app} 声明
     * 就是为它加的。
     *
     * <p>⚠ 那条 shell 是<b>最后一层</b>兜底，只有装了 Shizuku 才走得到。
     * 命令与阿田那条逐字一致：先 {@code am force-stop com.sec.android.app.launcher; sleep 0.2;}
     * 再起。这句是<b>必需</b>的，不是「起不来时的退路」—— 列表空的时候命令是成功的、报不出错，
     * 只有换一个新的 launcher 进程才能把那层内存模型清掉（详见类注释）。
     */
    public static void openRecentsNow(final int display) {
        openRecentsNow(display, "打开后台");
    }

    private static void openRecentsNow(final int display, final String why) {
        if (display <= 0) {
            return;
        }
        buzz();
        // v461：后台改回走 shell（「阿田自用」那条命令）。封面屏的最近任务是**系统目标**，
        // 应用身份怎么发都会被白名单拦成「打开手机以继续」；shell 身份不受那条策略管辖。
        if (ShellRunner.isReady()) {
            shellRecents(display, why);
            return;
        }
        // 没有 Shizuku 时才退到全局动作：它没有 display 参数、落点由系统挑，
        // 合盖时多半落到内屏（见 globalAction 注释），聊胜于无。
        Log.w(TAG, why + "：没有 Shizuku，后台只能发全局动作（落点可能不在封面屏）");
        globalAction(A_RECENTS, why);
    }

    /** 开后台（走 shell，就是「阿田自用」那条命令；只有装了 Shizuku 才走得到） */
    private static void shellRecents(int display, String why) {
        new Thread(() -> {
            String cmd = "am force-stop com.sec.android.app.launcher; sleep 0.2; "
                    + "am start --display " + display
                    + " -a android.intent.action.MAIN -c android.intent.category.DEFAULT"
                    + " -f 0x10800000"
                    + " -n com.sec.android.app.launcher/com.android.quickstep.RecentsActivity"
                    + " >/dev/null 2>&1"
                    + " || input -d " + display + " keyevent 187"
                    + " || input keyevent 187";
            String out = ShellRunner.run(cmd, 20);
            Log.i(TAG, why + "（shell）=> " + out);
        }, "cover-recents").start();
    }

    /**
     * 触发的那一下震一下。
     *
     * <p>用系统默认振动器（{@code getDefaultVibrator}）而不是自己挑马达：
     * 机器上哪颗马达该响不该由我们决定。带幅度的那版在有些机器上没有
     * 幅度控制会抛 {@code IllegalArgumentException}，所以退一步用不带幅度的。
     */
    private static void buzz() {
        // ⚠ 侧滑栏那一档也会走这三条去向，那时手势条不一定装着、appCtx 就是空的 ——
        //    退回无障碍服务自己的上下文，别让侧滑栏上按了没反应（连震动都没有）。
        Context c = appCtx != null ? appCtx : A11yInject.service();
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
                Log.d(TAG, "没有振动器，跳过");
                return;
            }
            // ⚠ VibrationEffect.createOneShot 只有带幅度的两参版本，没有单参重载。
            //    幅度控制不是每台机器都有，没有的时候就交回系统默认幅度（-1）。
            int amp = v.hasAmplitudeControl() ? BUZZ_AMP : VibrationEffect.DEFAULT_AMPLITUDE;
            v.vibrate(VibrationEffect.createOneShot(BUZZ_MS, amp));
            Log.d(TAG, "震一下 " + BUZZ_MS + "ms amp=" + amp);
        } catch (Throwable t) {
            Log.w(TAG, "震动失败（不影响打开后台）", t);
        }
    }

    // ------------------------------------------------------------------ 导航条

    /**
     * 造那条常驻亮杠（圆头的"导航条"）。
     *
     * <p>颜色固定白，明暗全靠 {@link View#setAlpha} 调 —— 这样亮暗之间的过渡
     * 交给属性动画就行，不用自己算颜色。
     */
    private static View makeBar(Context c, int wPx, int hPx) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(hPx / 2f);
        v.setBackground(d);
        v.setAlpha(BAR_REST);
        return v;
    }

    /**
     * 把导航条调到某个亮度与"抻长"程度。
     *
     * @param stretchY 竖向拉伸倍数（1 = 原始长度）。够格时抻长一点，像被拽了一下
     * @param ms       过渡时长；传 0 表示立刻到位
     */
    private static void barPress(float alpha, float stretchY, long ms) {
        View p = pill;
        if (p == null) {
            return;
        }
        p.animate().cancel();
        if (ms <= 0) {
            p.setAlpha(alpha);
            p.setScaleY(stretchY);
            return;
        }
        p.animate().alpha(alpha).scaleY(stretchY).setDuration(ms).start();
    }

    /**
     * 手指从条上划出去之后的"淡出"：先淡掉，停一下，再淡回常驻亮度。
     *
     * <p>用户要的就是这个手感 —— 手指一离开亮杠就顺势隐下去，过一会儿自己回来。
     *
     * @param restDelayMs 淡掉之后隔多久开始淡回来（开了后台就等久一点）
     */
    private static void barFadeOutThenBack(long restDelayMs) {
        final View p = pill;
        if (p == null) {
            return;
        }
        UI.removeCallbacks(barReturn);
        p.animate().cancel();
        p.animate().alpha(0f).scaleY(1f).setDuration(BAR_FADE_OUT_MS).start();
        UI.postDelayed(barReturn, BAR_FADE_OUT_MS + restDelayMs);
    }

    // ------------------------------------------------------------------ 穿透

    /**
     * 记一个轨迹采样点。离上一个太近就不记 —— 重放的点要稀疏一点，不然整段拖沓。
     */
    private static void record(float x, float y) {
        if (pathN > 0) {
            float dx = x - pathX[pathN - 1];
            float dy = y - pathY[pathN - 1];
            if (dx * dx + dy * dy < 36f) {   // 6px 以内不算新点
                return;
            }
        }
        if (pathN >= pathX.length) {
            return;
        }
        pathX[pathN] = x;
        pathY[pathN] = y;
        pathN++;
    }

    /**
     * 把这一次触摸原样重放给下面的应用。
     *
     * <p>覆盖窗口吃掉的触摸，框架<b>退不回去</b>给下面的应用 —— 这是手势条唯一的代价面。
     * 所以只能在判完「这不是我们的手势」之后，把这次触摸按同一位置注入一遍，等于「我们当没看见」：
     * 按钮正好压在导航条那一带上也照样按得到。
     *
     * <p>⚠ 注入的事件照常按坐标命中最上面的窗口（也就是我们自己），所以先把窗口临时设成
     * {@code FLAG_NOT_TOUCHABLE}，等这个改动传下去再发第一个事件，重放完恢复。
     * 这段时间里的触摸我们不收（{@link #replaying}）。
     *
     * <p>⭐ 重放本身也<b>不用 Shizuku</b>：走 {@link ExtScreen#tap}/{@link ExtScreen#swipe}，
     * 底下是无障碍手势（{@link A11yInject}，{@code dispatchGesture} 带 displayId）；
     * 只有无障碍那条路也走不通时，它才自己退回 {@code input}。
     *
     * <p>⚠ 外面那层 {@code FLAG_NOT_TOUCHABLE} 一步都不能省：注入的事件按坐标命中最上面的
     * 窗口，不先让开的话重放全打回我们自己身上。
     */
    private static void passThrough() {
        final WindowManager w = wm;
        final WindowManager.LayoutParams p = bandLp;
        final View s = strip;
        if (w == null || p == null || s == null || replaying) {
            return;
        }
        // 轨迹转成屏幕坐标（窗口内坐标 + 窗口左上角）
        final int n = Math.max(1, pathN);
        final int[] xs = new int[n];
        final int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = Math.round(winLeft + pathX[i]);
            ys[i] = Math.round(winTop + pathY[i]);
        }
        final boolean drag = maxDisp >= dp(TAP_SLOP_DP) && n > 1;
        final int display = shownDisplay;
        replaying = true;
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            w.updateViewLayout(s, p);
        } catch (Throwable t) {
            replaying = false;
            Log.w(TAG, "穿透失败：改窗口参数报错", t);
            return;
        }
        new Thread(() -> {
            try {
                // 上面那一下 FLAG_NOT_TOUCHABLE 得先传到输入管线，再发才不会打回自己身上
                Thread.sleep(REPLAY_ARM_MS);
                if (drag) {
                    // 折线只留首尾：这条带很窄，重放成一段直线就够
                    ExtScreen.swipe(display, xs[0], ys[0], xs[n - 1], ys[n - 1],
                            (int) Math.max(60L, (n - 1) * REPLAY_STEP_MS));
                    Log.i(TAG, "穿透（拖动重放）：屏坐标 (" + xs[0] + "," + ys[0] + ") → ("
                            + xs[n - 1] + "," + ys[n - 1] + ")，位移 " + (int) maxDisp + "px");
                } else {
                    ExtScreen.tap(display, xs[0], ys[0]);
                    Log.i(TAG, "穿透（点击重放）：屏坐标 (" + xs[0] + "," + ys[0] + ")");
                }
                Thread.sleep(REPLAY_RESTORE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Log.w(TAG, "穿透重放报错", t);
            } finally {
                UI.post(CoverRecentsGesture::restoreTouchable);
            }
        }, "cover-passthru").start();
    }

    /** 穿透重放完了，把窗口恢复成正常吃触摸。⚠ 不管成功失败都要恢复 */
    private static void restoreTouchable() {
        replaying = false;
        WindowManager w = wm;
        WindowManager.LayoutParams p = bandLp;
        View s = strip;
        if (w == null || p == null || s == null) {
            return;
        }
        p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            w.updateViewLayout(s, p);
        } catch (Throwable t) {
            Log.w(TAG, "恢复捕获带失败", t);
        }
    }

    /** 用户挑的触发区宽度（整屏的 1/N，v4.7） */
    private static int zoneDiv(Context ctx) {
        int idx = ExtPrefs.coverRecentsZone(ctx);
        if (idx < 0 || idx >= ZONE_DIV.length) {
            idx = 1;
        }
        return ZONE_DIV[idx];
    }

    /** 用户挑的捕获带档位（dp） */
    private static int bandDp(Context ctx) {
        int idx = ExtPrefs.coverRecentsBand(ctx);
        if (idx < 0 || idx >= BAND_DP.length) {
            idx = 1;
        }
        return BAND_DP[idx];
    }

    private static int dp(float v) {
        return Math.round(v * density);
    }
}
