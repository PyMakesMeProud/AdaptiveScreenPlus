package com.wb.extrotator;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * 桌面小组件被点之后真正干活的地方。
 *
 * <p>用「透明 Activity」而不是「广播接收器 + 服务」：小组件的 PendingIntent 是用户主动触发的，
 * 系统算作前台交互，不受「后台启动 Activity 被拦」的限制；要用 Shizuku 就得应用进程里有 binder，
 * Activity 天然满足；也能直接弹 Toast 给反馈（接收器在后台弹提示不稳妥）。
 *
 * <p>主题是 {@code Theme.Translucent.NoTitleBar}，界面上什么都看不到，干活期间只留一条 Toast，
 * 做完立刻 finish。
 */
public class WidgetActionActivity extends Activity {

    public static final String EXTRA_ACTION = "widget_action";
    /**
     * 直接给一个动作 key（{@link QuickActions} 那套），不必为它在这里再立一个常量。
     *
     * <p>「自定义操作」组件用的就是这条：四个格子上挂什么由用户自己挑，挑出来的就是
     * 一个 key，直接交给 {@link QuickActions#run} —— 组件那边不必知道有哪些动作。
     */
    public static final String EXTRA_KEY = "widget_action_key";
    /** 一键：并发点亮 + 把副屏桌面投到外屏 */
    public static final String ACT_DUAL = "dual";
    /** 折叠 ↔ 展开 */
    public static final String ACT_FOLD = "fold";
    /** 切换「自动方向同步」（外接屏跟随手机的横竖屏） */
    public static final String ACT_SYNC = "sync";
    /** 切换「锁手机方向」（等价于下拉栏关掉自动旋转并锁横向） */
    public static final String ACT_LOCK = "lock";
    /**
     * 一键投屏（打开投屏界面，投哪块屏由用户在界面上确认）。
     *
     * <p>⚠ 它是唯一**不需要 Shizuku** 的动作：投屏要的是用户在那个弹窗里点一次同意，
     * 跟 shell 权限无关。所以下面那道「没授权就别干」的闸门把它排除在外 ——
     * 否则 Shizuku 挂了的时候连投屏按钮都点不动，而它本来不靠 Shizuku。
     */
    public static final String ACT_CAST = "cast";

    /** 同一个动作在这个窗口内只认第一次（防系统重建/重复投递出来的"幽灵点击"） */
    private static final long DEDUP_MS = 1500L;
    private static String sLastAction = null;
    private static long sLastActionAt = 0L;

    /** @return true = 这一枪该我打；false = 是重复投递，别动 */
    private static synchronized boolean claim(String action) {
        long now = SystemClock.elapsedRealtime();
        if (action != null && action.equals(sLastAction) && now - sLastActionAt < DEDUP_MS) {
            return false;
        }
        sLastAction = action;
        sLastActionAt = now;
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(true);

        // ⚠ 一次点击只准执行一次：折叠/展开会把「当前活动的显示屏」整块换掉，系统会把这个
        // 透明 Activity 掀起来重造，于是 onCreate 带着同一个 Intent 又跑一遍。用户看到的就是
        // 「点一下、切了两次」—— 第一遍外屏→内屏，第二遍发现已经在内屏，就按反方向又切回外屏。
        // 两道闸门：① 重建（savedInstanceState != null）直接收摊；② 同一个动作 1.5 秒内只认第一次。
        if (savedInstanceState != null) {
            finish();
            return;
        }

        String action = null;
        try {
            action = getIntent().getStringExtra(EXTRA_ACTION);
        } catch (Throwable ignored) {
        }
        // 「自定义操作」组件那条路：带的是动作 key 本身（见 CustomCoverWidgetProvider）。
        String key = null;
        try {
            key = getIntent().getStringExtra(EXTRA_KEY);
        } catch (Throwable ignored) {
        }
        // 防重投递的"身份证"：走 key 的报 key，走固定动作的报 action
        if (!claim(key != null ? key : action)) {
            finish();
            return;
        }
        if (key != null && !key.isEmpty()) {
            // ⚠ 刻意**不**在这里判 Shizuku：{@link QuickActions#run} 自己按动作决定要不要
            //   shell（needsShell），一刀切会把「一键投屏」「打开应用」这些不碰 shell 的
            //   动作也一起拒掉。
            runKey(key);
            return;
        }

        if (!ACT_CAST.equals(action) && !ShellRunner.isReady()) {
            toast("请先打开「自适应屏幕」完成 Shizuku 授权");
            finish();
            return;
        }
        if (ACT_DUAL.equals(action)) {
            runDual();
        } else if (ACT_FOLD.equals(action)) {
            runFold();
        } else if (ACT_SYNC.equals(action)) {
            runToggleSync();
        } else if (ACT_LOCK.equals(action)) {
            runToggleLock();
        } else if (ACT_CAST.equals(action)) {
            runCast();
        } else {
            finish();
        }
    }

