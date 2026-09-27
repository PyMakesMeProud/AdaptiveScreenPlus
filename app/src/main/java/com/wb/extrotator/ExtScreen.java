package com.wb.extrotator;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 外接屏（第二显示设备）的发现与操作 —— 副屏页的共用底层。
 *
 * <p><b>两个 id 不是一回事</b>（这层最容易踩的坑）：同一块屏在系统里有两个编号，用途完全不同 ——
 * 逻辑 id（displayId，0/1/2…）用来<b>发输入</b>（{@code input -d 2 tap 100 200}），
 * 物理 id（64 位大数）用来<b>抓画面</b>（{@code screencap -p -d 4630947181303254916}）。
 * ⚠ 两个不能互换：{@code screencap -d 1} 会被拒（「Display Id '1' is not valid」），反之也一样。
 * {@code dumpsys display} 里每个逻辑屏那一行同时带着两个号（{@code displayId N} 与
 * {@code uniqueId local:<物理id>}），{@link #physicalIdOf} 就是拍在那一行上的。
 *
 * <p><b>只操作内屏</b>：本机（Z Flip5）用户是拿外接显示器<b>镜像内屏</b>来当大屏用的，
 * 所以往内屏（{@link Display#DEFAULT_DISPLAY}）注入，在外接屏上就看得见效果 ——
 * 目标恒定，不存在「挑一块屏」这件事，界面上也就不给选屏的口子（少一个能选错的地方）。
 * 内置两块恒为 id 0 = 内屏、{@link #COVER_ID} = 1 = 封面屏。
 *
 * <p><b>所有方法都是阻塞的</b>：每一条都要走 Binder 去开一个 shell 进程，必须在后台线程调用。
 * 给界面用的异步入口是 {@link #exec(String, Consumer)}。
 */
public final class ExtScreen {

    private static final String TAG = "ExtScreen";

    /** 内屏（主屏）的 displayId —— 三件套恒定的操作目标 */
    public static final int INNER_ID = Display.DEFAULT_DISPLAY;

    /** 封面屏（折叠机外屏）的 displayId —— 本机恒定 */
    public static final int COVER_ID = 1;

    /**
     * 单帧画面的大小上限。
     * 1080p 的 PNG 通常两三百 KB，这里给到 24MB 是防"命令异常时无限读"，
     * 不是真指望它用到这么多。
     */
    private static final int FRAME_MAX_BYTES = 24 * 1024 * 1024;

    /**
     * 发指令用的单线程池。
     *
     * <p>为什么不一次性丢线程池：开 shell 进程本来就是串行便宜、并行互相抢，
     * 而且用户连按方向键时"先按的先到"很重要 —— 单线程天然保序。
     * ⚠ 抓画面的循环**不要**用这个池（一次 screencap 两三百毫秒，
     * 会把点击指令堵在后面），抓帧的线程在 {@link TouchpadActivity} 里自己起。
     */
    private static final ExecutorService IO =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "extrot-ext-io");
                t.setDaemon(true);
                return t;
            });

    private ExtScreen() {
    }

    // ------------------------------------------------------------------ 屏幕清单

    /** 一块屏的摘要 */
    public static final class Dev {
        /** 逻辑 id —— `input -d` 用它 */
        public final int id;
        /** 物理 id —— `screencap -d` 用它；-1 = 没查到（抓不了画面） */
        public final long physicalId;
        public final String name;
        /** 当前逻辑尺寸（注入坐标的空间就是它） */
        public final int w;
        public final int h;
        /** 是不是本机内置的那两块 */
        public final boolean builtin;
        /**
         * 这块屏此刻是不是亮着的（{@code STATE_ON}）。
         *
         * <p>v3.7 收尾加的，就为了能跟用户说实话：屏灭着的时候按键/触摸<b>本来就送不到</b>
         * （那块屏上没有焦点窗口，InputDispatcher 直接丢），可界面还在回显"已发"，
         * 用户只能得出"按键是坏的"。这个字段是进程中直接问 DisplayManager 拿的，不花 shell。
         */
        public final boolean on;

        Dev(int id, long physicalId, String name, int w, int h, boolean builtin, boolean on) {
            this.id = id;
            this.physicalId = physicalId;
            this.name = name;
            this.w = w;
            this.h = h;
            this.builtin = builtin;
            this.on = on;
        }

        /** 亮着没 —— 熄着的屏收不到注入事件 */
        public boolean awake() {
            return on;
        }

        public boolean isExternal() {
            return !builtin;
        }

        /**
         * 界面上的屏名 —— 顶栏直接读它。
         * ⚠ 别用系统给的 {@link #name}：本机两块内置屏都叫「内置屏幕」，分不出来。
         */
        public int labelRes() {
            if (!builtin) {
                return R.string.dev_external;
            }
            return id == COVER_ID ? R.string.dev_cover : R.string.dev_inner;
        }

        public String sizeText() {
            return w + "×" + h;
        }
    }

    /**
     * 列出此刻所有有效的屏（{@link #find} 从这里挑内屏；排查时也靠它看全貌）。
     *
     * <p>⚠ <b>不要用 {@code dm.getDisplays()} 代替</b>：本机（Z Flip5）上它只返回内屏一块，
     * 封面屏（id 1）被系统整个藏掉；外接屏在并发模式下也可能时隐时现。
     * {@link DisplayUtil#allDisplays(DisplayManager)} 按 id 逐个 {@code getDisplay()} 探测，
     * 这才是能看见全部屏的那条路。
     */
    public static List<Dev> all(Context ctx) {
        List<Dev> out = new ArrayList<>();
        DisplayManager dm = dm(ctx);
        if (dm == null) {
            return out;
        }
        Map<Integer, Long> phys = physicalIdOf(ctx);
        List<Display> ds;
        try {
            ds = DisplayUtil.allDisplays(dm);
        } catch (Throwable t) {
            Log.w(TAG, "allDisplays 失败: " + t);
            return out;
        }
        if (ds == null || ds.isEmpty()) {
            return out;
        }
        for (Display d : ds) {
            if (d == null || !d.isValid()) {
                continue;
            }
            int id = d.getDisplayId();
            int[] sz = sizeOf(d);
            int w = sz[0], h = sz[1];
            Long pid = phys.get(id);
            String name;
            try {
                name = String.valueOf(d.getName());
            } catch (Throwable t) {
                name = "?";
            }
            Log.i(TAG, "屏 " + id + " [" + name + "] realMetrics=" + w + "x" + h
                    + " realSize=" + rect(d, false)
                    + " mode=" + modeText(d)
                    + " metrics=" + rect(d, true));
            boolean lit;
            try {
                lit = d.getState() == Display.STATE_ON;
            } catch (Throwable t) {
                lit = true;   // 读不到就别吓人，按"亮着"处理
            }
            out.add(new Dev(id, pid == null ? -1L : pid, name, w, h, isBuiltin(id), lit));
        }
        return out;
    }

    /**
     * 量一块屏的面板原生尺寸。
     *
     * <p>⚠ 顺序不能反：<b>先 {@code getMode()}</b>，别先 {@code getRealMetrics()}。
     * 应用跑在封面屏上时去量内屏，{@code getRealSize()} / {@code getRealMetrics()} 双双返回
     * 1496×1440，而内屏的实际面板是 1080×2640 —— 这两个 API 走的是
     * {@code DisplayInfo.getAppMetrics(…, mDisplayAdjustments)}，会把<b>本应用的兼容缩放</b>算进去，
     * 量「别人那块屏」时就串了。{@code getMode()} 给的是面板原生分辨率，不吃兼容缩放，才可信。
     *
     * <p>它的短板是不认 {@code wm size} 覆盖，而覆盖恰恰会改变注入坐标空间 —— 所以触控板那边
     * 不靠这个值定坐标，一律取 {@link CursorOverlay#bounds()}（目标屏给应用用的逻辑尺寸）：
     * 悬浮窗的 {@code lp.x/lp.y}、{@code input -d N} 的落点、以及那块屏的画面，三者天然同一套。
     */
    private static int[] sizeOf(Display d) {
        try {
            Display.Mode m = d.getMode();
            if (m != null && m.getPhysicalWidth() > 0 && m.getPhysicalHeight() > 0) {
                return new int[]{m.getPhysicalWidth(), m.getPhysicalHeight()};
            }
        } catch (Throwable ignored) {
        }
        try {
            DisplayMetrics dm = new DisplayMetrics();
            d.getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Throwable ignored) {
        }
        return new int[]{0, 0};
    }

    /** 诊断用：`getRealSize()`（app=false）或 `getMetrics()`（app=true）的尺寸文本 */
    private static String rect(Display d, boolean app) {
        try {
            android.graphics.Point p = new android.graphics.Point();
            if (app) {
                DisplayMetrics m = new DisplayMetrics();
                d.getMetrics(m);
                return m.widthPixels + "x" + m.heightPixels;
            }
            d.getRealSize(p);
            return p.x + "x" + p.y;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 诊断用：`getMode()` 的物理尺寸 —— 不受 wm 覆盖 / 兼容缩放影响 */
    private static String modeText(Display d) {
        try {
            Display.Mode m = d.getMode();
            return m == null ? "?" : m.getPhysicalWidth() + "x" + m.getPhysicalHeight();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 本机内置屏（内屏 0 / 封面屏 1）；其余一律当外接 */
    private static boolean isBuiltin(int id) {
        return id == Display.DEFAULT_DISPLAY || id == COVER_ID;
    }

    // ------------------------------------------------------------------ 注入坐标空间

    /**
     * 目标屏此刻的<b>注入坐标空间</b>尺寸 —— 也就是 {@code input -d N tap x y} 的落点坐标系。
     *
     * <p>唯一可靠的来源是 WindowManager：{@code dumpsys window displays} 里那块屏的
     * {@code cur=WxH}（已经算上旋转与 {@code wm size} 覆盖），逐次与 {@code screencap} 抓回来的
     * 画面像素核对过。
     *
     * <p>⚠ <b>别改用 Display 的那几个 API</b>，三个都不是这个坐标系：
     * {@code getRealSize()} / {@code getRealMetrics()} / {@code getMetrics()} 会把<b>本应用的
     * 兼容缩放</b>算进去（实测内屏 1920×1080 被读成 1496×1242，光标被夹在 1495 以内 ⇒
     * 用户报的「光标到不了最右边」）；{@code getMode()} 给面板原生分辨率（1080×2640），
     * 不认 {@code wm size} 覆盖。
     *
     * @return {w, h}；量不到返回 null。<b>阻塞</b>，要在后台线程调
     */
    public static int[] injectionSize(Context ctx, int displayId) {
        if (!ShellRunner.isReady()) {
            return null;
        }
        return parseInjectionSize(ShellRunner.run("dumpsys window displays"), displayId);
    }

    /**
     * 从 {@code dumpsys window displays} 的输出里取某块屏的 {@code cur=WxH}。
     *
     * <p>纯函数，方便异步取回来之后再解析（见 {@link CursorOverlay#show}）。输出形状是
     * 「一块屏一段」，所以从 {@code mDisplayId=N} 往后取到下一块屏为止，从中找第一个 {@code cur=}。
     */
    public static int[] parseInjectionSize(String dump, int displayId) {
        if (dump == null || dump.startsWith("ERR")) {
            return null;
        }
        String key = "mDisplayId=" + displayId;
        int i = dump.indexOf(key);
        if (i < 0) {
            return null;
        }
        int j = dump.indexOf("mDisplayId=", i + key.length());
        String seg = j > i ? dump.substring(i, j) : dump.substring(i);
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("cur=(\\d+)x(\\d+)").matcher(seg);
        if (!m.find()) {
            return null;
        }
        try {
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            return (w > 0 && h > 0) ? new int[]{w, h} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取要操作的那块屏 —— <b>恒定是内屏</b>（{@link Display#DEFAULT_DISPLAY} = 0）。
     *
     * <p>为什么不给用户挑：见类注释 —— 外接显示器是内屏的镜像，往内屏注入就等于操作外接屏上
     * 看到的东西，永远只有这一个目标。既如此，「选哪块屏」这一层就不该存在
     * （多一个能选错的地方，就一定会有人选错）。
     *
     * @return 内屏；读不到屏信息时返回 null（多半是 Shizuku 没跑起来，界面据此提示）
     */
    public static Dev find(Context ctx) {
        List<Dev> list = all(ctx);
        for (Dev d : list) {
            if (d.id == Display.DEFAULT_DISPLAY) {
                return d;
            }
        }
        Log.w(TAG, "内屏不在屏清单里，共读到 " + list.size() + " 块");
        return null;
    }

    private static DisplayManager dm(Context ctx) {
        try {
            return (DisplayManager) ctx.getApplicationContext()
                    .getSystemService(Context.DISPLAY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 逻辑 id → 物理 id。
     *
     * <p>只认 `mBaseDisplayInfo=DisplayInfo{…}` 那一行：它同时带 `displayId N`
     * 和 `uniqueId "local:<物理id>"`。整段 dumpsys 一百多 KB，一次读完扫一遍，
     * 结果不缓存 —— 外接屏插拔会让两个号都变，缓存反而容易拿旧的用。
     */
    public static Map<Integer, Long> physicalIdOf(Context ctx) {
        Map<Integer, Long> map = new HashMap<>();
        if (!ShellRunner.isReady()) {
            return map;
        }
        String dump = ShellRunner.run("dumpsys display");
        if (dump == null || dump.startsWith("ERR")) {
            return map;
        }
        for (String line : dump.split("\n")) {
            if (!line.contains("mBaseDisplayInfo=")) {
                continue;
            }
            int id = intAfter(line, "displayId ");
            long pid = longAfter(line, "uniqueId \"local:");
            if (id >= 0 && pid > 0) {
                map.put(id, pid);
            }
        }
        Log.i(TAG, "逻辑→物理: " + map);
        return map;
    }

    private static int intAfter(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return -1;
        }
        int j = i + key.length();
        int k = j;
        while (k < s.length() && Character.isDigit(s.charAt(k))) {
            k++;
        }
        try {
            return k > j ? Integer.parseInt(s.substring(j, k)) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long longAfter(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return -1;
        }
        int j = i + key.length();
        int k = j;
        while (k < s.length() && Character.isDigit(s.charAt(k))) {
            k++;
        }
        try {
            return k > j ? Long.parseLong(s.substring(j, k)) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 发指令

    /**
     * 往某块屏发一条 shell 指令（不等待结果，串行保序）。
     *
     * <p>为什么要异步：开一个 shell 进程 + `input` 那个 JVM 起来，实测两三百毫秒。
     * 在主线程上是直接卡死的，所以一律丢给 {@link #IO}。
     */
    public static void exec(String cmd) {
        exec(cmd, null);
    }

    public static void exec(String cmd, Consumer<String> done) {
        exec(cmd, 0, done);
    }

    /**
     * 带读取上限的 {@link #exec}（0 = 不限时）。
     *
     * <p>上限由 {@link ShellRunner#run(String, int)} 在自己这端把住 ——
     * 到点就放弃等待、放行队列。见那边的注释，别再用 shell 端 {@code timeout} 兜底，
     * 实测那样只杀掉直接子进程、管道还被孤儿攥着，等于没超时。
     */
    public static void exec(String cmd, int timeoutSec, Consumer<String> done) {
        IO.execute(() -> {
            String out = ShellRunner.run(cmd, timeoutSec);
            if (done != null) {
                done.accept(out == null ? "" : out);
            }
        });
    }

    /**
     * 带超时的指令 —— <b>所有注入类命令都必须走这条</b>。
     *
     * <p>为什么必须加：{@code input} 用的是「等结果」模式，目标屏<b>没有焦点窗口</b>时
     * InputDispatcher 会一直等，实测一条命令卡 5~8 秒。而所有命令都排在 {@link #IO} 这一个线程上 ——
     * 一条卡住，后面全部堵死，用户看到的就是「按了没反应」「拖动又卡」。加了超时之后：
     * 最坏情况是这条被丢弃（本来就送不到），但<b>队列活得下去</b>。
     *
     * <p>⚠ 只包注入命令。{@code dumpsys} 一次要 1~3 秒（几百 KB 输出）、抓画面更久，别套这个超时。
     *
     * @param timeoutSec 超时秒数；注入类给 2 秒足够（正常一条 40~65ms）
     */
    public static void execBounded(String cmd, int timeoutSec) {
        execBounded(cmd, timeoutSec, null);
    }

    /** 带超时 + 带回调（调用方靠回调排下一条） */
    public static void execBounded(String cmd, int timeoutSec, Consumer<String> done) {
        // 超时不再交给 shell 的 timeout 了 —— 那条路实测无效（见 ShellRunner.run(cmd,n)）：
        // 被孤立的 input 继续攥着管道，这端照样要等满 20 秒。
        // 现在由读取端把上限把住，复合命令（"sleep 0.7; input ... UP"）自然一起被罩住。
        exec(cmd, timeoutSec, done);
    }

    /**
     * 点一下（目标屏的绝对坐标）。
     *
     * <p>优先走无障碍手势（{@link A11yInject}）—— 不起进程、不等结果、一发即走；
     * 用户没开无障碍服务时才退回 {@code input}。
     */
    public static void tap(int displayId, int x, int y) {
        if (A11yInject.tap(displayId, x, y)) {
            return;
        }
        execBounded("input -d " + displayId + " tap " + x + " " + y, 1);
    }

    /**
     * 划一下 / 拖一段。
     *
     * <p>⚠ 只用来做**线性**的拖拽和滚动：中间给的是直线，做不了曲线路径。
     * 长按拖动走 {@link #dragStart} 那条（手势续笔画）。
     */
    public static void swipe(int displayId, int x1, int y1, int x2, int y2, int ms) {
        if (A11yInject.swipe(displayId, x1, y1, x2, y2, ms)) {
            return;
        }
        // 上限按手势本身要走多久给：swipe 要真的划满 ms 毫秒才回结果，
        // 给 1 秒会把正常的长划误判成超时（虽然事件照样送达，但日志会难看）。
        execBounded("input -d " + displayId + " swipe " + x1 + " " + y1
                + " " + x2 + " " + y2 + " " + ms, ms / 1000 + 1);
    }

    /**
     * 原地长按。
     *
     * <p>`input swipe` 起终点重合时不会触发长按（它照样发 MOVE，一 MOVE 就过了 touch slop）；
     * 所以老路是自己拼一段 `input motionevent`：DOWN → 停 → UP。
     * v3.7 起优先用手势：一条时长为 ms 的零长度笔画就是"在这个点按住 ms 毫秒"。
     */
    public static void hold(int displayId, int x, int y, int ms) {
        if (A11yInject.longPress(displayId, x, y, ms)) {
            return;
        }
        // 停留时间下限 0.4s：太短了系统根本不当成长按，白触发一次普通点击。
        float sec = Math.max(0.4f, ms / 1000f);
        execBounded("input -d " + displayId + " motionevent DOWN " + x + " " + y
                + "; sleep " + sec + "; "
                + "input -d " + displayId + " motionevent UP " + x + " " + y, (int) Math.ceil(sec) + 2);
    }

    // ------------------------------------------------------------------ 跟手拖动（v3.7 手势版）

    /**
     * 拖动开始 —— 手势那条路接管了吗？
     *
     * <p>true = 已经用 {@code willContinue} 的笔画把目标屏按住了，之后每动一下调 {@link #dragTo}、
     * 松手调 {@link #dragEnd}，<b>不必再发 DOWN/MOVE/UP</b>，也不需要在松手时补时长
     * （系统的笔画本身就撑住了「按住」）。
     *
     * <p>false = 手势不可用，调用方请用 {@link #motionDown}/{@link #motionMove}/
     * {@link #motionUpAfter} 的老办法。
     */
    public static boolean dragStart(int displayId, int x, int y) {
        return A11yInject.dragStart(displayId, x, y);
    }

    /** 拖动中手指到了 (x,y) */
    public static boolean dragTo(int displayId, int x, int y) {
        return A11yInject.dragTo(displayId, x, y);
    }

    /** 松手收尾 */
    public static boolean dragEnd(int displayId, int x, int y) {
        return A11yInject.dragEnd(displayId, x, y);
    }

    /**
     * 把正在进行的手势拖动强行收掉（页面被关掉 / 出错时）。
     *
     * <p>内部会补一段收尾笔画把手指抬起来 —— 少了这一下，目标屏那边会一直维持"按下"，
     * 拖到一半的东西就卡在那儿了。
     */
    public static void dragAbort() {
        A11yInject.abort();
    }

    /**
     * 分步发一个触摸事件（DOWN / MOVE / UP）—— 长按拖动专用。
     *
     * <p>⚠ <b>不能拿 {@link #swipe} 代替</b>：{@code input swipe} 是「按下后立刻线性插值移动」，
     * 一移动就超过 touch slop，目标应用的长按判定当场被取消 ⇒ 拖不起来。
     *
     * <p>⚠⚠ <b>三条可以分三次独立 shell 调用发</b>，不必拼在同一条命令里。这一点是
     * 「长按跟手拖动」能不能成立的前提 —— 实测把 DOWN 与后续的 MOVE 拆到不同进程发，
     * 目标屏照样接得上（用下拉通知栏验过，也没有 {@code Dropping ... no touch gesture} 之类的丢弃日志）。
     * 若这条性质不成立，MOVE 就只能等松手才发得出去，用户就又回到「松手才动」。
     */
    public static void motion(int displayId, String action, int x, int y) {
        execBounded("input -d " + displayId + " motionevent " + action + " " + x + " " + y, 1);
    }

    public static void motionDown(int displayId, int x, int y) {
        motion(displayId, "DOWN", x, y);
    }

    public static void motionMove(int displayId, int x, int y) {
        motion(displayId, "MOVE", x, y);
    }

    public static void motionUp(int displayId, int x, int y) {
        motion(displayId, "UP", x, y);
    }

    /**
     * 隔一小会儿再抬 —— 按住时长不够时补的那一档。
     *
     * <p>⚠ 等待做进 shell 命令（{@code sleep N; input ... UP}），不用 {@code View.postDelayed}：
     * 后者挂在页面身上，页面一走（长按到一半点了「关闭」、或来了通知走 onStop）回调就被丢掉，
     * UP 永远发不出去 —— 目标屏那边会一直维持「按下」，拖到一半卡住不放。
     * 走 shell 就跟 Activity 生命周期脱钩了。
     */
    public static void motionUpAfter(int displayId, int x, int y, long delayMs) {
        if (delayMs <= 0) {
            motionUp(displayId, x, y);
            return;
        }
        int sec = (int) Math.ceil(delayMs / 1000f) + 2;
        execBounded("sleep " + (delayMs / 1000f)
                + "; input -d " + displayId + " motionevent UP " + x + " " + y, sec);
    }

    /** 发一个按键（用 KEYCODE_ 名字，日志里看得懂） */
    public static void key(int displayId, String keyCodeName) {
        execBounded("input -d " + displayId + " keyevent " + keyCodeName, 1);
    }

    // ------------------------------------------------------------------ 送文字

    /**
     * `input text` 这条路。
     *
     * <p>⚠ 它只能敲出**虚拟键盘上有的字**（ASCII）—— 中文送不进去，这是 `input` 命令
     * 本身的限制（内部拿 KeyCharacterMap 把字符转成 KeyEvent）。所以中文走
     * {@link #paste(int, String)}。
     *
     * <p>换行不能塞在字符串里（会被 shell 吃掉一半），拆成"逐行 text + 回车"。
     */
    public static void typeText(int displayId, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // 每行都是一条 `text` 加一条回车，拼成一条 shell 走 —— 一次进程搞定整段，
        // 逐条发的话每敲一行就要等两三百毫秒，打个网址得等半天。
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                sb.append("input -d ").append(displayId)
                        .append(" text '").append(lines[i].replace("'", "'\\''")).append("'; ");
            }
            if (i < lines.length - 1) {
                sb.append("input -d ").append(displayId).append(" keyevent KEYCODE_ENTER; ");
            }
        }
        String cmd = sb.toString().trim();
        if (!cmd.isEmpty()) {
            exec(cmd);
        }
    }

    /**
     * 剪贴板 + 粘贴键这条路 —— 中文、emoji、长文本都靠它。
     *
     * <p>剪贴板是**整机共用**的：我们在这块屏上写进去，外接屏那个应用按粘贴键时读到的是同一份。
     * ⚠ Android 10 起"读剪贴板"只放行当前有焦点的应用，所以前提是外接屏那边
     * <b>光标已经落在输入框里</b>；否则粘贴键白按。
     */
    public static void paste(int displayId, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        exec("input -d " + displayId + " keyevent KEYCODE_PASTE");
    }

    // ------------------------------------------------------------------ 抓画面

    /**
     * 抓一帧外接屏画面（PNG 字节）。
     *
     * @param physicalId {@link Dev#physicalId}，不是逻辑 id
     * @return PNG 字节；失败返回 null。**阻塞**
     */
    public static byte[] frame(long physicalId) {
        if (physicalId <= 0) {
            return null;
        }
        byte[] b = ShellRunner.capture("screencap -p -d " + physicalId, FRAME_MAX_BYTES);
        if (b == null || b.length < 1024) {
            return null;
        }
        return b;
    }
}
