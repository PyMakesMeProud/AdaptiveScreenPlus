package com.wb.extrotator;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 「按键快捷指令」背后真正干活的那层。
 *
 * <p><b>为什么要单独抽一层</b>：同一批动作有<b>三个入口</b> —— 三星侧键（走
 * {@code ACTION_ASSIST}）、三星日常程序 / 别的自动化 App（走自定义 action 或广播）、
 * 以及 Beta 页里的「测试」按钮。三处都直连 shell 的话时序和判据迟早跑偏（折叠那条要等熄屏
 * 落地再唤醒，少等一秒就白干），所以统一收在这里。
 *
 * <p>动作白名单不是固定的四条：{@link CustomCmds} 里用户自建的指令也算一种动作
 * （key 形如 {@code custom:<id>}），所以 {@code label / isValidKey} 都要传入 Context 才能查名字。
 *
 * <p>⚠ 所有 run 方法都会走 binder / sleep，<b>必须在后台线程调用</b>。
 */
public final class QuickActions {

    private static final String TAG = "QuickActions";

    /** 外部调用时传动作的 extra 名（intent 与 broadcast 都用它） */
    public static final String EXTRA_ACTION = "action_key";
    /** 本应用对外的动作 action（静态快捷方式、MacroDroid、日常程序都用这个） */
    public static final String ACTION_RUN = "com.wb.extrotator.action.RUN_ACTION";

    /** 折叠 ↔ 展开 切换 */
    public static final String KEY_TOGGLE_FOLD = "toggle_fold";
    /** 切到折叠形态并回到外屏桌面首页 */
    public static final String KEY_COVER_HOME = "cover_home";
    /** 封面屏顺时针转 90° */
    public static final String KEY_COVER_ROTATE = "cover_rotate";
    /** 封面屏交还传感器（恢复自动旋转） */
    public static final String KEY_COVER_ROTATE_RESET = "cover_rotate_reset";
    /**
     * 打开无线调试（只开不关，跟主界面那个按钮一致）。
     */
    public static final String KEY_ADB_WIFI = "adb_wifi";

    /** 启动双屏模式（并发点亮 + 把副屏桌面投到外屏）—— 与桌面「双屏快捷面板」那条同路 */
    public static final String KEY_DUAL = "dual";
    /** 切换「自动方向同步」（外接屏跟随手机的横竖屏） */
    public static final String KEY_SYNC = "sync";
    /** 切换「锁手机方向」—— 替用户点一下下拉栏的自动旋转并固定横向 */
    public static final String KEY_LOCK = "lock";
    /** 一键投屏：打开投屏界面，投哪块屏由用户在界面上确认 */
    public static final String KEY_CAST = "cast";
    /** 打开应用本体 */
    public static final String KEY_OPEN_APP = "open_app";
    /**
     * 切换「外屏自动旋转」—— 封面屏跟着手机的横竖自己转（见 {@link CoverAutoRot}）。
     *
     * <p>跟 {@link #KEY_COVER_ROTATE} 不是一回事：那条是<b>手动转一格</b>，这条是个<b>开关</b>
     * —— 开之前的外屏角度会记下来，关的时候原样还回去。
     */
    public static final String KEY_COVER_AUTOROT = "cover_autorot";

    /** 打开副屏桌面（外屏启动器）—— 只起桌面，不做双屏投屏 */
    public static final String KEY_COVER_DESK = "cover_desk";

    /**
     * 「无」—— 不绑动作。
     *
     * <p>只给<b>音量键</b>用：选了它之后 {@link VolumeKeyService} 压根不会去拦那个键，
     * 一切交回系统 —— 长按就是系统原生的「音量连续快调」。有了这一档，用户不想用音量键时
     * 就不必把整个无障碍关掉（那会把另一个音量键也一起废掉）。
     *
     * <p>刻意<b>不放进 {@link #ALL}</b>：{@code ALL} 是「能对外调用的内置动作」，「无」不是一个
     * 动作，它只是界面上的一个选项（见 BetaActivity.pickAction）。
     */
    public static final String KEY_NONE = "none";

    /** 内置动作 key，按界面上希望出现的顺序 */
    public static final String[] ALL = {
            KEY_TOGGLE_FOLD, KEY_COVER_HOME, KEY_COVER_ROTATE, KEY_COVER_ROTATE_RESET,
            KEY_COVER_AUTOROT, KEY_COVER_DESK, KEY_ADB_WIFI, KEY_DUAL, KEY_SYNC, KEY_LOCK,
            KEY_CAST, KEY_OPEN_APP,
    };

