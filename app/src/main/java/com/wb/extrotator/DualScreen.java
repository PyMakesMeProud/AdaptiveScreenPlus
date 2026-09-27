package com.wb.extrotator;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;

/**
 * 折叠状态机（DeviceStateManager）与副屏桌面 的共用逻辑。
 *
 * <p>抽这一层的原因：这两件事有两个入口 —— 应用界面（MainActivity）与桌面小组件
 * （WidgetActionActivity）。同一套 shell 命令和等待时序写在两处迟早会跑偏（尤其是「切完并发要等
 * 系统把封面屏那块 Display 真正建起来」这段，少等 1 秒就会投空），所以统一收在这里。
 *
 * <p>这台 Z Flip5 实测支持的状态：
 * <pre>
 *   0 = CLOSED（合上 / 外屏）      1 = TENT（帐篷）
 *   2 = HALF_OPENED（Flex 半开）   3 = OPENED（展开 / 内屏）
 *   4 = CONCURRENT_INNER_DEFAULT（内外屏同时点亮）
 * </pre>
 * 命令是 {@code cmd device_state state <id>}，走 shell（Shizuku）即可，<b>不需要 root</b>；
 * {@code state reset} 交还给折叠传感器。注意覆盖是<b>持久</b>的（重启才清）。
 *
 * <p>本类里凡标注「阻塞」的方法都会真的 sleep / 走 binder，<b>必须在后台线程调用</b>。
 */
public final class DualScreen {

    private static final String TAG = "DualScreen";

    public static final int CLOSED = 0;
    public static final int TENT = 1;
    public static final int HALF_OPENED = 2;
    public static final int OPENED = 3;
    public static final int CONCURRENT = 4;

    private static final String[] NAMES = {
            "折叠 CLOSED", "帐篷 TENT", "半开 Flex HALF_OPENED",
            "展开 OPENED", "并发双屏 CONCURRENT_INNER_DEFAULT"};
    private static final String[] SHORT = {
            "折叠", "帐篷", "半开", "展开", "并发双屏"};

    /** 切到并发后，等系统把封面屏那块 Display 建起来的固定等待 */
    private static final long CONCURRENT_SETTLE_MS = 1800L;
    /** 第一次没读到封面屏时再补等一次 */
    private static final long COVER_RETRY_MS = 900L;
    /** 一键投屏时，等副屏桌面先铺上、再叠投屏控制的固定等待 */
    private static final long HOME_SETTLE_MS = 900L;
    /** 下发折叠状态后，等状态机把新状态落定再回读校验的等待 */
    private static final long VERIFY_MS = 350L;
    /**
     * 切到折叠后，等系统"合盖即睡"那一刀落地再叫醒的等待。
     * 实测下发 state 0 之后约 1 秒两块屏同时熄灭，早于此去看它还是亮的。
     */
    private static final long WAKE_SETTLE_MS = 1100L;
    /** 一次唤醒之后再看一眼的间隔 */
    private static final long WAKE_RETRY_MS = 500L;
    /** 唤醒最多补几刀 */
    private static final int WAKE_TRIES = 3;

    private DualScreen() {
    }

    public static String stateName(int id) {
        return id >= 0 && id < NAMES.length ? NAMES[id] : ("未知(" + id + ")");
    }

    public static String shortName(int id) {
        return id >= 0 && id < SHORT.length ? SHORT[id] : ("未知(" + id + ")");
    }

    // ------------------------------------------------------------------ 读状态

    /**
     * 某个 id 算不算"没展开"。
     *
     * <p>折叠机不是只有"合上 / 展开"两态：帐篷、Flex 半开同样只有外屏在显示。
     * 旧代码拿 {@code cur == CLOSED} 当唯一判据，于是"帐篷"被当成展开态 ——
     * 这是"点一次没反应、要点两次"的另一半原因。
     */
    private static boolean isCollapsed(int id) {
        return id == CLOSED || id == TENT || id == HALF_OPENED;
    }

