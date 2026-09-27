package com.wb.extrotator;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Random;

/**
 * 「老虎机」小组件 —— 左边一个红框罩着三个格子，右边竖着「计分器 + 摇杆」，4×2。
 *
 * <p><b>两个模式</b>（状态在 {@link SlotConfig}）：
 * <ul>
 *   <li><b>摇奖</b>：三格是滚筒，点摇杆摇一次；两格相同算小奖、三格相同算大奖。</li>
 *   <li><b>快捷按钮</b>：三格各挂一个开关类功能，点哪格执行哪个；摇杆这时改成「设置那三个位」。
 *       点右侧计分器在两个模式之间来回切。</li>
 * </ul>
 *
 * <p><b>三格里的图案是手机里已装应用的图标</b>（外加一个「七」彩蛋）：每次开摇现从
 * {@link AppIconLib} 那份图标库里抽 {@link SlotPool#APPS} 个应用 + 一个「七」组成池子，
 * 存盘之后交给 {@link SlotPool} 合成位图。三格同摇到「七」是头奖 —— 经典老虎机里
 * 那个红色 7 就是这么来的。
 *
 * <p><b>「滚动」是怎么做出来的</b>：RemoteViews 没有动画 API —— 系统里那份方法白名单
 * 连 {@code setScrollY} 都不收，唯一能出真动画的是 {@code ViewFlipper}（它带
 * {@code @RemoteView} 注解，且 {@code setDisplayedChild} 在白名单里）。所以每格是一个
 * 装着 {@link #SLOTS_PER_REEL} 个 child 的 ViewFlipper，第 k 个 child 永远对应池子第 k 项，
 * 每 {@link #TICK} 毫秒切一格，动画本体在 {@code anim/slot_roll_in|out.xml} 里由宿主渲染。
 *
 * <p>三格按 {@link #STOP} 依次停下（左早右晚，跟真机台一样）。停在哪一项不是"喊着停"的
 * —— 第 i 格的停止帧号是 {@code STOP[i]/TICK}，滚动时把索引从 {@code fin[i]} 往回倒推
 * 还要走几步，于是最后一帧恰好落在 {@code fin[i]}，中间不会出现"跳一格"。
 *
 * <p>⚠⚠ <b>落定的格子交给结果层（布局里的 slotResX），之后一帧都不再碰滚筒。</b>
 * 因为 {@code ViewAnimator.showOnly()} 对"当前该显示的那个 child"是<b>无条件</b>
 * {@code startAnimation(mInAnimation)} 的 —— 索引没变也照样重播一次进入动画。
 * 所以"定格之后再每帧设一次同一个值"＝那一格每帧重新从下方抽上来一次：
 * 用户报的"停下来之后还要闪一下"就是它，而这几下抽搐同时把三格的"依次停"糊成一团
 * （看着三格都还在动，分不出谁先停）。
 *
 * <p>⚠⚠ <b>位图只能在"滚动中的整帧"发一次</b>（第 0 帧）：每帧 3 格 × 8 张，
 * 一张 72px 的图约 10KB，一帧全塞进去就是 250KB 上下 —— 这是整套里唯一一处重活，
 * 所以只做一次；之后每一帧只发几个 int，宿主复用视图树，图还在。
 * 结论：**别把 setImageViewBitmap 挪到每帧都走的那条路上**。
 *
 * <p>⚠ <b>选池子、渲染位图都在后台线程做</b>（要读图标库的 PNG）；主线程只发帧。
 * 组件这条链路只要卡一下，用户看到的就是"按了摇杆没反应"。
 *
 * <p>⚠ <b>画上去的值全部来自本地资源与 SharedPreferences</b>（不碰 Shizuku、不读系统数据）：
 * 组件那边只要有一次画崩，桌面就会把整块换成「无法显示微件」。
 *
 * <p>⚠ {@code setDisplayedChild} 要 API 31+。低版本上退化成"不滚动、直接显示结果"
 * （借用结果层显示图标），好歹功能是完整的，不会崩。
 */
