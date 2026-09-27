package com.wb.extrotator;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 「长按音量键」的实现。
 *
 * <p>三星系统里没有任何原生入口能把音量键绑到第三方动作上（侧键设置只管电源键），只能自己用
 * 无障碍服务拦按键。关键是配置文件里的 {@code flagRequestFilterKeyEvents} —— 只有申请了它，
 * 系统才会把 KEYCODE_VOLUME_UP / KEYCODE_VOLUME_DOWN 这类硬件键送进 {@link #onKeyEvent}。
 *
 * <p><b>按键怎么分红</b>：判据是按住不放的时长，但<b>按下的一瞬间就把事件拦下来</b>，一个字都
 * 不放给系统 ——
 * <ul>
 *   <li>按下 → 消费掉事件、起一个 700ms 的定时器；</li>
 *   <li>700ms 内松手（短按）→ 这一格音量<b>由我们自己补</b>（{@link #stepVolume}），体感与原生
 *       一致：有音量条、调一格；</li>
 *   <li>按住超过 700ms → 执行动作，音量<b>一点都不动</b>。</li>
 * </ul>
 *
 * <p>⚠ 为什么非要自己补音量：音量键的原生行为是「按下就调」，如果按下时放行、等 700ms 再判断
 * 是不是长按，那长按必然已经先调掉一格（用户反馈的「长按会先蹭掉一点音量」）。代价是「按住不放
 * 连续快调」没有了 —— 绑了动作的那一侧只能连按。
 *
 * <p><b>两个键各管各的</b>：各自的定时器、各自的「已触发」标志，一只手同时按住上下键也不会串。
 * 动作设成 {@link QuickActions#KEY_NONE} 就是「这个键不绑」，此时<b>一个事件都不消费</b>，
 * 长短按全交回系统原生那套音量（含长按连续调节）。
 */
public class VolumeKeyService extends AccessibilityService {

    private static final String TAG = "VolumeKeyA11y";

    /** 判定为「长按」的时长 */
    private static final long LONG_PRESS_MS = 700L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 本键这次长按是否已经触发过动作 —— 上下键各一份，别共用 */
    private boolean firedUp;
    private boolean firedDown;

    /** 服务实例，供界面查询"开着没有" */
    private static volatile VolumeKeyService sInstance;

    private final Runnable onLongPressUp = () -> {
        firedUp = true;
        run(KeyEvent.KEYCODE_VOLUME_UP, QuickPrefs.volumeUpAction(this));
    };

    private final Runnable onLongPressDown = () -> {
        firedDown = true;
        run(KeyEvent.KEYCODE_VOLUME_DOWN, QuickPrefs.volumeDownAction(this));
    };

    /** 动作落到后台线程去跑（里面有 binder / sleep，不能占着主线程） */
    private void run(int keyCode, String actionKey) {
        Log.i(TAG, (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? "长按音量上键" : "长按音量下键")
                + " → " + actionKey);
        if (QuickActions.KEY_NONE.equals(actionKey)) {
            // 兜底：正常路径上选「无」时压根进不来（onKeyEvent 已经放行给系统了）
            return;
        }
        vibrate();
        final ContextRef ref = new ContextRef(getApplicationContext());
        new Thread(() -> QuickActions.run(ref.ctx, actionKey), "volume-key-action").start();
    }

    public static boolean isRunning() {
        return sInstance != null;
    }

    /**
     * 让「外屏手势后台」那一层跟当前设置对齐。界面拨开关时调它。
     *
     * <p><b>必须由服务自己来装</b>：手势条用的窗口类型是 {@code TYPE_ACCESSIBILITY_OVERLAY}，
     * 只有无障碍服务的上下文加得上，拿 Activity 的上下文会被系统直接拒掉。
     *
     * <p>服务没在跑时不报错 —— 记一条日志即可，等它连上时 {@link #onServiceConnected()} 会再
     * 对齐一次。
     */
    public static void syncCoverRecents() {
        VolumeKeyService s = sInstance;
        if (s != null) {
            CoverRecentsGesture.sync(s);
        } else {
            Log.d(TAG, "无障碍服务没在跑，「外屏手势后台」等它连上再装");
        }
    }

    /**
     * 捕获带档位改了 ⇒ 立刻按新高度重装那一条。
     *
     * <p>跟 {@link #syncCoverRecents()} 的区别：sync 见到「已经装着同一个屏」就直接返回，
     * 改高度必须拆掉重装才生效。
     *
     * <p>跟 sync 一样，只认服务自己的上下文 —— 那层窗口是 {@code TYPE_ACCESSIBILITY_OVERLAY}，
     * Activity 的上下文加不上。
     */
    public static void refreshCoverRecents() {
        VolumeKeyService s = sInstance;
        if (s != null) {
            CoverRecentsGesture.refresh(s);
        } else {
            Log.d(TAG, "无障碍服务没在跑，捕获带高度等它连上再对");
        }
    }

    /** 侧边栏：开关 / 位置 / 内容变了 ⇒ 按新参数对齐那一层 */
    public static void syncSidebar() {
        VolumeKeyService s = sInstance;
        if (s != null) {
            CoverSidebar.sync(s);
        } else {
            Log.d(TAG, "无障碍服务没在跑，侧边栏等它连上再装");
        }
    }

    /** 侧边栏的位置 / 内容改了 ⇒ 拆了重装（位置只有重建窗口才生效） */
    public static void refreshSidebar() {
        VolumeKeyService s = sInstance;
        if (s != null) {
            CoverSidebar.refresh(s);
        } else {
            Log.d(TAG, "无障碍服务没在跑，侧边栏等它连上再对");
        }
    }

    /**
     * 内屏手势：开关变了 ⇒ 按新设置对齐那三条热区。
     *
     * <p>和上面几个同理，只能由服务自己来装（{@code TYPE_ACCESSIBILITY_OVERLAY}）。
     */
    public static void syncInnerGesture() {
        VolumeKeyService s = sInstance;
        if (s != null) {
            InnerGesture.sync(s);
        } else {
            Log.d(TAG, "无障碍服务没在跑，内屏手势等它连上再装");
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        /*
         * 这个服务除了拦音量键，还兼职「手势注入」——
         * 副屏触控板的点击与拖动都改走 dispatchGesture（见 A11yInject）。
         * 能力早在 v3.1 就一次性声明好了（XML 里的 canPerformGestures），
         * 所以这里只是把实例交给它，用户不需要再去「设置 → 无障碍」点一次。
         */
        A11yInject.attach(this);
        /*
         * 同一层还兼「代点弹窗」—— 无线调试那张确认框必须有人点掉，
         * 而它只在"内屏被点亮"的那几秒里可见（见 AdbWifi / AutoAuth）。
         */
        AutoAuth.attach(this);
        /*
         * 这一层还兼管「外屏手势后台」—— 开关开着就在封面屏底部补一条手势条。
         * 服务被重建（开关过无障碍、系统回收过）时这里会自动补回来，
         * 用户不需要再去拨一次那个开关。
         */
        CoverRecentsGesture.sync(this);
        CoverSidebar.sync(this);
        /*
         * 还兼管「外屏自动旋转」：那个功能要一路读加速度计，挂在常驻的服务进程里最稳。
         * 服务被重建（开关过无障碍、系统回收过）时这里会把传感器补挂回来，
         * 用户不需要再去拨一次那个开关。
         */
        CoverAutoRot.sync(this);
        /*
         * 这层还兼管「内屏手势」—— 开关开着就在内屏贴三条隐形热区
         * （左右边缘返回、底部上滑回桌面 / 开后台）。
         */
        InnerGesture.sync(this);
        /*
         * 把「过滤按键」这项能力打进日志。
         *
         * ⚠ 配置文件里只申请了 flagRequestFilterKeyEvents、却没声明 canRequestFilterKeyEvents
         * 能力时，系统会**静默忽略**整个申请 —— 表现是「开关全开了、长按音量键毫无反应、连一条
         * 日志都没有」，上次排查只能靠 dumpsys accessibility 里的 capabilities 数值。服务自己连上
         * 时就说清楚，省得下次又去翻系统 dump。
         */
        boolean canFilter = false;
        boolean canGesture = false;
        int caps = 0;
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                caps = info.getCapabilities();
                canFilter = (caps & android.accessibilityservice.AccessibilityServiceInfo
                        .CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0;
                canGesture = (caps & android.accessibilityservice.AccessibilityServiceInfo
                        .CAPABILITY_CAN_PERFORM_GESTURES) != 0;
            }
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "无障碍服务已连接；过滤按键能力=" + canFilter
                + " 执行手势能力=" + canGesture
                + " capabilities=" + caps
                + (canFilter ? "" : " ⚠ 配置文件缺 canRequestFilterKeyEvents，长按音量键不会生效")
                + (canGesture ? "" : " ⚠ 配置文件缺 canPerformGestures，副屏触摸注入会退回 input 慢路"));
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        // 设置里关掉这个功能时完全不拦，两个音量键一起回归系统
        if (!QuickPrefs.isVolumeKeyEnabled(this)) {
            return false;
        }

        final boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
        final String actionKey = up ? QuickPrefs.volumeUpAction(this)
                : QuickPrefs.volumeDownAction(this);
        // 这一侧选了「无」⇒ 一个事件都不消费，长短按全走系统原生（含长按连续调节）
        if (QuickActions.KEY_NONE.equals(actionKey)) {
            return false;
        }

        final Runnable task = up ? onLongPressUp : onLongPressDown;
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (event.getRepeatCount() == 0) {
                    // 这条日志专门用来排查「长按没反应」：
                    // 能打到 = 无障碍确实收到了音量键，问题只可能在长按判定上；
                    // 打不到 = 系统压根没把按键送进来（flagRequestFilterKeyEvents 没生效）。
                    Log.d(TAG, (up ? "音量上键" : "音量下键") + "按下，起 " + LONG_PRESS_MS + "ms 定时器");
                    setFired(up, false);
                    handler.removeCallbacks(task);
                    handler.postDelayed(task, LONG_PRESS_MS);
                }
                /*
                 * 一律消费 —— 包括按住不放时系统持续发来的重复事件。
                 * 放行的心跳一点都不能留：放行 = 系统立刻调一格音量，
                 * 长按就成了"先蹭掉一格音量再触发动作"；重复事件放行则会让音量一路涨上去。
                 */
                return true;

            case KeyEvent.ACTION_UP:
                handler.removeCallbacks(task);
                boolean wasFired = isFired(up);
                setFired(up, false);
                if (!wasFired) {
                    // 没够到长按 ⇒ 这是一次短按，把那一格音量补给用户（事件被我们拦下了）
                    stepVolume(up);
                }
                return true;

            default:
                // 已经决定接管这个键了，别的动作类型也一并吃掉
                return true;
        }
    }

    /**
     * 替系统补一次音量调节 —— 也就是短按的那一格。
     *
     * <p>用 {@code adjustSuggestedStreamVolume} 而不是写死 {@code STREAM_MUSIC}：传
     * {@code USE_DEFAULT_STREAM_TYPE} 是让系统自己挑「此刻该调哪个流」（通话音量 / 媒体音量 /
     * 铃声），跟按原生音量键时的判断完全一致。
     *
     * <p>带 {@code FLAG_SHOW_UI} 是为了把音量条一起画出来 —— 不然用户按了音量键，屏幕上一点
     * 反馈都没有，会以为坏了。
     */
    private void stepVolume(boolean up) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            am.adjustSuggestedStreamVolume(
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI);
            Log.d(TAG, "短按" + (up ? "音量上键" : "音量下键") + " → 补调一格音量");
        } catch (Throwable t) {
            Log.w(TAG, "补音量失败", t);
        }
    }

    private boolean isFired(boolean up) {
        return up ? firedUp : firedDown;
    }

    private void setFired(boolean up, boolean v) {
        if (up) {
            firedUp = v;
        } else {
            firedDown = v;
        }
    }

    /** (触发感) */
    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(40);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 音量键本身不需要看界面内容，但 canRetrieveWindowContent 必须为 true 才拿得到
        // filterKeyEvents —— 所以这个回调一直留着。
        // v4.21 起它兼职「代点弹窗」：无线调试那张确认框要靠它点掉，见 AutoAuth。
        AutoAuth.handle(event, this);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        // 服务断了就没有手势注入了（A11yInject 会顺手把半截拖动收掉）
        A11yInject.detach(this);
        AutoAuth.detach();
        // 手势条也得一起摘 —— 服务没了它就没有主人，留在屏上只会吃掉那一条的触摸
        CoverRecentsGesture.remove();
        CoverSidebar.remove();
        InnerGesture.remove();
        // 服务断开就没人读方向了，传感器一起摘掉（重新连上时 sync 会再挂）
        CoverAutoRot.stop();
        handler.removeCallbacks(onLongPressUp);
        handler.removeCallbacks(onLongPressDown);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        A11yInject.detach(this);
        AutoAuth.detach();
        CoverRecentsGesture.remove();
        CoverSidebar.remove();
        InnerGesture.remove();
        CoverAutoRot.stop();
        handler.removeCallbacks(onLongPressUp);
        handler.removeCallbacks(onLongPressDown);
        super.onDestroy();
    }

    /** 只是为了避免在 lambda 里捕获 Activity 上下文 */
    private static final class ContextRef {
        final Context ctx;

        ContextRef(Context c) {
            this.ctx = c;
        }
    }
}
