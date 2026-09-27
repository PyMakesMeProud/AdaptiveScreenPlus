package com.wb.extrotator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Method;

/**
 * 内屏上的虚拟光标 —— 一块挂在<b>内屏</b>上的悬浮层。
 *
 * <p><b>为什么不画在触控板上</b>：触控板在封面屏上，光标画在那儿就只是「面板上的一个环」，
 * 而用户看的是内屏，位置对不上；笔记本触控板是手上盲摸、指针出现在你正在看的那块屏上。
 *
 * <p><b>为什么能画上去</b>：系统只给真实鼠标（USB / uinput）画指针，应用侧注入的事件不会点亮它，
 * 非 root 没有接口 —— 只能自己挂一层：拿目标屏的 display context 加一个
 * {@code TYPE_APPLICATION_OVERLAY} 悬浮窗，需要「显示在其他应用上层」权限。
 *
 * <p>⚠ 试过、走不通的两条（别回头再试）：用 shell 身份（{@code app_process}）挂窗 ——
 * WMS 的 {@code openSession} 直接抛 {@code Unknown pid=… uid=2000}；{@code Presentation} ——
 * 抛 {@code InvalidDisplayException}，它只认带 FLAG_PRESENTATION 的辅助屏，内屏（默认屏）不算。
 *
 * <p><b>坐标：窗口坐标 = 注入坐标 = 画面坐标</b>：窗口的 {@code lp.x/lp.y} 与
 * {@code input -d N tap x y} 用的是同一套（目标屏旋转后的逻辑坐标，实测 1920×1080 时
 * lp(160,360) 正好落在截图 (162,393)），所以这里维护的就是注入坐标，不用再换算。
 *
 * <p>⚠ <b>范围（bounds）必须从系统那一侧量，不能问 Display</b>：display context 的
 * {@code getResources().getDisplayMetrics()} 会被<b>本应用的兼容缩放</b>污染 —— 实测内屏明明是
 * 1920×1080，它报 1496×1242，于是光标被夹在 x≤1495，右边 424px 永远够不到（用户报的
 * 「光标到不了最右边」）。现在改成取 {@code dumpsys window displays} 里的 {@code cur=WxH}
 * （WindowManager 眼里的逻辑尺寸，与 {@code input} / {@code screencap} 同一套）。
 * ⚠ Display 的那几个 API 都别用：{@code getRealSize/getMetrics} 会串应用缩放，
 * {@code getMode()} 又不认 {@code wm size} 覆盖。
 */
public final class CursorOverlay {

    private static final String TAG = "ExtCursor";

    /** 光标直径（dp） */
    private static final float SIZE_DP = 28f;

    /** 复核范围的最小间隔 —— 旋转过程中系统会连报几次 display changed */
    private static final long CALIB_DEBOUNCE_MS = 350L;

    private static View view;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static int sizePx;
    /** 光标活动范围（= 目标屏给应用的逻辑尺寸 = 注入坐标空间） */
    private static int boundW, boundH;
    /** 光标中心（目标屏坐标）；-1 = 还没定位过 */
    private static int cx = -1, cy = -1;
    private static DisplayManager dm;
    private static DisplayManager.DisplayListener displayListener;

    /** 正在操作哪块屏（复核范围时要按它去查） */
    private static Context appCtx;
    private static int targetDisplay = -1;
    private static long lastCalibAt;
    /** 范围变了通知界面刷读数（Activity 在自己 onStop 里清掉，别长期持有） */
    private static Runnable boundsListener;

    /** 手指正按在触控板上 */
    static boolean hot;

    private CursorOverlay() {
    }

    public static boolean isOn() {
        return view != null;
    }

    /** 光标活动范围；没挂上返回 null */
    public static int[] bounds() {
        return view == null ? null : new int[]{boundW, boundH};
    }

    public static int x() {
        return cx;
    }

    public static int y() {
        return cy;
    }

    /** 范围变了（校准 / 目标屏旋转）时回调一次，用来刷新界面上那句读数 */
    public static void setBoundsListener(Runnable r) {
        boundsListener = r;
    }