public class SlotWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "SlotWidget";

    /** 摇杆按下去发的广播：摇奖模式下 = 摇一次 */
    static final String ACT_SPIN = "com.wb.extrotator.SLOT_SPIN";
    /** 计分器按下去发的广播：切「摇奖 / 快捷按钮」两个模式 */
    static final String ACT_MODE = "com.wb.extrotator.SLOT_MODE";

    /** 几个滚筒 */
    static final int REELS = 3;
    /** 每个滚筒几个 child —— 必须跟 widget_slot.xml 里每支 ViewFlipper 的 child 数一致 */
    static final int SLOTS_PER_REEL = 8;

    /**
     * 三个滚筒的 child id：{@code ICON[滚筒][池子里第几项]}。
     *
     * ⚠ 这张表必须跟布局里的 slotA0..slotC7 一一对应，顺序也不能换 ——
     * RemoteViews 的动作是**按 id 找 View** 的，塞错一格就是"图案和结果对不上"。
     * 好在漏 id 会在编译期就报错（R.id 找不到），只有"顺序写反"是静默的。
     */
    private static final int[][] ICON = {
            {R.id.slotA0, R.id.slotA1, R.id.slotA2, R.id.slotA3,
                    R.id.slotA4, R.id.slotA5, R.id.slotA6, R.id.slotA7},
            {R.id.slotB0, R.id.slotB1, R.id.slotB2, R.id.slotB3,
                    R.id.slotB4, R.id.slotB5, R.id.slotB6, R.id.slotB7},
            {R.id.slotC0, R.id.slotC1, R.id.slotC2, R.id.slotC3,
                    R.id.slotC4, R.id.slotC5, R.id.slotC6, R.id.slotC7},
    };

    /**
     * 一帧的时长。
     *
     * ⚠ 跟动画时长（anim/slot_roll_in|out.xml 里的 100ms）刻意**不相等**：动画要比帧间隔
     * 短一档，每一跳才能在自己的那一格里走完。两者相等时，一次 IPC 抖动就会让下一帧的
     * 动画在上一跳还没走完时打断它 —— 那正是"顿挫"的来源。
     */
    private static final int TICK = 130;
    /** 三格各自的停下时刻（第几帧）：左 10、中 14、右 18 ⇒ 约 1.3 / 1.8 / 2.3 秒，间隔 520ms */
    private static final int[] STOP = {10, 14, 18};

    /** 「三格都落定」—— 静止态、切模式、改完配置重画都用它 */
    private static final boolean[] HELD_ALL = {true, true, true};

    /** 小奖（两格相同）加多少分 */
    private static final int PT_DOUBLE = 2;
    /** 大奖（三格相同）加多少分 */
    private static final int PT_TRIPLE = 7;

    /** 中奖后结论停留多久再把底部换回累计战绩 */
    private static final long HOLD_MS = 1800L;

    /** 战绩与分数的档名 */
    private static final String SP = "slot_stats";

    /** 池子里一个图标都没有时的占位（不是"没配"，是真的抽不出东西来） */
    private static final String[] NO_POOL = new String[0];

    private static final int[] LED = {
            R.drawable.widget_slot_led_0, R.drawable.widget_slot_led_1,
            R.drawable.widget_slot_led_2, R.drawable.widget_slot_led_3,
            R.drawable.widget_slot_led_4, R.drawable.widget_slot_led_5,
            R.drawable.widget_slot_led_6, R.drawable.widget_slot_led_7,
            R.drawable.widget_slot_led_8, R.drawable.widget_slot_led_9,
    };

    /** 滚筒那套 API 要 API 31（S）才在框架里 */
    private static final boolean CAN_ROLL = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;

    private static final Random RND = new Random();

    /** 主线程的延时器。发帧、收尾都得在主线程上排 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 滚动循环挂这儿：连点摇杆时先把上一轮掐掉，否则两个循环会抢着画 */
    private static Handler loop;
    /** 当前这一轮广播的存活凭据，被打断时由下一轮负责收尾 */
    private static BroadcastReceiver.PendingResult pending;
    /** 轮次号：每摇一次 +1；还在准备/发帧的老轮次看到号变了就自己退场 */
    private static int gen;
    /** 中奖那张艺术字，生成一次就够（它的内容是固定的） */
    private static Bitmap winArt;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String act = intent.getAction();
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            super.onReceive(ctx, intent);
            return;
        }
        if (ACT_SPIN.equals(act)) {
            // 自定义模式下摇杆是「设置键」。正常不会走到这儿（那时绑的是配置页），
            // 但万一点的是旧模式留下的 PendingIntent，也别真摇一次。
            if (SlotConfig.mode(ctx) == SlotConfig.MODE_SPIN) {
                spin(ctx, getClass(), id, goAsync());
            } else {
                redraw(ctx, getClass(), id);
            }
            return;
        }
        if (ACT_MODE.equals(act)) {
            SlotConfig.toggleMode(ctx);
            redraw(ctx, getClass(), id);
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        final Context app = ctx.getApplicationContext();
        final Class<?> cls = getClass();
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, make(app, cls, id, idle(app), HELD_ALL, null, false, true));
            } catch (Throwable ignored) {
            }
        }
        // 刚装上（或刚清过数据）时图标库还是空的，先把这一版空壳画出来，
        // 后台把库建好再重画一遍 —— 否则第一眼看到的是三个空格子。
        new Thread(new Runnable() {
            @Override
            public void run() {
                AppIconLib.ensure(app);
                refreshAll(app, SlotWidgetProvider.class, SlotCoverWidgetProvider.class);
            }
        }, "slot-warmup").start();
    }

    // ── 摇奖 ────────────────────────────────────────────────────────────

    /**
     * 静止态三格各显示哪一项：**上一次摇出来的结果**（存过盘）。
     * 从没摇过就退化成 0/1/2 —— 三格各不相同，免得静止态看着像已经三连了。
     */
    private static int[] idle(Context ctx) {
        try {
            String s = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                    .getString("last_fin", null);
            if (s != null) {
                String[] a = s.split(",");
                if (a.length == 3) {
                    return new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1]),
                            Integer.parseInt(a[2])};
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{0, 1, 2};
    }

    /**
     * 摇一次。**先把上一轮掐掉**（连点摇杆时两个循环会抢着画），
     * 然后在后台线程选池子、合成位图，最后回主线程发帧。
     */
    private static void spin(final Context ctx, final Class<?> cls, final int id,
                             BroadcastReceiver.PendingResult pr) {
        cancelPrev();
        pending = pr;
        final int my = gen;
        final Context app = ctx.getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] pool = null;
                Bitmap[] tiles = null;
                try {
                    AppIconLib.ensure(app);                 // 库旧了/还没建，顺手补一次
                    pool = SlotPool.pick(app);              // 每次现抽，所以每摇一把图案都换一批
                    if (pool.length >= 2) {
                        tiles = SlotPool.tiles(app, pool);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "准备摇奖失败", t);
                }
                final String[] fp = pool;
                final Bitmap[] ft = tiles;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (my != gen) {
                            return;                         // 准备的这点时间里又被点过一次
                        }
                        if (fp == null || fp.length < 2) {
                            redraw(app, cls, id);           // 图标库是空的（极少见）
                            done();
                            return;
                        }
                        SlotPool.store(app, fp);            // 存盘：静止态要照它重画
                        roll(app, cls, id, fp, ft);
                    }
                });
            }
        }, "slot-prepare").start();
    }

    /** 发帧。每帧只发几个 int，图案位图只在第 0 帧塞一次（见类注释那条 ⚠⚠）。 */
    private static void roll(final Context ctx, final Class<?> cls, final int id,
                             final String[] pool, final Bitmap[] tiles) {
        if (loop != null) {
            loop.removeCallbacksAndMessages(null);
        }
        loop = new Handler(Looper.getMainLooper());
        final AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        final int my = gen;
        final int p = pool.length;

        // 三格停在哪一项：纯随机
        final int[] fin = new int[REELS];
        for (int i = 0; i < REELS; i++) {
            fin[i] = RND.nextInt(p);
        }

        // 最后一格定格之后**再等一格**才结算：那一格留给它的"最后一跳"把动画走完，
        // 不然艺术字和金边会跟最后一跳挤在同一帧上，看着像"还没停稳就弹字"。
        final int end = STOP[2] + 1;
        for (int f = 0; f <= end; f++) {
            final int frame = f;
            loop.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (my != gen) {
                        return;
                    }
                    if (frame >= end) {
                        settle(ctx, mgr, cls, id, pool, tiles, fin);
                        return;
                    }
                    int[] cur = new int[REELS];
                    boolean[] held = new boolean[REELS];
                    for (int i = 0; i < REELS; i++) {
                        if (frame > STOP[i]) {
                            // 已经停了：这一格交回结果层，之后一帧都不再碰它
                            cur[i] = fin[i];
                            held[i] = true;
                        } else if (frame == STOP[i]) {
                            // 最后一跳，动画正好落进 fin[i]
                            cur[i] = fin[i];
                        } else {
                            // 从终点往回倒推：还差几步就在终点前几步，于是最后一帧正好落在终点
                            int back = STOP[i] - frame;
                            cur[i] = ((fin[i] - back) % p + p) % p;
                        }
                    }
                    // 头两帧是摇杆拉下来的样子，给点手感；chrome 只在第 0 帧发一次
                    try {
                        mgr.updateAppWidget(id, make(ctx, cls, id, cur, held, null,
                                frame < 2, frame == 0, pool, tiles));
                    } catch (Throwable t) {
                        Log.w(TAG, "发第 " + frame + " 帧失败", t);
                    }
                }
            }, (long) f * TICK);
        }
    }

    /** 三格定格：判中奖、记战绩与分数、亮金边、冒出艺术字、震动，1.8 秒后收回结论 */
    private static void settle(final Context ctx, final AppWidgetManager mgr,
                               final Class<?> cls, final int id,
                               final String[] pool, final Bitmap[] tiles, final int[] fin) {
        final int best = sameCount(fin);
        final boolean[] hl = highlight(fin, best);

        SharedPreferences sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
        int gain = best == 3 ? PT_TRIPLE : best == 2 ? PT_DOUBLE : 0;
        sp.edit()
                .putInt("spins", sp.getInt("spins", 0) + 1)
                .putInt("dbl", sp.getInt("dbl", 0) + (best == 2 ? 1 : 0))
                .putInt("tri", sp.getInt("tri", 0) + (best == 3 ? 1 : 0))
                .putInt("score", sp.getInt("score", 0) + gain)
                // 静止态（组件被重画、重启、切模式）要照这一次的结果画，所以得记下来
                .putString("last_fin", fin[0] + "," + fin[1] + "," + fin[2])
                .apply();

        // 三格都已经交回结果层了，这一帧只负责"中奖"这件事：金边 + 艺术字 + 震动
        try {
            mgr.updateAppWidget(id, make(ctx, cls, id, fin, HELD_ALL, hl, false, true, pool, tiles));
        } catch (Throwable ignored) {
        }
        buzz(ctx, best);

        if (loop == null) {
            done();
            return;
        }
        loop.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    mgr.updateAppWidget(id,
                            make(ctx, cls, id, fin, HELD_ALL, null, false, true, pool, tiles));
                } catch (Throwable ignored) {
                }
                done();
            }
        }, HOLD_MS);
    }

    /** 掐掉上一轮：广播凭据结掉、延时帧全撤、轮次号 +1 */
    private static void cancelPrev() {
        gen++;
        if (loop != null) {
            loop.removeCallbacksAndMessages(null);
        }
        if (pending != null) {
            try {
                pending.finish();
            } catch (Throwable ignored) {
            }
            pending = null;
        }
    }

    private static void done() {
        if (pending != null) {
            try {
                pending.finish();
            } catch (Throwable ignored) {
            }
            pending = null;
        }
    }

    /** 只重画一次（切模式、以及摇杆在错误模式上被按到时用） */
    private static void redraw(Context ctx, Class<?> cls, int id) {
        try {
            AppWidgetManager.getInstance(ctx)
                    .updateAppWidget(id, make(ctx, cls, id, idle(ctx), HELD_ALL, null, false, true));
        } catch (Throwable ignored) {
        }
    }

    // ── 画 ──────────────────────────────────────────────────────────────

    static RemoteViews make(Context ctx, Class<?> cls, int id, int[] idx,
                            boolean[] held, boolean[] hl, boolean down, boolean chrome) {
        return make(ctx, cls, id, idx, held, hl, down, chrome, null, null);
    }

    /**
     * 画一帧。
     *
     * @param idx    三格各自要显示的项（滚动中 = 这一帧滚到哪；落定后 = 结果）
     * @param held   哪几格**已经落定**。null 与**全 false 都表示"还有格子在滚"**
     *               （判定见下面那条 ⚠⚠）。落定的格子交给 slotResX 结果层，而且**一帧都不再碰
     *               setDisplayedChild** —— 原因见类注释里那条 ⚠
     * @param hl     中奖高亮；null = 没中奖（也就没有艺术字）
     * @param down   摇杆按下态（只有开摇头两帧）
     * @param chrome 要不要连"外壳"一起发（点击、金边、艺术字层、滚筒上的图案）。
     *               滚动中间帧传 false：那几帧只该动滚筒，跨进程传的东西越少越顺
     * @param pool   这一轮的池子；null = 现读（静止态、切模式用这条）
     * @param tiles  池子对应的位图，跟 pool 配对着传；null = 现合成
     */
    static RemoteViews make(Context ctx, Class<?> cls, int id, int[] idx,
                            boolean[] held, boolean[] hl, boolean down, boolean chrome,
                            String[] pool, Bitmap[] tiles) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_slot);

        final boolean custom = SlotConfig.mode(ctx) == SlotConfig.MODE_BTN;
        // 低版本没法切 ViewFlipper 的 child，就不滚了，直接把结果放进结果层
        final boolean roll = !custom && CAN_ROLL;
        final boolean flat = !custom && !CAN_ROLL;
        // 还有格子在滚吗 —— 只有这种帧需要把图案位图交出去。
        // ⚠⚠ 判定**千万别写成 held == null**：roll() 传的是一支**全 false 的数组**（表示
        // "三格都在滚"），不是 null。写成 == null 会让 rolling 恒为 false ⇒ 位图一次都塞不进去
        // ⇒ 滚筒里是三个空白块，切 child 时看不出任何动静（v466 用户报的"摇的时候没有动画"就是它；
        // v465 是文字走 setTextViewText，绕开了这条判定，所以那时看得见）。
        boolean anySpinning = true;
        if (held != null) {
            anySpinning = false;
            for (int i = 0; i < held.length; i++) {
                if (!held[i]) {
                    anySpinning = true;
                    break;
                }
            }
        }
        final boolean rolling = !custom && anySpinning;

        final String[] use = custom ? NO_POOL : (pool != null ? pool : SlotPool.pool(ctx));
        final int n = Math.min(use.length, SLOTS_PER_REEL);

        int[] rolls = {R.id.slotRollA, R.id.slotRollB, R.id.slotRollC};
        int[] btns = {R.id.slotBtnA, R.id.slotBtnB, R.id.slotBtnC};
        int[] ress = {R.id.slotResA, R.id.slotResB, R.id.slotResC};
        int[] glows = {R.id.slotGlowA, R.id.slotGlowB, R.id.slotGlowC};

        // 滚筒上的图案。**必须排在下面 setDisplayedChild 之前** —— 宿主是按动作入队的
        // 顺序依次套用的，"先切过去、再把图塞给它"会有一瞬间是空白（艺术字那条踩过同款）。
        // 整帧 + 还在滚 = 一帧 3×8 张位图（约 250KB），这是整套里唯一的重活，只在第 0 帧做；
        // 后面每帧宿主复用视图树，图还在。低版本（不能滚）连塞都不用塞。
        if (chrome && rolling && roll && n > 0) {
            Bitmap[] t = tiles != null ? tiles : SlotPool.tiles(ctx, use);
            for (int i = 0; i < REELS; i++) {
                for (int k = 0; k < n; k++) {
                    Bitmap b = k < t.length ? t[k] : null;
                    if (b != null) {
                        v.setImageViewBitmap(ICON[i][k], b);
                    }
                }
            }
        }

        for (int i = 0; i < REELS; i++) {
            final boolean h = held != null && held[i];
            final boolean spinning = roll && !h;            // 还在滚
            final boolean landed = (roll && h) || flat;     // 落定了，交给结果层
            final int k = n <= 0 ? 0 : Math.max(0, Math.min(idx[i], n - 1));

            // 三层可见性每帧都显式发：宿主复用视图树时它是幂等的；万一这一帧走的是
            // "重新 inflate"，漏发的那一层就会露出 XML 默认值（滚筒闪回第 0 项）。
            v.setViewVisibility(rolls[i], spinning ? View.VISIBLE : View.GONE);
            v.setViewVisibility(btns[i], custom ? View.VISIBLE : View.GONE);
            v.setViewVisibility(ress[i], landed && n > 0 ? View.VISIBLE : View.GONE);

            if (spinning) {
                v.setDisplayedChild(rolls[i], idx[i]);
            }
            if (landed && n > 0) {
                // 结果层是图标，不是文字
                Bitmap b = tileAt(ctx, use, tiles, k);
                if (b != null) {
                    v.setImageViewBitmap(ress[i], b);
                }
            }
            if (custom) {
                v.setTextViewText(btns[i], SlotConfig.label(ctx, i));
            }
            if (chrome) {
                // 中奖那几格压一层金边；自定义模式下不亮
                v.setViewVisibility(glows[i],
                        (!custom && hl != null && hl[i]) ? View.VISIBLE : View.GONE);
            }
        }

        // 计分器：三位数码管，高位为 0 就熄灭（不然一直挂着两个 0，像没接通电）
        int score = score(ctx);
        v.setImageViewResource(R.id.slotLedH,
                score >= 100 ? LED[score / 100 % 10] : R.drawable.widget_slot_led_blank);
        v.setImageViewResource(R.id.slotLedT,
                score >= 10 ? LED[score / 10 % 10] : R.drawable.widget_slot_led_blank);
        v.setImageViewResource(R.id.slotLedO, LED[score % 10]);

        v.setTextViewText(R.id.slotFoot, footNow(ctx));

        v.setImageViewResource(R.id.slotLever,
                down ? R.drawable.widget_slot_lever_on : R.drawable.widget_slot_lever);

        if (!chrome) {
            // 滚动中间帧不碰点击：这几帧点什么都没道理，而 PendingIntent 是 Parcelable，
            // 每帧往事务里塞五个，比五个 int 重得多。
            return v;
        }

        v.setOnClickPendingIntent(R.id.slotLever, leverIntent(ctx, cls, id, custom));
        v.setOnClickPendingIntent(R.id.slotScoreBox, modeIntent(ctx, cls, id));

        if (custom) {
            for (int i = 0; i < REELS; i++) {
                if (!SlotConfig.slotAlive(ctx, i)) {
                    continue;   // 没配 / 指令已删：不绑点击，免得点了没反应让人以为坏了
                }
                Intent fi = new Intent(ctx, QuickActionReceiver.class)
                        .setAction(QuickActions.ACTION_RUN)
                        .putExtra(QuickActions.EXTRA_ACTION, SlotConfig.slot(ctx, i));
                v.setOnClickPendingIntent(btns[i], PendingIntent.getBroadcast(ctx,
                        id * 16 + 10 + i, fi,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
        }

        // 中奖艺术字：child 1 是字、child 0 是空的。切过去就播 anim/slot_win_in。
        // ⚠ 顺序不能反：位图要先塞进 child 1，再切过去。反过来的话切过去那一帧
        // 位图还没到位，会闪一下空白（v463 就是这么写的）。
        if (hl != null) {
            v.setImageViewBitmap(R.id.slotWinArt, art(ctx));
            v.setDisplayedChild(R.id.slotWinFlip, 1);
        } else {
            v.setDisplayedChild(R.id.slotWinFlip, 0);
        }
        return v;
    }

    /** 池子第 k 项的位图：优先用调用方已经合成好的那一份，省一次缓存查询 */
    private static Bitmap tileAt(Context ctx, String[] pool, Bitmap[] tiles, int k) {
        if (tiles != null && k < tiles.length && tiles[k] != null) {
            return tiles[k];
        }
        if (k >= pool.length) {
            return null;
        }
        return SlotPool.tile(ctx, pool[k]);
    }

    /** 摇杆：摇奖模式发广播，自定义模式开配置页 */
    private static PendingIntent leverIntent(Context ctx, Class<?> cls, int id, boolean custom) {
        int rc = id * 16 + 1;
        if (custom) {
            return PendingIntent.getActivity(ctx, rc,
                    new Intent(ctx, SlotConfigActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Intent i = new Intent(ctx, cls)
                .setAction(ACT_SPIN)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(ctx, rc, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 计分器：切模式 */
    private static PendingIntent modeIntent(Context ctx, Class<?> cls, int id) {
        Intent i = new Intent(ctx, cls)
                .setAction(ACT_MODE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(ctx, id * 16 + 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 底部那一行：还没摇过提示一下；自定义模式下显示「上一次按了什么」 */
    private static String footNow(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
        if (SlotConfig.mode(ctx) == SlotConfig.MODE_BTN) {
            String m = sp.getString("last_msg", null);
            if (m != null && !m.isEmpty()) {
                return m;
            }
            return ctx.getString(R.string.widget_slot_btn_hint);
        }
        int n = sp.getInt("spins", 0);
        if (n == 0) {
            return ctx.getString(R.string.widget_slot_ready);
        }
        return ctx.getString(R.string.widget_slot_stats,
                n, sp.getInt("dbl", 0), sp.getInt("tri", 0));
    }

    /** 累计得分 —— 小奖 +2、大奖 +7 */
    static int score(Context ctx) {
        try {
            return ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("score", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 中奖那张「中奖了！」。
     *
     * <p>为什么要画成 Bitmap：RemoteViews 给不了文字描边与渐变（那两个都要 Paint，
     * 而白名单里只有 setTextViewText 这类）。这里画三层 —— 深红外描边、米白内描边、
     * 金色渐变填充 —— 「艺术字」的味道全在这三层叠起来的地方。
     *
     * <p>⭐ <b>字号缩到原来的 1/3，位图尺寸（400×120）却不动</b>：位图是交给宿主
     * {@code fitEnd} 缩放的，位图尺寸不变则缩放倍率不变，于是屏幕上显示出来正好是
     * 原来的 1/3 —— 只占中间一小块，不糊住三个滚筒（用户要求）。
     * 描边宽度与渐变范围跟着一起按 1/3 缩，不然细字会被描边吃掉。
     *
     * <p>内容是固定的，所以只生成一次存着。⚠ 不回收：它不是每帧都画，
     * 一张 400×120 的位图（约 190KB）留着换个"不用反复分配"更划算。
     */
    private static Bitmap art(Context ctx) {
        if (winArt != null) {
            return winArt;
        }
        int w = 400;
        int h = 120;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(25f);
        p.setTextAlign(Paint.Align.CENTER);
        String s = ctx.getString(R.string.widget_slot_win);
        float cx = w / 2f;
        float cy = h / 2f - (p.descent() + p.ascent()) / 2f;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4.3f);
        p.setColor(0xFF7C0F13);
        c.drawText(s, cx, cy, p);

        p.setStrokeWidth(1.7f);
        p.setColor(0xFFFFF4C8);
        c.drawText(s, cx, cy, p);

        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, cy - 14, 0, cy + 10,
                0xFFFFF8DC, 0xFFFFAE00, Shader.TileMode.CLAMP));
        c.drawText(s, cx, cy, p);
        p.setShader(null);

        winArt = bm;
        return bm;
    }

    // ── 判定 ────────────────────────────────────────────────────────────

    /** 3 = 三格一样，2 = 有两格一样，1 = 全不一样 */
    private static int sameCount(int[] s) {
        if (s[0] == s[1] && s[1] == s[2]) {
            return 3;
        }
        if (s[0] == s[1] || s[1] == s[2] || s[0] == s[2]) {
            return 2;
        }
        return 1;
    }

    /** 哪几格该亮 —— 亮的是真正凑成一对/三连的那几个；没中就给 null（省得每帧判两次） */
    private static boolean[] highlight(int[] s, int best) {
        if (best < 2) {
            return null;
        }
        boolean[] h = new boolean[3];
        if (best == 3) {
            h[0] = h[1] = h[2] = true;
        } else if (s[0] == s[1]) {
            h[0] = h[1] = true;
        } else if (s[1] == s[2]) {
            h[1] = h[2] = true;
        } else {
            h[0] = h[2] = true;
        }
        return h;
    }

    /** 中了才震：大奖一串，小奖一下 */
    private static void buzz(Context ctx, int best) {
        if (best < 2) {
            return;
        }
        try {
            android.os.Vibrator v =
                    (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                return;
            }
            if (best == 3) {
                v.vibrate(android.os.VibrationEffect.createWaveform(
                        new long[]{0, 60, 90, 60, 90, 170}, -1));
            } else {
                v.vibrate(android.os.VibrationEffect.createOneShot(
                        45, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自定义模式下按了一格之后，把结果记下来在底部晃一下，6 秒后收回去。
     *
     * <p>为什么要这个：方向同步 / 锁方向这类动作没有肉眼可见的即时变化，按下去毫无反馈，
     * 用户只会以为"这格子坏了"。写在底部那行就够了 —— 不必弹 Toast
     * （后台弹 Toast 从 Android 11 起本来就受限，何况组件点一下也就一句短话）。
     *
     * <p>⚠ 用静态 Handler 延时清：进程没了这个 Runnable 也就没了，
     * 那时组件多半也不再显示（不需要额外兜底）。
     */
    static void noteResult(Context ctx, String msg) {
        if (msg == null || msg.isEmpty()) {
            return;
        }
        try {
            final Context app = ctx.getApplicationContext();
            app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                    .putString("last_msg", msg)
                    .apply();
            refreshAll(app, SlotWidgetProvider.class, SlotCoverWidgetProvider.class);
            POST.removeCallbacksAndMessages(null);
            POST.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                                .remove("last_msg").apply();
                        refreshAll(app, SlotWidgetProvider.class, SlotCoverWidgetProvider.class);
                    } catch (Throwable ignored) {
                    }
                }
            }, 6000L);
        } catch (Throwable ignored) {
        }
    }

    /** 收尾那一刻的延时器（主线程） */
    private static final Handler POST = new Handler(Looper.getMainLooper());

    /** 需要重画时调一下（装好 / 改过配置 / 图标库更新过）—— 内外屏两份都刷 */
    static void refreshAll(Context ctx, Class<?>... classes) {
        for (Class<?> cls : classes) {
            try {
                AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
                int[] ids = mgr.getAppWidgetIds(new android.content.ComponentName(ctx, cls));
                if (ids == null) {
                    continue;
                }
                for (int id : ids) {
                    mgr.updateAppWidget(id, make(ctx, cls, id, idle(ctx), HELD_ALL, null, false, true));
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
