package com.wb.extrotator;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 外屏「通知中心」—— 侧边栏划到「通知」那一环时弹出的那整屏面板（v4.22 重做）。
 *
 * <p><b>这一版长什么样</b>（从上到下、从左到右）：
 * <pre>
 *   通知 · N 条                                   全部清除
 *   [WiFi][数据][蓝牙][旋转][手电][勿扰][飞行]…  ＋  ×
 *   ┌──┬───────────────────────────────────────┐
 *   │图│ [全部][微信][QQ] …                     │  ← 按应用筛
 *   │图│ 标题                            12:30  │
 *   │图│ 正文…                                  │
 *   │＋│ …                                      │
 *   └──┴───────────────────────────────────────┘
 * </pre>
 *
 * <p><b>跟旧版的三处不同</b>：
 * <ol>
 *   <li>铺满整屏（旧版是个留 5dp 边距的"超级大弹窗"），底板不透明；</li>
 *   <li>左栏改成<b>用户自己挑的常用应用</b>（{@link NotifPins}），点一下真的把它启到外屏；
 *       旧版那排「快捷启动」拿的是"刚发过通知的应用"—— 跟上面那排筛选项本来就是同一批，
 *       而且那些应用不一定有启动入口，所以经常点了没反应；</li>
 *   <li>顶上多一排<b>快捷开关</b>（{@link QuickSettings}），而且<b>这一排是用户自己排的</b> ——
 *       点右边那个「＋」进编辑页，可增、可删、可上下调序；在那一排上长按某一格也能直接拿掉。</li>
 * </ol>
 *
 * <p>「选常用应用」不是另外弹一个对话框，而是把右半边就地切成应用列表 —— 这里的窗口是
 * {@code TYPE_ACCESSIBILITY_OVERLAY}，标准的 Dialog/AlertDialog 会落到<b>默认屏</b>
 * （内屏，用户那块屏是坏的）上去，等于点了没反应。就地切换没有这个坑。
 *
 * <p>窗口是全屏的 {@code TYPE_ACCESSIBILITY_OVERLAY}，<b>吃掉全部触摸</b>，只带
 * {@code FLAG_NOT_FOCUSABLE} —— 别去碰键盘焦点。⚠ 必须显式给 {@code gravity = TOP|START}
 * 与 {@code FLAG_LAYOUT_NO_LIMITS}，否则 x/y 会变成"相对屏幕中心的偏移"、
 * 整屏窗口还会被系统条的 inset 顶下去（这是 v4.21b 返工过一次的坑）。
 *
 * <p>数据全走 {@link CoverNotifListener}（它自己持有通知使用权），这里只读快照、只发清除；
 * 点一条优先用它的 {@code contentIntent}，没有就退成把那个应用拉起来。
 */
public final class CoverNotifCenter {

    private static final String TAG = "CoverNotifCenter";

    /** 面板底色。铺满整屏，所以用不透明的，免得底下各种桌面透上来花眼 */
    private static final int C_BG = 0xFF141418;
    private static final int C_TITLE = 0xF2FFFFFF;
    private static final int C_TEXT = 0x99FFFFFF;
    private static final int C_DIM = 0x6BFFFFFF;
    private static final int C_ACCENT = 0xFF5B9DFF;
    private static final int C_CHIP = 0x14FFFFFF;
    private static final int C_CHIP_ON = 0x335B9DFF;
    private static final int C_TILE = 0x14FFFFFF;
    private static final int C_TILE_ON = 0x3D5B9DFF;
    /** 开关没切成功时闪一下的颜色 */
    private static final int C_ERR = 0xFFC2453A;

    private static final long ANIM_MS = 150L;
    /** 通知行最多摆几条（列表本身能滚，所以给得比旧版宽） */
    private static final int MAX_ROWS = 30;
    /** 左栏宽度 / 格子的边长（dp）。用户点名"图标都做小一些" */
    private static final int PIN_W = 36;
    private static final int CELL = 36;
    private static final int CELL_ICON = 30;
    /** 快捷开关那一格 */
    private static final int TILE = 34;
    private static final int TILE_ICON = 16;
    /** 右上角那个「×」的边长（v4.23 撑大了） */
    private static final int CLOSE = 42;

    private static Context displayCtx;
    private static WindowManager wm;
    private static int display;
    private static int wpx;
    private static int hpx;
    private static float density = 2.125f;

    private static FrameLayout root;
    private static LinearLayout card;
    private static LinearLayout qsRow;
    private static LinearLayout body;
    private static TextView countView;

    private static Handler ui;