    /**
     * 把光标挂到指定屏上。已经挂着就直接返回 true。
     *
     * @param displayId 目标屏逻辑 id（本应用用 {@link ExtScreen#INNER_ID}）
     * @return 挂上了 / 挂上过；失败返回 false（权限、屏不存在等，日志里有原因）
     */
    public static boolean show(Context app, int displayId) {
        if (view != null) {
            return true;
        }
        try {
            DisplayManager dmg = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dmg.getDisplay(displayId);
            if (d == null) {
                Log.w(TAG, "屏 " + displayId + " 不存在，挂不了光标");
                return false;
            }
            Context dc = displayContext(app, d);
            WindowManager w = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
            android.util.DisplayMetrics m = dc.getResources().getDisplayMetrics();
            // 密度是可信的（内屏 480dpi → 3.0）；万一被兼容缩放带跑就退回 2.0，
            // 顶多光标大小不理想，不至于大得离谱。
            float density = m.density;
            if (density < 1f || density > 6f) {
                density = 2f;
            }
            sizePx = Math.max(24, Math.round(SIZE_DP * density));

            // 临时范围：display context 的量法可能被应用缩放污染，随后的复核会换成真值。
            boundW = m.widthPixels > 0 ? m.widthPixels : 1080;
            boundH = m.heightPixels > 0 ? m.heightPixels : 1920;
            if (cx < 0) {
                // 第一次挂上：光标摆屏幕正中，跟刚插上鼠标一个感觉
                cx = boundW / 2;
                cy = boundH / 2;
            }
            cx = clamp(cx, 0, Math.max(0, boundW - 1));
            cy = clamp(cy, 0, Math.max(0, boundH - 1));

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    sizePx, sizePx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.LEFT;
            p.setTitle("ext-cursor");
            p.x = cx - sizePx / 2;
            p.y = cy - sizePx / 2;

            CursorView v = new CursorView(dc, sizePx);
            w.addView(v, p);

            view = v;
            wm = w;
            lp = p;
            dm = dmg;
            appCtx = app.getApplicationContext();
            targetDisplay = displayId;
            registerDisplayListener(dmg);
            Log.i(TAG, "光标已挂到屏 " + displayId + "：临时范围 " + boundW + "×" + boundH
                    + "，直径 " + sizePx + "px，初始 " + cx + "," + cy);
            calibrate();     // 立刻用系统那一侧的量法复核一次
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "挂光标失败", t);
            view = null;
            wm = null;
            lp = null;
            return false;
        }
    }

    /** 移光标（目标屏坐标，越界自动夹住） */
    public static void move(int x, int y) {
        if (view == null) {
            return;
        }
        int nx = clamp(x, 0, Math.max(0, boundW - 1));
        int ny = clamp(y, 0, Math.max(0, boundH - 1));
        if (nx == cx && ny == cy) {
            return;
        }
        cx = nx;
        cy = ny;
        lp.x = cx - sizePx / 2;
        lp.y = cy - sizePx / 2;
        try {
            wm.updateViewLayout(view, lp);
        } catch (Throwable t) {
            Log.w(TAG, "移光标失败: " + t);
        }
    }

    /** 手上按下/抬起 → 光标给点视觉反馈（按的时候实一点） */
    public static void setHot(boolean h) {
        if (view == null || hot == h) {
            return;
        }
        hot = h;
        view.invalidate();
    }

    /** 撤掉光标 */
    public static void hide() {
        if (view == null) {
            return;
        }
        try {
            wm.removeViewImmediate(view);
        } catch (Throwable t) {
            Log.w(TAG, "撤光标失败: " + t);
        }
        view = null;
        wm = null;
        lp = null;
        hot = false;
        Log.i(TAG, "光标已撤下");
    }

    // ------------------------------------------------------------------ 范围复核

    /**
     * 去系统那一侧量一次目标屏的逻辑尺寸，把光标范围校准成真值。
     *
     * <p>为什么绕这一圈：见类注释 —— display context 的 metrics 会被本应用的兼容缩放污染
     * （1920×1080 读成 1496×1242），{@code dumpsys window displays} 里的 {@code cur=WxH}
     * 才是 WindowManager 自己的账本，跟 {@code input} / {@code screencap} 同一套。
     *
     * <p>走 shell 要两三百毫秒，所以丢给 {@link ExtScreen} 的单线程池，量完回主线程改窗口。
     */
    private static void calibrate() {
        final Context c = appCtx;
        final int id = targetDisplay;
        if (c == null || id < 0 || view == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCalibAt < CALIB_DEBOUNCE_MS) {
            return;   // 旋转动画期间系统会连报，合并掉
        }
        lastCalibAt = now;
        ExtScreen.exec("dumpsys window displays", out -> {
            final int[] s = ExtScreen.parseInjectionSize(out, id);
            final View v = view;
            if (s == null) {
                Log.w(TAG, "复核范围失败：没从 dumpsys 里读到屏 " + id + " 的 cur=");
                return;
            }
            if (v == null) {
                return;
            }
            v.post(() -> applyBounds(s[0], s[1], "dumpsys"));
        });
    }

    /**
     * 换范围。**按比例迁移光标**而不是只做夹取 ——
     * 折叠 ↔ 展开会让内屏在 1080×1920 与 1920×1080 之间翻转，
     * 只夹取的话光标会突然贴到边上，看着像丢了。
     */
    private static void applyBounds(int w, int h, String src) {
        if (view == null || w <= 0 || h <= 0) {
            return;
        }
        if (w == boundW && h == boundH) {
            return;
        }
        float rx = boundW > 0 ? (float) cx / boundW : 0.5f;
        float ry = boundH > 0 ? (float) cy / boundH : 0.5f;
        int ow = boundW, oh = boundH;
        boundW = w;
        boundH = h;
        cx = clamp(Math.round(rx * boundW), 0, Math.max(0, boundW - 1));
        cy = clamp(Math.round(ry * boundH), 0, Math.max(0, boundH - 1));
        lp.x = cx - sizePx / 2;
        lp.y = cy - sizePx / 2;
        try {
            wm.updateViewLayout(view, lp);
        } catch (Throwable t) {
            Log.w(TAG, "改范围失败: " + t);
        }
        Log.i(TAG, "光标范围 " + ow + "×" + oh + " → " + boundW + "×" + boundH
                + "（" + src + "），光标迁到 " + cx + "," + cy);
        if (boundsListener != null) {
            boundsListener.run();
        }
    }

    /** 目标屏变了（旋转 / 分辨率覆盖 / 点亮）→ 重新量一次范围 */
    private static void registerDisplayListener(DisplayManager dmg) {
        if (displayListener != null) {
            return;
        }
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (view == null || displayId != targetDisplay) {
                    return;   // 别的屏变了和光标的坐标空间无关
                }
                Log.i(TAG, "屏 " + displayId + " 有变化 → 重新量光标范围");
                calibrate();
            }
        };
        dmg.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
    }

    /** {@code Context.createDisplayContext} 是 @SystemApi，公开 stub 里没有 —— 走反射 */
    private static Context displayContext(Context app, Display d) throws Exception {
        Method m = Context.class.getMethod("createDisplayContext", Display.class);
        m.setAccessible(true);
        return (Context) m.invoke(app, d);
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------ 光标长相

    /**
     * 一个圆环 + 中心点。
     *
     * <p>为什么不用鼠标箭头：外接屏上的内容明暗都有，细箭头的描边在小尺寸下
     * 容易糊；圆环配黑描边，压在白底和黑底上都看得见，而且"中心点"就是点击点，
     * 比箭头尖端更好判断。
     */
    private static class CursorView extends View {

        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        CursorView(Context c, int size) {
            super(c);
            this.size = size;
            stroke.setStyle(Paint.Style.STROKE);
            fill.setStyle(Paint.Style.FILL);
            dot.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float c = size / 2f;
            float u = size / 28f;              // 以 28px 为基准按比例缩放
            float r = c - 5f * u;              // 留出描边宽度，别被窗口边界裁掉

            // 黑色外描边：白底上也能看清
            stroke.setColor(0xB0000000);
            stroke.setStrokeWidth(5f * u);
            cv.drawCircle(c, c, r, stroke);

            // 按下时铺一层淡淡的白，给"手正按着"的反馈
            if (hot) {
                fill.setColor(0x33FFFFFF);
                cv.drawCircle(c, c, r, fill);
            }

            // 白色圆环
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(2.2f * u);
            cv.drawCircle(c, c, r, stroke);

            // 中心点 = 点击落点
            dot.setColor(Color.WHITE);
            cv.drawCircle(c, c, (hot ? 4.6f : 3.2f) * u, dot);
            dot.setColor(0x80000000);
            cv.drawCircle(c, c, (hot ? 2.2f : 1.5f) * u, dot);
        }
    }
}
