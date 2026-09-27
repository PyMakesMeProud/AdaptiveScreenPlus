package com.wb.extrotator;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * 把触摸<b>注进目标屏</b>的那条路 —— 用无障碍服务的 {@code dispatchGesture}。
 *
 * <p><b>为什么不再走 {@code input}</b>：v3.6 及以前所有触摸 / 按键都走
 * {@code ShellRunner.run("input -d N ...")}，那条路有三个死结 —— 每条命令起两个进程
 * （sh + input 那个 JVM），实测 40~65ms；更要命的是 {@code input} 用的是「等结果」模式，
 * 目标屏<b>没有焦点窗口</b>时 InputDispatcher 会一直等，实测一条命令卡 5~8 秒，而所有命令还排在
 * 同一个线程上，一条卡住整条链就废；拖动要逐帧发 MOVE，靠 {@code input} 根本发不到帧率上。
 *
 * <p>{@code dispatchGesture} 把这三个一起解决：<b>不起进程</b>（一次 binder 调用）、
 * <b>不等结果</b>、而且路径由<b>系统按帧插值</b> —— 我们在一条路径里给几个点，帧与帧之间是系统
 * 自己填的，所以是「丝滑」而不是「跳格子」。
 *
 * <p><b>跟手拖动的正确做法</b>：靠 {@link GestureDescription.StrokeDescription#continueStroke}
 * 串成<b>一条连续的手指</b> —— 按下时发一条 {@code willContinue=true} 的零长度笔画
 * （这一刻目标屏就是「按住」状态），之后手指每走一段就续一段，松手时再续一段
 * {@code willContinue=false} 收尾。
 *
 * <p>⚠⚠ <b>旧手势的「遗言」会把新链当场杀掉</b>：旧代码里所有 dispatch 共用一个静态 callback，
 * 而 {@code onCancelled} 是异步来的（system_server 那边排队），常常延迟到下一次 {@code dragStart}
 * 之后才送达 —— 于是刚建立的新链被上一条链的取消回调杀掉。实测：按下后 8ms 手势就被取消，
 * 之后 700ms 手指一直在走、一条事件都没注进去，用户看到的就是「卡成 2~3 帧」。
 * 现在拿两把锁把它锁死（见 {@link #cb}）：<b>代（{@code gen}）</b>——每次 {@code dragStart} 自增，
 * 旧代的任何回调一律忽略；<b>段序（{@code seq}）</b>——当前链内每发一段自增，只有<b>最新那一段</b>
 * 被取消才算真断（被下一段接替掉的旧段收到取消，是正常现象）。
 *
 * <p>⚠ <b>两条硬约束</b>：{@code setDisplayId} 要 API 30+（低版本设备直接返回 false 走
 * {@code input}）；「按住」不能干等 —— 续接笔画之间隔得太久系统会认为手势结束（顺手把手指抬起来），
 * 所以 {@link #KEEP_MS} 那条心跳是必需的（手指停在原地不动时也要隔一会儿补一小段「原地不动」的笔画）。
 */
public final class A11yInject {

    private static final String TAG = "ExtA11yInject";

    /** 点一下的笔画时长 —— 太短系统可能不当一次点击，60ms 足够稳 */
    private static final long TAP_MS = 60L;
    /** 跟手拖动时第一段笔画的时长 */
    private static final long SEG_MS = 150L;
    /** 收尾那一段（松手）的时长 */
    private static final long END_MS = 40L;
    /** 心跳间隔：超过这么久没人续笔画，就补一段"原地不动"续住按住状态 */
    private static final long KEEP_MS = 90L;
    /**
     * 两段笔画之间的最短间隔（限速）。
     *
     * <p>触控板的 MOVE 事件能到上百 Hz，来一个就 dispatchGesture 一次会把系统冲垮 ——
     * 实测被批量 onCancelled（同一毫秒十几条「手势被系统取消」）。
     *
     * <p>取 24ms（约 42 段/秒）：采样越密跟手越紧；真正的下限由触摸本身的采样率决定，
     * 重复位置会原地早退。
     */
    private static final long STEP_MS = 24L;
    /** 一段笔画的时长夹在这个区间里 */
    private static final long MIN_SEG_MS = 16L;
    private static final long SEG_MAX_MS = 200L;
    /** 单条手势的最长时长 —— 系统上限 60s，留点余量 */
    private static final long MAX_CHAIN_MS = 55_000L;
    /** 一条笔画里最多带几个中间点 */
    private static final int PATH_MAX_PTS = 8;

    /** 无障碍服务实例；没连上 = 这条路不可用 */
    private static volatile AccessibilityService svc;

    /** 正在续的那条笔画（null = 当前没有按住的拖动） */
    private static volatile GestureDescription.StrokeDescription chain;
    private static volatile int chainDisplay = -1;
    /** 已经<b>发出去</b>的那一段走到哪了（下一段从这儿起笔） */
    private static volatile float chainX, chainY;
    /** 上一次续笔画的时刻，心跳用它判断"该不该补" */
    private static volatile long chainAt;
    /** 上一次真正 dispatchGesture 的时刻 —— 限速与"时长=真实间隔"都靠它 */
    private static volatile long lastFireAt;

    /**
     * 自上次发出以来，手指<b>走过但还没发出去</b>的那一串位置（折线顶点）。
     *
     * <p>v3.18 新加。以前只记"最后一个位置"，一次续一段直线 —— 手指走的是曲线时，
     * 中间的形状全丢了。现在把这一小串点整条塞进 Path 交给系统，
     * 系统按帧插值走完这条折线，曲线也就跟着弯了。
     */
    private static final float[] pathX = new float[PATH_MAX_PTS];
    private static final float[] pathY = new float[PATH_MAX_PTS];
    private static volatile int pathN;

    /**
     * 链子断了（系统取消了这次手势）。
     *
     * <p>⚠ 断了必须<b>停手</b>：v3.7 只在 onCancelled 里记了条日志就继续续笔画，
     * 于是一条已经被系统扔掉的手势上接着 continueStroke ⇒ 后面全是空的，
     * 用户看到的是"拖不动 / 拖一半东西掉了"。现在一断就置 dead，
     * {@code dragTo} 立刻返回 false，调用方退回 {@code input} 老路。
     */
    private static volatile boolean dead;

    /** 链的代数 —— 每次 {@link #dragStart} 自增。旧代的任何回调一律忽略。 */
    private static volatile int chainGen;
    /** 当前链内已发出的段序 —— 只有"最新那段"被取消才算真断。 */
    private static volatile int segSeq;

    private static final Handler H = new Handler(Looper.getMainLooper());

    private A11yInject() {
    }

    // ------------------------------------------------------------------ 挂载

    /** 无障碍服务连上时调用 */
    static void attach(AccessibilityService s) {
        svc = s;
        Log.i(TAG, "手势注入可用（API " + Build.VERSION.SDK_INT + "）");
    }

    /**
     * 把服务本身给出去 —— 外屏那几层覆盖（{@link CoverSidebar} / {@link CoverRecentsGesture}）要用它。
     *
     * <p>拿到<b>必须立刻判空</b>：服务可能压根没连上，也可能在下一毫秒就被 {@link #detach} 置空。
     * 所以别存成字段、别假设长期有效 —— 每次要用就重新问一次。
     */
    static AccessibilityService service() {
        return svc;
    }

    /** 服务断开 / 销毁时调用 —— 顺手把手势状态清干净，别留个半截手势在那边 */
    static void detach(AccessibilityService s) {
        if (svc != s) {
            return;
        }
        svc = null;
        abortChain();
        Log.i(TAG, "手势注入不可用（服务断开）");
    }

    /** 这条路现在能不能用 */
    public static boolean ready() {
        return svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    // ------------------------------------------------------------------ 一次性的手势

    /** 在光标处点一下 */
    public static boolean tap(int displayId, int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return fireOnce(displayId, new GestureDescription.StrokeDescription(p, 0L, TAP_MS, false));
    }

    /**
     * 原地长按 ms 毫秒。
     *
     * <p>这里不需要"按下 - 睡 - 抬起"三步：一条时长为 ms 的零长度笔画，
     * 在系统那边就是"在这个点按住 ms 毫秒"，中间的按下 / 按住 / 抬起由它自己发。
     */
    public static boolean longPress(int displayId, int x, int y, long ms) {
        Path p = new Path();
        p.moveTo(x, y);
        return fireOnce(displayId, new GestureDescription.StrokeDescription(
                p, 0L, Math.max(200L, ms), false));
    }

    /** 从 (x1,y1) 划到 (x2,y2)，用时 ms —— 滚动 / 翻页都用它 */
    public static boolean swipe(int displayId, int x1, int y1, int x2, int y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return fireOnce(displayId, new GestureDescription.StrokeDescription(
                p, 0L, Math.max(60L, ms), false));
    }

    // ------------------------------------------------------------------ 跟手拖动

    /**
     * 拖动开始（手指按下的那一刻）—— 发一条 {@code willContinue} 的零长度笔画，
     * 目标屏从这一刻起就是"按住"状态。
     *
     * @return false = 这条路走不通，调用方请用 {@code input} 的老办法
     */
    public static boolean dragStart(int displayId, int x, int y) {
        if (!ready()) {
            return false;
        }
        abortChain();

        // 开启新的一代：从这一行起，所有旧手势的取消回调都自动作废（见 cb 里那段）
        final int gen = ++chainGen;
        segSeq = 0;

        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription st =
                new GestureDescription.StrokeDescription(p, 0L, SEG_MS, true);
        if (!fire(displayId, st, gen)) {
            return false;
        }
        chain = st;
        chainDisplay = displayId;
        chainX = x;
        chainY = y;
        chainAt = SystemClock.uptimeMillis();
        lastFireAt = chainAt;
        pathN = 0;
        dead = false;
        H.removeCallbacks(keepAlive);
        H.postDelayed(keepAlive, KEEP_MS);
        return true;
    }

    /** 拖动中手指到了 (x,y) —— 记下走位，到点续一段笔画 */
    public static boolean dragTo(int displayId, int x, int y) {
        GestureDescription.StrokeDescription prev = chain;
        if (prev == null || chainDisplay != displayId) {
            return false;
        }
        if (prev.getStartTime() + prev.getDuration() > MAX_CHAIN_MS) {
            Log.w(TAG, "单次拖动超过 " + MAX_CHAIN_MS + "ms，收尾重来");
            dragEnd(displayId, x, y);
            return false;
        }
        if (dead) {
            // 系统已经取消了这次手势，再续也是空的 —— 让调用方走 input 老路
            return false;
        }
        // 手指没动就别记也别发（心跳那条会替我们续住按住状态）
        if (x == chainX && y == chainY && pathN == 0) {
            return true;
        }
        // 折线里攒着（只留形状，去重相邻的重复点）
        if (pathN > 0 && pathX[pathN - 1] == x && pathY[pathN - 1] == y) {
            // 和上一个待发点重合，不用再记
        } else if (pathN >= PATH_MAX_PTS) {
            // 攒满了：先把这一串发出去，别把形状丢了
            return flushSegment(displayId);
        } else {
            pathX[pathN] = x;
            pathY[pathN] = y;
            pathN++;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastFireAt >= STEP_MS) {
            return flushSegment(displayId);
        }
        return true;
    }

    /**
     * 把攒着的那一串走位续成一段笔画发出去。
     *
     * <p>⚠⚠ 关键在时长：<b>必须用「距上一段真实经过了多少毫秒」，不能写死</b>。系统是按笔画自己的
     * 时间轴播放注入手势的 —— 真实世界走 16ms 却续一段 150ms 的笔画，注入的时间轴就比手指慢近 10 倍，
     * 手感是「又迟又飘」。用真实间隔后，注入的时间轴与真实时间同步：手指走多快，目标屏上就走多快。
     *
     * @return false = 这条路断了，调用方请退回 {@code input}
     */
    private static boolean flushSegment(int displayId) {
        GestureDescription.StrokeDescription prev = chain;
        if (prev == null || chainDisplay != displayId || dead) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        long dur = now - lastFireAt;
        if (dur < MIN_SEG_MS) {
            dur = MIN_SEG_MS;
        } else if (dur > SEG_MAX_MS) {
            dur = SEG_MAX_MS;
        }

        Path p = new Path();
        p.moveTo(chainX, chainY);
        for (int i = 0; i < pathN; i++) {
            p.lineTo(pathX[i], pathY[i]);
        }
        final float nx = pathN > 0 ? pathX[pathN - 1] : chainX;
        final float ny = pathN > 0 ? pathY[pathN - 1] : chainY;

        GestureDescription.StrokeDescription st = prev.continueStroke(
                p, prev.getStartTime() + prev.getDuration(), dur, true);
        if (!fire(displayId, st, chainGen)) {
            abortChain();
            return false;
        }
        chain = st;
        chainX = nx;
        chainY = ny;
        chainAt = now;
        lastFireAt = now;
        pathN = 0;
        return true;
    }

    /** 松手 —— 续最后一段并让它结束，整条手势到此收尾 */
    public static boolean dragEnd(int displayId, int x, int y) {
        GestureDescription.StrokeDescription prev = chain;
        H.removeCallbacks(keepAlive);
        final int gen = chainGen;
        chain = null;
        chainDisplay = -1;
        pathN = 0;
        if (prev == null || !ready()) {
            return false;
        }
        Path p = new Path();
        p.moveTo(chainX, chainY);
        if (x != chainX || y != chainY) {
            p.lineTo(x, y);
        }
        GestureDescription.StrokeDescription st = prev.continueStroke(
                p, prev.getStartTime() + prev.getDuration(), END_MS, false);
        return fire(displayId, st, gen);
    }

    /**
     * 心跳 —— 手指停在原地不动时，把"按住"续住。
     *
     * <p>没有这一条，用户按住不放超过 {@link #SEG_MS} 不走动，系统就认为这段笔画结束了，
     * 顺手把手指抬起来：拖到一半东西掉了。
     *
     * <p>顺带也把"被限速挡住、还没来得及发"的那串走位补发出去（时限速了也不丢走位）。
     */
    private static final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            GestureDescription.StrokeDescription prev = chain;
            if (prev == null || !ready()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (pathN > 0 || now - chainAt >= KEEP_MS) {
                if (!flushSegment(chainDisplay)) {
                    // 续不上（多半是超时/被取消）—— 干脆收掉，别留个半截手势
                    Log.w(TAG, "心跳续笔画失败，收掉这次拖动");
                    abortChain();
                    return;
                }
            }
            H.postDelayed(this, KEEP_MS);
        }
    };

    /** 把当前拖动丢掉（页面走了 / 服务断了 / 续不上时）—— 公开版给 {@link ExtScreen#dragAbort()} */
    public static void abort() {
        abortChain();
    }

    private static void abortChain() {
        H.removeCallbacks(keepAlive);
        final GestureDescription.StrokeDescription prev = chain;
        final int disp = chainDisplay;
        final float x = chainX, y = chainY;
        chain = null;
        chainDisplay = -1;
        pathN = 0;
        /*
         * ⚠ 这里也要换代：收尾这一发会让在途的段全部收到 onCancelled，
         * 若不换代，那些"遗言"会在下一次 dragStart 之后到达、把新链误杀
         * （v3.17 的 bug，见类注释）。
         */
        chainGen++;
        AccessibilityService s = svc;
        if (prev == null || s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        // 补一段零长度的收尾，把手指抬起来 —— 不补的话目标屏会一直维持"按下"
        Path p = new Path();
        p.moveTo(x, y);
        try {
            s.dispatchGesture(build(disp, prev.continueStroke(
                    p, prev.getStartTime() + prev.getDuration(), END_MS, false)), null, null);
        } catch (Throwable t) {
            Log.w(TAG, "收尾手势失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 底层

    /** 一次性手势（点 / 长按 / 划）—— 不参与链，用简版回调 */
    private static boolean fireOnce(int displayId, GestureDescription.StrokeDescription st) {
        AccessibilityService s = svc;
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            return s.dispatchGesture(build(displayId, st),
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription g) {
                            Log.d(TAG, "一次性手势完成");
                        }

                        @Override
                        public void onCancelled(GestureDescription g) {
                            Log.w(TAG, "一次性手势被取消");
                        }
                    }, null);
        } catch (Throwable t) {
            Log.w(TAG, "dispatchGesture 抛异常: " + t);
            return false;
        }
    }

    /**
     * 发一段<b>链上</b>的笔画，并带上"我是哪一代、第几段"。
     *
     * <p>⚠ 每次都要 new 一个回调 —— 不能用静态单例。这是拆 v3.17 那颗雷的关键：
     * 回调里靠闭包拿到 {@code gen}/{@code seq}，才能分辨"这声取消是谁的"。
     */
    private static boolean fire(int displayId, GestureDescription.StrokeDescription st, int gen) {
        AccessibilityService s = svc;
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        final int mySeq = ++segSeq;
        try {
            boolean ok = s.dispatchGesture(build(displayId, st), cb(gen, mySeq), null);
            /*
             * ⚠ 这行是<b>真实派发时刻</b> —— 量节拍只能靠它，别拿 onCompleted 当表。
             *
             * 实测：同一场 1.46s 的滑动里我们明明派发了二十来次，而 onCompleted 只回来 7 次、
             * 且最后三次落在松手之后 500ms —— 回调是跨进程来的、要排队，拿它当节拍表会把
             * 「系统还欠着没播」误读成「我们没发出去」，从而把好好的节拍判成 410ms。
             */
            Log.d(TAG, "派发 #" + gen + "." + mySeq
                    + " start=" + st.getStartTime() + " dur=" + st.getDuration()
                    + (ok ? "" : "  ← dispatchGesture 返回 false"));
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "dispatchGesture 抛异常: " + t);
            return false;
        }
    }

    /**
     * 链上笔画的结果回调 —— <b>那个「新链刚建好就被杀」的 bug 就在这几行</b>。
     *
     * <p>旧写法是一个静态 callback，{@code onCancelled} 里只要 {@code chain != null} 就置 dead。
     * 可取消回调是异步来的、会延迟，常常正好落在下一次 {@code dragStart} 之后 ⇒
     * <b>新链刚建好就被上一条链的遗言当场杀掉</b>，整个滑动期间一条事件都注不进去。
     *
     * <p>两把锁：{@code gen != chainGen} ⇒ 是上一条链（甚至上几次滑动）的残留，与当前无关；
     * {@code seq != segSeq} ⇒ 是当前链里被<b>下一段接替</b>掉的旧段，正常现象。
     * 只有「当前这条链的最新那一段」被取消，才是真的断了。
     */
    private static AccessibilityService.GestureResultCallback cb(final int gen, final int seq) {
        return new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                Log.d(TAG, "手势完成 #" + gen + "." + seq);
            }

            @Override
            public void onCancelled(GestureDescription g) {
                if (gen != chainGen) {
                    Log.d(TAG, "旧链的取消回调（#" + gen + "." + seq + "）—— 忽略");
                    return;
                }
                if (seq != segSeq) {
                    Log.d(TAG, "旧段的取消回调（#" + gen + "." + seq + "）—— 已被新段接替，忽略");
                    return;
                }
                if (chain != null) {
                    dead = true;
                    chain = null;
                    chainDisplay = -1;
                    pathN = 0;
                    H.removeCallbacks(keepAlive);
                    Log.w(TAG, "当前手势链被取消（#" + gen + "." + seq
                            + "）—— 这次拖动改用输入路（用户碰了屏 / 手势超时 / 目标屏不可用）");
                } else {
                    Log.w(TAG, "手势被取消（#" + gen + "." + seq
                            + "，此时没有进行中的链）");
                }
            }
        };
    }

    private static GestureDescription build(int displayId, GestureDescription.StrokeDescription st) {
        GestureDescription.Builder b = new GestureDescription.Builder().addStroke(st);
        b.setDisplayId(displayId);
        return b.build();
    }
}
