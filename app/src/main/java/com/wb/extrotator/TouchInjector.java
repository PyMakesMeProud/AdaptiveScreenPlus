package com.wb.extrotator;

import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

/**
 * 把触摸注进目标屏的那条通道 —— <b>一条独立线程 + 一个「最新位置」格子</b>。
 *
 * <p>它解决的两件事（都是实测钉下来的）：
 * <ol>
 *   <li><b>注入不能跑在 UI 线程上</b>：{@code input} 一条命令是同步阻塞的，实测 47ms，放在触摸
 *       回调里会把目标页的 UI 线程一行行拖住 —— 画面回调被挤、按钮半天不响应；</li>
 *   <li><b>通道太窄还排队</b>：无障碍手势链（{@link A11yInject} 那条 willContinue 续笔画）在系统
 *       那边是一条一条串着放的，派发比它放得快就积压 —— 拖得越久越迟。</li>
 * </ol>
 * 现在的做法：调用方（UI 线程）只往格子里写最新坐标，几微秒就返回；注入线程拿最新坐标去发，
 * <b>发不完的中间位置直接丢掉</b>。画面追的是手指<b>现在</b>在哪，不是它去过哪 —— 于是延迟不随
 * 拖动时长增长。
 *
 * <p><b>通道优先级</b>：
 * <pre>
 *   ① Shizuku 直注（{@link TouchInject}）—— 一次 Binder 调用，不起进程，几百次/秒
 *   ② input 命令（{@link ExtScreen#motionDown} 那条）—— 一条 47ms，会补中间点凑平滑
 * </pre>
 * 直注探不到（没装 Shizuku / 反射失败）时自动落回 ②，界面右下角那行写的就是此刻走的哪条。
 *
 * <p><b>为什么要和投屏页共用一份</b>：两边面对的是同一件事 —— 手上这块屏的触摸 → 另一块屏的
 * 触摸；差别只有「坐标怎么来」（画面里直接取点 vs 面板上累积位移）与「要不要补按住时长」
 * （触控板的长按要比目标应用的长按阈值多按一会儿），这两点都由构造参数给。各写一份的话
 * 改一处会忘一处。
 *
 * <p>⚠ <b>一次性动作（tap / hold / swipe）也在这儿排队执行</b>：它们不是流式的，得整条走完，
 * 所以单独一个格子存「下一个要做的动作」，新动作<b>覆盖</b>旧的（连点两下只需要最后那一下落地），
 * 并且只在手指没按住时才跑。
 */
public final class TouchInjector {

    /** 界面拿它显示"现在走哪条通道、走得怎么样" */
    public interface Status {
        /**
         * @param direct true = Shizuku 直注；false = 退回 input 命令
         * @param detail 一句话：每秒实发几次、丢掉了几个落后位置
         */
        void onPath(boolean direct, String detail);
    }

    private static final int WANT_NONE = 0;
    private static final int WANT_PRESS = 1;
    private static final int WANT_RELEASE = 2;
    /** 作废：只把按下的手指抬起来，别的什么都不做（跟"抬手"是两件事，见 abort） */
    private static final int WANT_ABORT = 3;

    private static final int CMD_NONE = 0;
    private static final int CMD_TAP = 1;
    private static final int CMD_HOLD = 2;
    private static final int CMD_SWIPE = 3;

    /**
     * 走 input 那条慢路时，一轮最多补几个中间点。
     *
     * <p>{@code input} 一条 47ms：不补点就是 21 个位置/秒，画面明显一跳一跳；
     * 一轮并发补 3 个点 ≈ 40 个位置/秒，够看了。直注通道不要这个 —— 它本身就够快，
     * 补点只会把最新的位置往后拖。
     */
    private static final int TWEEN_MAX = 3;

    /** 点一下时，按下与抬起之间至少隔这么久（太短系统可能不当一次点击） */
    private static final long TAP_MS = 60L;

    /** 一条 swipe 最多切几段（段太密也只是白花时间，注入本身的精度够） */
    private static final int SWIPE_STEPS = 12;

    private final String tag;
    /** 目标屏 id（注入到哪块屏） */
    private final int displayId;
    /**
     * 松手前至少按这么久。
     *
     * <p>触控板的长按用它：那边是"本地判出长按（500ms）之后才把 DOWN 发下去"，
     * 目标应用收到 DOWN 时才开始数它自己的长按计时 —— 所以必须替目标应用把
     * 这段时间补上，否则用户按 0.6 秒的本意会被当成一次普通点击。
     * 投屏页是"按下就发 DOWN"，不需要补，给 0。
     */
    private final long holdPadMs;

    private volatile Status status;

