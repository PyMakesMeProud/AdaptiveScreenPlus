package com.wb.extrotator;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * 手势条划出去时那层「整屏模糊」。
 *
 * <p>手势被认下之后（上滑够格），整块外屏跟着糊起来；上滑越多越糊，松手淡掉。糊的那一层画在
 * 捕获带<b>下面</b>，所以那条亮杠始终是清楚的。
 *
 * <p><b>为什么是「抓一帧再糊它」</b>：让合成器模糊窗口后面的内容只有一条公开路 ——
 * {@link WindowManager.LayoutParams#FLAG_BLUR_BEHIND} 再加 {@code setBlurBehindRadius}。
 * 它更活，但那层模糊由系统按窗口类型决定，无障碍覆盖窗口吃不吃得到没有任何保证；更要命的是
 * 它跟窗口自己的 View 透明度无关，一旦哪次没关干净，整块屏会一直糊着，很难收场。所以走自己能
 * 完全掌控的一条：用手势条自己的无障碍服务抓一帧画面，画在自己的全屏覆盖窗口里，
 * 模糊半径与透明度都按上滑距离算。
 *
 * <p>⚠ <b>几何：这一层必须按「整块显示」给，别用资源度量</b>：
 * {@code displayCtx.getResources().getDisplayMetrics()} 给的是 <b>app 区</b>（外屏 748x654，
 * 比整屏少了 66px 系统条），而 {@code takeScreenshot(display)} 抓回来的是<b>整屏 748x720</b>。
 * 拿 654 去开窗口、再用 FIT_XY 贴 720 的帧，结果就是纵向被压扁 9.2%、底部 66px 还漏在外面
 * （截图里能看到一条笔直的「没糊」边界）。所以尺寸一律用 {@link Display#getRealSize}
 * （退化时才用资源度量），并用 {@code Gravity.BOTTOM} 锚。
 *
 * <p>⚠ <b>抓帧的两个坑</b>：抓帧前这一层必须是<b>隐形的</b>，否则会把自己的糊面也抓进去
 * （越糊越糊）—— 所以按下（{@link #prepare()}）就先置零透明度，趁「按下 → 够格」这段空档
 * 把帧抓了；抓帧是异步的，回调可能在松手<b>之后</b>才回来，所以用一个 {@code visible} 闸门，
 * 只有还在手势里才允许画，不然糊面会留在屏上不走。
 *
 * <p>⚠ 服务上下文加的窗口<b>默认不是硬件加速</b>的，而 {@link RenderEffect} 在软件渲染下会被
 * 直接忽略 —— 窗口必须带 {@code FLAG_HARDWARE_ACCELERATED}，少了它什么都不糊。
 */
public final class CoverBlur {

    private static final String TAG = "CoverBlur";

    /** 刚出现时的模糊半径（dp）。不从 0 起，太浅的糊看着像画面脏 */
    private static final float RAD_MIN_DP = 5f;
    /** 划到头时的半径（dp）。封面屏 340dpi，20dp 约 43px，已经是认不出内容的霜了 */
    private static final float RAD_MAX_DP = 20f;
    /** 上滑到屏高的这个比例才满糊。给得长一点，"越划越糊"要让人划得出来 */
    private static final float FULL_AT_H = 0.40f;
    /** 出现之后，从屏高的这个比例开始淡入 */
    private static final float ALPHA_FROM_H = 0.06f;
    /** 淡入用掉屏高的这个比例 */
    private static final float ALPHA_IN_H = 0.09f;
    /** 有画面时压在最上面的暗度，让糊面沉一点 */
    private static final float SCRIM = 0.14f;
    /** 抓不到画面时只压暗度，这时压重一点，至少看得出"在动" */
    private static final float SCRIM_NO_SHOT = 0.55f;
    /** 松手之后的淡出 */
    private static final long FADE_OUT_MS = 200L;
    /** 手上那帧超过这么久就算旧了，下次按下重抓 */
    private static final long SHOT_FRESH_MS = 1500L;
    /** 抓帧失败后的退避。别设成"永久放弃"：系统的按屏截图有间隔限制，撞上只是这一下的问题 */
    private static final long RETRY_MS = 1500L;
    /** 半径变化不到这么多就不重画，省掉一堆无谓的重绘 */
    private static final float RAD_STEP = 2f;
    /** 判"抓回来是空图"用的亮度范围阈值 */
    private static final int BLANK_RANGE = 10;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static WindowManager wm;
    private static FrameLayout root;
    private static ImageView image;
    private static View scrim;
    private static AccessibilityService svc;
    private static int display = -1;
    private static int displayW;
    private static int displayH;

    private static Bitmap shot;
    private static long shotAt;
    private static boolean capturing;
    /** 抓帧失败后的退避截止时刻（uptimeMillis） */
    private static long retryAfter;
    /** 还在手势里：只有它为真才允许把这一层画出来 */
    private static boolean visible;
    /** 手上那帧贴到 ImageView 上了没有 */
    private static boolean painted;
    /** 这一次上滑的最高点（px），半径与透明度都按它算 */
    private static float curUp;
    private static float lastRadius = -1f;
    private static float radMin = 11f;
    private static float radMax = 43f;
    private static float fullAt = 288f;
    private static float alphaFrom = 43f;
    private static float alphaIn = 65f;

    private CoverBlur() {
    }

    /** 这一层挂上了没有（排错用） */
    static boolean attached() {
        return root != null;
    }

    /**
     * 挂上这一层。
     *
     * <p>⚠ 必须在捕获带<b>之前</b>调：同一类窗口是后加的在上，反了的话拖动时
     * 这块糊面会把那条亮杠盖住。
     */
    static void attach(Context displayCtx, WindowManager w, AccessibilityService s,
                       int displayId, int wPx, int hPx) {
        if (w == null || root != null || wPx <= 0 || hPx <= 0) {
            return;
        }
        try {
            float d = displayCtx.getResources().getDisplayMetrics().density;
            if (d <= 0f) {
                d = 2.125f;
            }
            // 尺寸要"整块显示"，不是 app 区（见类注释）。这里以 getRealSize 为准。
            int wpx = wPx;
            int hpx = hPx;
            String how = "资源度量";
            try {
                DisplayManager dm =
                        (DisplayManager) displayCtx.getSystemService(Context.DISPLAY_SERVICE);
                Display disp = dm == null ? null : dm.getDisplay(displayId);
                if (disp != null) {
                    Point pt = new Point();
                    disp.getRealSize(pt);
                    if (pt.x > 0 && pt.y > 0) {
                        wpx = pt.x;
                        hpx = pt.y;
                        how = "整屏";
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "取整屏尺寸失败，退回资源度量", t);
            }

            radMin = RAD_MIN_DP * d;
            radMax = RAD_MAX_DP * d;
            fullAt = Math.max(1f, hpx * FULL_AT_H);
            alphaFrom = hpx * ALPHA_FROM_H;
            alphaIn = Math.max(1f, hpx * ALPHA_IN_H);

            FrameLayout r = new FrameLayout(displayCtx);
            ImageView iv = new ImageView(displayCtx);
            // 抓到的那一帧就是整屏，跟窗口 1:1；用 FIT_XY 是防着哪天尺寸对不上，
            // 至少铺满，不会露出一条没糊的边
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            r.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            View sc = new View(displayCtx);
            sc.setBackgroundColor(0xFF000000);
            sc.setAlpha(0f);
            r.addView(sc, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            r.setAlpha(0f);

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    wpx,
                    hpx,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // 不吃焦点、不吃触摸；两个 LAYOUT 旗标是为了铺满整块屏；
                    // HARDWARE_ACCELERATED 少了它 RenderEffect 会被静默忽略
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            // 用 BOTTOM 锚：跟捕获带同一个基准，实测贴的就是显示空间的下沿。
            // 高度给的是整屏，所以从 0 铺到屏底。
            p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            w.addView(r, p);

            wm = w;
            root = r;
            image = iv;
            scrim = sc;
            svc = s;
            display = displayId;
            displayW = wpx;
            displayH = hpx;
            shot = null;
            shotAt = 0L;
            capturing = false;
            retryAfter = 0L;
            visible = false;
            painted = false;
            curUp = 0f;
            lastRadius = -1f;
            Log.i(TAG, "模糊层已装上（在捕获带下面），窗口 " + wpx + "x" + hpx + "px（" + how
                    + "），半径 " + (int) radMin + "~" + (int) radMax + "px，满糊在 "
                    + (int) fullAt + "px，全不透明在 "
                    + (int) (alphaFrom + alphaIn) + "px");
        } catch (Throwable t) {
            Log.w(TAG, "装模糊层失败（不影响手势条本身）", t);
            reset();
        }
    }

    /**
     * 按下时调（手势还没成）。干两件事：把这一层先置成隐形（否则抓帧会把自己抓进去），
     * 以及趁"按下 → 上滑够格"这段空档把手上那帧换新。
     */
    static void prepare() {
        FrameLayout r = root;
        if (r == null) {
            return;
        }
        r.animate().cancel();
        r.setAlpha(0f);
        visible = false;
        curUp = 0f;
        long now = SystemClock.uptimeMillis();
        if (shot == null || now - shotAt > SHOT_FRESH_MS) {
            invalidate();
            capture();
        }
    }

    /**
     * 手势被认下之后每动一下就调一次，参数是这一次上滑的最高点（px）。
     * 只管"要糊多少"，什么时候开始、什么时候收，都在调用方那边。
     */
    static void show(float upPx) {
        FrameLayout r = root;
        if (r == null) {
            return;
        }
        visible = true;
        curUp = upPx;
        if (shot == null) {
            capture();
        }
        render();
    }

    /** 松手 / 放弃 / 被系统收走：淡掉 */
    static void hide() {
        FrameLayout r = root;
        if (r == null) {
            return;
        }
        visible = false;
        r.animate().cancel();
        r.animate().alpha(0f).setDuration(FADE_OUT_MS).start();
    }

    /** 画面要换走了（打开后台 / 回主界面）=> 手上这帧作废，别拿旧画面糊 */
    static void invalidate() {
        Bitmap b = shot;
        shot = null;
        shotAt = 0L;
        painted = false;
        if (image != null) {
            image.setImageDrawable(null);
        }
        if (b != null) {
            b.recycle();
        }
    }

    /** 摘掉（服务断开 / 开关关掉 / 手势条重装） */
    static void detach() {
        FrameLayout r = root;
        WindowManager w = wm;
        reset();
        if (r != null && w != null) {
            try {
                w.removeViewImmediate(r);
                Log.i(TAG, "模糊层已摘掉");
            } catch (Throwable t) {
                Log.w(TAG, "摘模糊层失败", t);
            }
        }
    }

    private static void reset() {
        if (image != null) {
            image.setImageDrawable(null);
        }
        wm = null;
        root = null;
        image = null;
        scrim = null;
        svc = null;
        display = -1;
        displayW = 0;
        displayH = 0;
        capturing = false;
        retryAfter = 0L;
        visible = false;
        painted = false;
        lastRadius = -1f;
        curUp = 0f;
        Bitmap b = shot;
        shot = null;
        shotAt = 0L;
        if (b != null) {
            b.recycle();
        }
    }

    // ------------------------------------------------------------------ 画

    private static void render() {
        FrameLayout r = root;
        ImageView iv = image;
        View sc = scrim;
        if (r == null || iv == null || sc == null) {
            return;
        }
        // 抓帧回调可能在松手之后才回来，那时这一层该是隐形的，什么都别动
        if (!visible) {
            return;
        }
        if (shot != null && !painted) {
            iv.setImageBitmap(shot);
            painted = true;
        }
        float p = curUp / fullAt;
        if (p < 0f) {
            p = 0f;
        }
        if (p > 1f) {
            p = 1f;
        }
        float a = (curUp - alphaFrom) / alphaIn;
        if (a < 0f) {
            a = 0f;
        }
        if (a > 1f) {
            a = 1f;
        }
        float radius = radMin + (radMax - radMin) * p;
        if (Math.abs(radius - lastRadius) >= RAD_STEP) {
            lastRadius = radius;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                iv.setRenderEffect(RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP));
            }
        }
        sc.setAlpha((shot == null ? SCRIM_NO_SHOT : SCRIM) * p);
        if (Math.abs(r.getAlpha() - a) > 0.01f) {
            r.animate().cancel();
            r.setAlpha(a);
        }
    }

    // ------------------------------------------------------------------ 抓帧

    private static void capture() {
        if (capturing || shot != null || svc == null || display <= 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now < retryAfter) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            retryAfter = now + 60000L;
            Log.w(TAG, "系统低于 Android 11，没有按屏截图这条路，改用暗度");
            return;
        }
        capturing = true;
        try {
            svc.takeScreenshot(display, svc.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult r) {
                            Bitmap b = null;
                            try {
                                HardwareBuffer buf = r.getHardwareBuffer();
                                Bitmap hb = Bitmap.wrapHardwareBuffer(buf, r.getColorSpace());
                                if (hb != null) {
                                    b = hb.copy(Bitmap.Config.ARGB_8888, false);
                                }
                                buf.close();
                            } catch (Throwable t) {
                                Log.w(TAG, "把截到的帧转成 bitmap 失败", t);
                            }
                            capturing = false;
                            if (b == null) {
                                retryAfter = SystemClock.uptimeMillis() + RETRY_MS;
                                Log.w(TAG, "帧转换失败，改用暗度");
                                return;
                            }
                            if (blank(b)) {
                                b.recycle();
                                retryAfter = SystemClock.uptimeMillis() + RETRY_MS;
                                Log.w(TAG, "抓回来是空图（多半被 FLAG_SECURE 挡了），改用暗度");
                                return;
                            }
                            shot = b;
                            shotAt = SystemClock.uptimeMillis();
                            if (b.getWidth() != displayW || b.getHeight() != displayH) {
                                Log.w(TAG, "⚠ 帧 " + b.getWidth() + "x" + b.getHeight()
                                        + " 与窗口 " + displayW + "x" + displayH
                                        + " 不一致，画面会被拉伸");
                            } else {
                                Log.i(TAG, "抓到一帧 " + b.getWidth() + "x" + b.getHeight()
                                        + "，与窗口一致");
                            }
                            UI.post(CoverBlur::render);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            capturing = false;
                            retryAfter = SystemClock.uptimeMillis() + RETRY_MS;
                            Log.w(TAG, "截图失败 code=" + errorCode + "，这一手改用暗度");
                        }
                    });
        } catch (Throwable t) {
            capturing = false;
            retryAfter = SystemClock.uptimeMillis() + RETRY_MS;
            Log.w(TAG, "takeScreenshot 抛了（多半是服务配置里没声明 canTakeScreenshot）", t);
        }
    }

    /**
     * 抓回来是不是一张空图。
     *
     * <p>缩到 16x16 看亮度的极差：被 FLAG_SECURE 挡住的窗口截出来是纯黑，
     * 极差接近 0，一眼就能认出来。真画面（哪怕壁纸很素）极差也不会这么小。
     */
    private static boolean blank(Bitmap b) {
        Bitmap small = null;
        try {
            small = Bitmap.createScaledBitmap(b, 16, 16, true);
            int min = 255;
            int max = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int c = small.getPixel(x, y);
                    int l = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100;
                    if (l < min) {
                        min = l;
                    }
                    if (l > max) {
                        max = l;
                    }
                }
            }
            return max - min < BLANK_RANGE;
        } catch (Throwable t) {
            return false;
        } finally {
            if (small != null && small != b) {
                small.recycle();
            }
        }
    }
}
