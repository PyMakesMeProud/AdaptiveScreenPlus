package com.wb.extrotator;

import android.accessibilityservice.AccessibilityService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * 内屏手势的<b>动画层</b>：手势走到哪，画面跟到哪。
 *
 * <p>一块整屏大、<b>不吃触摸</b>的无障碍覆盖窗口，专门负责画。认手势和画动画是两件事，
 * 所以跟 {@link InnerGesture} 分开放：那边只管"这一划算不算"，这边只管"看起来像什么"。
 *
 * <p><b>底部上滑</b>按风格分两种画法：
 * <ul>
 *   <li><b>原生风格</b> —— 抓一帧画面铺满整屏、随上滑越来越糊，底下再压一层暗度；
 *       做法与 {@link CoverBlur}（外屏手势条）完全一致。换掉原来那张"缩小的卡片"是因为：
 *       卡片要把整屏缩进一个小框里，横竖屏切换的一瞬间比例对不上就会被拉成一条，
 *       而且卡片框比屏幕小，边上还会漏一条没盖住。整屏 1:1 铺开就没有这两个问题。</li>
 *   <li><b>炫彩风格</b> —— 沿底边亮起<b>一条横向彩带</b>，跟侧滑是同一套画法（不糊画面）。
 *       照 MyGesture 那张 {@code bottom_light.png}：它的「边缘多彩光效」三条边共用一套
 *       贴边贴图，底边有自己的一张横条（整条屏宽 × 约 5% 屏高）。</li>
 * </ul>
 * <p>底部"划多远算糊满"由 {@code InnerGesture} 按屏高比例折算好再喂进来（现在 = 屏高的 70%）。
 * 这跟外屏手势条的 40% 本来就不一样 —— 那边快、这边慢，是有意分开的。
 *
 * <p><b>侧滑两档</b>（{@link ExtPrefs#gestureAnimStyle}）：
 * <ul>
 *   <li><b>原生风格</b>（{@link #STYLE_NATIVE}）—— 从被划的那条边推出一枚深色<b>圆</b>、
 *       圆心一个<b>指向屏幕内侧</b>的箭头。</li>
 *   <li><b>炫彩风格</b>（{@link #STYLE_GLOW}）—— 沿被划那条边叠出<b>一条渐变彩色光带</b>：
 *       贴边那一线最亮，往屏内 {@link #GLOW_DEPTH_DP} dp 之内收到没有，沿边两头也收到没有；
 *       整条按调色板顺次推移颜色，看着像一条 RGB 灯带。
 *       照的是 MyGesture 的<b>默认档「边缘多彩光效」</b>——它的真身是
 *       {@code left_light.png} 一张贴图被<b>拉伸成整条边高</b>，再用 {@link #TONES}
 *       里随机一个色 {@code setTint}，不透明度随划出距离涨。
 *       ⚠ 所以它<b>既不是同心圆环</b>（那是它的「水波」档、pro 的活，走另一个类），
 *       <b>也不是跟着手指走的一片柳叶弧</b>（我前两版抄错的就是这两种）。
 *       左右两边用 {@code left_light / right_light}，底边用 {@code bottom_light}
 *       ——<b>三条边同一套</b>，底边只是把长轴从竖的转成横的。</li>
 * </ul>
 *
 * <p>⚠ <b>窗口必须给 gravity</b>：不给的话系统按"居中"摆放，{@code x / y} 会变成
 * <b>相对屏幕中心的偏移</b> —— 贴在左边的那条带会被摆到屏幕正中，右边那条因为被夹回
 * 边界才"看起来是对的"。v4.21b 栽过这个（RULES §114.2）。
 *
 * <p>⚠ <b>加窗顺序 = 视觉层序</b>：同类窗口后加的在上，这一层必须由
 * {@link InnerGesture#install} 在三条捕获带<b>之后</b>加，才压在它们上面（它自己不吃触摸）。
 */
public final class GestureAnim {

    private static final String TAG = "GestureAnim";

    /** 手势种类：没有 / 左边返回 / 右边返回 / 底部上滑 */
    public static final int KIND_NONE = 0;
    public static final int KIND_BACK_L = 1;
    public static final int KIND_BACK_R = 2;
    public static final int KIND_HOME = 3;

    /** 动画风格：0 = 原生（默认），1 = 炫彩 */
    public static final int STYLE_NATIVE = 0;
    public static final int STYLE_GLOW = 1;

    /** 原生侧滑：那枚圆的半径、离屏边的起步距离、随进度再往里推的距离（dp） */
    private static final float NATIVE_R_DP = 17f;
    private static final float NATIVE_INSET_DP = 8f;
    private static final float NATIVE_PUSH_DP = 16f;
    /** 原生侧滑：箭头杆的一半、箭头翅（dp；都随进度再长一点） */
    private static final float NATIVE_ARM_DP = 5f;
    private static final float NATIVE_WING_DP = 4.5f;

    /**
     * 炫彩：光带沿边占多长（边长的比例）—— MyGesture 那条贴图就是整条边。
     * 侧滑按屏高算、底部按屏宽算（各自那条边有多长）。
     */
    private static final float GLOW_SPAN = 0.88f;
    /**
     * 炫彩：光带往屏内伸多远（dp；按进度再长一点）—— 32 → 40 是「光晕再明显一些」调的第二轮。
     */
    private static final float GLOW_DEPTH_DP = 40f;
    /** 炫彩：光带沿边排成的团数（相邻两团叠着，排成一条连续的带） */
    private static final int GLOW_BLOBS = 9;
    /** 炫彩：单团半径 = 边长的这个比例（比团间距大，才叠得上） */
    private static final float GLOW_BLOB_R = 0.115f;
    /** 炫彩：相邻两团隔调色板里的几格 —— 1 = 取相邻色，扫出来才像 RGB 渐变 */
    private static final int GLOW_HUE_STEP = 1;
    /**
     * 炫彩：单团的峰值不透明度。
     *
     * <p>最初按 {@code left_light.png} 逐像素量的峰值 180/255（= 0.71）反推成 0.34/团。
     * 真机上用户两轮都说不够明显：先调到 0.46，再调到 0.58 —— 九团叠起来比原版那张
     * 贴图更亮更浓。这是<b>有意</b>加重的「光污染」，不是量错了。
     * 再往上就得当心：九团 alpha 相加会把整条带糊成一片死白。
     */
    private static final float GLOW_GAIN = 0.58f;

    /**
     * 炫彩的 16 色。照 MyGesture 的调色板抄的（{@code me.hisn.utils.k} 里的静态数组）：
     * 红 → 粉 → 紫 → 靛 → 蓝 → 青 → 绿 → 黄 → 橙。它每次触发<b>随机取一个</b>，
     * 所以看着"像 RGB 一样"变化。
     */
    private static final int[] TONES = {
            0xFFE51C23, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7,
            0xFF3F51B5, 0xFF5677FC, 0xFF03A9F4, 0xFF00BCD4,
            0xFF009688, 0xFF259B24, 0xFF8BC34A, 0xFFCDDC39,
            0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722,
    };

    /** 底部上滑：模糊半径的起止（dp）、铺满全屏需要到的进度 */
    private static final float BLUR_MIN_DP = 7f;
    private static final float BLUR_MAX_DP = 26f;
    /** 画面抓到之后，到多少进度就完全不透明（别一上来就闪） */
    private static final float ALPHA_AT = 0.35f;
    /** 有画面时压在上面的暗度 / 没画面时（安全界面截出黑）压得更重 */
    private static final float SCRIM = 0.20f;
    private static final float SCRIM_NO_SHOT = 0.55f;
    /** 半径变化不到这么多就不重设，省掉一堆无谓的重绘 */
    private static final float RAD_STEP = 2f;
    /** 抓帧失败之后这么久不再试（系统对按屏截图有节流） */
    private static final long RETRY_MS = 1500L;

    private static WindowManager wm;
    private static AccessibilityService svc;
    private static AnimView root;
    private static float density = 3f;
    private static int sw;
    private static int sh;
    /** 手指现在在哪（整屏坐标）。侧滑那团光晕跟着它走 */
    private static float fingerX;
    private static float fingerY;

    private GestureAnim() {
    }

    /** 有没有装上 */
    static boolean installed() {
        return root != null;
    }

    /** 装上动画层。由 {@link InnerGesture#install} 在三条捕获带加完之后调（顺序要紧） */
    static void install(Context displayCtx, WindowManager w, AccessibilityService s,
                        int wPx, int hPx, float dens) {
        if (root != null) {
            return;
        }
        svc = s;
        wm = w;
        sw = wPx;
        sh = hPx;
        density = dens;
        fingerX = wPx / 2f;
        fingerY = hPx / 2f;
        try {
            AnimView v = new AnimView(displayCtx);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    sw, sh,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // 不吃焦点、不吃触摸（它只负责画）；两个 LAYOUT 旗标是为了真的铺满整块屏
                    //（少了 NO_LIMITS 会被系统条的 inset 顶下去一截）
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            // ⚠ 一定要给 gravity：不给的话 x / y 是"相对屏幕中心"的偏移
            p.gravity = Gravity.TOP | Gravity.START;
            p.x = 0;
            p.y = 0;
            w.addView(v, p);
            root = v;
            Log.i(TAG, "动画层已装：" + sw + "x" + sh + "px");
        } catch (Throwable t) {
            Log.e(TAG, "装动画层失败", t);
            wm = null;
            svc = null;
        }
    }

    /** 摘掉。无障碍服务断开时必须调 —— 留着它就是一块永远不走的画布 */
    static void remove() {
        AnimView v = root;
        WindowManager w = wm;
        root = null;
        wm = null;
        svc = null;
        if (v == null) {
            return;
        }
        v.abort();
        if (w != null) {
            try {
                w.removeViewImmediate(v);
            } catch (Throwable t) {
                Log.w(TAG, "摘动画层失败", t);
            }
        }
        Log.i(TAG, "动画层已摘掉");
    }

    /** 手势成立（越过门槛）时调一次。风格在这一刻读、颜色在这一刻挑，下一手才生效 */
    static void begin(int kind) {
        AnimView v = root;
        if (v == null || kind == KIND_NONE) {
            return;
        }
        v.start(kind, styleNow());
    }

    /** 手指现在在哪（整屏坐标）。侧滑那团光晕要贴着手指画，所以每个 MOVE 都喂一次 */
    static void at(float x, float y) {
        fingerX = x;
        fingerY = y;
    }

    /** 手指走到哪：0 = 刚够格，1 = 走满 */
    static void progress(float p) {
        AnimView v = root;
        if (v != null) {
            v.setProgress(p);
        }
    }

    /**
     * 松手。
     *
     * @param fired 动作真发出去了没有 —— 发出去了就把进度补满再淡出，没发就原地淡掉
     */
    static void end(boolean fired) {
        AnimView v = root;
        if (v != null) {
            v.finish(fired);
        }
    }

    /** 不是我们的手势（或手势被系统收走）：立刻收干净，别在屏上留东西 */
    static void cancel() {
        AnimView v = root;
        if (v != null) {
            v.abort();
        }
    }

    private static int styleNow() {
        Context c = svc;
        if (c == null) {
            return STYLE_NATIVE;
        }
        return ExtPrefs.gestureAnimStyle(c) == STYLE_GLOW ? STYLE_GLOW : STYLE_NATIVE;
    }

    private static float dpPx(float v) {
        return v * density;
    }

    /** 把 {@code v} 夹进 [lo, hi]；范围反了（屏太小）就取中间 */
    private static float clamp(float v, float lo, float hi) {
        if (hi <= lo) {
            return (lo + hi) / 2f;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------ 画布

    /**
     * 那一整块画布：底下是抓回来那帧（只给底部上滑用，会被模糊），
     * 上面压暗度，最上面是自绘的指示层。三个孩子都铺满，坐标就是整屏坐标。
     */
    private static final class AnimView extends FrameLayout {

        private final ImageView blurIv;
        private final View scrim;
        private final IndView ind;

        private int kind = KIND_NONE;
        private int style = STYLE_NATIVE;
        /** 0..1：手指走到哪 */
        private float p;
        /** 这一次手势里还允不允许画（抓帧是异步的，回调可能晚于松手） */
        private boolean visible;
        /** 抓帧期间先别画自己，否则自己的画面会被抓进帧里 */
        private boolean hideForShot;
        private boolean asked;
        private long retryAfter;
        /** 这一次光带的起始色号（手势开始时挑一次，整条带按它顺次往下推） */
        private int toneIdx;
        private float lastRadius = -1f;
        /** 底部上滑抓回来的那一帧（整屏，1:1 铺开） */
        private Bitmap shot;
        private ValueAnimator anim;

        AnimView(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            blurIv = new ImageView(c);
            blurIv.setScaleType(ImageView.ScaleType.FIT_XY);
            blurIv.setAlpha(0f);
            addView(blurIv, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));
            scrim = new View(c);
            scrim.setBackgroundColor(0xFF000000);
            scrim.setAlpha(0f);
            addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));
            ind = new IndView(c, this);
            addView(ind, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));
        }

        void start(int k, int st) {
            abortAnim();
            kind = k;
            style = st;
            p = 0f;
            setAlpha(1f);
            visible = true;
            hideForShot = false;
            asked = false;
            lastRadius = -1f;
            toneIdx = (int) (Math.random() * TONES.length);
            recycleShot();
            blurIv.setImageDrawable(null);
            if (k == KIND_HOME && st != STYLE_GLOW) {
                // 原生档的底部上滑要一张实景来糊 —— 趁手指还没走远先抓。
                // ★ 炫彩档底部画的是横向彩带，不糊画面，所以既不抓帧、也不压暗度。
                grab();
            }
            apply();
        }

        void setProgress(float v) {
            if (!visible) {
                return;
            }
            float n = v < 0f ? 0f : (v > 1f ? 1f : v);
            if (n == p) {
                return;
            }
            p = n;
            apply();
        }

        void finish(boolean fired) {
            if (!visible) {
                return;
            }
            visible = false;
            hideForShot = false;
            if (fired) {
                p = 1f;
            }
            fadeOut(fired ? 200L : 140L);
        }

        void abort() {
            abortAnim();
            visible = false;
            hideForShot = false;
            kind = KIND_NONE;
            p = 0f;
            setAlpha(1f);
            recycleShot();
            blurIv.setImageDrawable(null);
            blurIv.setAlpha(0f);
            scrim.setAlpha(0f);
            ind.invalidate();
        }

        private void abortAnim() {
            ValueAnimator a = anim;
            anim = null;
            if (a != null) {
                a.cancel();
            }
        }

        private void fadeOut(long ms) {
            abortAnim();
            ValueAnimator a = ValueAnimator.ofFloat(getAlpha(), 0f);
            anim = a;
            a.setDuration(ms);
            a.addUpdateListener(x -> setAlpha((float) x.getAnimatedValue()));
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator x) {
                    if (anim != x) {
                        return;
                    }
                    anim = null;
                    kind = KIND_NONE;
                    p = 0f;
                    setAlpha(1f);
                    recycleShot();
                    blurIv.setImageDrawable(null);
                    blurIv.setAlpha(0f);
                    scrim.setAlpha(0f);
                    ind.invalidate();
                }
            });
            a.start();
        }

        private void recycleShot() {
            Bitmap b = shot;
            shot = null;
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }

        /** 底部这一手走不走"抓帧 + 整屏模糊"（只有原生档走；炫彩档画横向彩带） */
        private boolean blurHome() {
            return kind == KIND_HOME && style != STYLE_GLOW;
        }

        /** 把这一手的状态刷到三个孩子身上 */
        private void apply() {
            final boolean show = visible && !hideForShot;
            if (!show || !blurHome()) {
                // 侧滑、以及炫彩档的底部，都不用帧；改由指示层自己画
                blurIv.setAlpha(0f);
                scrim.setAlpha(0f);
            }
            if (show && blurHome()) {
                Bitmap b = shot;
                final boolean hasShot = b != null && !b.isRecycled();
                blurIv.setAlpha(hasShot ? Math.min(1f, p / ALPHA_AT) : 0f);
                scrim.setAlpha((hasShot ? SCRIM : SCRIM_NO_SHOT) * p);
                if (hasShot) {
                    final float rad = dpPx(BLUR_MIN_DP)
                            + (dpPx(BLUR_MAX_DP) - dpPx(BLUR_MIN_DP)) * p;
                    if (Math.abs(rad - lastRadius) >= RAD_STEP) {
                        lastRadius = rad;
                        setBlur(rad);
                    }
                }
            }
            ind.invalidate();
        }

        /** 给画面上模糊。API 31 才有 RenderEffect，之前只压暗度 */
        private void setBlur(float radius) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return;
            }
            try {
                blurIv.setRenderEffect(RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP));
            } catch (Throwable t) {
                Log.w(TAG, "设模糊失败（不影响压暗度）", t);
            }
        }

        // -------------------------------------------------------------- 抓帧

        /**
         * 抓当前画面。跟 {@link CoverBlur} 那套一模一样，只是这里只抓一帧。
         *
         * <p>抓帧这一刻先把 {@code hideForShot} 立起来、把自己擦掉 —— 动画层是整屏的，
         * 不擦就会把自己画的模糊面抓进帧里（越糊越糊）。
         */
        private void grab() {
            AccessibilityService s = svc;
            if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < retryAfter || asked) {
                return;
            }
            asked = true;
            hideForShot = true;
            apply();
            try {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(),
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
                                hideForShot = false;
                                if (b == null || blank(b)) {
                                    if (b != null) {
                                        b.recycle();
                                    }
                                    retryAfter = System.currentTimeMillis() + RETRY_MS;
                                    Log.w(TAG, "没拿到可用帧（安全界面会截出纯色），这一手只压暗度");
                                    apply();
                                    return;
                                }
                                if (!visible || kind != KIND_HOME) {
                                    // 手已经松了 —— 帧就是废的，别留着
                                    b.recycle();
                                    apply();
                                    return;
                                }
                                recycleShot();
                                shot = b;
                                blurIv.setImageBitmap(b);
                                Log.i(TAG, "底部拿到帧：" + b.getWidth() + "x" + b.getHeight()
                                        + "（窗口 " + sw + "x" + sh + "）");
                                apply();
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                hideForShot = false;
                                retryAfter = System.currentTimeMillis() + RETRY_MS;
                                Log.w(TAG, "截图失败 code=" + errorCode + "，这一手只压暗度");
                                apply();
                            }
                        });
            } catch (Throwable t) {
                hideForShot = false;
                retryAfter = System.currentTimeMillis() + RETRY_MS;
                Log.w(TAG, "takeScreenshot 抛了（多半是服务配置里没声明 canTakeScreenshot）", t);
                apply();
            }
        }

        /**
         * 抓回来是不是一张纯色图。
         *
         * <p>缩到 16x16 看亮度极差：被 {@code FLAG_SECURE} 挡住的窗口截出来是纯黑，
         * 极差接近 0。真画面（哪怕壁纸很素）极差不会这么小。照 CoverBlur 的做法。
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
                return max - min < 10;
            } catch (Throwable t) {
                return false;
            } finally {
                if (small != null && small != b) {
                    small.recycle();
                }
            }
        }
    }

    // ------------------------------------------------------------------ 侧滑的指示层

    /** 只画侧滑那点东西（底部的模糊在上面的 ImageView 里）。坐标是整屏坐标 */
    private static final class IndView extends View {

        private final AnimView owner;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IndView(Context c, AnimView o) {
            super(c);
            owner = o;
        }

        @Override
        protected void onDraw(Canvas c) {
            AnimView a = owner;
            if (!a.visible || a.hideForShot || a.p <= 0.01f) {
                return;
            }
            if (a.kind == KIND_NONE) {
                return;
            }
            if (a.style == STYLE_GLOW) {
                // 炫彩档三条边都画彩带（含底部）
                drawGlow(c, a);
            } else if (a.kind != KIND_HOME) {
                // 原生档的底部走整屏模糊，这里不画
                drawNative(c, a);
            }
        }

        /** 原生风格：边缘一枚圆 + 指向内侧的箭头 */
        private void drawNative(Canvas c, AnimView a) {
            final boolean left = a.kind == KIND_BACK_L;
            final float r = dpPx(NATIVE_R_DP);
            final float inset = dpPx(NATIVE_INSET_DP) + a.p * dpPx(NATIVE_PUSH_DP);
            final float cx = left ? inset + r : sw - inset - r;
            final float cy = sh / 2f;

            // 深色圆底，越往里推越实
            paint.reset();
            paint.setAntiAlias(true);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int) (255f * (0.34f + 0.46f * a.p)), 0, 0, 0));
            c.drawCircle(cx, cy, r, paint);

            // 箭头指向<b>屏幕内侧</b>：左边缘划入朝右、右边缘划入朝左（不是指回边缘）
            final float dir = left ? 1f : -1f;
            final float arm = dpPx(NATIVE_ARM_DP) + dpPx(3f) * a.p;
            final float wing = dpPx(NATIVE_WING_DP) + dpPx(2f) * a.p;
            final float tipX = cx + dir * arm;
            final float backX = cx - dir * arm;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.6f, dpPx(2.2f)));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.argb((int) (255f * (0.60f + 0.40f * a.p)), 255, 255, 255));
            c.drawLine(backX, cy, tipX, cy, paint);
            c.drawLine(tipX, cy, tipX - dir * wing, cy - wing, paint);
            c.drawLine(tipX, cy, tipX - dir * wing, cy + wing, paint);
        }

        /**
         * 炫彩风格：沿边叠出<b>一条渐变彩色光带</b>。
         *
         * <p>每一团都用 {@link #halo} 画椭圆径向渐变：长轴沿着边、短轴伸向屏内，
         * 于是一团的剖面就是"贴着边最亮、往屏内 GLOW_DEPTH_DP 之内收到没有"。
         * 再把它们沿边排成一串（相邻两团叠着），每团沿边方向的亮度按 sin 钟形收放
         * —— 合起来就是"沿边两头收到没有、贴边那一线最亮"的一条带，
         * 跟 MyGesture 那张 {@code left_light.png} 的剖面一致。
         *
         * <p>颜色是<b>顺次推进</b>的：第 i 团取调色板里第 {@code 起始色号 + i} 个色，
         * 一条带从头到尾扫过好几个色相 —— 这就是"像 RGB 一样"的观感。
         *
         * <p><b>三条边共用这一套</b>，只是轴的朝向不同：侧滑那条边是竖的（沿边 = 纵轴、
         * 入屏 = 横轴），底边那条是横的（沿边 = 横轴、入屏 = 纵轴）。{@link #halo} 收的是
         * "X 半径 / Y 半径"两个数，所以底部只要把两个坐标对调、贴边那条线换成 {@code y = sh}
         * 就成 —— 出来的是一条贴着屏幕底边、朝上伸 {@link #GLOW_DEPTH_DP} 的横向彩带。
         */
        private void drawGlow(Canvas c, AnimView a) {
            final boolean bottom = a.kind == KIND_HOME;
            // 被划的那条边有多长（侧滑 = 屏高，底部 = 屏宽）
            final float edgeLen = bottom ? sw : sh;
            final float depth = dpPx(GLOW_DEPTH_DP) * (0.55f + 0.45f * a.p);
            final float span = edgeLen * GLOW_SPAN;
            final float r = edgeLen * GLOW_BLOB_R * (0.5f + 0.5f * a.p);
            // 整条带跟着手指那一格挪，但要让整条都留在屏内（手划到边角时不缺一块）
            final float half = span * 0.5f;
            final float mid = clamp(bottom ? fingerX : fingerY, half, edgeLen - half);
            final float from = mid - half;
            // 贴边那一线：左边 x=0、右边 x=sw、底边 y=sh
            final float edge = bottom ? sh : (a.kind == KIND_BACK_L ? 0f : sw);
            for (int i = 0; i < GLOW_BLOBS; i++) {
                final float t = (i + 0.5f) / GLOW_BLOBS;
                // 沿边方向的钟形包络：两头收到 0、中间最亮（照贴图那条剖面）
                final float w = (float) Math.sin(Math.PI * t);
                final int hue = TONES[(a.toneIdx + i * GLOW_HUE_STEP) % TONES.length];
                final float u = from + span * t;
                final float alpha = a.p * w * GLOW_GAIN;
                if (bottom) {
                    halo(c, u, edge, r, depth, hue, alpha);
                } else {
                    halo(c, edge, u, r, depth, hue, alpha);
                }
            }
        }

        /** 一团光：圆心在 {@code (x, y)}，纵轴被压到 {@code reach / halfLen} 变成贴边椭圆 */
        private void halo(Canvas c, float x, float y, float halfLen, float reach,
                          int tone, float alpha) {
            if (halfLen < 2f || reach < 1f || alpha <= 0.01f) {
                return;
            }
            final int r = Color.red(tone);
            final int g = Color.green(tone);
            final int b = Color.blue(tone);
            final int core = Color.argb((int) (255f * 0.80f * alpha), r, g, b);
            final int mid = Color.argb((int) (255f * 0.28f * alpha), r, g, b);
            final int out = Color.argb(0, r, g, b);

            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(x, y, halfLen,
                    new int[]{core, mid, out}, new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            final int save = c.save();
            c.scale(1f, reach / halfLen, x, y);
            c.drawCircle(x, y, halfLen, paint);
            c.restoreToCount(save);
            paint.setShader(null);
        }
    }
}
