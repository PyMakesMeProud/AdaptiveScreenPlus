package com.wb.extrotator;

/**
 * 「控制页正在用」的标记 —— 让 {@link RotationService} 在这些时候完全不碰显示设置。
 *
 * <p>控制页（副屏触控板 / 副屏键盘 / 投屏控制）是拿来<b>操作内屏</b>的，而用户此刻眼睛盯的是
 * 外接屏上那份镜像；此时任何旋转 / 分辨率下发都会当场把画面拧一下 —— 实测写下「锁手机方向」后
 * 内屏 cur 从 1080x1920 变 1920x1080，用户看到的就是「外接屏歪 90°」。而触发它的往往就是
 * 「打开控制页」：进程一起来，系统把常驻的服务拉回来，它顺手就把锁写下去。
 *
 * <p>两道口子缺一不可：{@link #on()} / {@link #off()} 在控制页 onStart / onStop 时置位，
 * 服务周期性的 evaluate 也看这个标记；{@link #arm(long)} 在封面屏桌面点伪应用图标时预置一个
 * 定时窗口 —— 服务被系统拉回来的时刻可能<b>早于</b>页面 onStart（实测早了 70ms）。
 *
 * <p>静态标记就够：这些控制页和 {@code RotationService} 都在同一个（主）进程里。用户在主界面
 * 或小组件点「锁手机方向 / 分辨率接管」是服务里的显式 action，不受这里影响。
 */
public final class ExtControlMode {

    /** 前台的控制页个数（几个页面可能同时存在，用计数而不是布尔） */
    private static volatile int pages;

    /** 定时窗口的截止时刻 —— 兜"点图标 → 服务启动"这段竞态，以及退出后的尾巴 */
    private static volatile long armedUntil;

    /**
     * 退出控制页之后的冷却时长。
     *
     * <p>取值依据：退场动画 + 光标悬浮窗卸载 + 系统抛 display 回调 + 服务端 150ms 防抖
     * + 可能的一轮 400ms 延迟评估 —— 实测全程在 1 秒内，给 3 秒是留足余量，
     * 又不至于长到挡住用户紧接着去主界面点「锁手机方向」（那条是显式 action，不看这里）。
     */
    private static final long EXIT_GRACE_MS = 3000L;

    private ExtControlMode() {
    }

    /** 控制页进前台 */
    public static void on() {
        pages++;
    }

    /** 控制页离开前台 */
    public static void off() {
        if (pages > 0) {
            pages--;
        }
        // ⚠ 退出时也要留一段冷却（理由与 arm 那条不同）：离开这页会让本应用的窗口从屏上消失
        // （触控页还带着一层光标悬浮窗），系统随即抛 display 变化回调把服务的 evaluate 叫醒。
        // 那一刻 pages 已归零、闸门全开，它当场做一次方向评估 / 重排 —— 用户看到的就是
        // 「进去没事、一退出画面又拧一下」。冷却窗口把这段尾巴一起盖住，窗口过后没有任何
        // 新事件，也就不会再重排。
        arm(EXIT_GRACE_MS);
    }

    /**
     * 预置一个定时窗口（毫秒）。窗口内视同"控制页正在用"。
     *
     * <p>只延长、不缩短 —— 连点两个图标时以更晚的那个为准。
     */
    public static void arm(long ms) {
        long t = System.currentTimeMillis() + Math.max(1000L, ms);
        if (t > armedUntil) {
            armedUntil = t;
        }
    }

    /** 控制页正在用（或刚点完图标还在窗口里） */
    public static boolean active() {
        return pages > 0 || System.currentTimeMillis() < armedUntil;
    }
}