    /**
     * 全部可选动作 = 内置四条 + 用户自定义指令（{@link CustomCmds}）。
     *
     * <p>界面上凡是「选动作」的地方都从这里取，内置的永远排在前面。
     * ⚠️ 不含 {@link #KEY_NONE} —— 那是"不绑动作"，只有音量键的选单要它。
     */
    public static String[] options(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String k : ALL) {
            out.add(k);
        }
        for (CustomCmds.Cmd c : CustomCmds.list(ctx)) {
            out.add(CustomCmds.KEY_PREFIX + c.id);
        }
        return out.toArray(new String[0]);
    }

    /** 自定义指令的 key 是不是还指着一条存在的指令 */
    public static boolean customAlive(Context ctx, String key) {
        return CustomCmds.find(ctx, key.substring(CustomCmds.KEY_PREFIX.length())) != null;
    }

    private QuickActions() {
    }

    /** 动作的中文名（日志、界面提示用）。自定义指令显示成「自定义：名字」 */
    public static String label(Context ctx, String key) {
        if (key == null) {
            return "未知";
        }
        if (KEY_NONE.equals(key)) {
            return "无（系统默认）";
        }
        if (key.startsWith(CustomCmds.KEY_PREFIX)) {
            CustomCmds.Cmd c = CustomCmds.find(ctx, key.substring(CustomCmds.KEY_PREFIX.length()));
            // 侧键上还挂着、但指令已经被删了 —— 说清楚，别让用户猜为什么没反应
            return c == null ? "自定义指令（已删除）" : ("自定义：" + c.name);
        }
        switch (key) {
            case KEY_TOGGLE_FOLD:
                return "切换折叠/展开";
            case KEY_COVER_HOME:
                return "回到外屏主界面";
            case KEY_COVER_ROTATE:
                return "外屏旋转 90°";
            case KEY_COVER_ROTATE_RESET:
                return "外屏取消固定角度";
            case KEY_COVER_AUTOROT:
                return "外屏自动旋转";
            case KEY_ADB_WIFI:
                return "打开无线调试";
            case KEY_DUAL:
                return "启动双屏模式";
            case KEY_COVER_DESK:
                return "打开副屏桌面";
            case KEY_SYNC:
                return "自动方向同步";
            case KEY_LOCK:
                return "锁手机方向";
            case KEY_CAST:
                return "一键投屏";
            case KEY_OPEN_APP:
                return "打开应用本体";
            default:
                return "未知(" + key + ")";
        }
    }

    public static boolean isValidKey(Context ctx, String key) {
        if (key == null) {
            return false;
        }
        if (KEY_NONE.equals(key)) {
            return true;
        }
        if (key.startsWith(CustomCmds.KEY_PREFIX)) {
            return customAlive(ctx, key);
        }
        for (String k : ALL) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行一个动作。<b>阻塞</b>（0.2 ~ 2.5 秒，取决于动作）。
     *
     * @return null = 成功；否则是给用户看的一句话
     */
    public static String run(Context ctx, String key) {
        if (key == null) {
            return "缺少动作参数";
        }
        // 「无」= 故意不做事。不报错、也不提示成功，静静返回。
        // （界面上真有走到这儿的路径：音量键选了「无」之后点「测试」。）
        if (KEY_NONE.equals(key)) {
            return null;
        }
        // ★ 无线调试这一条**不能**卡 Shizuku。
        //   它存在的理由恰恰是"Shizuku 起不来的时候把无线调试打开"（Shizuku 自己要靠
        //   无线调试启动），再拿 Shizuku 当闸门就正好把那圈死循环续上了。
        //   它现在也确实不需要 Shizuku：整条路走 app 自己的身份（见 AdbWifi / DualScreen.openLocal）。
        if (needsShell(key) && !ShellRunner.isReady()) {
            return "Shizuku 未就绪";
        }
        Log.i(TAG, "执行快捷动作: " + key);
        if (key.startsWith(CustomCmds.KEY_PREFIX)) {
            return runCustom(ctx, key);
        }
        switch (key) {
            case KEY_TOGGLE_FOLD:
                return DualScreen.toggleFold(ctx) < 0 ? "切换失败" : null;
            case KEY_COVER_HOME:
                return coverHome(ctx);
            case KEY_COVER_ROTATE:
                return CoverDisplay.rotate(ctx) < 0 ? "旋转失败（找不到封面屏或没授权）" : null;
            case KEY_COVER_ROTATE_RESET:
                return CoverDisplay.resetRotation(ctx) ? null : "恢复失败";
            case KEY_COVER_AUTOROT:
                return toggleCoverAutoRot(ctx);
            case KEY_COVER_DESK:
                return coverDesk(ctx);
            case KEY_ADB_WIFI:
                return AdbWifi.set(ctx, true);
            case KEY_DUAL:
                return DualScreen.oneKeyDual(ctx,
                        (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
                        ? null : "外屏没就绪（手机要展开着）";
            case KEY_SYNC:
                return toggleSync(ctx);
            case KEY_LOCK:
                return toggleLock(ctx);
            case KEY_CAST:
                return openCast(ctx);
            case KEY_OPEN_APP:
                return openApp(ctx);
            default:
                return "未知动作: " + key;
        }
    }

    /**
     * 这个动作是不是非 shell 不可。
     *
     * <p>抽出来是因为 run() 原来是一刀切「没 Shizuku 就全拒」—— 那把 KEY_ADB_WIFI 也拒了
     * （它存在的理由恰恰是「Shizuku 起不来时把无线调试打开」，见 run 里那段注释），
     * 现在又多了几条本来就不碰 shell 的，再一刀切会把它们一起废掉。
     */
    private static boolean needsShell(String key) {
        switch (key) {
            case KEY_ADB_WIFI:
            case KEY_OPEN_APP:
            case KEY_SYNC:
            case KEY_LOCK:
                return false;
            default:
                return true;
        }
    }

    /**
     * 切换「外屏自动旋转」。
     *
     * <p>点一下就是切一下，所以按完得报一句"现在是开还是关" —— 侧键、桌面小组件都是这个
     * 入口，不报的话用户没法知道刚才那一下到底切成了哪个状态（与
     * {@link #toggleSync} / {@link #toggleLock} 同一条理由）。
     */
    public static String toggleCoverAutoRot(Context ctx) {
        boolean want = !CoverAutoRot.on(ctx);
        String err = CoverAutoRot.set(ctx, want);
        if (err != null) {
            return err;
        }
        return want ? "外屏自动旋转：开" : "外屏自动旋转：关";
    }

    /**
     * 切换「自动方向同步」—— 跟主界面那个开关、桌面面板组件那颗按钮是同一套语义
     * （见 MainActivity.setSyncEnabled）：关的时候如果「锁手机方向」还开着，
     * 就只关同步、锁保持不动。
     *
     * <p>返回给用户看的一句话而不是 null：它是个开关，按完不弹任何东西的话，
     * 用户没法知道刚才那一下到底切成了开还是关。
     */
    public static String toggleSync(Context ctx) {
        Prefs p = new Prefs(ctx);
        boolean on = !p.isEnabled();
        p.setEnabled(on);
        if (on) {
            RotationService.start(ctx);
        } else {
            if (!p.isLockPhoneRotation()) {
                RotationService.restorePhoneRotation(ctx);
            }
            if (!p.shouldKeepRunning()) {
                RotationService.stop(ctx);
            }
        }
        PanelWidgetProvider.updateAll(ctx);
        return on ? "自动方向同步：开" : "自动方向同步：关";
    }

    /**
     * 切换「锁手机方向」—— 就是替用户点一下下拉栏里的「自动旋转」并固定方向，
     * 不牵扯外接屏那边的任何判断。
     */
    public static String toggleLock(Context ctx) {
        Prefs p = new Prefs(ctx);
        boolean on = !p.isLockPhoneRotation();
        p.setLockPhoneRotation(on);
        if (on) {
            // 服务起来时自己会把锁落下去，这里再补一刀保证即时
            RotationService.start(ctx);
            RotationService.lockPhoneNow(ctx);
        } else {
            RotationService.restorePhoneRotation(ctx);
            if (!p.shouldKeepRunning()) {
                RotationService.stop(ctx);
            }
        }
        PanelWidgetProvider.updateAll(ctx);
        return on ? "锁手机方向：开（横向锁定）" : "锁手机方向：关";
    }

    /**
     * 一键投屏：切并发 → 等外屏就绪 → 先铺副屏桌面 → 全屏打开投屏控制并自动开始投屏。
     *
     * <p>⚠ 这一条<b>不是</b>"打开投屏界面"那一件事。用户原话是三步：「先进入双屏模式，
     * 再打开副屏桌面，然后再以全屏状态打开投屏控制并开始投屏」—— 只 startActivity 的话
     * 连双屏都没开，投出去也没地方落。整套时序在 {@link DualScreen#oneKeyCast}，
     * 它与「一键双屏」共用同一条"等外屏就绪"的逻辑。
     *
     * <p>⚠ 铺副屏桌面这一步不能省、也不能颠倒：顺序反过来的话，退出投屏时底下没有东西
     * 接着，会直接落到系统锁屏页（v4.16 用户实测）。
     *
     * <p><b>阻塞</b>（约 2 秒），调用方在后台线程里。
     */
    private static String openCast(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            return DualScreen.oneKeyCast(ctx, dm) ? null : "外屏没就绪（手机要展开着）";
        } catch (Throwable t) {
            return "投屏没起来";
        }
    }

    /** 打开应用本体 */
    private static String openApp(Context ctx) {
        try {
            ctx.startActivity(new Intent(ctx, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return null;
        } catch (Throwable t) {
            return "打不开主界面";
        }
    }

    /**
     * 切到折叠形态，回到外屏桌面首页，并让内容避开左上角的刘海。
     *
     * <p>三步都不能省：① {@link CoverDisplay#avoidCutout} —— 切 {@code TENT} 并把封面屏钉在
     * 180°，这两条同时成立时系统才会在封面屏顶端给挖孔让出 66px（见 RULES §27）；
     * <b>这里不能改成 {@code CLOSED}</b>：实测 CLOSED 下不避让，而且还会让整机入睡 ——
     * 换成 TENT 之后连那一觉都省了。② 补一次唤醒（状态切换偶尔会让屏睡过去；TENT 一般不睡，
     * 所以不用等）。③ 往<b>封面屏</b>（不是默认屏）发一次 HOME，把还停在某个 App 上的画面收回
     * 桌面 —— 必须带 {@code -d <封面屏 id>}：{@code input} 不带 -d 是发给默认屏（内屏）的，
     * 而内屏此刻是灭的，发了等于没发。
     *
     * <p>避让是覆盖状态，会一直保持 —— 用户在主页上看不到刘海压着内容，
     * 退出按钮也不会再落进挖孔带里。
     */
    private static String coverHome(Context ctx) {
        int did = CoverDisplay.id(ctx);
        if (did <= 0) {
            return "找不到封面屏";
        }
        CoverDisplay.avoidCutout(ctx);
        // 与 toggleFold 同一套唤醒逻辑，这里复用，不再抄一份（不用等熄屏，TENT 不睡）
        DualScreen.keepScreenOn(ctx, false);
        ShellRunner.run("input -d " + did + " keyevent KEYCODE_HOME");
        Log.i(TAG, "已回到外屏主界面（含刘海避让）(display " + did + ")");
        return null;
    }

    /**
     * 打开「副屏桌面」（{@link SecondaryHomeActivity}）—— 跟主界面那颗「打开副屏桌面」按钮、
     * 「一键双屏 / 一键投屏」铺的是同一页：时钟 + 电量 + 应用网格，伪应用钉在最前面。
     *
     * <p>⛔ <b>别投 {@link CoverLauncherActivity}</b>（v476 修过一次）：那是「外屏启动器」
     * 的完整配置界面（搜索 / 勾选 / 拖拽 / 外观 / 背景图），外屏启动器小组件点开看到的就是它。
     * 两个都往封面屏投、名字又都带"外屏 / 副屏"，但用户说的「副屏桌面」始终是前者。
     *
     * <p>跟 {@link #KEY_DUAL}（启动双屏模式）的区别：这条<b>只起桌面</b>，
     * 不把内屏投到外屏上。用户点名要的：「打开副屏桌面（不开双屏）」。
     */
    private static String coverDesk(Context ctx) {
        int did = CoverDisplay.id(ctx);
        if (did <= 0) {
            return "找不到外屏";
        }
        // 与上面「回外屏主界面」同一套前置：先把屏唤醒、再让开刘海
        DualScreen.keepScreenOn(ctx, false);
        CoverDisplay.avoidCutout(ctx);
        if (SecondaryLauncher.launchOwn(ctx, did, SecondaryHomeActivity.class)) {
            return null;
        }
        return "副屏桌面没打开";
    }

    /**
     * 跑一条用户自定义指令。
     *
     * <p>成功与否**不看退出码** —— {@link ShellRunner#run} 只回文本、不回码，
     * 所以这里把那点输出截一行回显给用户，让他自己判断；完全没输出就当成功。
     *
     * <p>指令已被删掉时不要静默失败：侧键上可能还挂着它，得说清楚。
     */
    private static String runCustom(Context ctx, String key) {
        String id = key.substring(CustomCmds.KEY_PREFIX.length());
        CustomCmds.Cmd c = CustomCmds.find(ctx, id);
        if (c == null) {
            return "这条自定义指令已经删掉了";
        }
        String out = ShellRunner.run(c.cmd);
        String brief = out == null ? "" : out.replace('\n', ' ').trim();
        if (brief.length() > 60) {
            brief = brief.substring(0, 60) + "…";
        }
        return brief.isEmpty() ? null : ("输出：" + brief);
    }
}