    /**
     * 读<b>当前生效</b>的折叠状态（Committed 状态）。
     *
     * <p>即 {@code cmd device_state print-state}：有覆盖时是覆盖值，没有就是传感器的值。
     * 「当前系统按哪个状态在显示」这个问题它是准确的答案，所以既用来显示、也用来在屏幕全灭
     * （无法从亮屏情况判断）时兜底。
     *
     * @return 状态 id；没有授权 / 命令失败时返回 -1。<b>阻塞</b>
     */
    public static int readDeviceState() {
        try {
            return Integer.parseInt(ShellRunner.run("cmd device_state print-state").trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 读<b>物理</b>折叠状态（传感器的 Base 状态）。
     *
     * <p>与 {@link #readDeviceState} 分开的原因：{@code cmd device_state state}（不带参数）
     * 会把三个值都打出来 ——
     * <pre>
     *   Committed state: …identifier=1, name='TENT'    ← 覆盖后正在生效的
     *   Base state:      …identifier=0, name='CLOSED'  ← 传感器说的物理状态
     *   Override state:  …identifier=1, name='TENT'
     * </pre>
     * 界面把两个都显示出来，是为了让「盖子明明合着、状态却是展开」这种被别的软件改过的情况
     * 一眼可见。
     *
     * @return 状态 id；解析失败返回 -1。<b>阻塞</b>
     */
    public static int readPhysicalState() {
        try {
            String out = ShellRunner.run("cmd device_state state");
            for (String line : out.split("\n")) {
                if (line.contains("Base state")) {
                    int id = identifierOf(line);
                    if (id >= 0) {
                        return id;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到就算了，界面会显示 -1
        }
        return -1;
    }

    /** 从 {@code DeviceState{identifier=3, name='OPENED', …}} 里抠出 identifier */
    private static int identifierOf(String line) {
        int i = line.indexOf("identifier=");
        if (i < 0) {
            return -1;
        }
        int j = i + "identifier=".length();
        int k = j;
        while (k < line.length() && Character.isDigit(line.charAt(k))) {
            k++;
        }
        try {
            return k > j ? Integer.parseInt(line.substring(j, k)) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 某块屏此刻的点亮状态，取值见 {@link Display#getState()}。
     *
     * <p>先走一次 {@code getDisplays()} 是为了让 DisplayManager 的缓存刷新到最新。
     *
     * @return {@link Display#STATE_ON} / {@link Display#STATE_OFF} 等；读不到返回 -1
     */
    public static int readDisplayState(Context ctx, int displayId) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getApplicationContext()
                    .getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return -1;
            }
            dm.getDisplays();
            Display d = dm.getDisplay(displayId);
            return d == null ? -1 : d.getState();
        } catch (Throwable t) {
            Log.w(TAG, "读 display " + displayId + " 状态失败: " + t);
            return -1;
        }
    }

    /**
     * 现在是不是「外屏（折叠）形态」—— 也就是用户此刻看到的是哪块屏。
     *
     * <p>三级判据，从最贴近眼睛的往后退：
     * <ol>
     *   <li><b>内屏亮着</b> → 展开（并发同亮也算展开）；</li>
     *   <li>内屏没亮、<b>封面屏亮着</b> → 折叠；</li>
     *   <li>两块都不亮（息屏 / 读不到）→ 退回当前生效的折叠状态。</li>
     * </ol>
     *
     * <p>⚠ 第 3 条不能省：息屏时内屏的 {@code getState()} 也是 OFF，只按第 1 条判会把「息屏」
     * 误当「折叠」。
     *
     * <p>不干脆只看状态机的原因：状态机的值会被别的软件（或本应用自己的覆盖）改写，跟实际的
     * 亮屏情况对不上 —— 用户看到的永远是屏。
     */
    public static boolean isCoverMode(Context ctx) {
        int inner = readDisplayState(ctx, Display.DEFAULT_DISPLAY);
        if (inner == Display.STATE_ON) {
            return false;
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getApplicationContext()
                    .getSystemService(Context.DISPLAY_SERVICE);
            Display cover = dm == null ? null : DisplayUtil.coverDisplay(dm);
            if (cover != null && readDisplayState(ctx, cover.getDisplayId()) == Display.STATE_ON) {
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "读封面屏状态失败: " + t);
        }
        return isCollapsed(readDeviceState());
    }

    /**
     * 覆盖折叠状态机。<b>阻塞</b>
     *
     * <p>没有 Shizuku 时改走 {@link #cmdLocal}（以本应用自己的身份 fork 一个
     * {@code cmd device_state}）—— 这条路能省下授权，但只对带 {@code app_accessible}
     * 属性的状态有效（展开 / 半开 / 并发）。折叠与帐篷仍然要 Shizuku，
     * 用 {@link #appCanRequest} 先判一下再调。
     */
    public static void setState(int state) {
        if (ShellRunner.isReady()) {
            ShellRunner.run("cmd device_state state " + state);
        } else {
            cmdLocal("state", String.valueOf(state));
        }
    }

    /**
     * 这个状态以<b>本应用自己的身份</b>请求得到吗（也就是没有 Shizuku 时能不能切）。
     *
     * <p>AOSP 只放行带 {@code app_accessible} 属性的状态：展开 / 半开 / 并发。
     * 折叠与帐篷没这个属性 —— 想切到那两个仍然得先有 Shizuku。
     */
    public static boolean appCanRequest(int state) {
        return state == OPENED || state == HALF_OPENED || state == CONCURRENT;
    }

    /** 交还给折叠传感器，恢复随折叠自动切换。<b>阻塞</b> */
    public static void resetState() {
        ShellRunner.run("cmd device_state state reset");
    }

    // ------------------------------------------------- 不借 Shizuku 的那条路

    /**
     * 以<b>本应用自己的身份</b>直接跑 {@code cmd device_state …}。
     *
     * <p>不需要 Shizuku：AOSP 里 {@code DeviceStateManagerService.requestState} 的判据是
     * 「有 {@code CONTROL_DEVICE_STATE} 权限，<b>或者</b>调用方是顶层应用且请求的状态带
     * {@code app_accessible} 属性」—— {@link #OPENED} / {@link #HALF_OPENED} /
     * {@link #CONCURRENT} 带这个属性，{@link #CLOSED} / {@link #TENT} 不带。真机实测
     * （app 身份，甚至不在前台）{@code state 1 → 3} 成功、内屏 {@code -> ON}。
     *
     * <p>这条路是「无线调试死循环」的钥匙：合盖时那张确认框被系统投到<b>灭着的内屏</b>上，
     * 无障碍根本看不见它；先用这把杆把内屏点亮，框就成了可见窗口。
     *
     * @return 命令输出（{@code print-state} 这类读命令用得上）；跑不起来返回 null。<b>阻塞</b>
     */
    public static String cmdLocal(String... args) {
        Process p = null;
        try {
            String[] cmd = new String[args.length + 2];
            cmd[0] = "/system/bin/cmd";
            cmd[1] = "device_state";
            System.arraycopy(args, 0, cmd, 2, args.length);
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } finally {
                r.close();
            }
            String out = sb.toString();
            int rc = p.waitFor();
            Log.i(TAG, "cmd device_state " + java.util.Arrays.toString(args)
                    + " => rc=" + rc + " out=" + out.trim());
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "cmd device_state " + java.util.Arrays.toString(args) + " 失败: " + t);
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /** 不借 Shizuku：展开并把内屏点亮（{@code state 3}）。<b>阻塞</b> */
    public static boolean openLocal() {
        return cmdLocal("state", String.valueOf(OPENED)) != null;
    }

    /** 不借 Shizuku：折回折叠形态（{@code state 0}）。<b>阻塞</b> */
    public static boolean closeLocal() {
        return cmdLocal("state", String.valueOf(CLOSED)) != null;
    }

    /** 不借 Shizuku：读当前生效的折叠状态，失败返回 -1。<b>阻塞</b> */
    public static int readDeviceStateLocal() {
        String out = cmdLocal("print-state");
        try {
            return Integer.parseInt(out.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 封面屏（折叠机外屏）的 display id，找不到返回 -1 */
    public static int coverDisplayId(DisplayManager dm) {
        Display c = DisplayUtil.coverDisplay(dm);
        return c == null ? -1 : c.getDisplayId();
    }

    /**
     * 折叠 ↔ 展开。
     *
     * <p>判据是 {@link #isCoverMode}（哪块屏亮着），<b>不是</b>读回来的状态数字：跟用户的直觉
     * 一致，也不会被别的软件留下的覆盖值带偏。
     *
     * <p>下发之后还会回读校验一次：{@code cmd device_state state N} 偶尔会「收下命令但不落地」，
     * 所以等 {@link #VERIFY_MS} 再看屏态有没有真的切过去，没切就补发一刀。
     *
     * <p><b>没有 Shizuku 也能切一半</b>：由折叠切展开（{@code OPENED} 带 {@code app_accessible}）
     * 本应用自己就能请求；反过来由展开切折叠要 {@code CLOSED}，那个属性没有，够不着 ——
     * 如实返回 -1，不假装切了（见 {@link #appCanRequest}）。
     *
     * @return 切换后的状态 id（按「实际亮着哪块屏」推出来的）；够不着时返回 -1。
     *         <b>阻塞</b>（约 0.5 s）
     */
    public static int toggleFold(Context ctx) {
        boolean wasCover = isCoverMode(ctx);
        int target = wasCover ? OPENED : CLOSED;
        if (!ShellRunner.isReady() && !appCanRequest(target)) {
            Log.w(TAG, "toggleFold：没有 Shizuku，切不到 " + stateName(target) + "，放弃");
            return -1;
        }
        setState(target);

        sleep(VERIFY_MS);
        boolean nowCover = isCoverMode(ctx);
        if (nowCover == wasCover) {
            Log.w(TAG, "toggleFold 第一刀没落地：wasCover=" + wasCover
                    + " target=" + target + " → 补一刀");
            setState(target);
            sleep(VERIFY_MS);
            nowCover = isCoverMode(ctx);
        }
        int result = nowCover ? CLOSED : OPENED;
        Log.i(TAG, "toggleFold wasCover=" + wasCover + " target=" + target
                + "（实际 " + stateName(result) + "）");

        // ⚠ 切到折叠会被系统当成"合盖" —— 整机当场入睡（实测切换后约 1 秒
        // 两块屏同时熄灭，随后封面屏只剩 AOD）。用户看到的就是"切完就熄屏"，
        // 而且跟息屏超时无关（本机超时是"永不"）。所以状态落定后自己把屏叫回来。
        keepScreenOn(ctx, isCollapsed(target));
        return result;
    }

    /**
     * 一键双屏：切并发 → 等外屏就绪 → 把副屏桌面投上去。
     *
     * <p>必须等的原因：{@code cmd device_state state 4} 只是改了状态，系统把封面屏作为一块可投
     * 内容的 Display 真正建立起来还要一点时间；下手太早 {@code am start} 会打在一块还不存在的屏上，
     * 表现就是「点了没反应」。
     *
     * <p><b>阻塞</b>（约 2 秒），必须在后台线程调用。
     *
     * @return 副屏桌面是否成功投上去
     */
    public static boolean oneKeyDual(Context ctx, DisplayManager dm) {
        if (!ShellRunner.isReady()) {
            return false;
        }
        setState(CONCURRENT);
        sleep(CONCURRENT_SETTLE_MS);

        int cover = coverDisplayId(dm);
        if (cover < 0) {
            sleep(COVER_RETRY_MS);
            cover = coverDisplayId(dm);
        }
        if (cover < 0) {
            Log.w(TAG, "已切并发，但拿不到封面屏 id");
            return false;
        }
        boolean ok = SecondaryLauncher.launchOwn(ctx, cover, SecondaryHomeActivity.class);
        Log.i(TAG, "oneKeyDual cover=" + cover + " ok=" + ok);
        return ok;
    }

    /**
     * 一键投屏：切并发 → 等外屏就绪 → 把「投屏控制」投上去，并让它自己全屏、直接开始投屏。
     *
     * <p>时序与 {@link #oneKeyDual} 一模一样（同理：{@code cmd device_state state 4} 只是改了状态，
     * 封面屏那块 Display 真正建起来还要等一会儿，下手太早的表现就是"点了没反应"），
     * 差别只在于落到哪个页面、以及多带两个参数（{@code fullscreen} / {@code auto_cast}，
     * 见 {@link CastActivity#EXTRA_FULLSCREEN}）。
     *
     * <p>自动开始投屏之所以成立：系统那个授权弹窗在副屏上点不到，本来就得绕开 ——
     * {@link CastActivity} 会用 Shizuku 把本应用的 {@code PROJECT_MEDIA} 预先设成 allow，
     * 弹窗直接返回、不显示；没接通 Shizuku 时它会照实提示，让用户回内屏点。
     *
     * <p><b>阻塞</b>（约 2 秒），必须在后台线程调用。
     *
     * @return 投屏控制是否成功投上去
     */
    public static boolean oneKeyCast(Context ctx, DisplayManager dm) {
        if (!ShellRunner.isReady()) {
            return false;
        }
        setState(CONCURRENT);
        sleep(CONCURRENT_SETTLE_MS);

        int cover = coverDisplayId(dm);
        if (cover < 0) {
            sleep(COVER_RETRY_MS);
            cover = coverDisplayId(dm);
        }
        if (cover < 0) {
            Log.w(TAG, "已切并发，但拿不到封面屏 id");
            return false;
        }
        /*
         * v4.16：先把副屏桌面铺上，再把投屏控制叠上去。顺序反过来的话，退出投屏时底下
         * 没有东西接着，会直接落到系统锁屏页（用户实测）。
         */
        SecondaryLauncher.launchOwn(ctx, cover, SecondaryHomeActivity.class);
        sleep(HOME_SETTLE_MS);

        Bundle args = new Bundle();
        args.putBoolean(CastActivity.EXTRA_FULLSCREEN, true);
        args.putBoolean(CastActivity.EXTRA_AUTO_CAST, true);
        boolean ok = SecondaryLauncher.launchOwn(ctx, cover, CastActivity.class, args);
        Log.i(TAG, "oneKeyCast cover=" + cover + " ok=" + ok);
        return ok;
    }

    /**
     * 只把副屏桌面投上去，<b>不动折叠 / 并发状态</b>。
     *
     * <p>与 {@link #oneKeyDual} 唯一的差别就是没有那句 {@code setState(CONCURRENT)} ——
     * 用户要的是「副屏上那块桌面」，而不是把手机整个切进双屏模式：后者会连带改掉落点、
     * 影响内屏那边正在做的事。
     *
     * <p>不切状态也能成：并发模式下那块副屏就是封面屏，而封面屏一直存在（折叠机上 displayId=1
     * 常驻），投内容不需要先切状态。实测可行。
     *
     * <p><b>阻塞</b>（约 0.5 秒），必须在后台线程调用。
     *
     * @return 副屏桌面是否投上去了
     */
    public static boolean openSecondaryHome(Context ctx, DisplayManager dm) {
        if (!ShellRunner.isReady()) {
            return false;
        }
        int cover = coverDisplayId(dm);
        if (cover < 0) {
            sleep(COVER_RETRY_MS);
            cover = coverDisplayId(dm);
        }
        if (cover < 0) {
            Log.w(TAG, "openSecondaryHome：拿不到封面屏 id");
            return false;
        }
        boolean ok = SecondaryLauncher.launchOwn(ctx, cover, SecondaryHomeActivity.class);
        Log.i(TAG, "openSecondaryHome cover=" + cover + " ok=" + ok);
        return ok;
    }

    /**
     * 保证「该亮着的屏」是亮着的 —— 切换折叠状态之后必须调。
     *
     * <p>原因：把折叠状态覆盖成 {@link #CLOSED} 时系统按「合盖」处理，直接 {@code goToSleep} ——
     * 实测下发后约 1 秒两块屏同时熄灭、之后封面屏只剩 AOD、整机停在 {@code Dozing}
     * （息屏超时设成「永不」也一样，所以这不是超时）。只能切换完自己唤醒。
     *
     * <p>判据用 {@link PowerManager#isInteractive()}（整机醒着没有），而<b>不是</b>读某块屏的
     * state：折叠时内屏本来就是灭的，该亮的是封面屏，照着「屏」去问会问歪。
     *
     * @param waitForSleep true = 目标是折叠态，要先等那一刀熄屏落地再叫（早看的话它还没睡，
     *                     会白白漏过去）；展开态不会熄屏，直接看即可
     *                     <b>阻塞</b>（折叠态约 1~2.5 秒），必须在后台线程调用
     */
    public static void keepScreenOn(Context ctx, boolean waitForSleep) {
        PowerManager pm = (PowerManager) ctx.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        if (waitForSleep) {
            sleep(WAKE_SETTLE_MS);
        }
        for (int i = 0; i < WAKE_TRIES; i++) {
            if (pm == null || pm.isInteractive()) {
                return;
            }
            wakeUp();
            sleep(WAKE_RETRY_MS);
        }
        Log.w(TAG, "keepScreenOn 连唤 " + WAKE_TRIES + " 次仍未亮起来");
    }

    /** 发一次唤醒键（走 shell，需要 Shizuku）。<b>阻塞</b> */
    private static void wakeUp() {
        try {
            ShellRunner.run("input keyevent KEYCODE_WAKEUP");
            Log.i(TAG, "keepScreenOn 已发唤醒键");
        } catch (Throwable t) {
            Log.w(TAG, "唤醒键发送失败: " + t);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