    /* ---------------- 格子（UI 线程写、注入线程读） ---------------- */
    private final Object lock = new Object();
    /** 位置版本号：手指每动一下 +1 */
    private int seq;
    /** 最新位置 */
    private int x, y;
    /** 待办：1 = 按下，2 = 抬手，0 = 只是位置变了 */
    private int want;
    /** 待执行的一次性动作 */
    private int cmd = CMD_NONE;
    private int cx0, cy0, cx1, cy1, cms, cmin;
    /** 注入线程此刻正按着（只给"等不等一次性动作"用） */
    private boolean pressed;
    private boolean die;

    private Thread thread;
    /** 这一按的起始时刻 —— MOVE / UP 必须跟 DOWN 用同一个，不然系统不认这是一次手势 */
    private long downAt;
    /** 此刻实际在走的通道 */
    private volatile boolean direct;
    /** 每秒实发几个事件 / 丢了几个落后位置（证明"通道到底多快"的硬数据） */
    private int sent, dropped;
    private long tickAt;

    public TouchInjector(String tag, int displayId, long holdPadMs) {
        this.tag = tag;
        this.displayId = displayId;
        this.holdPadMs = Math.max(0L, holdPadMs);
    }

    public void setStatus(Status s) {
        this.status = s;
    }

    /** 此刻走的哪条通道（true = 直注） */
    public boolean direct() {
        return direct;
    }

    /** 不能用直注的话，卡在哪一步 —— 界面上要把这句说实话 */
    public String why() {
        return TouchInject.why();
    }