    /** 一键双屏：切并发 + 等外屏就绪 + 投副屏桌面（约 2 秒，放后台线程） */
    private void runDual() {
        toast("正在切到并发双屏…");
        final DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        new Thread(() -> {
            final boolean ok = DualScreen.oneKeyDual(getApplicationContext(), dm);
            runOnUiThread(() -> {
                toast(ok ? "已并发点亮，副屏桌面已投到外屏"
                        : "已切到并发，但外屏没就绪（请确认手机是展开的）");
                finish();
            });
        }, "extrot-widget-dual").start();
    }

    /** 折叠 ↔ 展开 */
    private void runFold() {
        new Thread(() -> {
            final int target = DualScreen.toggleFold(this);
            runOnUiThread(() -> {
                // 报的是**回读校验后的实际状态**，不是我们想切过去的那个
                toast(target < 0
                        ? "切换失败：没有 Shizuku 授权"
                        : "已切到「" + DualScreen.shortName(target) + "」");
                finish();
            });
        }, "extrot-widget-fold").start();
    }

    /**
     * 切换「自动方向同步」。
     *
     * <p>语义与主界面上那个开关完全一致（见 MainActivity.setSyncEnabled）：
     * 关的时候如果「锁手机方向」还开着，就只关同步、锁保持不动。
     */
    private void runToggleSync() {
        Prefs p = new Prefs(this);
        boolean on = !p.isEnabled();
        p.setEnabled(on);
        if (on) {
            RotationService.start(this);
        } else {
            if (!p.isLockPhoneRotation()) {
                RotationService.restorePhoneRotation(this);
            }
            if (!p.shouldKeepRunning()) {
                RotationService.stop(this);
            }
        }
        PanelWidgetProvider.updateAll(this);
        toast("自动方向同步：" + (on ? "开" : "关"));
        finish();
    }

    /**
     * 切换「锁手机方向」—— 就是替用户点一下下拉栏里的「自动旋转」并固定方向，
     * 不牵扯外接屏那边的任何判断（v2.13 起两者彻底不互相看）。
     */
    private void runToggleLock() {
        Prefs p = new Prefs(this);
        boolean on = !p.isLockPhoneRotation();
        p.setLockPhoneRotation(on);
        if (on) {
            // 服务起来时自己会把锁落下去（ACTION_START 里那条），这里再补一刀保证即时
            RotationService.start(this);
            RotationService.lockPhoneNow(this);
        } else {
            RotationService.restorePhoneRotation(this);
            if (!p.shouldKeepRunning()) {
                RotationService.stop(this);
            }
        }
        PanelWidgetProvider.updateAll(this);
        toast("锁手机方向：" + (on ? "开（横向锁定）" : "关"));
        finish();
    }

    /**
     * 一键投屏。直接借 {@link QuickActions} 里那条 —— 组件这个入口和「快捷操作」、
     * 侧键、MacroDroid 用的是同一份实现，不留第二条要维护的路。
     */
    private void runCast() {
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String err = QuickActions.run(app, QuickActions.KEY_CAST);
            runOnUiThread(() -> {
                if (err != null) {
                    toast(err);
                }
                finish();
            });
        }, "extrot-widget-cast").start();
    }

    /**
     * 执行一个由「自定义操作」组件挂上来的动作。
     *
     * <p>跟上面那几个固定动作的区别：这里不预判内容，整个交给 {@link QuickActions#run}。
     * 那一层是所有动作的唯一实现处（主界面「快捷操作」、侧键、老虎机、通知面板用的是同一份），
     * 所以组件挂什么、这里就跑什么，不留第二条要维护的路。
     */
    private void runKey(final String key) {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            final String err = QuickActions.run(app, key);
            runOnUiThread(() -> {
                if (err != null) {
                    toast(err);
                }
                finish();
            });
        }, "extrot-widget-key").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
