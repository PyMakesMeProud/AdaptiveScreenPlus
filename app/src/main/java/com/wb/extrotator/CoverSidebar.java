package com.wb.extrotator;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 「外屏侧边栏」。
 *
 * <p>合盖时在外屏的<b>一条边</b>上留一块很窄的感应带（位置可选：左/右 × 上/中/下，六选一），
 * 从那儿往里拉会带出一张半透明面板 —— 面板内容由用户勾：通知 / 最近任务 / 返回 / 回到桌面，
 * 勾几项就几行，先后按界面上那四行的次序（箭头可调），出厂勾前三项。
 *
 * <p><b>手感</b>：往感应带里划够 {@link #TRIGGER_DP}dp 那一下，面板就整张拉开，不再跟手拖、
 * 也不用等松手看进度 —— 这条跟底部手势条<b>故意是两套逻辑</b>（底部要「上滑 + 停一下」，
 * 这边要「一划就出」）。拉开那一下原本有一次短震动，现已关掉（代码留着没删）。
 * 打开与收起各有一段 190ms 的滑出动画。
 *
 * <p><b>动画效果</b>：划出来画什么由「动画效果」那栏决定 —— 0 是 {@link CoverWheel} 的动力圆环，
 * 1 是 {@link CoverGear} 的调速齿轮，3 是 {@link CoverRoulette} 的抽奖轮盘。几套都<b>松手才执行</b>、
 * 没选中就当没划，落到「通知」那一档弹出的是 {@link CoverNotifCenter}。旧的抽屉面板没删，
 * 偏好设成 2 还能用，留着当退路。
 *
 * <p><b>导航条</b>：这条感应带以前是隐形的，用户找不到；现在带子里常驻一条 5x64dp 的白色圆头
 * 亮杠（按住变亮、往里拉时顺方向淡掉并抻长，松手后自己淡回来）。亮杠画在感应带窗口<b>里面</b>，
 * 不额外占触区。
 *
 * <p>⚠ <b>代价（跟手势条同一条铁律）</b>：感应带上的触摸会被这层窗口收走，所以宽度只给
 * {@link #EDGE_W_DP}dp（≈30px），跟系统自己的手势感应区一个量级。面板一旦拉开就是全屏窗口
 * （点空白处收起），这时候底下整个屏都不响应是正常的 —— 抽屉本来就该这样。
 *
 * <p><b>实现要点</b>：
 * <ul>
 *   <li>两层窗口都必须是 {@code TYPE_ACCESSIBILITY_OVERLAY} ⇒ 只能由无障碍服务自己的上下文加
 *       （Activity 加不上），所以入口全走 {@link VolumeKeyService}；</li>
 *   <li>dp 换算要用<b>封面屏</b>的密度（340dpi），不是 {@code Resources.getSystem()}；</li>
 *   <li>感应带与面板是<b>两个窗口</b>：感应带一直很小，面板只在拉的时候才建、松手后拆掉。</li>
 * </ul>
 */
public final class CoverSidebar {

    private static final String TAG = "CoverSidebar";

    /** 感应带宽度（dp）。死区，越小越好 —— 跟手势条同一个道理 */
    private static final int EDGE_W_DP = 14;
    /**
     * 往里划多少（dp）就整张拉开。
     *
     * <p>之前是「跟手拖 + 松手看进度」：往里拖够 16dp 面板才出现、拖过三成八
     * （约 190px）松手才留得住，用户体感就是划了没反应。现在往里划够这一下就拉开。
     * 这条跟底部手势条<b>故意是两套逻辑</b>：底部要「上滑 + 停一下」，这边要「一划就出」。
     */
    private static final int TRIGGER_DP = 10;
    /**
     * 感应带占外屏高度的比例（原来固定切成 1/3）。
     *
     * <p>感应带是<b>死区</b>：落在带子上的触摸被我们收走，Android 转不给下面的应用。所以加长它
     * 不是在跟应用抢更多地方 —— 整条都在屏幕最边上那 14dp 里，加长只是让手指更容易蹭到，
     * 纵向的落点更宽容。
     *
     * <p>三段沿这条边均分：上段贴顶、下段贴底、中段居中（见 install 里的 bandY）。
     */
    private static final float BAND_RATIO = 0.45f;
    /** 面板宽度占屏宽的比例 */
    private static final float PANEL_RATIO = 0.68f;
    /** 开关动画时长（ms） */
    private static final long ANIM_MS = 190L;
    /** 打开时的短震动（ms） */
    private static final long BUZZ_MS = 14L;
    /** 面板里最多列几条通知 */
    private static final int MAX_NOTIF_ROWS = 6;
    /**
     * 感应带上那条常驻亮杠（竖着的"导航条"）：宽 / 高（dp）。
     *
     * <p>v4.11 从 40dp 拉到 64dp。用户原话是"亮杠也画长一些，对<b>手感</b>提升比较大" ——
     * 它长什么样其实不重要，重要的是它一眼就告诉人"从这一段往下划"，
     * 划出来那条长约等于它，两头就不用反复试。宽度不动（5dp）。
     */
    private static final int HANDLE_W_DP = 5;
    private static final int HANDLE_H_DP = 64;
    /** 亮杠的两种亮度（View alpha）：歇着 / 按住 */
    private static final float HANDLE_REST = 0.5f;
    private static final float HANDLE_TOUCH = 0.95f;
    /** 亮杠回到常驻亮度的动画时长（ms） */
    private static final long HANDLE_ANIM_MS = 220L;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static WindowManager wm;
    /** 常驻的细感应带 */
    private static View band;
    /** 感应带上那条常驻亮杠（竖着的"导航条"） */
    private static View handle;
    /** 拉出来的那张面板（全屏窗口的根）；收起后拆掉，置 null */
    private static FrameLayout panelRoot;
    private static View panelCard;
    private static View scrim;
    private static LinearLayout itemsBox;
    private static TextView titleView;

    private static Context appCtx;
    private static int shownDisplay = -1;
    private static float density = 2.125f;
    private static int panelW;
    /** 0 = 左边，1 = 右边 */
    private static int side = 1;
    /** 感应带在窗口坐标里的左上角（圆环要把手指坐标换算到同一个空间里） */
    private static int bandLeft;
    private static int bandTop;
    /** 拖动状态 */
    private static float downX;
    private static float downY;
    private static boolean dragging;
    private static boolean open;
    /** 面板当前进度 0..1（0 = 完全收起） */
    private static float progress;

    private CoverSidebar() {
    }

    // ------------------------------------------------------------------ 生命周期

    public static boolean installed() {
        return band != null;
    }

    /** 按当前设置对齐：开关开着且找得到封面屏 ⇒ 装上；否则摘掉 */
    public static void sync(Context ctx) {
        boolean want = ExtPrefs.coverSidebar(ctx);
        int display = CoverDisplay.id(ctx);
        Log.d(TAG, "sync want=" + want + " 封面屏=" + display + " 已装=" + installed());
        if (!want || display <= 0) {
            remove();
            return;
        }
        if (band != null && shownDisplay == display) {
            return;
        }
        remove();
        install(ctx, display);
    }

    /** 位置 / 内容改了 ⇒ 拆了重装（位置这东西只有重建窗口才生效） */
    public static void refresh(Context ctx) {
        remove();
        sync(ctx);
    }

    public static void remove() {
        View b = band;
        FrameLayout r = panelRoot;
        WindowManager w = wm;
        band = null;
        handle = null;
        panelRoot = null;
        panelCard = null;
        scrim = null;
        itemsBox = null;
        titleView = null;
        shownDisplay = -1;
        open = false;
        dragging = false;
        progress = 0;
        try {
            if (r != null && w != null) {
                w.removeViewImmediate(r);
            }
        } catch (Throwable t) {
            Log.w(TAG, "拆面板失败", t);
        }
        try {
            if (b != null && w != null) {
                w.removeViewImmediate(b);
            }
        } catch (Throwable t) {
            Log.w(TAG, "拆感应带失败", t);
        }
        if (b != null || r != null) {
            Log.i(TAG, "侧边栏已摘掉");
        }
        // 几套动画与通知中心是另外几层窗口，一起收掉
        CoverWheel.release();
        CoverGear.release();
        CoverRoulette.release();
        CoverNotifCenter.release();
        wm = null;
        appCtx = null;
    }

    private static void install(Context ctx, int display) {
        if (!(ctx instanceof AccessibilityService)) {
            Log.w(TAG, "不是无障碍服务上下文，装不了侧边栏");
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
            int h = displayCtx.getResources().getDisplayMetrics().heightPixels;
            int wpx = displayCtx.getResources().getDisplayMetrics().widthPixels;
            appCtx = ctx.getApplicationContext();
            side = ExtPrefs.sidebarSide(ctx);
            panelW = Math.round(wpx * PANEL_RATIO);

            int zone = ExtPrefs.sidebarZone(ctx);
            if (zone < 0 || zone > 2) {
                zone = 0;
            }
            // 感应带有多长（v4.11 起自己占屏高 BAND_RATIO，不再固定 1/3）。
            // 三段沿这条边均分 ⇒ 空余的那一截要除 2：上段贴顶、中段居中、下段贴底。
            int bandH = Math.max(dp(140), Math.round(h * BAND_RATIO));
            int bandY = Math.round(zone * (h - bandH) / 2f);

            // 感应带本体透明，只在正中放一条竖着的亮杠（导航条）
            FrameLayout box = new FrameLayout(displayCtx);
            handle = makeHandle(displayCtx);
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                    dp(HANDLE_W_DP), dp(HANDLE_H_DP));
            hlp.gravity = Gravity.CENTER;
            box.addView(handle, hlp);
            box.setOnTouchListener((v, e) -> onBandTouch(v, e));
            band = box;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(EDGE_W_DP),
                    bandH,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | (side == 1 ? Gravity.END : Gravity.START);
            lp.y = bandY;

            // 几套动画与通知中心要跟感应带在同一个窗口坐标空间里（封面屏那 66px，RULES §64.1）
            bandTop = bandY;
            bandLeft = side == 1 ? wpx - dp(EDGE_W_DP) : 0;
            CoverWheel.env(displayCtx, wm, wpx, density, side);
            CoverGear.env(displayCtx, wm, wpx, h, density, side);
            CoverRoulette.env(displayCtx, wm, wpx, h, density, side);
            CoverNotifCenter.env(displayCtx, wm, display, wpx, h, density);

            wm.addView(band, lp);
            shownDisplay = display;
            Log.i(TAG, "感应带已装到 display " + display + "：" + (side == 1 ? "右" : "左")
                    + "侧" + (zone == 0 ? "上" : zone == 1 ? "中" : "下") + "段，宽 "
                    + dp(EDGE_W_DP) + "px 高 " + bandH + "px；面板宽 " + panelW
                    + "px；导航条 " + dp(HANDLE_W_DP) + "x" + dp(HANDLE_H_DP) + "px");
        } catch (Throwable t) {
            Log.e(TAG, "装侧边栏失败", t);
            remove();
        }
    }

    // ------------------------------------------------------------------ 触摸：感应带

    private static boolean onBandTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                UI.removeCallbacksAndMessages(null);
                handleTo(HANDLE_TOUCH, 1.06f, 90);
                // 圆心就落在手指按下的那一点（轮盘是个整圆，它自己再往里挪一个半径）
                animPrepare(winX(e.getX()), winY(e.getY()));
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (dragging) {
                    // 动画跟着手指走：划进哪一块哪一块就亮，松手才算数
                    animMove(winX(e.getX()), winY(e.getY()));
                    return true;
                }
                float in = inward(e.getX());
                if (in < dp(TRIGGER_DP)) {
                    return true;
                }
                // 主要在上下划（贴着边滚列表）就不算，别把滚动当成拉面板
                if (in < Math.abs(e.getY() - downY)) {
                    return true;
                }
                dragging = true;
                if (animKind() == 2) {
                    buildPanel();
                    settle(true);
                } else {
                    // v4.9 起：划出来这一下**不震动**（震动时机定在"换块"那一下）
                    animShow(winX(e.getX()), winY(e.getY()));
                }
                // 亮杠跟着划出去隐掉并抻长，跟底部那条一个意思
                handleTo(HANDLE_TOUCH * 0.08f, 1.5f, 140);
                Log.i(TAG, "滑动即出：往内 " + (int) in + "px");
                return true;
            }

            case MotionEvent.ACTION_UP: {
                UI.removeCallbacksAndMessages(null);
                if (dragging) {
                    // 面板正在开（见 MOVE）：什么都别做，等它开完
                    dragging = false;
                    handleTo(HANDLE_REST, 1f, HANDLE_ANIM_MS);
                    // 松手才算数：停在哪一档就执行哪一档（没选中就是 RING_NONE）
                    runRingAction(animPickAndHide());
                    return true;
                }
                handleTo(HANDLE_REST, 1f, HANDLE_ANIM_MS);
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                UI.removeCallbacksAndMessages(null);
                // 被系统打断（不是正常松手）：收起动画，什么都不执行
                dragging = false;
                animHide();
                handleTo(HANDLE_REST, 1f, HANDLE_ANIM_MS);
                return true;

            default:
                return true;
        }
    }

    /**
     * 现在用的是哪一套动画：0 = 动力圆环（缺省），1 = 调速齿轮，2 = 旧的抽屉面板，
     * 3 = 抽奖轮盘。
     *
     * <p>认不出来的值一律回落到 0 —— 偏好里存着脏值时，宁可给圆环也别给一片空白。
     */
    private static int animKind() {
        Context c = appCtx;
        int v = c == null ? 0 : ExtPrefs.sidebarStyle(c);
        return (v >= 0 && v <= 3) ? v : 0;
    }

    /** 手指按下：把圆心交给当档那套动画（旧的抽屉不用这一套） */
    private static void animPrepare(float x, float y) {
        switch (animKind()) {
            case 1:
                CoverGear.prepare(x, y);
                break;
            case 3:
                CoverRoulette.prepare(x, y);
                break;
            case 2:
                break;
            default:
                CoverWheel.prepare(x, y);
                break;
        }
    }

    /** 拖动中：让当档那套动画跟着手指更新选中 */
    private static void animMove(float x, float y) {
        switch (animKind()) {
            case 1:
                CoverGear.move(x, y);
                break;
            case 3:
                CoverRoulette.move(x, y);
                break;
            case 2:
                break;
            default:
                CoverWheel.move(x, y);
                break;
        }
    }

    /** 划够距离：把当档那套动画点亮 */
    private static void animShow(float x, float y) {
        switch (animKind()) {
            case 1:
                CoverGear.show(x, y);
                break;
            case 3:
                CoverRoulette.show(x, y);
                break;
            case 2:
                break;
            default:
                CoverWheel.show(x, y);
                break;
        }
    }

    /**
     * 松手：看<b>谁正亮着</b>就找谁要结果，顺带把它收起来。
     *
     * <p>⚠ 这里按"谁亮着"分派，不按 {@link #animKind()} —— 亮着的那一套才是真正在收手指
     * 事件的那一套，按偏好去问会问错人。
     */
    private static int animPickAndHide() {
        if (CoverGear.showing()) {
            int act = CoverGear.pick();
            CoverGear.hide();
            return act;
        }
        if (CoverRoulette.showing()) {
            int act = CoverRoulette.pick();
            CoverRoulette.hide();
            return act;
        }
        if (CoverWheel.showing()) {
            int act = CoverWheel.pick();
            CoverWheel.hide();
            return act;
        }
        return ExtPrefs.RING_NONE;
    }

    /** 全套收掉（被系统打断时用） */
    private static void animHide() {
        CoverWheel.hide();
        CoverGear.hide();
        CoverRoulette.hide();
    }

    /** 感应带内部坐标 -> 这个窗口空间里的坐标（跟感应带同一套，RULES §64.1） */
    private static float winX(float localX) {
        return bandLeft + localX;
    }

    private static float winY(float localY) {
        return bandTop + localY;
    }

    /** 松手落到哪一档就执行哪一档（几套动画共用） */
    private static void runRingAction(int act) {
        switch (act) {
            case ExtPrefs.RING_BACK:
                CoverRecentsGesture.goBack();
                break;
            case ExtPrefs.RING_RECENTS:
                CoverRecentsGesture.openRecentsNow(shownDisplay);
                break;
            case ExtPrefs.RING_NOTIFY:
                CoverNotifCenter.show();
                break;
            case ExtPrefs.RING_HOME:
                CoverRecentsGesture.goHome(shownDisplay);
                break;
            default:
                Log.i(TAG, "松手：没选中任何一档");
                break;
        }
    }

    /** 这次拖动"往屏内"走了多少（正数 = 往里） */
    private static float inward(float x) {
        // 右侧的感应带在屏幕右边，手指往左挪才是"往屏内"；左侧反过来
        return side == 1 ? (downX - x) : (x - downX);
    }

    private static void settle(boolean toOpen) {
        if (panelCard == null) {
            return;
        }
        float off = side == 1 ? panelW : -panelW;
        float target = toOpen ? 0f : off;
        panelCard.animate()
                .translationX(target)
                .alpha(toOpen ? 1f : 0.65f)
                .setDuration(ANIM_MS)
                .withEndAction(() -> {
                    if (toOpen) {
                        open = true;
                        progress = 1f;
                    } else {
                        teardownPanel();
                    }
                })
                .start();
        if (scrim != null) {
            scrim.animate().alpha(toOpen ? 1f : 0f).setDuration(ANIM_MS).start();
        }
    }

    /** 面板已打开时让它收起（点空白、点条目都走它） */
    public static void close() {
        if (panelRoot == null || !open) {
            return;
        }
        open = false;
        settle(false);
    }

    private static void teardownPanel() {
        FrameLayout r = panelRoot;
        panelRoot = null;
        panelCard = null;
        scrim = null;
        itemsBox = null;
        titleView = null;
        progress = 0;
        // 面板收起了：亮杠该回来了（拉出去的时候它淡掉了）
        handleTo(HANDLE_REST, 1f, HANDLE_ANIM_MS);
        if (r != null && wm != null) {
            try {
                wm.removeViewImmediate(r);
            } catch (Throwable t) {
                Log.w(TAG, "拆面板失败", t);
            }
        }
    }

    // ------------------------------------------------------------------ 面板

    private static void buildPanel() {
        if (panelRoot != null || wm == null || appCtx == null) {
            return;
        }
        try {
            Context c = band.getContext();
            FrameLayout root = new FrameLayout(c);
            scrim = new View(c);
            scrim.setBackgroundColor(0x33000000);
            scrim.setOnClickListener(v -> close());

            panelCard = buildCard(c);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    panelW, ViewGroup.LayoutParams.MATCH_PARENT);
            plp.gravity = side == 1 ? Gravity.END : Gravity.START;
            plp.topMargin = dp(6);
            plp.bottomMargin = dp(6);

            root.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(panelCard, plp);

            float off = side == 1 ? panelW : -panelW;
            panelCard.setTranslationX(off);
            panelCard.setAlpha(0.65f);
            scrim.setAlpha(0f);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // 这一层要吃掉全部触摸（抽屉行为），所以**不加** NOT_TOUCH_MODAL
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            wm.addView(root, lp);
            panelRoot = root;
            fillItems(c);
            Log.i(TAG, "面板已拉起（" + (side == 1 ? "右侧" : "左侧") + "）");
        } catch (Throwable t) {
            Log.e(TAG, "拉面板失败", t);
            teardownPanel();
        }
    }

    /** 面板骨架：高透明圆角卡片 + 标题 + 滚动列表 + 底部提示 */
    private static View buildCard(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);          // 吃掉落在卡片空白处的触摸，别漏给蒙层
        GradientDrawable bg = new GradientDrawable();
        // 高透明度：72% 的深色 —— 用户要的就是"能透出底下的东西"
        bg.setColor(0xB81B1B1F);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), 0x1FFFFFFF);
        card.setBackground(bg);
        int pad = dp(10);
        card.setPadding(pad, pad + dp(2), pad, pad);

        titleView = new TextView(c);
        titleView.setText("快捷面板");
        titleView.setTextColor(0x99FFFFFF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        titleView.setPadding(dp(8), dp(2), dp(8), dp(6));
        card.addView(titleView);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        itemsBox = new LinearLayout(c);
        itemsBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(itemsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(c);
        hint.setText("点空白处收起");
        hint.setTextColor(0x59FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        hint.setPadding(dp(8), dp(6), dp(8), dp(2));
        card.addView(hint);
        return card;
    }

    /** 按设置把列表填满（每次拉开、每次通知变动都重来一遍） */
    private static void fillItems(Context c) {
        if (itemsBox == null) {
            return;
        }
        itemsBox.removeAllViews();
        try {
            // 顺序就是「面板内容」那四行从上到下的次序（勾几项就几行，箭头可调）
            for (int act : ExtPrefs.sidebarSlots(c)) {
                switch (act) {
                    case ExtPrefs.RING_NOTIFY:
                        if (!CoverNotifListener.granted(c)) {
                            itemsBox.addView(actionRow(c, "授予通知使用权", "还没授权，点这里",
                                    () -> grantNotif(c)));
                        } else {
                            CoverNotifListener.refreshAll();
                            List<CoverNotifListener.Entry> list = CoverNotifListener.snapshot();
                            if (list.isEmpty()) {
                                itemsBox.addView(actionRow(c, "暂时没有通知", null, null));
                            } else {
                                int n = Math.min(MAX_NOTIF_ROWS, list.size());
                                for (int i = 0; i < n; i++) {
                                    itemsBox.addView(notifRow(c, list.get(i)));
                                }
                                if (list.size() > n) {
                                    itemsBox.addView(actionRow(c,
                                            "还有 " + (list.size() - n) + " 条未显示", null, null));
                                }
                            }
                        }
                        break;
                    case ExtPrefs.RING_RECENTS:
                        itemsBox.addView(actionRow(c, "最近任务", null, () -> {
                            close();
                            CoverRecentsGesture.openRecentsNow(shownDisplay);
                        }));
                        break;
                    case ExtPrefs.RING_BACK:
                        itemsBox.addView(actionRow(c, "返回", null, () -> {
                            close();
                            CoverRecentsGesture.goBack();
                        }));
                        break;
                    case ExtPrefs.RING_HOME:
                        itemsBox.addView(actionRow(c, "回到桌面", null, () -> {
                            close();
                            CoverRecentsGesture.goHome(shownDisplay);
                        }));
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "填面板失败", t);
        }
    }

    /** 通知一变就重填（只在本进程、只在面板开着时干活） */
    public static void onNotificationsChanged() {
        // 通知中心也吃这份快照
        CoverNotifCenter.onNotificationsChanged();
        if (panelRoot == null) {
            return;
        }
        UI.post(() -> {
            if (itemsBox != null && appCtx != null) {
                fillItems(itemsBox.getContext());
            }
        });
    }

    // ------------------------------------------------------------------ 行

    /** 通知行：标题 + 正文，点一下打开、右边 × 清掉 */
    private static View notifRow(Context c, CoverNotifListener.Entry e) {
        LinearLayout row = baseRow(c);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(c);
        t.setText(e.title);
        t.setTextColor(0xF0FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(t);

        if (!TextUtils.isEmpty(e.text)) {
            TextView s = new TextView(c);
            s.setText(e.text);
            s.setTextColor(0x8CFFFFFF);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            s.setMaxLines(2);
            s.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(s);
        }
        row.addView(body);

        // × 放大：外屏上手指粗，够得着才算数 —— 撑到 40dp 见方
        TextView x = new TextView(c);
        x.setText("×");
        x.setTextColor(0x99FFFFFF);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        x.setGravity(Gravity.CENTER);
        x.setMinWidth(dp(40));
        x.setMinHeight(dp(40));
        x.setPadding(dp(6), 0, dp(6), 0);
        x.setOnClickListener(v -> {
            CoverNotifListener.dismiss(e.key);
            if (itemsBox != null) {
                fillItems(itemsBox.getContext());
            }
        });
        row.addView(x);

        row.setOnClickListener(v -> {
            boolean went = false;
            try {
                if (e.contentIntent != null) {
                    e.contentIntent.send();
                    went = true;
                }
            } catch (Throwable err) {
                Log.w(TAG, "打开通知失败", err);
            }
            if (!went) {
                // 没有 contentIntent 就退一步：把那个应用的启动界面拉起来
                final String pkg = e.pkg;
                new Thread(() -> ShellRunner.run(
                        "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1", 10),
                        "sidebar-open").start();
            }
            close();
        });
        return row;
    }

    /** 动作行：小圆点 + 文字（runnable 为 null 表示纯提示行，不可点） */
    private static View actionRow(Context c, String label, String sub, Runnable action) {
        LinearLayout row = baseRow(c);
        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(action == null ? 0x40FFFFFF : 0xCC7AA2FF);
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.rightMargin = dp(9);
        dlp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(dot, dlp);

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(0xF0FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        body.addView(t);
        if (!TextUtils.isEmpty(sub)) {
            TextView s = new TextView(c);
            s.setText(sub);
            s.setTextColor(0x8CFFFFFF);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            body.addView(s);
        }
        row.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (action != null) {
            row.setOnClickListener(v -> action.run());
        }
        return row;
    }

    private static LinearLayout baseRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        row.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(14));
        row.setBackground(bg);
        // 按下去有反馈（API 23+ 才有 setForeground）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            row.setForeground(new RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF), null, null));
        }
        return row;
    }

    // ------------------------------------------------------------------ 动作

    private static void grantNotif(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String cmd = CoverNotifListener.grantCmd(app);
            String out = ShellRunner.run(cmd, 12);
            Log.i(TAG, "授予通知使用权 => " + out);
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
            CoverNotifListener.refreshAll();
            UI.post(() -> {
                if (itemsBox != null) {
                    fillItems(app);
                }
            });
        }, "sidebar-grant").start();
    }

    /**
     * 短震动。
     *
     * <p>⚠ v4.9 起侧边栏<b>触发时不再调它</b>（用户："在生效之前暂时不要震动"，
     * 具体震动时机他后面再说）。留着没删，等他定了接回哪儿。
     */
    private static void buzz() {
        Context c = appCtx;
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
            int amp = v.hasAmplitudeControl() ? 120 : VibrationEffect.DEFAULT_AMPLITUDE;
            v.vibrate(VibrationEffect.createOneShot(BUZZ_MS, amp));
        } catch (Throwable t) {
            Log.w(TAG, "震动失败", t);
        }
    }

    // ------------------------------------------------------------------ 小工具

    // ------------------------------------------------------------------ 导航条

    /** 造感应带上那条常驻亮杠（竖着的导航条）。白底，明暗靠 alpha 调 */
    private static View makeHandle(Context c) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(dp(HANDLE_W_DP) / 2f);
        v.setBackground(d);
        v.setAlpha(HANDLE_REST);
        return v;
    }

    /** 亮杠动过去（按住变亮 / 松手回位） */
    private static void handleTo(float alpha, float scaleY, long ms) {
        View h = handle;
        if (h == null) {
            return;
        }
        h.animate().cancel();
        h.animate().alpha(alpha).scaleY(scaleY).setDuration(ms).start();
    }

    /** 亮杠立刻到位（拖动时每帧都调，别叠动画） */
    private static void handleNow(float alpha, float scaleY) {
        View h = handle;
        if (h == null) {
            return;
        }
        h.animate().cancel();
        h.setAlpha(alpha);
        h.setScaleY(scaleY);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int dp(float v) {
        return Math.round(v * density);
    }

    /** 面板里那点"看着像小组件"的底色（给外部预览用，也顺手统一色调） */
    public static int themeColor() {
        return Color.argb(0xB8, 0x1B, 0x1B, 0x1F);
    }
}