    // ------------------------------------------------------------------ 起停

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::loop, tag);
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        synchronized (lock) {
            die = true;
            lock.notifyAll();
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(600L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ 流式：按下 / 移动 / 抬手

    /** 手指按下了（UI 线程，只写格子） */
    public void press(int px, int py) {
        synchronized (lock) {
            x = px;
            y = py;
            seq++;
            want = WANT_PRESS;
            lock.notifyAll();
        }
    }

    /** 手指到了新位置（UI 线程，只写格子 —— 正在发的那个发不完就算了，绝不排队） */
    public void move(int px, int py) {
        synchronized (lock) {
            x = px;
            y = py;
            seq++;
            lock.notifyAll();
        }
    }

    /** 手指抬起（新位置和抬手一起交出去，让画面落在真正松开的那一点上） */
    public void release(int px, int py) {
        synchronized (lock) {
            x = px;
            y = py;
            seq++;
            want = WANT_RELEASE;
            lock.notifyAll();
        }
    }

    /**
     * 这次触摸作废（被系统抢走 / 页面要走）—— 只把按着的手指抬起来，不补位置。
     *
     * <p>⚠ 它跟 {@link #release} 必须分开：release 有"补一条短按"的语义
     * （手指点太快时按下和抬起会被压成一轮），作废时补短按就是往目标屏上
     * 多点一下 —— 页面被划走、来了通知，都会走到这儿，绝不能顺手点人家一下。
     */
    public void abort() {
        synchronized (lock) {
            want = WANT_ABORT;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ 一次性动作

    /**
     * 点一下（自己拼 DOWN + 短停 + UP）。
     *
     * <p>⚠ 为什么要短停：DOWN 和 UP 之间不留时间的话，目标应用那边可能判不出这是一次点击
     * （只看到按下又立刻抬起，某些控件直接忽略）。
     */
    public void tap(int px, int py) {
        oneShot(CMD_TAP, px, py, px, py, (int) TAP_MS, 0);
    }

    /** 原地长按 ms 毫秒 */
    public void hold(int px, int py, int ms) {
        oneShot(CMD_HOLD, px, py, px, py, Math.max(200, ms), 0);
    }

    /** 从 (x0,y0) 划到 (x1,y1)，用时 ms —— 滚动 / 翻页用它 */
    public void swipe(int x0, int y0, int x1, int y1, int ms) {
        oneShot(CMD_SWIPE, x0, y0, x1, y1, Math.max(60, ms), 0);
    }

    private void oneShot(int what, int a0, int b0, int a1, int b1, int ms, int min) {
        synchronized (lock) {
            // 后一个动作覆盖前一个：连点两下只需要最后那一下真的落地
            cmd = what;
            cx0 = a0;
            cy0 = b0;
            cx1 = a1;
            cy1 = b1;
            cms = ms;
            cmin = min;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ 注入线程

    /**
     * 注入线程本体：永远是"拿最新位置 → 发 → 再看一眼最新位置"。
     *
     * <p>它只认 {@link #x} / {@link #y} 那个格子。手指在它发这一条的几十毫秒里
     * 又动了三下？那三个位置<b>直接不要了</b>。
     */
    private void loop() {
        int lastDoneSeq = 0;
        int lx = 0, ly = 0;
        while (true) {
            int s, px, py, w, c, a0, b0, a1, b1, ms, min;
            boolean busy;
            synchronized (lock) {
                /*
                 * 等什么：位置更新了（seq 变了）、有新待办（want）、或有一次性动作要跑。
                 * ⚠ 最后那条带个 "|| pressed"：手指还按着时不能去跑一次性动作
                 * （那会把按住的拖动打断），所以那种情况下接着等，
                 * 等这一轮抬手之后再跑 —— 也正因为有这个条件，循环不会空转。
                 */
                while (!die && seq == lastDoneSeq && want == WANT_NONE
                        && (cmd == CMD_NONE || pressed)) {
                    try {
                        lock.wait(500L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (die) {
                    break;
                }
                s = seq;
                px = x;
                py = y;
                w = want;
                want = WANT_NONE;
                c = cmd;
                a0 = cx0;
                b0 = cy0;
                a1 = cx1;
                b1 = cy1;
                ms = cms;
                min = cmin;
                busy = pressed;
                /*
                 * 一次性动作只有在"这一轮真的会去执行它"时才从格子里取走：
                 * 正要按下、或者手指还按着时都留着，下一轮再跑 ——
                 * 免得在最忙的那一刻把动作吞掉。
                 */
                if (c != CMD_NONE && w != WANT_PRESS && !busy) {
                    cmd = CMD_NONE;
                }
            }

            if (w == WANT_PRESS) {
                down(px, py);
                synchronized (lock) {
                    pressed = true;
                }
                lx = px;
                ly = py;
                lastDoneSeq = s;
                continue;
            }

            if (!busy) {
                if (w == WANT_RELEASE) {
                    /*
                     * 按下和抬起被压在同一轮里了 —— 手指点得很快，UI 线程连着写完
                     * "要按下"又写"要抬手"，注入线程醒来时只看得到抬手。
                     * 直接丢掉的话用户看到的就是"点了没反应"，所以补一条短按。
                     */
                    quickTap(px, py);
                }
                if (c != CMD_NONE) {
                    runCmd(c, a0, b0, a1, b1, ms, min);
                }
                lastDoneSeq = s;
                continue;
            }

            if (s != lastDoneSeq) {
                if (s - lastDoneSeq > 1) {
                    // 手指在"发上一条"的这点时间里又动了 N 下 —— N 个位置直接不要了。
                    // 这就是"永不排队"的实锤：丢的是落后位置，不是把队列拉长。
                    dropped += s - lastDoneSeq - 1;
                }
                move(lx, ly, px, py);
                lx = px;
                ly = py;
                lastDoneSeq = s;
            }

            if (w == WANT_RELEASE || w == WANT_ABORT) {
                if (w == WANT_RELEASE) {
                    long held = SystemClock.uptimeMillis() - downAt;
                    if (held < holdPadMs) {
                        // 替目标应用把"按住时长"补齐（见 holdPadMs 那段）
                        SystemClock.sleep(holdPadMs - held);
                    }
                    Log.d(tag, "抬手已注入 · 从按下算起 "
                            + (SystemClock.uptimeMillis() - downAt) + "ms");
                }
                up(px, py);
                synchronized (lock) {
                    pressed = false;
                }
            }
        }
        // 收摊之前把手指抬起来 —— 不然后面一直维持"按下"，被拖的东西卡在半路
        if (pressed) {
            synchronized (lock) {
                pressed = false;
            }
            up(x, y);
        }
    }

    /** 点得特别快：DOWN 短停 UP 一次做完 */
    private void quickTap(int px, int py) {
        long t0 = SystemClock.uptimeMillis();
        if (TouchInject.send(displayId, MotionEvent.ACTION_DOWN, px, py, t0)) {
            direct = true;
            SystemClock.sleep(TAP_MS);
            TouchInject.send(displayId, MotionEvent.ACTION_UP, px, py, t0);
            return;
        }
        direct = false;
        ExtScreen.tap(displayId, px, py);
    }

    /** 一次性动作的执行（在当前线程上整条走完 —— 这几十毫秒里位置格子照样在更新） */
    private void runCmd(int what, int a0, int b0, int a1, int b1, int ms, int min) {
        if (what == CMD_TAP) {
            long t0 = SystemClock.uptimeMillis();
            if (TouchInject.send(displayId, MotionEvent.ACTION_DOWN, a0, b0, t0)) {
                direct = true;
                SystemClock.sleep(Math.max(TAP_MS, ms));
                TouchInject.send(displayId, MotionEvent.ACTION_UP, a1, b1, t0);
                return;
            }
            direct = false;
            ExtScreen.tap(displayId, a0, b0);
            return;
        }
        if (what == CMD_HOLD) {
            long t0 = SystemClock.uptimeMillis();
            if (TouchInject.send(displayId, MotionEvent.ACTION_DOWN, a0, b0, t0)) {
                direct = true;
                SystemClock.sleep(ms);
                TouchInject.send(displayId, MotionEvent.ACTION_UP, a1, b1, t0);
                return;
            }
            direct = false;
            ExtScreen.hold(displayId, a0, b0, ms);
            return;
        }
        // CMD_SWIPE：自己按帧插值走一条直线 —— 直注通道一秒能发几百条，
        // 没必要像 input 那样只切两三段（切得密，目标屏那边看起来才是"滑"而不是"跳"）。
        long t0 = SystemClock.uptimeMillis();
        if (!TouchInject.send(displayId, MotionEvent.ACTION_DOWN, a0, b0, t0)) {
            direct = false;
            ExtScreen.swipe(displayId, a0, b0, a1, b1, ms);
            return;
        }
        direct = true;
        int steps = Math.max(2, Math.min(SWIPE_STEPS, ms / 12));
        long stepMs = Math.max(1L, ms / steps);
        for (int i = 1; i <= steps; i++) {
            SystemClock.sleep(stepMs);
            float f = (float) i / steps;
            TouchInject.send(displayId, MotionEvent.ACTION_MOVE,
                    Math.round(a0 + (a1 - a0) * f), Math.round(b0 + (b1 - b0) * f), t0);
        }
        TouchInject.send(displayId, MotionEvent.ACTION_UP, a1, b1, t0);
    }

    // ------------------------------------------------------------------ 底层发送

    private void down(int px, int py) {
        downAt = SystemClock.uptimeMillis();
        rateTick();
        if (TouchInject.send(displayId, MotionEvent.ACTION_DOWN, px, py, downAt)) {
            direct = true;
            return;
        }
        direct = false;
        ExtScreen.motionDown(displayId, px, py);
    }

    /**
     * 从上次发出去的位置走到这次的最新位置。
     *
     * <p>两条通道两种打法：直注够快，只发最新那一个点（补点等于把旧位置又画一遍，
     * 反而把最新的位置往后拖）；{@code input} 一条 47ms 发不过来，就把这段路补
     * 1~2 个中间点<b>并发</b>发出去 —— 用这点延迟余量换平滑（见 {@link #TWEEN_MAX}）。
     */
    private void move(int x0, int y0, int x1, int y1) {
        rateTick();
        if (TouchInject.send(displayId, MotionEvent.ACTION_MOVE, x1, y1, downAt)) {
            direct = true;
            return;
        }
        direct = false;
        int k = tweenSteps(x0, y0, x1, y1);
        if (k <= 1) {
            ExtScreen.motionMove(displayId, x1, y1);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= k; i++) {
            if (i > 1) {
                sb.append(" & ");
            }
            sb.append("input -d ").append(displayId).append(" motionevent MOVE ")
                    .append(x0 + Math.round((x1 - x0) * (float) i / k)).append(' ')
                    .append(y0 + Math.round((y1 - y0) * (float) i / k));
        }
        ShellRunner.run(sb.append(" & wait").toString(), 3);
    }

    private void up(int px, int py) {
        rateTick();
        if (TouchInject.send(displayId, MotionEvent.ACTION_UP, px, py, downAt)) {
            return;
        }
        ExtScreen.motionUp(displayId, px, py);
    }

    /** 这段路要不要补点（太近了补了也看不出来，白花 47ms） */
    private static int tweenSteps(int x0, int y0, int x1, int y1) {
        double d = Math.hypot(x1 - x0, y1 - y0);
        if (d < 16.0) {
            return 1;
        }
        return d < 56.0 ? 2 : TWEEN_MAX;
    }

    /**
     * 每秒报一次实发事件数 —— 这是唯一能证明"通道到底多快"的硬数据。
     *
     * <p>老版本靠 {@code input} 一条 47ms，撑死 21 次/秒；直注通了就是几百次/秒。
     * 丢掉的那些是"发这条的工夫里手指又动了几下"——丢掉是对的，画面要的是最新位置。
     */
    private void rateTick() {
        sent++;
        long now = SystemClock.uptimeMillis();
        if (tickAt == 0L) {
            tickAt = now;
            return;
        }
        if (now - tickAt < 1000L) {
            return;
        }
        final boolean d = direct;
        final int n = sent, drop = dropped;
        sent = 0;
        dropped = 0;
        tickAt = now;
        if (n > 1) {
            Log.i(tag, "注入 " + n + " 次/秒 · " + (d ? "直注" : "输入")
                    + " · 丢掉落后位置 " + drop + " 个 · " + TouchInject.why());
        }
        Status st = status;
        if (st != null) {
            st.onPath(d, n + "/秒 · 丢 " + drop);
        }
    }
}