    /** 只看某个应用（null = 全部） */
    private static String filterPkg;
    /** 右半边现在是「选常用应用」还是「看通知」 */
    private static boolean picking;
    /** 下半块现在是「挑快捷开关」那一页（跟 picking 同一种就地换页的做法） */
    private static boolean editingQs;
    /** 挑快捷开关那一页的列表容器 */
    private static LinearLayout qsEditBox;
    /** 选应用那一页的列表容器（应用清单是后台读的，读完往这里填） */
    private static LinearLayout pickBox;

    /** 应用清单缓存：读图标是 IO，且 {@code AppRepo.load} 明确要求后台线程，读过一次就留着 */
    private static volatile List<AppRepo.Item> appCache;

    /** 左栏图标的位图缓存：包名 → 位图（值可能是 null，同样算"查过了"，免得反复读盘） */
    private static final Map<String, Bitmap> PIN_ICON_CACHE = new HashMap<>();

    private CoverNotifCenter() {
    }

    static void env(Context ctx, WindowManager w, int disp, int wPx, int hPx, float den) {
        displayCtx = ctx;
        wm = w;
        display = disp;
        wpx = wPx;
        hpx = hPx;
        density = den;
        ui = new Handler(Looper.getMainLooper());
    }

    public static boolean showing() {
        return root != null;
    }

    /** 弹出来（已经开着就先整个重建一遍，保证是新的） */
    static void show() {
        if (displayCtx == null || wm == null) {
            Log.w(TAG, "还没环境，通知中心弹不出来");
            return;
        }
        if (root != null) {
            refreshViews();
            return;
        }
        try {
            FrameLayout r = new FrameLayout(displayCtx);
            card = buildCard(displayCtx);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            r.addView(card, clp);
            card.setAlpha(0f);
            card.setTranslationY(-dp(12));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            // ⚠ 还要显式「不吃任何 inset」：TYPE_ACCESSIBILITY_OVERLAY 的 fitInsetsTypes
            //   默认含 systemBars，外屏顶上那条状态栏的高度就会被让出来 —— 面板看着
            //   就是「没铺满、顶上露一条桌面」（用户截图报的正是这个）。
            //   FLAG_LAYOUT_IN_SCREEN 管不到这里，得走这个 setter；它是 API 30 才有的。
            if (Build.VERSION.SDK_INT >= 30) {
                lp.setFitInsetsTypes(0);
            }
            wm.addView(r, lp);
            root = r;
            buildContent(displayCtx);
            card.animate().alpha(1f).translationY(0f).setDuration(ANIM_MS).start();
            Log.i(TAG, "通知中心弹出：屏 " + wpx + "x" + hpx + "，屏密度 " + density);
        } catch (Throwable t) {
            Log.e(TAG, "弹通知中心失败", t);
            hide();
        }
    }

    public static void hide() {
        final FrameLayout r = root;
        root = null;
        final LinearLayout c = card;
        card = null;
        qsRow = null;
        body = null;
        countView = null;
        pickBox = null;
        filterPkg = null;
        picking = false;
        editingQs = false;
        qsEditBox = null;
        if (r == null) {
            return;
        }
        if (c != null) {
            c.animate().alpha(0f).setDuration(ANIM_MS).start();
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (wm != null) {
                    wm.removeViewImmediate(r);
                }
            } catch (Throwable t) {
                Log.w(TAG, "拆通知中心失败", t);
            }
        }, ANIM_MS);
        Log.i(TAG, "通知中心收起");
    }

    /** 侧边栏整个拆掉时调 */
    static void release() {
        FrameLayout r = root;
        root = null;
        card = null;
        qsRow = null;
        body = null;
        countView = null;
        pickBox = null;
        filterPkg = null;
        picking = false;
        editingQs = false;
        qsEditBox = null;
        if (r != null && wm != null) {
            try {
                wm.removeViewImmediate(r);
            } catch (Throwable t) {
                Log.w(TAG, "拆通知中心失败", t);
            }
        }
        displayCtx = null;
        wm = null;
        ui = null;
        synchronized (PIN_ICON_CACHE) {
            PIN_ICON_CACHE.clear();
        }
    }

    /** 装了 / 卸了应用之后调一次：选常用应用那一页的清单得重读 */
    static void dropAppCache() {
        appCache = null;
    }

    /** 通知有变动时刷新（没开着就什么都不做） */
    static void onNotificationsChanged() {
        if (root == null) {
            return;
        }
        refreshViews();
    }

    private static void refreshViews() {
        if (root == null || card == null || picking || editingQs) {
            return;
        }
        card.post(() -> {
            try {
                if (root != null && !picking && !editingQs) {
                    buildContent(card.getContext());
                }
            } catch (Throwable t) {
                Log.w(TAG, "刷新通知中心失败", t);
            }
        });
    }

    // ------------------------------------------------------------------ 骨架

    private static LinearLayout buildCard(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_BG);
        box.setClickable(true);            // 吃掉落在空白处的触摸
        box.setPadding(dp(8), dp(5), dp(8), dp(6));

        // ① 标题行：通知 · N 条 ......................... 全部清除
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = text(c, "通知", 13f, C_TITLE, true);
        head.addView(t);

        countView = text(c, "", 10f, C_DIM, false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(5);
        head.addView(countView, cp);

        // 「全部清除」在标题行右端，做成一颗按钮（有底色、撑到 30dp 高）——
        // 外屏手指粗，原来那行 10.5sp 的裸字既难点，也不像个能按的东西（v4.23 用户提的）
        TextView clear = text(c, "全部清除", 11.5f, C_TEXT, false);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(dp(11), dp(6), dp(11), dp(6));
        clear.setBackground(chipBg(C_CHIP));
        clear.setMinHeight(dp(30));
        clear.setOnClickListener(v -> dismissAll());
        head.addView(clear);

        box.addView(head);

        // ② 快捷开关那一排 + 「＋」+ 「×」
        //
        // ⚠ 开关是摆在 HorizontalScrollView 里的，所以「＋」与「×」**不能塞进滚动区** ——
        //   塞进去它们会跟着一起滚走，开关一多就找不着退出按钮了。
        //   这里再套一层横向布局：滚动区吃掉剩下的宽度，「＋」「×」固定在右端。
        LinearLayout qsBar = new LinearLayout(c);
        qsBar.setOrientation(LinearLayout.HORIZONTAL);
        qsBar.setGravity(Gravity.CENTER_VERTICAL);

        HorizontalScrollView qs = new HorizontalScrollView(c);
        qs.setHorizontalScrollBarEnabled(false);
        qsRow = new LinearLayout(c);
        qsRow.setOrientation(LinearLayout.HORIZONTAL);
        qsRow.setGravity(Gravity.CENTER_VERTICAL);
        qsRow.setPadding(0, dp(4), 0, dp(4));
        qs.addView(qsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        qsBar.addView(qs, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 「＋」＝挑快捷开关（增 / 删 / 调序）
        TextView qsAdd = text(c, "＋", 17f, C_DIM, false);
        qsAdd.setGravity(Gravity.CENTER);
        qsAdd.setBackground(chipBg(C_CHIP));
        qsAdd.setContentDescription("挑选快捷开关");
        qsAdd.setOnClickListener(v -> {
            editingQs = true;
            picking = false;
            buildContent(c);
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(TILE), dp(TILE));
        ap.leftMargin = dp(6);
        qsBar.addView(qsAdd, ap);

        // 「×」：v4.23 从标题行挪到这一排的最右端，并撑到 42dp 见方 —— 手指粗，
        // 点的是控件不是字；与快捷开关之间留 10dp，免得一滑就误碰
        TextView close = text(c, "×", 26f, C_TITLE, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(chipBg(C_CHIP));
        close.setContentDescription("关闭");
        close.setOnClickListener(v -> hide());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(CLOSE), dp(CLOSE));
        clp.leftMargin = dp(10);
        qsBar.addView(close, clp);

        box.addView(qsBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ③ 主体（左栏 + 通知，或整块换成"选应用"）
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    private static void buildContent(Context c) {
        List<CoverNotifListener.Entry> all = new ArrayList<>();
        try {
            CoverNotifListener.refreshAll();
            all = CoverNotifListener.snapshot();
        } catch (Throwable t) {
            Log.w(TAG, "读通知失败", t);
        }
        if (countView != null) {
            countView.setText(all.isEmpty() ? "" : ("· " + all.size() + " 条"));
        }
        fillQs(c);
        if (picking) {
            buildPicker(c);
        } else if (editingQs) {
            buildQsEditor(c);
        } else {
            buildBody(c, all);
        }
    }

    // ------------------------------------------------------------------ 快捷开关

    private static void fillQs(Context c) {
        if (qsRow == null) {
            return;
        }
        qsRow.removeAllViews();
        List<Integer> ids = QuickSettings.list(c);
        for (int id : ids) {
            qsRow.addView(qsTile(c, id));
        }
        if (ids.isEmpty()) {
            TextView t = text(c, "这一排是空的，点右边的「＋」加几个", 10f, C_DIM, false);
            t.setPadding(dp(4), 0, 0, 0);
            qsRow.addView(t);
        }
    }

    /**
     * 一格开关：一个 34dp 的圆角方块 + 16dp 图标。
     * 开着＝蓝底白图标（加一道细蓝边），关着＝灰底灰图标。
     */
    private static View qsTile(Context c, final int id) {
        boolean on = QuickSettings.isOn(c, id);
        FrameLayout box = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(on ? C_TILE_ON : C_TILE);
        if (on) {
            bg.setStroke(dp(1), 0x665B9DFF);
        }
        box.setBackground(bg);

        ImageView iv = new ImageView(c);
        iv.setImageResource(QuickSettings.icon(id));
        iv.setColorFilter(on ? 0xFFFFFFFF : 0x8AFFFFFF);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(TILE_ICON), dp(TILE_ICON));
        ip.gravity = Gravity.CENTER;
        box.addView(iv, ip);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE), dp(TILE));
        lp.rightMargin = dp(6);
        box.setLayoutParams(lp);
        box.setContentDescription(QuickSettings.name(id));
        box.setOnClickListener(v -> tapQs(c, id, box));
        // 长按直接把这一格从这一排上拿掉（要加回来点右边的「＋」）——
        // 比"先进编辑页再删"少两步，常用的那几个开关平时并不需要动
        box.setOnLongClickListener(v -> {
            QuickSettings.remove(c, id);
            hint("已拿掉「" + QuickSettings.name(id) + "」，点「＋」可以加回来");
            fillQs(c);
            return true;
        });
        return box;
    }

    /** 顶上那行临时写一句话（下一次重画就没了） */
    private static void hint(String s) {
        if (countView != null) {
            countView.setText(s);
        }
    }

    private static void tapQs(final Context c, final int id, final View tile) {
        if (tile.getAlpha() < 1f) {
            return;                     // 这一格还在跑
        }
        tile.setAlpha(0.4f);
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            final String err = QuickSettings.toggle(app, id);
            post(() -> {
                if (root == null) {
                    return;
                }
                tile.setAlpha(1f);
                if (err != null) {
                    flashErr(c, tile, QuickSettings.name(id) + "：" + err);
                } else {
                    fillQs(c);          // 状态变了，整排重画
                }
            });
        }, "qs-toggle").start();
    }

    /** 没切成功：那一格闪红，顶上把那句话写出来，然后整块重画回真实状态 */
    private static void flashErr(Context c, View tile, String msg) {
        Log.w(TAG, "快捷开关没成：" + msg);
        hint(msg);
        Drawable d = tile.getBackground();
        if (d instanceof GradientDrawable) {
            try {
                ((GradientDrawable) d).setColor(C_ERR);
            } catch (Throwable ignored) {
            }
        }
        tile.postDelayed(() -> {
            if (root != null) {
                buildContent(c);
            }
        }, 900);
    }

    // ------------------------------------------------------------------ 主体

    private static void buildBody(Context c, List<CoverNotifListener.Entry> all) {
        body.removeAllViews();

        // 左：常用应用竖栏
        ScrollView pinScroll = new ScrollView(c);
        pinScroll.setVerticalScrollBarEnabled(false);
        LinearLayout pinCol = new LinearLayout(c);
        pinCol.setOrientation(LinearLayout.VERTICAL);
        // ⚠ 左对齐（不是居中）：格子左边缘要跟上面「通知」那行、以及快关那一排
        //   齐在同一条竖线上（都是 card 的 8dp padding）。居中会多让出 6dp。
        pinCol.setGravity(Gravity.START);
        pinScroll.addView(pinCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                dp(PIN_W), ViewGroup.LayoutParams.MATCH_PARENT);
        plp.rightMargin = dp(6);
        body.addView(pinScroll, plp);
        fillPins(c, pinCol);

        // 右：筛选栏 + 通知列表
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chipBox = new LinearLayout(c);
        chipBox.setOrientation(LinearLayout.HORIZONTAL);
        chipBox.setGravity(Gravity.CENTER_VERTICAL);
        chipBox.setPadding(0, dp(4), 0, dp(4));
        hs.addView(chipBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout listBox = new LinearLayout(c);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(right, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        fillChips(c, chipBox, all);
        fillList(c, listBox, all);
    }

    // ------------------------------------------------------------------ 左栏：常用应用

    private static void fillPins(Context c, LinearLayout pinCol) {
        pinCol.removeAllViews();
        List<String> pins = NotifPins.list(c);
        List<ImageView> waiting = new ArrayList<>();
        List<String> waitingPkg = new ArrayList<>();

        for (String pkg : pins) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap bm;
            synchronized (PIN_ICON_CACHE) {
                bm = PIN_ICON_CACHE.get(pkg);
            }
            if (bm != null) {
                iv.setImageBitmap(bm);
            } else {
                waiting.add(iv);
                waitingPkg.add(pkg);
            }
            FrameLayout cell = cellBox(c);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                    dp(CELL_ICON), dp(CELL_ICON));
            ip.gravity = Gravity.CENTER;
            cell.addView(iv, ip);
            cell.setOnClickListener(v -> {
                hide();
                launchPkg(c, pkg);
            });
            cell.setOnLongClickListener(v -> {
                NotifPins.remove(c, pkg);
                fillPins(c, pinCol);
                return true;
            });
            pinCol.addView(cell);
        }

        // 末尾那个「＋」：点一下把右半边切成应用列表
        FrameLayout add = cellBox(c);
        TextView plus = text(c, "＋", 17f, C_DIM, false);
        plus.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.CENTER;
        add.addView(plus, pp);
        add.setOnClickListener(v -> {
            picking = true;
            editingQs = false;
            buildContent(c);
        });
        pinCol.addView(add);

        if (!waiting.isEmpty()) {
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                for (int i = 0; i < waiting.size(); i++) {
                    String pkg = waitingPkg.get(i);
                    Bitmap bm = AppIconLib.icon(app, pkg);
                    synchronized (PIN_ICON_CACHE) {
                        PIN_ICON_CACHE.put(pkg, bm);
                    }
                    if (bm == null) {
                        continue;
                    }
                    final Bitmap f = bm;
                    final ImageView v = waiting.get(i);
                    post(() -> v.setImageBitmap(f));
                }
            }, "notif-pin-icon").start();
        }
    }

    // ------------------------------------------------------------------ 选常用应用

    private static void buildPicker(Context c) {
        body.removeAllViews();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(pickTop(c, "点一下放进左栏，再点一下拿出来（长按左栏图标也能拿掉）"));

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        pickBox = new LinearLayout(c);
        pickBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(pickBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        fillPicker(c);
    }

    private static void fillPicker(final Context c) {
        if (pickBox == null) {
            return;
        }
        List<AppRepo.Item> cache = appCache;
        if (cache != null) {
            renderPicker(c, cache);
            return;
        }
        pickBox.removeAllViews();
        TextView t = text(c, "正在读应用列表…", 11f, C_DIM, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(20), 0, 0);
        pickBox.addView(t);

        final Context app = c.getApplicationContext();
        new Thread(() -> {
            List<AppRepo.Item> items;
            try {
                items = AppRepo.load(app);
            } catch (Throwable e) {
                items = new ArrayList<>();
            }
            appCache = items;
            final List<AppRepo.Item> f = items;
            post(() -> {
                if (root != null && picking) {
                    renderPicker(c, f);
                }
            });
        }, "notif-pick-load").start();
    }

    private static void renderPicker(Context c, List<AppRepo.Item> items) {
        if (pickBox == null) {
            return;
        }
        pickBox.removeAllViews();
        for (AppRepo.Item it : items) {
            pickBox.addView(pickRow(c, it));
        }
        if (items.isEmpty()) {
            TextView t = text(c, "读不到应用列表", 11f, C_DIM, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(20), 0, 0);
            pickBox.addView(t);
        }
    }

    /**
     * 选择列表里的一行。选中状态直接靠<b>底色</b>表达，不用打钩字符 ——
     * 「✓」这种符号在某些机型的字体里没有字形，会整块看不见（ⓘ 那边踩过同类坑）。
     */
    private static View pickRow(final Context c, final AppRepo.Item it) {
        boolean on = NotifPins.has(c, it.pkg);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(on ? C_CHIP_ON : 0x00FFFFFF);
        row.setBackground(bg);
        row.setPadding(dp(7), dp(5), dp(7), dp(5));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(3);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(c);
        if (it.icon != null) {
            iv.setImageDrawable(it.icon);
        }
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(26), dp(26));
        ip.rightMargin = dp(8);
        row.addView(iv, ip);

        TextView name = text(c, it.label, 11.5f, on ? 0xFFFFFFFF : C_TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            if (NotifPins.has(c, it.pkg)) {
                NotifPins.remove(c, it.pkg);
                recolorRow(row, name, false);
            } else if (NotifPins.add(c, it.pkg)) {
                recolorRow(row, name, true);
            } else if (countView != null) {
                countView.setText("左栏最多 " + NotifPins.MAX + " 个");
            }
        });
        return row;
    }

    /**
     * 编辑页顶上那一条：一句提示 + 「完成」。选常用应用与挑快捷开关共用。
     */
    private static View pickTop(Context c, String tipText) {
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView tip = text(c, tipText, 9.5f, C_DIM, false);
        top.addView(tip, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = text(c, "完成", 11.5f, C_ACCENT, false);
        done.setPadding(dp(10), dp(5), dp(4), dp(5));
        done.setOnClickListener(v -> {
            picking = false;
            editingQs = false;
            buildContent(c);
        });
        top.addView(done);
        return top;
    }

    // ------------------------------------------------------------------ 挑快捷开关

    /**
     * 「挑快捷开关」那一页（v4.23）。跟「选常用应用」同一套做法：把下半块就地换掉，
     * 不弹对话框 —— 对话框会落到那块坏掉的内屏上去（见类注释里那条）。
     *
     * <p>分两段：「已排上的」按现在的次序列出，点一下拿掉、↑↓ 调次序；「还能加的」列剩下的，
     * 点一下加到末尾。改一下立刻重画，顶上那一排跟着变 —— 改完不用退出去才知道效果。
     */
    private static void buildQsEditor(Context c) {
        body.removeAllViews();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(pickTop(c, "点一下加进这一排，点「已排上的」拿掉，↑↓ 调次序"));

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        qsEditBox = new LinearLayout(c);
        qsEditBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(qsEditBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        fillQsEditor(c);
    }

    private static void fillQsEditor(Context c) {
        if (qsEditBox == null) {
            return;
        }
        qsEditBox.removeAllViews();
        List<Integer> on = QuickSettings.list(c);
        qsEditBox.addView(qsSection(c, "已排上的（" + on.size() + "）"));
        for (int i = 0; i < on.size(); i++) {
            qsEditBox.addView(qsPickRow(c, on.get(i), true, i == 0, i == on.size() - 1));
        }
        List<Integer> rest = new ArrayList<>();
        for (int id : QuickSettings.ALL_IDS) {
            if (!on.contains(id)) {
                rest.add(id);
            }
        }
        qsEditBox.addView(qsSection(c, rest.isEmpty() ? "全都在上面了" : "还能加的"));
        for (int id : rest) {
            qsEditBox.addView(qsPickRow(c, id, false, true, true));
        }
    }

    /** 编辑页里的一行。已排上的亮底 + ↑↓；没排上的暗一点，右边一个「＋」 */
    private static View qsPickRow(Context c, final int id, final boolean on,
                                  boolean first, boolean last) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(on ? C_CHIP_ON : 0x00FFFFFF);
        row.setBackground(bg);
        row.setPadding(dp(7), dp(4), dp(3), dp(4));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(3);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(c);
        iv.setImageResource(QuickSettings.icon(id));
        iv.setColorFilter(on ? 0xFFFFFFFF : 0x8AFFFFFF);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(17), dp(17));
        ip.rightMargin = dp(8);
        row.addView(iv, ip);

        TextView name = text(c, QuickSettings.name(id), 11.5f, on ? 0xFFFFFFFF : C_TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (on) {
            row.addView(qsArrow(c, "↑", !first, v -> {
                QuickSettings.move(c, id, -1);
                fillQsEditor(c);
                fillQs(c);
            }));
            row.addView(qsArrow(c, "↓", !last, v -> {
                QuickSettings.move(c, id, 1);
                fillQsEditor(c);
                fillQs(c);
            }));
        } else {
            TextView plus = text(c, "＋", 14f, C_DIM, false);
            plus.setGravity(Gravity.CENTER);
            plus.setMinWidth(dp(32));
            row.addView(plus);
        }

        row.setOnClickListener(v -> {
            if (on) {
                QuickSettings.remove(c, id);
            } else {
                QuickSettings.add(c, id);
            }
            fillQsEditor(c);
            fillQs(c);
        });
        return row;
    }

    /** 调次序的小箭头；已经到头就变暗、也不响应 */
    private static View qsArrow(Context c, String s, boolean usable, View.OnClickListener click) {
        TextView t = text(c, s, 14f, usable ? C_TITLE : 0x30FFFFFF, false);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(32));
        t.setMinHeight(dp(30));
        t.setBackground(usable ? chipBg(C_CHIP) : null);
        if (usable) {
            t.setOnClickListener(click);
        }
        return t;
    }

    private static View qsSection(Context c, String s) {
        TextView t = text(c, s, 9.5f, C_DIM, false);
        t.setPadding(dp(3), dp(8), 0, dp(4));
        return t;
    }

    private static void recolorRow(View row, TextView name, boolean on) {
        Drawable d = row.getBackground();
        if (d instanceof GradientDrawable) {
            try {
                ((GradientDrawable) d).setColor(on ? C_CHIP_ON : 0x00FFFFFF);
            } catch (Throwable ignored) {
            }
        }
        name.setTextColor(on ? 0xFFFFFFFF : C_TEXT);
    }

    // ------------------------------------------------------------------ 应用筛选栏

    private static void fillChips(Context c, LinearLayout chipBox,
                                  List<CoverNotifListener.Entry> all) {
        chipBox.removeAllViews();
        Set<String> pkgs = new LinkedHashSet<>();
        for (CoverNotifListener.Entry e : all) {
            pkgs.add(e.pkg);
        }
        if (pkgs.size() > 1) {
            chipBox.addView(chip(c, "全部", null, null));
        }
        for (String pkg : pkgs) {
            CoverNotifListener.Entry one = firstOf(all, pkg);
            chipBox.addView(chip(c, labelOf(one, pkg), one == null ? null : one.icon, pkg));
        }
    }

    private static View chip(Context c, String name, Drawable icon, String pkg) {
        boolean on = pkg == null ? filterPkg == null : pkg.equals(filterPkg);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(13));
        bg.setColor(on ? C_CHIP_ON : C_CHIP);
        if (on) {
            bg.setStroke(dp(1), 0x805B9DFF);
        }
        box.setBackground(bg);
        box.setPadding(dp(7), dp(3), dp(9), dp(3));

        if (icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(12), dp(12));
            ip.rightMargin = dp(4);
            box.addView(iv, ip);
        }
        TextView t = text(c, name, 10f, on ? 0xFFFFFFFF : C_TEXT, false);
        box.addView(t);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(5);
        box.setLayoutParams(lp);
        box.setOnClickListener(v -> {
            filterPkg = pkg;
            buildContent(c);
        });
        return box;
    }

    // ------------------------------------------------------------------ 通知列表

    private static void fillList(Context c, LinearLayout listBox,
                                 List<CoverNotifListener.Entry> all) {
        listBox.removeAllViews();
        List<CoverNotifListener.Entry> show = new ArrayList<>();
        for (CoverNotifListener.Entry e : all) {
            if (filterPkg == null || filterPkg.equals(e.pkg)) {
                show.add(e);
            }
        }
        if (show.isEmpty()) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, dp(24), 0, dp(24));
            TextView t = text(c, "", 11.5f, C_DIM, false);
            if (!CoverNotifListener.granted(c)) {
                t.setText("还没给通知使用权\n到「Beta 实验室 → 外屏管理」里点一下授权");
            } else if (all.isEmpty()) {
                t.setText("现在没有通知");
            } else {
                t.setText("这个应用没有别的通知了");
            }
            t.setGravity(Gravity.CENTER);
            box.addView(t);
            listBox.addView(box);
            return;
        }
        int n = Math.min(MAX_ROWS, show.size());
        for (int i = 0; i < n; i++) {
            listBox.addView(notifRow(c, show.get(i)));
        }
        if (show.size() > n) {
            TextView more = text(c, "还有 " + (show.size() - n) + " 条", 10f, C_DIM, false);
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(6), 0, dp(4));
            listBox.addView(more);
        }
    }

    /**
     * 一条通知。旧版是三行（应用名+时间 / 标题 / 正文最多两行），现在压成<b>两行</b> ——
     * 应用名交给左边的图标和上面的筛选项去认，这里只留标题与正文，字号也各降了一档。
     */
    private static View notifRow(Context c, final CoverNotifListener.Entry e) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x0DFFFFFF);
        row.setBackground(bg);
        row.setPadding(dp(7), dp(6), dp(2), dp(6));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(5);
        row.setLayoutParams(rlp);
        row.setClickable(true);

        View icon = appIcon(c, e);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22));
        ip.rightMargin = dp(7);
        row.addView(icon, ip);

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout meta = new LinearLayout(c);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text(c, e.title, 11.5f, C_TITLE, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView when = text(c, hhmm(e.when), 9.5f, C_DIM, false);
        when.setPadding(dp(4), 0, 0, 0);
        meta.addView(when);
        textCol.addView(meta);

        if (!TextUtils.isEmpty(e.text)) {
            TextView t = text(c, e.text, 10f, C_TEXT, false);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(t);
        }
        row.addView(textCol);

        // 单条清掉的那个 ×。跟右上角那个同理 —— 点的是控件不是字。
        TextView x = text(c, "×", 18f, C_DIM, false);
        x.setGravity(Gravity.CENTER);
        x.setMinWidth(dp(34));
        x.setMinHeight(dp(34));
        x.setOnClickListener(v -> {
            CoverNotifListener.dismiss(e.key);
            buildContent(c);
        });
        row.addView(x);

        row.setOnClickListener(v -> open(e));
        return row;
    }

    // ------------------------------------------------------------------ 动作

    /** 点一条通知：优先用它自己的 contentIntent，没有就把那个应用拉起来 */
    private static void open(final CoverNotifListener.Entry e) {
        boolean went = false;
        try {
            if (e.contentIntent != null) {
                e.contentIntent.send();
                went = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "打开通知失败", t);
        }
        hide();
        if (!went) {
            launchPkg(displayCtx, e.pkg);
        }
    }

    private static void dismissAll() {
        List<CoverNotifListener.Entry> all;
        try {
            all = CoverNotifListener.snapshot();
        } catch (Throwable t) {
            all = new ArrayList<>();
        }
        for (CoverNotifListener.Entry e : all) {
            if (filterPkg == null || filterPkg.equals(e.pkg)) {
                CoverNotifListener.dismiss(e.key);
            }
        }
        if (card == null) {
            return;
        }
        buildContent(card.getContext());
    }

    /**
     * 把某个应用启到外屏那一块。
     *
     * <p>走 {@link SecondaryLauncher}（shell → 三星封面屏通道 → 直投，三条路它自己挑），
     * 而不是像旧版那样发一句 {@code am start -a MAIN -c LAUNCHER -p <包名>} ——
     * 旧写法对"有多个入口、或入口不是 MAIN/LAUNCHER"的应用会静默失败。
     */
    private static void launchPkg(final Context c, final String pkg) {
        if (c == null || pkg == null) {
            return;
        }
        final Context app = c.getApplicationContext();
        final int d = display;
        new Thread(() -> {
            try {
                Intent li = app.getPackageManager().getLaunchIntentForPackage(pkg);
                if (li == null) {
                    Log.w(TAG, pkg + " 没有可启动入口");
                    return;
                }
                boolean ok = SecondaryLauncher.launch(app, d, li);
                Log.i(TAG, "快捷启动 " + pkg + " => " + ok);
            } catch (Throwable t) {
                Log.w(TAG, "快捷启动失败 " + pkg, t);
            }
        }, "notif-launch").start();
    }

    // ------------------------------------------------------------------ 小工具

    private static CoverNotifListener.Entry firstOf(List<CoverNotifListener.Entry> all, String pkg) {
        for (CoverNotifListener.Entry e : all) {
            if (e.pkg != null && e.pkg.equals(pkg)) {
                return e;
            }
        }
        return null;
    }

    /** 应用名：优先用监听器读到的真名，读不到就拿包名末段顶上 */
    private static String labelOf(CoverNotifListener.Entry e, String pkg) {
        if (e != null && !TextUtils.isEmpty(e.label)) {
            return e.label;
        }
        if (pkg == null) {
            return "通知";
        }
        int i = pkg.lastIndexOf('.');
        return i >= 0 && i + 1 < pkg.length() ? pkg.substring(i + 1) : pkg;
    }

    /** 应用图标；拿不到就画一个带首字母的圆 */
    private static View appIcon(Context c, CoverNotifListener.Entry e) {
        if (e != null && e.icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(e.icon);
            return iv;
        }
        TextView tv = new TextView(c);
        String s = labelOf(e, e == null ? null : e.pkg);
        tv.setText(s.isEmpty() ? "?" : s.substring(0, 1));
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 10f * density);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(hueOf(e == null ? "" : e.pkg));
        tv.setBackground(d);
        return tv;
    }

    /** 一颗小按钮的底：圆角 + 给定颜色 */
    private static GradientDrawable chipBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setColor(color);
        return d;
    }

    /** 左栏那一格：圆角底 + 居中内容，点击/长按都挂在它身上 */
    private static FrameLayout cellBox(Context c) {
        FrameLayout box = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0x14FFFFFF);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CELL), dp(CELL));
        lp.bottomMargin = dp(6);
        box.setLayoutParams(lp);
        return box;
    }

    /** 一行字的简写（字号 sp、颜色、要不要加粗） */
    private static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
        }
        return t;
    }

    /** 由包名定一个稳定的颜色，同一应用每次都是同一个色 */
    private static int hueOf(String pkg) {
        int h = pkg == null ? 0 : (pkg.hashCode() & 0x7FFFFFFF);
        return android.graphics.Color.HSVToColor(new float[]{(h % 360), 0.42f, 0.62f});
    }

    private static String hhmm(long when) {
        try {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(when));
        } catch (Throwable t) {
            return "";
        }
    }

    private static void post(Runnable r) {
        Handler h = ui;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
        }
        h.post(r);
    }

    private static int dp(float v) {
        return Math.round(v * density);
    }
}
