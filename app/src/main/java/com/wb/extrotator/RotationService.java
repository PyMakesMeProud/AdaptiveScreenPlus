package com.wb.extrotator;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.Surface;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 常驻前台服务，管两件事：
 *
 * <p><b>1) 外接屏方向同步</b>：让外接屏的角度跟着手机走，两边方向一致才不会出现黑边。
 * 核心命令两条 —— {@code wm fixed-to-user-rotation -d <ID> enabled}（让外接屏忽略应用自己申请的方向，
 * 每块屏一次）、{@code wm user-rotation -d <ID> lock <0|1|2|3>}。
 *
 * <p><b>2) 手机内屏分辨率接管</b>（替代 SecondScreen）：{@code wm size <W>x<H>} / {@code wm size reset}
 * （⚠ {@code size} 不能带 {@code -d}，见 {@link ScreenSizeUtil}）。插屏时弹窗询问、拔屏自动还原；
 * 接入时读面板原生分辨率推算内屏该用多少，一律转成竖屏方向（面板 1920×1080 → 内屏 1080×1920），
 * 免得黑边或拉伸。
 *
 * <p>硬约束（都是为了不把画面搞错位）：
 * <ul>
 *   <li>绝不碰机身自带屏：折叠机外屏会被误判成外接屏，转它会把整机显示几何搅乱。</li>
 *   <li>shell 全走单线程队列，绝不阻塞主线程 —— 阻塞会让 DisplayListener 回调集中爆发成「命令风暴」。</li>
 *   <li>只在手机方向稳定 {@link #STABLE_MS} 后才动作；每次下发后有 {@link #SETTLE_MS} 静默期，
 *       期间读到旧角度也绝不下发第二条（事务没提交完再补一刀，正是画面偏移的直接诱因）。</li>
 *   <li>显示事件只认手机内屏（display 0）：外接屏的变化基本都是自己命令的回声，拿它触发会自激循环。</li>
 *   <li><b>「角度对」不等于「几何对」</b>：插屏、折叠 / 展开时外接屏的 rotation 往往没变，但系统
 *       重算了几何 —— 只看 rotation 会把半成品几何留在屏上，这就是「只显示 1/4 屏」的成因。
 *       现在两头都盯：拓扑签名（内屏开关状态 + 内屏面板尺寸 + 外接屏集合）一变就做一次完整的
 *       free → lock 重排，且每次评估都校验外接屏实际尺寸是否等于「它这个角度应有的尺寸」。</li>
 *   <li>「锁手机方向」锁出来的角度才是外接屏该跟的方向（手机锁成横向，外接屏就横着铺满）。
 *       不能反过来读重力矢量：那样手一竖，外接屏转成竖屏，跟手机里横着的画面正好拧着。</li>
 *   <li>用户在系统里重新打开「自动旋转」时，「锁手机方向」必须同步关掉 —— 否则应用以为锁着、
 *       系统却在自动转，两边不一致又会把画面搞乱。</li>
 * </ul>
 */
public class RotationService extends Service {

    private static final String TAG = "RotationService";

    public static final String ACTION_START = "com.wb.extrotator.START";
    public static final String ACTION_STOP = "com.wb.extrotator.STOP";
    public static final String ACTION_MANUAL = "com.wb.extrotator.MANUAL";
    /** 强制对外接屏做一次「先 free 再 lock」的完整重排，用于修复错位画面 */
    public static final String ACTION_REPAIR = "com.wb.extrotator.REPAIR";
    /** 立即给内屏套上预设分辨率 */
    public static final String ACTION_APPLY_SIZE = "com.wb.extrotator.APPLY_SIZE";
    /** 立即把内屏分辨率还原成原生 */
    public static final String ACTION_RESET_SIZE = "com.wb.extrotator.RESET_SIZE";
    /** 用户点了「启用」/「不用」（来自弹窗或通知按钮） */
    public static final String ACTION_PROMPT_YES = "com.wb.extrotator.PROMPT_YES";
    public static final String ACTION_PROMPT_NO = "com.wb.extrotator.PROMPT_NO";
    /** 界面上的「测试弹窗」：不管有没有真的插屏，直接走一遍询问流程 */
    public static final String ACTION_TEST_PROMPT = "com.wb.extrotator.TEST_PROMPT";
    /**
     * 只把手机自身的「自动旋转」还回去。
     * <p>关掉方向同步时必须发这一条：服务可能因为「内屏分辨率接管」还开着而继续常驻，
     * 那就永远走不到 {@link #ACTION_STOP}，手机被自动旋转关掉、方向钉死在横屏的状态
     * 会一直留着 —— 用户看到的就是"手机一直是横屏，怎么摆都不转"。
     */
    public static final String ACTION_UNLOCK_PHONE = "com.wb.extrotator.UNLOCK_PHONE";
    /**
     * 只把手机自身的「自动旋转」关掉并锁定方向。
     *
     * <p>与 {@link #ACTION_UNLOCK_PHONE} 配对。加上它是因为「锁手机方向」从一个
     * 附属选项变成了独立开关：它不再要求「方向同步」也开着，用户完全可以只要
     * 后者中的任意一个（常见诉求：不想做外接屏同步，但也不想手机老是自己转）。
     */
    public static final String ACTION_LOCK_PHONE = "com.wb.extrotator.LOCK_PHONE";

    private static final String CH_ID = "extrot";
    private static final String CH_PROMPT = "extrot_prompt";
    private static final int NOTI_ID = 1001;
    private static final int NOTI_PROMPT = 1002;

    /** 供界面显示的最后一条执行记录 */
    public static volatile String LAST_LOG = "";
    /** 服务是否正在运行（供界面判断要不要重新拉起服务） */
    public static volatile boolean RUNNING = false;

    /** 兜底轮询间隔。主路径已由 DisplayListener 驱动，这里只防事件丢失 */
    private static final long POLL_MS = 1500L;
    /**
     * 手机方向需稳定这么久才动作。
     * 系统本身已经对重力感应做过去抖（getRotation() 报出来就是稳定的方向），
     * 这里只是再拦一道"短时间来回翻"，不需要很长。
     */
    private static final long STABLE_MS = 400L;
    /** 下发命令后的最长静默期（确认真已到位可提前解除，见 MIN_SETTLE_MS） */
    private static final long SETTLE_MS = 2500L;
    /** 静默期最短等待：即使已确认上一刀落地，也至少隔这么久才允许下一刀 */
    private static final long MIN_SETTLE_MS = 800L;
    private static final long REARM_MS = 15000L;  // 长时间没生效后允许再慢速试一轮
    private static final int MAX_RETRY = 2;       // 同一个目标角度最多补发几次
    /** 显示事件去重窗口：旋转动画期间可能连报几次，合并成一次评估 */
    private static final long EVAL_DEBOUNCE_MS = 150L;

    /**
     * 检测到「拓扑变化」（插上便携屏 / 折叠展开）后，等这么久再去做重排。
     * 这个延迟是给系统把新的显示几何提交完用的：太早下发会被正在进行的
     * 重配置吞掉，反而留下半成品几何。
     */
    private static final long TOPO_SETTLE_MS = 900L;
    /** 两次强制重排之间至少隔这么久，避免把屏幕刷成闪烁 */
    private static final long REASSERT_MIN_GAP_MS = 2500L;
    /**
     * 拓扑变化后，等到"最近一次下发命令"过去这么久了，才认为系统把新几何提交完了。
     * 比 {@link #SETTLE_MS} 短：这里只需要确认事务落地，残余的几何问题交给几何校验兜底。
     */
    private static final long TOPO_WAIT_MS = 1300L;
    /** 同一轮里几何校验失败最多重排几次（超过说明校验本身不准，停手并留日志） */
    private static final int MAX_GEOM_REPAIR = 3;
    /** 几何校验连败「停手」之后，隔这么久允许重新开一轮 */
    private static final long GEOM_BACKOFF_MS = 60000L;
    /** 我们刚写完手机方向锁定后，多久内不把读到的差异当成"用户改回去了" */
    private static final long LOCK_DIVERGE_GRACE_MS = 3000L;
    /** 检查「手机方向锁定是否被系统改动」的间隔 */
    private static final long LOCK_CHECK_MS = 2500L;

    /** 默认屏刚被点亮之后，允许多长时间以内用系统旋转策略给方向读数兜底 */
    private static final long POLICY_GRACE_MS = 4000L;

    /**
     * 这两个状态必须是 static —— 界面改个设置就会重新 startService，
     * 实例状态会被重置，放成实例字段会导致"每次改设置都重新弹一次询问"。
     */
    private static boolean sExtPresent = false;
    private static boolean sPrompted = false;

    private DisplayManager dm;
    private Handler handler;
    private Prefs prefs;
    private ExecutorService exec;

    /** 手机方向稳定检测 */
    private int lastOrientKey = -1;
    private long orientSince = 0L;

    /**
     * 最近一次从「真正亮着的那块内置屏」读到的横竖状态。
     * <p>为什么要缓存：屏幕全灭 / 锁屏时读不到有效方向，这时宁可沿用上一次的判断，
     * 也绝不能拿一块灭屏停在旧值的 rotation 去猜 —— 猜出来的错方向会直接把外接屏转飞。
     */
    private boolean lastPhonePortrait = false;
    private boolean lastPhonePortraitValid = false;
    /** 默认屏上一次是不是亮着（用来捕捉 OFF→ON 这个瞬间） */
    private boolean lastDefOn = false;
    /** 默认屏最近一次被点亮的时刻 */
    private long defOnAt = 0L;
    private int lastLoggedKey = -1;
    /** "屏上读数 vs 系统策略不一致"最近一次采用的结论（-1 = 当前没不一致），用来给日志去重 */
    private int lastMismatchKey = -1;

    /** 上一次收到「手机内屏发生变化」事件的时刻，用于把旋转动画期间的连续事件去重 */
    private long lastDefaultChangeAt = 0L;

    /** 第一次 evaluate 的标志：首次只记录状态，不当作"刚插入"来处理 */
    private boolean firstEvalDone = false;

    /** 上一次看到的「显示拓扑签名」，用来发现插屏 / 折叠展开这类变化 */
    private String lastTopology = "";
    /** 有待执行的一次完整重排（拓扑变化时置位） */
    private boolean pendingRepair = false;
    /** 最近一次发现拓扑变化的时刻 */
    private long topoChangedAt = 0L;
    /** 最近一次真正下发旋转命令的时刻（含维修重排），用来判断几何是否已稳定 */
    private long lastLockIssuedAt = 0L;
    /** 最近一次强制重排的时刻 */
    private long lastReassertAt = 0L;
    /** 本轮几何校验失败后已经重排的次数 */
    private int geomRepairCount = 0;
    /** 几何校验被证明"和这块屏对不上"时置位，避免误报把屏幕刷成闪烁 */
    private boolean geomCheckUnreliable = false;

    /** 我们最近一次写「手机方向锁定」的时刻 */
    private long lockWriteAt = 0L;
    /** 连续几次读到「系统自动旋转被重新打开」 */
    private int lockDivergeStrikes = 0;
    private long lastLockCheckAt = 0L;

    /** 下一次评估要带 force 跑（由 scheduleEval(delay, true) 置位） */
    private boolean pendingForce = false;

    /** 每块外接屏的写入状态 */
    private final SparseArray<ExtState> states = new SparseArray<>();
    /** fixed-to-user-rotation 已经下发过的屏（这个设置是持久的，不必每次重发） */
    private final Set<Integer> fixToUserDone = new HashSet<>();

    private String lastNotiText = "";

    private static class ExtState {
        int target = -1;
        long issuedAt = 0L;
        int retries = 0;
    }

    /**
     * 「评估一次」这个动作本身。
     * <p>刻意与 {@link #pollRunnable} 分开：轮询是一条自续的链，而方向变化、显示事件
     * 需要的是"立刻/延时跑一次"。共用同一个 Runnable 会在 Handler 里挂出两条链，
     * 互相 removeCallbacks 不掉，评估次数会莫名翻倍。
     */
    private final Runnable evalRunnable = new Runnable() {
        @Override
        public void run() {
            boolean force = pendingForce;
            pendingForce = false;
            try {
                evaluate(force);
            } catch (Throwable t) {
                Log.w(TAG, "evaluate failed", t);
            }
        }
    };

    /** 兜底轮询：主路径靠显示事件，这里只在事件丢失时补一刀 */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            evalRunnable.run();
            handler.postDelayed(this, POLL_MS);
        }
    };

    /**
     * 安排"再评估一次"：先撤掉上一次还没跑的预约，避免堆积。
     *
     * @param delayMs 0 = 立刻
     */
    private void scheduleEval(long delayMs) {
        scheduleEval(delayMs, false);
    }

    /**
     * @param force true 时这次评估会忽略静默期 / 稳定期，立刻按现实重排
     */
    private void scheduleEval(long delayMs, boolean force) {
        if (handler == null) {
            return;
        }
        if (force) {
            pendingForce = true;   // 即使下面 remove 掉了预约，force 语义也保留
        }
        handler.removeCallbacks(evalRunnable);
        if (delayMs <= 0L) {
            handler.post(evalRunnable);
        } else {
            handler.postDelayed(evalRunnable, delayMs);
        }
    }

    /**
     * 显示事件。
     * <p><b>只认手机内屏（display 0）</b>：它变了＝用户把手机转了方向，必须立刻响应 ——
     * 这是整个应用响应速度的关键。<br>
     * 外接屏的变化基本都是我们自己刚下发命令的回声，忽略掉，免得形成自激循环
     * （评估本身已经有静默期和"已就位"两道保险，即使漏进来也不会刷命令）。
     */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            evaluate(false);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            states.remove(displayId);
            fixToUserDone.remove(displayId);
            evaluate(false);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastDefaultChangeAt < EVAL_DEBOUNCE_MS) {
                return;   // 旋转动画期间会连报几次，合并掉
            }
            lastDefaultChangeAt = now;
            Log.d(TAG, "default display changed → re-evaluate");
            scheduleEval(0L);
        }
    };

    // ---------------------------------------------------------------- 静态入口

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, RotationService.class).setAction(ACTION_START);
            c.startForegroundService(i);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isRunning() {
        return RUNNING;
    }

    public static void stop(Context c) {
        try {
            Intent i = new Intent(c, RotationService.class).setAction(ACTION_STOP);
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void manual(Context c, int rotation) {
        try {
            Intent i = new Intent(c, RotationService.class)
                    .setAction(ACTION_MANUAL)
                    .putExtra("rotation", rotation);
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void repair(Context c) {
        try {
            Intent i = new Intent(c, RotationService.class).setAction(ACTION_REPAIR);
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    /** 立即把预设分辨率套到内屏上 */
    public static void applySizeNow(Context c) {
        try {
            c.startService(new Intent(c, RotationService.class).setAction(ACTION_APPLY_SIZE));
        } catch (Throwable ignored) {
        }
    }

    /** 立即把内屏分辨率还原成原生 */
    public static void resetSizeNow(Context c) {
        try {
            c.startService(new Intent(c, RotationService.class).setAction(ACTION_RESET_SIZE));
        } catch (Throwable ignored) {
        }
    }

    /** 测试用：立刻走一遍「插入便携屏」的询问流程 */
    public static void testPrompt(Context c) {
        try {
            c.startService(new Intent(c, RotationService.class).setAction(ACTION_TEST_PROMPT));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把手机自己的「自动旋转」还回去（只动这一项，不碰外接屏）。
     * 关掉方向同步、或用户主动关掉「锁手机方向」时调用，
     * 见 {@link #ACTION_UNLOCK_PHONE} 与 {@link #ACTION_LOCK_PHONE}。
     */
    public static void restorePhoneRotation(Context c) {
        try {
            c.startService(new Intent(c, RotationService.class).setAction(ACTION_UNLOCK_PHONE));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 关掉手机自动旋转并把方向钉在 {@link Prefs#getPhoneRotation()} 上。
     * <p>「锁手机方向」开关打开时调用；与「方向同步」是否开启无关。
     */
    public static void lockPhoneNow(Context c) {
        try {
            c.startForegroundService(
                    new Intent(c, RotationService.class).setAction(ACTION_LOCK_PHONE));
        } catch (Throwable ignored) {
        }
    }

    /** 弹窗 / 通知里的「启用」「不用」回执 */
    public static void answerPrompt(Context c, boolean yes, boolean noAsk) {
        try {
            Intent i = new Intent(c, RotationService.class)
                    .setAction(yes ? ACTION_PROMPT_YES : ACTION_PROMPT_NO)
                    .putExtra("noAsk", noAsk);
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    /** 主动撤掉询问通知（弹窗已经显示出来时调用） */
    public static void dismissPromptNotification(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTI_PROMPT);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        handler = new Handler(Looper.getMainLooper());
        dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "extrot-shell");
            t.setDaemon(true);
            return t;
        });

        createChannels();
        startForeground(NOTI_ID, buildNotification("正在启动…"));
        RUNNING = true;

        if (dm != null) {
            dm.registerDisplayListener(displayListener, handler);
        }
        handler.post(pollRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            prefs.setEnabled(false);
            // 先异步把手机自动旋转还原，再停服务，避免服务先死命令发不出去
            exec.execute(() -> {
                try {
                    if (prefs.isLockPhoneRotation()) {
                        ShellRunner.run("settings put system accelerometer_rotation 1");
                    }
                } catch (Throwable ignored) {
                }
                handler.post(this::stopSelf);
            });
            updateNotification("已关闭同步");
            return START_NOT_STICKY;
        }

        if (ACTION_UNLOCK_PHONE.equals(action)) {
            // 只还原手机自身的自动旋转。注意这个 action 有可能是"为了发它才把服务拉起来"，
            // 所以做完之后如果已经没有别的理由常驻，就自己收摊，别留一个空的前台服务。
            final boolean keep = prefs.shouldKeepRunning();
            exec.execute(() -> {
                try {
                    ShellRunner.run("settings put system accelerometer_rotation 1");
                    Log.d(TAG, "phone auto-rotate restored (keep=" + keep + ")");
                } catch (Throwable ignored) {
                }
                if (!keep) {
                    handler.post(this::stopSelf);
                }
            });
            return START_NOT_STICKY;
        }

        if (ACTION_LOCK_PHONE.equals(action)) {
            // 与上面严格对称：这条也可能"为了它才把服务拉起来"。
            // 但既然锁是开着的，shouldKeepRunning() 必然为 true，所以不会自停。
            applyPhoneRotationLock();
            return START_STICKY;
        }

        if (ACTION_MANUAL.equals(action) && intent != null) {
            applyManual(intent.getIntExtra("rotation", -1));
            return START_STICKY;
        }

        if (ACTION_REPAIR.equals(action)) {
            repairAll();
            return START_STICKY;
        }

        if (ACTION_APPLY_SIZE.equals(action)) {
            // 界面上的「立即应用」/ 打开开关：用户是主动要这个分辨率，
            // 所以标记为"与便携屏无关"，不会被拔屏或重启自动清掉
            applySizeAsync("手动启用", false);
            return START_STICKY;
        }

        if (ACTION_RESET_SIZE.equals(action)) {
            resetSizeAsync("手动还原");
            return START_STICKY;
        }

        if (ACTION_TEST_PROMPT.equals(action)) {
            askAboutSize(DisplayUtil.externalDisplays(dm));
            return START_STICKY;
        }

        if (ACTION_PROMPT_YES.equals(action)) {
            prefs.setResolutionEnabled(true);
            if (intent != null && intent.getBooleanExtra("noAsk", false)) {
                prefs.setAskOnConnect(false);
            }
            sPrompted = true;
            dismissPromptNotification(this);
            // 只有在确实接着便携屏时，才算"与便携屏绑定"
            boolean bound = !DisplayUtil.externalDisplays(dm).isEmpty();
            applySizeAsync("已启用", bound);
            return START_STICKY;
        }

        if (ACTION_PROMPT_NO.equals(action)) {
            if (intent != null && intent.getBooleanExtra("noAsk", false)) {
                prefs.setAskOnConnect(false);
            }
            sPrompted = true;
            dismissPromptNotification(this);
            updateNotification("已忽略本次分辨率调整");
            return START_STICKY;
        }

        // ACTION_START（含开机自启、用户打开开关、切设置后重启服务）
        //
        // 闸门是 isLockPhoneRotation() 而不是 isEnabled()：这两件事现在是独立开关，
        // 「只要锁手机方向」这种用法（不开方向同步）也必须能把锁落下去。
        //
        // ⚠ 两道刹车，都是「别在用户正看着画面的时候把屏拧一下」：
        //   · 控制页（触控板 / 键盘 / 投屏控制）在用的时候，一律不碰显示设置；
        //   · 系统把常驻服务拉回来（intent == null）时不重写锁 —— 那不是用户的意图
        //     （实测：进程一起来服务被拉回 → 写 user_rotation 1 → 内屏翻成 1920×1080，
        //     用户看到的就是「外接屏歪了 90°」）。
        if (ExtControlMode.active()) {
            Log.i(TAG, "控制页在用 → 跳过锁方向与方向评估");
            return START_STICKY;
        }
        if (prefs.isLockPhoneRotation() && intent != null) {
            applyPhoneRotationLock();
        }
        lastOrientKey = -1;      // 重新开始做方向稳定判定
        orientSince = 0L;
        evaluate(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        if (handler != null) {
            handler.removeCallbacks(pollRunnable);
            handler.removeCallbacks(evalRunnable);
        }
        if (dm != null) {
            try {
                dm.unregisterDisplayListener(displayListener);
            } catch (Throwable ignored) {
            }
        }
        if (exec != null) {
            exec.shutdown();   // 允许队列里的任务跑完
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- 手机自身旋转

    /**
     * 把关掉自动旋转 + 锁定方向这件事做成一条 settings 命令，
     * 效果等同于下拉快捷开关里把「自动旋转」关掉并固定当前方向。
     * 不做任何「强制所有应用横屏」的动作。
     */
    private void applyPhoneRotationLock() {
        if (!prefs.isLockPhoneRotation()) {
            return;
        }
        final int rot = prefs.getPhoneRotation();
        lockWriteAt = System.currentTimeMillis();
        exec.execute(() -> {
            try {
                ShellRunner.run("settings put system accelerometer_rotation 0");
                ShellRunner.run("settings put system user_rotation " + rot);
                Log.d(TAG, "phone rotation locked to " + rot);
            } catch (Throwable t) {
                Log.e(TAG, "lock phone rotation failed", t);
            }
        });
    }

    /**
     * 盯着「锁手机方向」这件事有没有被系统那一侧改掉。
     *
     * <p>用户从下拉快捷开关把「自动旋转」打开时，{@code accelerometer_rotation} 会被
     * 系统改回 1，但本应用的开关还开着 —— 应用以为自己锁着、系统却在自动转，
     * 两边对"当前方向"的理解不一致，又会把镜像画面搞乱。
     *
     * <p>这里的选择是<b>尊重用户</b>：既然他自己把自动旋转打开了，就把本应用这个开关
     * 同步关掉，而不是再去把系统锁回来（那样等于和用户对着干）。
     */
    /**
     * 控制页在用时的「只看不动」：把拓扑签名刷成当前值，别的什么都不做。
     *
     * <p>为什么不一并把 {@code firstEvalDone} / {@code lastDefOn} 也更新：前者是「服务起来后
     * 第一次评估」的语义，提前置位会让退出后那次评估跳过「首次只记录状态」的约定；
     * 后者若在控制页期间被吃掉，退出后本该走的「刚点亮」兜底窗口就没了。
     */
    private void trackTopologyOnly() {
        try {
            List<Display> ext = DisplayUtil.externalDisplays(dm);
            Display def = DisplayUtil.defaultDisplay(dm);
            String t = topologyKey(def, ext);
            if (t != null && !t.equals(lastTopology)) {
                Log.i(TAG, "控制页在用 → 只更新拓扑签名: " + t);
                lastTopology = t;
            }
        } catch (Throwable t) {
            // 跟踪失败无所谓：下一次 evaluate 照旧按"拓扑变了"走一遍完整重排，
            // 只是多拧一下，不会把状态搞坏
        }
    }

    private void checkPhoneLockDivergence(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastLockCheckAt < LOCK_CHECK_MS) {
            return;
        }
        lastLockCheckAt = now;

        if (!prefs.isLockPhoneRotation()) {
            lockDivergeStrikes = 0;
            return;
        }
        // 刚写完不久时读到旧值很正常，给它一点时间落盘
        if (now - lockWriteAt < LOCK_DIVERGE_GRACE_MS) {
            lockDivergeStrikes = 0;
            return;
        }

        int accel;
        try {
            accel = Settings.System.getInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1);
        } catch (Throwable t) {
            return;
        }
        if (accel != 1) {
            lockDivergeStrikes = 0;
            return;
        }
        // 连续两次都读到"自动旋转开着"才认定，避免落在命令落盘的间隙上误判
        lockDivergeStrikes++;
        if (lockDivergeStrikes < 2) {
            return;
        }
        lockDivergeStrikes = 0;
        Log.d(TAG, "system auto-rotate re-enabled → turn off phone rotation lock");
        prefs.setLockPhoneRotation(false);
        updateNotification("系统已恢复自动旋转，已同步关闭「锁手机方向」");
    }

    // ---------------------------------------------------------------- 便携屏插拔

    /**
     * 这块外接屏存过档案的话，把档案里那<b>整套</b>设置套回全局设置。
     *
     * <p>必须在 {@link #syncResFromExt} <b>之前</b>调：档案里就存着「接入时要不要自动套分辨率」
     * 以及分辨率本身，得先写回去，后面那步才会按这块屏的偏好走。
     *
     * <p>只在「外接屏刚接上」这个翻转的瞬间调用（见 {@link #handleExtConnection}），
     * 不在服务每次启动时调用 —— 服务重启（被回收、开机自启）时屏可能本来就接着，
     * 那时用户刚在界面上手动调好的值会当场被档案覆盖，看起来就是「我改的它不认」。
     *
     * <p>⚠ 档案<b>不含</b>「方向同步」与「手机方向锁定」：它们是全局的开关意图，不属于哪块屏；
     * 早先写回它们会在插屏时把用户手动打开的开关悄悄关掉。
     *
     * @return 是否命中了档案。存过档案说明用户已经为这块屏表过态，调用方据此决定别再弹询问。
     */
    private boolean applyProfileIfAny(List<Display> ext) {
        if (ext == null || ext.isEmpty()) {
            return false;
        }
        Display best = DisplayUtil.largestByPanel(ext);
        if (best == null) {
            return false;
        }
        DisplayProfile.Item it = DisplayProfile.load(this, DisplayProfile.signature(best));
        if (it == null) {
            return false;
        }
        boolean changed = it.applyTo(prefs);
        // ⚠ 套档案时不再去动「手机方向锁定」的开关，也不再去改系统旋转设置。
        // 档案只管这块屏的画面参数（角度 / 分辨率）；那两个开关是用户此刻的意图。
        Log.d(TAG, "display profile hit " + DisplayProfile.label(best)
                + " port=" + it.portrait + " land=" + it.landscape
                + " fixToUser=" + it.fixToUser
                + " resAuto=" + it.resAuto
                + " res=" + (it.resEnabled ? (it.resW + "x" + it.resH) : "关")
                + " dpi=" + it.resDensity
                + " changed=" + changed);
        updateNotification("已套用这块屏保存的设置");
        return true;
    }

    /**
     * 处理「便携屏插入 / 拔出」这个事件本身。
     * 只有状态真的发生翻转才动作，轮询重复调用不会重复触发。
     */
    private void handleExtConnection(List<Display> ext, boolean isFirstEval) {
        boolean has = !ext.isEmpty();

        if (isFirstEval) {
            // 首次评估不做"插入"判定，否则服务每次拉起都会弹一次询问
            sExtPresent = has;
            if (has) {
                if (prefs.isResolutionEnabled() || prefs.isAskOnConnect()) {
                    syncResFromExt(ext);
                }
                if (prefs.isResolutionEnabled()) {
                    applySizeAsync("自动");
                } else if (prefs.isAskOnConnect() && !sPrompted) {
                    sPrompted = true;
                    askAboutSize(ext);
                }
            } else if (prefs.isSizeApplied() && prefs.isResBoundToExt() && prefs.isAutoResetSize()) {
                // 上次是"因为接了便携屏"才加的覆盖，而现在没接屏 → 属于异常退出的残留，清掉
                resetSizeAsync("启动时清理");
            }
            return;
        }

        if (has && !sExtPresent) {
            sExtPresent = true;
            sPrompted = false;
            Log.d(TAG, "external display connected");
            // 先看看这块屏是不是存过档案 —— 存过就先按它的设置来
            boolean profiled = applyProfileIfAny(ext);
            // 再按这块屏的实际情况算出合适的分辨率，最后决定是问还是直接套
            // （档案里 "自动套用时要不要读面板分辨率" 那项已经写回去了，所以这一步会照它的意思走）
            syncResFromExt(ext);
            if (profiled) {
                // 存过档案 = 用户已经为这块屏表过态了，就别再弹一次询问打扰他。
                // 档案里写着要接管分辨率就直接落下去；写着不接管就什么都不做。
                sPrompted = true;
                if (prefs.isResolutionEnabled()) {
                    applySizeAsync("按这块屏保存的设置");
                }
            } else if (prefs.isAskOnConnect()) {
                sPrompted = true;
                askAboutSize(ext);
            } else if (prefs.isResolutionEnabled()) {
                applySizeAsync("自动");
            }
            return;
        }

        if (!has && sExtPresent) {
            sExtPresent = false;
            sPrompted = false;
            Log.d(TAG, "external display disconnected");
            if (prefs.isAutoResetSize() && prefs.isSizeApplied() && prefs.isResBoundToExt()) {
                resetSizeAsync("便携屏已拔出");
            }
        }
    }

    /**
     * 接入便携屏时，读它的面板原生分辨率来推算内屏该用多少，并写进设置。
     *
     * <p>便携屏面板基本是横向的（1920×1080 之类），而内屏镜像过去是竖着看的，
     * 所以要转成竖屏 1080×1920。多块屏时取像素最多的那块。
     *
     * <p>用户把这个功能关掉（{@link Prefs#isAutoMatchExt()} = false）时直接返回，
     * 保留他自己填的分辨率。
     */
    private void syncResFromExt(List<Display> ext) {
        if (!prefs.isAutoMatchExt() || ext == null || ext.isEmpty()) {
            return;
        }
        int[] best = null;
        for (Display d : ext) {
            int[] s = DisplayUtil.suggestedInternalSize(d);
            if (s[0] <= 0 || s[1] <= 0) {
                continue;
            }
            if (best == null || (long) s[0] * s[1] > (long) best[0] * best[1]) {
                best = s;
            }
        }
        if (best == null) {
            return;
        }
        if (best[0] == prefs.getResWidth() && best[1] == prefs.getResHeight()) {
            return;
        }
        Log.d(TAG, "auto match ext size => " + best[0] + "x" + best[1]);
        prefs.setResSize(best[0], best[1]);
    }

    /** 实际会下发到内屏的尺寸：始终是竖屏方向（高 ≥ 宽） */
    private int[] effSize() {
        return ScreenSizeUtil.portrait(prefs.getResWidth(), prefs.getResHeight());
    }

    // ---------------------------------------------------------------- 内屏分辨率

    private void applySizeAsync(String why) {
        applySizeAsync(why, true);
    }

    /**
     * @param boundToExt 是否「因为接了便携屏」才加上的覆盖。
     *                   见 {@link Prefs#isResBoundToExt()} —— 手动点「立即应用」传 false，
     *                   这样它不会在拔屏或应用重启时被"清理"掉。
     */
    private void applySizeAsync(String why, boolean boundToExt) {
        if (!ShellRunner.isReady()) {
            updateNotification("等待 Shizuku 授权（点开 App 授权）");
            return;
        }
        // 统一转成竖屏方向后再下发 —— 1920×1080 这类横向值到这里会被自动换成 1080×1920
        final int[] p = effSize();
        final int w = p[0];
        final int h = p[1];
        final int dpi = prefs.getResDensity();
        exec.execute(() -> {
            try {
                String out = ScreenSizeUtil.apply(w, h, dpi);
                prefs.setSizeApplied(true);
                prefs.setResBoundToExt(boundToExt);
                if (dpi > 0) {
                    prefs.setDensityApplied(true);
                }
                Log.d(TAG, "apply size " + w + "x" + h + " dpi=" + dpi + " bound=" + boundToExt + " => " + out);
                handler.post(() -> updateNotification("内屏分辨率 → " + w + "×" + h + "（" + why + "）"));
            } catch (Throwable t) {
                Log.e(TAG, "apply size failed", t);
            }
        });
    }

    private void resetSizeAsync(String why) {
        if (!ShellRunner.isReady()) {
            updateNotification("等待 Shizuku 授权（点开 App 授权）");
            return;
        }
        final boolean alsoDpi = prefs.isDensityApplied() || prefs.getResDensity() > 0;
        exec.execute(() -> {
            try {
                String out = ScreenSizeUtil.reset(alsoDpi);
                prefs.setSizeApplied(false);
                prefs.setResBoundToExt(false);
                prefs.setDensityApplied(false);
                Log.d(TAG, "reset size => " + out);
                handler.post(() -> updateNotification("内屏分辨率已还原原生（" + why + "）"));
            } catch (Throwable t) {
                Log.e(TAG, "reset size failed", t);
            }
        });
    }

    /**
     * 询问是否要把内屏改成分辨率覆盖。
     * 两条腿走路：一条高优先级通知（一定能看到）+ 一个真正的对话框（能弹就弹）。
     */
    private void askAboutSize(List<Display> ext) {
        // 询问里展示的必须就是真正会下发的那个值（已转竖屏），不能让用户看到一个
        // 横屏数字、结果写进去是另一个
        final int[] p = effSize();
        final int w = p[0];
        final int h = p[1];
        final String desc = extDesc(ext, w, h);

        showPromptNotification(desc, w, h);
        tryShowDialog(desc, w, h);
    }

    /** 描述外接屏：带上它的面板原生分辨率和旋转后的实际尺寸 */
    private String extDesc(List<Display> ext, int targetW, int targetH) {
        if (ext == null || ext.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("已连接便携显示屏 ");
        for (int i = 0; i < ext.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            Display d = ext.get(i);
            int[] n = DisplayUtil.nativeSize(d);
            sb.append("ID ").append(d.getDisplayId())
                    .append(" 原生 ").append(n[0]).append("×").append(n[1])
                    .append("（转 90° 后 ").append(n[1]).append("×").append(n[0]).append("）");
        }
        // 明确告诉用户"横屏数字被自动转竖屏了"，否则会以为填错了
        int cw = prefs.getResWidth();
        int ch = prefs.getResHeight();
        if (cw > ch) {
            sb.append(" · 已自动转竖屏 ").append(cw).append("×").append(ch)
                    .append(" → ").append(targetW).append("×").append(targetH);
        }
        return sb.toString();
    }

    /**
     * 尽力弹一个真正的对话框。
     * <p>息屏或锁屏时不弹 —— 既看不到，还会平白点亮屏幕。
     */
    private void tryShowDialog(String desc, int w, int h) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null || !pm.isInteractive()) {
                Log.d(TAG, "skip dialog: screen off");
                return;
            }
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                Log.d(TAG, "skip dialog: keyguard locked");
                return;
            }
        } catch (Throwable ignored) {
        }

        final Intent i = new Intent(this, PromptActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(PromptActivity.EXTRA_DESC, desc)
                .putExtra(PromptActivity.EXTRA_W, w)
                .putExtra(PromptActivity.EXTRA_H, h);

        boolean overlay = false;
        try {
            overlay = Settings.canDrawOverlays(this);
        } catch (Throwable ignored) {
        }

        // A. 有「显示在其他应用上层」权限时，后台启动 Activity 是被系统明确允许的
        if (overlay) {
            try {
                startActivity(i);
                Log.d(TAG, "prompt dialog started directly (overlay granted)");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "startActivity blocked", t);
            }
        }

        // B. 没有该权限时，退而用 shell 身份拉起 —— shell 不受后台启动限制
        final String cmd = "am start -n " + getPackageName() + "/.PromptActivity"
                + " --activity-single-top"
                + " --es " + PromptActivity.EXTRA_DESC + " '" + safe(desc) + "'"
                + " --ei " + PromptActivity.EXTRA_W + " " + w
                + " --ei " + PromptActivity.EXTRA_H + " " + h;
        final boolean hasOverlay = overlay;
        exec.execute(() -> {
            String out = ShellRunner.run(cmd);
            Log.d(TAG, "shell am start (overlay=" + hasOverlay + ") => " + out);
        });
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("'", "").replace("\n", " ");
    }

    // ---------------------------------------------------------------- 核心逻辑

    /**
     * 便携屏该锁到哪个角度：手机竖屏用「竖屏锁到」，横屏用「横屏锁到」。
     *
     * <p>v4.13 起没有「固定角度」这一档了 —— 同步开关打开就只可能是跟随手机方向；
     * 关掉整个同步则这一句根本走不到（前面那道 {@code isEnabled()} 闸门会先 return）。
     */
    private int targetFor(boolean phonePortrait) {
        return phonePortrait ? prefs.getPortraitAngle() : prefs.getLandscapeAngle();
    }

    /** 通知里附带的分辨率状态片段 */
    private String sizeTail() {
        if (!prefs.isSizeApplied()) {
            return "";
        }
        int[] p = effSize();
        return " · 内屏 " + p[0] + "×" + p[1];
    }

    /**
     * @param force true 时忽略静默期与稳定期，立即下发（启动 / 手动修复）
     */
    private void evaluate(boolean force) {
        // 控制页在用的时候，一次方向评估都不做 —— 评估会下发外接屏方向 / 分辨率，
        // 而此刻用户正盯着那份镜像画面看。轮询还在跑，控制页一退就自动接上。
        //
        // ⚠ 这里不能只是 return：**拓扑跟踪得继续走**。只 return 的话 lastTopology 会停在
        // 「控制页打开之前」那个值，退出后第一次评估一比对就认定「拓扑变了」→ pendingRepair
        // → 整块 free→lock 重排，屏幕当场拧一下（用户看到的是「进去没事、一退出画面又歪了」）。
        if (ExtControlMode.active()) {
            trackTopologyOnly();
            return;
        }
        List<Display> ext = DisplayUtil.externalDisplays(dm);
        Display def = DisplayUtil.defaultDisplay(dm);

        boolean first = !firstEvalDone;
        firstEvalDone = true;
        handleExtConnection(ext, first);

        // ---- 用户有没有在系统那一侧把「自动旋转」重新打开
        checkPhoneLockDivergence(force);

        // ---- 拓扑变化检测（插屏 / 折叠展开 / 内屏面板换尺寸）。
        // 这些变化会让系统重算整机显示几何，而外接屏的 rotation 值通常不变 ——
        // 正是"角度对、几何错"的来源，所以这里必须另起一次完整重排。
        String topology = topologyKey(def, ext);
        if (!topology.equals(lastTopology)) {
            boolean known = !lastTopology.isEmpty();
            lastTopology = topology;
            geomRepairCount = 0;
            geomCheckUnreliable = false;
            // 手机内屏灭掉的时候不用重排 —— 没人能看见，而且点亮时那次 OFF→ON
            // 会自己再触发一次。只有"亮着"的状态变化才值得动手。
            boolean defOn = def == null || def.getState() == Display.STATE_ON;
            if (defOn && !lastDefOn) {
                defOnAt = System.currentTimeMillis();   // 刚点亮：屏上的 rotation 可能还是旧值
            }
            lastDefOn = defOn;
            if (known && !first && defOn) {
                Log.d(TAG, "topology changed → schedule full repair");
                pendingRepair = true;
                topoChangedAt = System.currentTimeMillis();
                scheduleEval(TOPO_SETTLE_MS, true);
                // 折叠展开有可能连内屏的分辨率覆盖一起冲掉；只有是我们加上的才补回来
                if (prefs.isSizeApplied() && prefs.isResBoundToExt()
                        && DisplayUtil.sizeOverrideInfo(def) == null) {
                    applySizeAsync("显示状态变化后补回", true);
                }
            }
        }

        // ---- 手机方向判定。
        // 刻意放在下面几个早退分支之前：方向计时必须一直维护，而且方向一变就要「预约」一次评估
        // （STABLE_MS 之后再跑），不能等下一个轮询周期 —— 等轮询会把响应时间整段放大成一个轮询间隔。
        //
        // 输入源必须是「真正亮着的那块内置屏」：合盖后内屏是灭的，灭屏的 getRotation() 停在旧值
        // 恒为 0，拿它判断就永远得到「竖屏」，外接屏被锁到竖屏角度（用户配的 270°）。
        Display phone = DisplayUtil.activePhoneDisplay(dm);
        boolean phoneOn = DisplayUtil.isOn(phone);

        // 方向输入源只有一个：手机此刻真正在显示的那块屏的实际方向。
        //
        // ⚠ 「锁着」不该是判定的输入 —— 它只是一个「替用户关掉系统自动旋转」的动作，
        // 效果通过手机自己的画面体现出来。只要锁着就让目标角度恒等于锁定角度，外接屏就再也不会
        // 跟着手机变，用户看到的是「两个开关一起开，自适应旋转像是死的」。
        //
        // 反过来，应用一旦强行声明方向（只支持竖屏的，例如 QQ），手机会当场转成竖屏，
        // 外接屏就必须跟着转过去，否则那个应用竖着居中、左右留黑边，也就是铺不满全屏。
        boolean phoneKnown = phoneOn;
        boolean phonePortrait = lastPhonePortrait;

        long now = System.currentTimeMillis();

        if (phoneKnown) {
            boolean p = DisplayUtil.isPhonePortrait(phone);

            // ---- 只在「默认屏刚被点亮」的短窗口内，拿系统旋转策略兜一次底。
            //
            // 这是为一个具体现象加的：内屏被关掉再点亮（例如切并发双屏）之后，它的 rotation 会短暂
            // 停在 0，而 user_rotation 还是 1 —— 光看屏就把横屏判成竖屏，外接屏被抡到 270°。
            //
            // ⚠ 不能无条件生效：user_rotation 正是本应用为「锁手机方向」写进去的那个值，
            // 无条件对账等于「锁着的时候策略永远赢」，只支持竖屏的应用把手机转成竖屏也拉不回来。
            // 所以只认刚点亮之后的 POLICY_GRACE_MS，之后一律信屏上的实际值。
            if (phone != null && phone.getDisplayId() == Display.DEFAULT_DISPLAY
                    && now - defOnAt < POLICY_GRACE_MS) {
                int policy = DisplayUtil.policyRotation(getContentResolver());
                if (policy >= 0) {
                    boolean byPolicy = DisplayUtil.isPortraitAt(phone, policy);
                    if (byPolicy != p) {
                        // 不一致可能持续很久（比如整场并发双屏都这样），只在"新出现"时打一条，
                        // 否则每 1.5 秒的轮询会把日志刷爆
                        int mk = byPolicy ? 1 : 0;
                        if (mk != lastMismatchKey) {
                            lastMismatchKey = mk;
                            Log.w(TAG, "手机方向：屏上读数与系统策略不一致，采用策略"
                                    + "（user_rotation=" + policy + " → " + (byPolicy ? "竖屏" : "横屏")
                                    + "；屏上 rotation=" + phone.getRotation()
                                    + " → " + (p ? "竖屏" : "横屏") + "）");
                        }
                        p = byPolicy;
                    } else {
                        lastMismatchKey = -1;   // 恢复了，下次不一致再提醒一次
                    }
                }
            }

            lastPhonePortrait = p;
            lastPhonePortraitValid = true;
            phonePortrait = p;

            int orientKey = p ? 1 : 0;
            if (orientKey != lastOrientKey) {
                lastOrientKey = orientKey;
                orientSince = now;
                Log.d(TAG, "phone orientation -> " + (p ? "portrait" : "landscape")
                        + " (by " + DisplayUtil.kindOf(dm, phone) + ") "
                        + DisplayUtil.orientationDebug(phone));
                if (!force) {
                    // 稳定期一结束立刻重评估，不等下一次轮询
                    scheduleEval(STABLE_MS + 30L);
                }
            }
        }

        if (!prefs.isEnabled()) {
            // 旋转同步关着，但分辨率接管 / 手机方向锁定可能还在工作 —— 这三件事互不依赖
            String busy;
            if (prefs.isResolutionEnabled()) {
                busy = ext.isEmpty() ? "等待便携屏接入…（分辨率接管已开）"
                                     : "内屏分辨率已接管" + sizeTail();
            } else if (prefs.isLockPhoneRotation()) {
                busy = "方向同步已关（手机方向锁定中）";
            } else {
                busy = "已关闭同步";
            }
            updateNotification(busy);
            return;
        }

        if (!ShellRunner.isReady()) {
            updateNotification("等待 Shizuku 授权（点开 App 授权）");
            return;
        }

        if (ext.isEmpty()) {
            states.clear();
            pendingRepair = false;
            updateNotification("未检测到便携屏" + sizeTail());
            return;
        }

        // 一次有效方向都没读到过（屏幕全灭 / 刚开机）→ 什么都不做。
        // 宁可让外接屏维持在现状，也不要凭一个凭空的"竖屏"去把它转 270°。
        if (!lastPhonePortraitValid) {
            updateNotification("手机屏幕未点亮，暂不判断方向");
            return;
        }

        if (!force && now - orientSince < STABLE_MS) {
            updateNotification("手机方向变化中，等待稳定…");
            return;
        }

        int target = targetFor(phonePortrait);

        // ---- 拓扑变化后的完整重排。
        // 等系统把新几何提交完（拿"最近一次下发命令"的时刻当参照），再做 free → lock；
        // 太早下发会被正在进行的重配置吞掉，反而留下半成品几何。
        if (pendingRepair) {
            // 两重等待：①变化发生后先给系统 ~0.7s 去重配置；②我们自己的命令落地之后
            // 再等 TOPO_WAIT_MS。超时 5s 就直接干，免得因为事件被吃掉而永远不重排。
            boolean settled = now - topoChangedAt >= 700L
                    && (lastLockIssuedAt == 0L || now - lastLockIssuedAt >= TOPO_WAIT_MS);
            if (!settled && now - topoChangedAt < 5000L) {
                scheduleEval(400L, true);
                updateNotification("显示状态已变化，正在重新排版…");
                return;
            }
            pendingRepair = false;
            lastReassertAt = now;
            geomRepairCount = 0;
            Log.d(TAG, "topology re-assert → full repair");
            repairAllInternal(target);
            updateNotification("显示状态变化，已重新排版 → " + DisplayUtil.degrees(target) + "°");
            return;
        }

        // ---- 几何校验：角度对 ≠ 画面对。
        // 外接屏在目标角度下的实际尺寸（getRealSize 已按当前旋转换算）必须等于面板原生尺寸在该角度
        // 下的换算值，不等就说明系统留了半成品几何 → 整块重排。
        //
        // ⚠ 只有「角度已经落在目标上」时才校验：手机刚转方向时外接屏的角度还是上一个旧值，
        // 那时尺寸不符是<b>必然的中转状态</b>。无条件校验会让每次转向都先误报一次「几何异常」、
        // 接着做一次 free → lock 重排，而 free 那一瞬间外接屏会自己重算方向，屏幕就当场乱转一下。
        boolean angleAtTarget = true;
        for (Display d : ext) {
            if (d.getRotation() != target) {
                angleAtTarget = false;
                break;
            }
        }
        if (angleAtTarget && !geomCheckUnreliable && !geometryOk(ext, target)) {
            if (now - lastReassertAt > GEOM_BACKOFF_MS) {
                geomRepairCount = 0;   // 停手很久了，允许再试一轮
            }
            if (geomRepairCount < MAX_GEOM_REPAIR
                    && now - lastReassertAt >= REASSERT_MIN_GAP_MS) {
                geomRepairCount++;
                lastReassertAt = now;
                Log.d(TAG, "geometry mismatch → full repair #" + geomRepairCount);
                repairAllInternal(target);
                updateNotification("画面几何异常，已强制重排 → " + DisplayUtil.degrees(target) + "°");
                return;
            }
            if (geomRepairCount >= MAX_GEOM_REPAIR) {
                // 重排这么多次之后还是"不符"，那多半不是几何坏了，而是这块屏上报的
                // 尺寸和我们的换算方式对不上。关掉这个校验，免得每隔一会儿就去刷一遍屏幕；
                // 下次拓扑变化会重新打开。
                geomCheckUnreliable = true;
                Log.w(TAG, "geometry check seems unreliable for this display → disabled");
            }
        } else {
            geomRepairCount = 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("手机").append(phonePortrait ? "竖屏" : "横屏")
                .append(" → 目标 ").append(DisplayUtil.degrees(target)).append("° | ");

        boolean issued = false;
        for (Display d : ext) {
            int id = d.getDisplayId();
            ExtState st = states.get(id);
            if (st == null) {
                st = new ExtState();
                states.put(id, st);
            }

            int cur = d.getRotation();

            // ---- 静默期：命令刚下发，绝不再补刀。
            // 但如果"上一刀已经确认落地"（当前角度就是上一次的目标角度），就没必要
            // 死等满整个窗口 —— 否则手机来回翻转时会被无谓地压住 2.5 秒。
            // 注意判据是"到达上一次的目标"，不是"到达新目标"，所以绝不放过没提交完的事务。
            if (!force && now - st.issuedAt < SETTLE_MS) {
                boolean lastCommitted = st.target >= 0
                        && cur == st.target
                        && now - st.issuedAt >= MIN_SETTLE_MS;
                if (!lastCommitted) {
                    sb.append("屏").append(id).append(" 同步中 ");
                    continue;
                }
            }

            if (cur == target) {
                st.target = target;
                st.retries = 0;
                sb.append("屏").append(id).append(" 已就位 ");
                continue;
            }

            // 目标没变但实际角度不对 → 系统或别的应用改回去了，慢速补发
            if (st.target == target) {
                if (now - st.issuedAt > REARM_MS) {
                    st.retries = 0;   // 隔了很久，允许再试一轮
                }
                if (st.retries >= MAX_RETRY) {
                    sb.append("屏").append(id).append(" 重试到上限(").append(DisplayUtil.degrees(cur)).append("°) ");
                    continue;
                }
                st.retries++;
            } else {
                st.retries = 0;
            }

            if (prefs.isFixToUserRotation() && !fixToUserDone.contains(id)) {
                final int fixId = id;
                exec.execute(() -> ShellRunner.run("wm fixed-to-user-rotation -d " + fixId + " enabled"));
                fixToUserDone.add(id);
            }

            final int lockId = id;
            final int lockTarget = target;
            exec.execute(() -> ShellRunner.run("wm user-rotation -d " + lockId + " lock " + lockTarget));

            st.target = target;
            st.issuedAt = now;
            lastLockIssuedAt = now;
            issued = true;

            sb.append("屏").append(id).append(" ")
                    .append(DisplayUtil.degrees(cur)).append("°→")
                    .append(DisplayUtil.degrees(target)).append("° ");
        }

        String text = sb.toString().trim() + sizeTail();
        updateNotification(text);
        // 方向判定这块连着两版栽在"输入本身不对"上，而事后光看通知文本分不清
        // "没生效"和"算错了"。方向或目标一变就无条件留一条可追溯的日志。
        int logKey = (phonePortrait ? 2 : 0) | (target == 0 ? 1 : 0);
        if (issued || logKey != lastLoggedKey) {
            lastLoggedKey = logKey;
            Log.d(TAG, "方向判定 locked=" + prefs.isLockPhoneRotation()
                    + " phoneOn=" + phoneOn + " → " + text);
        }
    }

    /**
     * 手动锁定/还原（来自通知按钮或 App 内测试按钮）
     * rotation &lt; 0 表示还原成系统自动旋转。
     */
    private void applyManual(int rotation) {
        if (!ShellRunner.isReady()) {
            updateNotification("等待 Shizuku 授权");
            return;
        }
        List<Display> ext = DisplayUtil.externalDisplays(dm);
        if (ext.isEmpty()) {
            updateNotification("未检测到便携屏");
            return;
        }
        final int[] ids = new int[ext.size()];
        for (int i = 0; i < ext.size(); i++) {
            ids[i] = ext.get(i).getDisplayId();
        }
        final int rot = rotation;
        exec.execute(() -> {
            for (int id : ids) {
                if (rot < 0) {
                    ShellRunner.run("wm user-rotation -d " + id + " free");
                    ShellRunner.run("wm fixed-to-user-rotation -d " + id + " default");
                } else {
                    ShellRunner.run("wm fixed-to-user-rotation -d " + id + " enabled");
                    ShellRunner.run("wm user-rotation -d " + id + " lock " + rot);
                }
            }
        });

        long now = System.currentTimeMillis();
        for (int id : ids) {
            ExtState st = states.get(id);
            if (st == null) {
                st = new ExtState();
                states.put(id, st);
            }
            st.issuedAt = now;
            st.retries = 0;
            st.target = rot;
            if (rot < 0) {
                fixToUserDone.remove(id);
            }
        }
        if (rot >= 0) {
            lastOrientKey = -1;  // 让自动逻辑别立刻把刚锁的角度改回去
        }
        updateNotification(rot < 0
                ? "已恢复系统自动旋转"
                : "手动锁定 " + DisplayUtil.degrees(rot) + "°");
    }

    /**
     * 修复画面：对外接屏做一次「先解除锁定、再重新锁定到目标角度」的完整重排。
     * 当镜像画面出现整体偏移（只剩 1/4 在屏内）时，用它强制显示系统重算一次几何。
     */
    private void repairAll() {
        if (!ShellRunner.isReady()) {
            updateNotification("等待 Shizuku 授权");
            return;
        }
        List<Display> ext = DisplayUtil.externalDisplays(dm);
        if (ext.isEmpty()) {
            updateNotification("未检测到便携屏");
            return;
        }
        final int target = targetFor(phonePortraitNow());
        lastReassertAt = System.currentTimeMillis();
        repairAllInternal(target);
        updateNotification("已修复画面 → " + DisplayUtil.degrees(target) + "°");
    }

    /**
     * 手机现在是竖屏还是横屏 —— 问「真正亮着的那块内置屏」。
     * <p>读不到有效读数时沿用上一次的判断（见 {@link #lastPhonePortraitValid}），
     * 绝不用灭屏停在旧值的 rotation 去猜。
     */
    private boolean phonePortraitNow() {
        // 与 evaluate() 用同一套口径：只问手机此刻真正在显示什么，不看锁没锁
        Display phone = DisplayUtil.activePhoneDisplay(dm);
        if (DisplayUtil.isOn(phone)) {
            lastPhonePortrait = DisplayUtil.isPhonePortrait(phone);
            lastPhonePortraitValid = true;
        }
        return lastPhonePortrait;
    }

    /**
     * 重排的实体：对每一块外接屏「先 free 再 lock」。
     *
     * <p>为什么要 free 一下：只重复下发同一个 lock 角度，DisplayManager 认为值没变，
     * 根本不会重新走一遍几何计算，画面就永远停在错位状态。先放开再锁回去才能逼它重算。
     */
    private void repairAllInternal(int target) {
        List<Display> ext = DisplayUtil.externalDisplays(dm);
        if (ext.isEmpty()) {
            return;
        }
        final int[] ids = new int[ext.size()];
        for (int i = 0; i < ext.size(); i++) {
            ids[i] = ext.get(i).getDisplayId();
        }

        long now = System.currentTimeMillis();
        lastLockIssuedAt = now;

        exec.execute(() -> {
            for (int id : ids) {
                // 两步：先放开，让它彻底重新配置一次；再锁回目标角度
                ShellRunner.run("wm fixed-to-user-rotation -d " + id + " default");
                ShellRunner.run("wm user-rotation -d " + id + " free");
                try {
                    Thread.sleep(400L);
                } catch (InterruptedException ignored) {
                }
                ShellRunner.run("wm fixed-to-user-rotation -d " + id + " enabled");
                ShellRunner.run("wm user-rotation -d " + id + " lock " + target);
            }
        });

        for (int id : ids) {
            ExtState st = states.get(id);
            if (st == null) {
                st = new ExtState();
                states.put(id, st);
            }
            st.issuedAt = now;
            st.retries = 0;
            st.target = target;
            fixToUserDone.add(id);
        }
    }

    // ---------------------------------------------------------------- 几何 / 拓扑

    /**
     * 显示拓扑签名。任何一项变了都意味着「系统重新算过一遍整机显示几何」：
     * <ul>
     *   <li>手机内屏的开 / 关 —— 折叠机的展开 / 合盖</li>
     *   <li>内屏的面板尺寸 —— 系统换了显示模式</li>
     *   <li>外接屏的集合 —— 插上 / 拔掉便携屏</li>
     * </ul>
     * 刻意<b>不</b>包含旋转角度：正常转手机走的是另一条即时路径，不需要整块重排。
     */
    private String topologyKey(Display def, List<Display> ext) {
        StringBuilder sb = new StringBuilder();
        if (def == null) {
            sb.append("no-def");
        } else {
            sb.append(def.getState() == Display.STATE_ON ? "on" : "off");
            int[] n = DisplayUtil.nativeSize(def);
            sb.append('/').append(n[0]).append('x').append(n[1]);
        }
        for (Display d : ext) {
            sb.append("|e").append(d.getDisplayId());
            int[] n = DisplayUtil.nativeSize(d);
            sb.append(':').append(n[0]).append('x').append(n[1]);
        }
        return sb.toString();
    }

    /**
     * 校验外接屏的实际几何是否与目标角度相符。
     *
     * <p>判据：面板原生 (pw, ph)。目标是 0°/180° 时实际尺寸应当是 (pw, ph)，90°/270° 时应当是
     * (ph, pw)（{@code getRealSize} 已按当前旋转换算）。不符就说明 DisplayManager 那边留的是
     * 半成品几何 —— 画面会整体偏移、只露 1/4。
     *
     * <p>读不到原生尺寸时一律当作「正常」：宁可漏报也不要误报（误报会把屏幕刷成闪烁）。
     */
    private boolean geometryOk(List<Display> ext, int target) {
        boolean rotated = target == Surface.ROTATION_90 || target == Surface.ROTATION_270;
        for (Display d : ext) {
            try {
                int[] n = DisplayUtil.nativeSize(d);
                if (n[0] <= 0 || n[1] <= 0) {
                    continue;
                }
                int ew = rotated ? n[1] : n[0];
                int eh = rotated ? n[0] : n[1];
                Point real = new Point();
                d.getRealSize(real);
                if (real.x != ew || real.y != eh) {
                    Log.d(TAG, "geometry mismatch id=" + d.getDisplayId()
                            + " expected " + ew + "x" + eh
                            + " actual " + real.x + "x" + real.y
                            + " (target " + DisplayUtil.degrees(target) + "°)");
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 通知

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CH_ID, "便携屏方向同步", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);

        // 询问用得着"横幅"，所以给高优先级
        NotificationChannel pc = new NotificationChannel(CH_PROMPT, "便携屏接入询问", NotificationManager.IMPORTANCE_HIGH);
        pc.setShowBadge(true);
        nm.createNotificationChannel(pc);
    }

    private PendingIntent servicePi(int requestCode, String action, int rotation) {
        Intent i = new Intent(this, RotationService.class).setAction(action);
        if (ACTION_MANUAL.equals(action)) {
            i.putExtra("rotation", rotation);
        }
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Icon icon = Icon.createWithResource(this, R.drawable.ic_stat_rot);

        Notification.Builder b = new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_rot)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder(icon, "转 90°", servicePi(201, ACTION_MANUAL, 1)).build())
                .addAction(new Notification.Action.Builder(icon, "转 0°", servicePi(202, ACTION_MANUAL, 0)).build())
                .addAction(new Notification.Action.Builder(icon, "修复画面", servicePi(203, ACTION_REPAIR, 0)).build())
                .addAction(new Notification.Action.Builder(icon, "停止", servicePi(204, ACTION_STOP, 0)).build());

        return b.build();
    }

    private void updateNotification(String text) {
        if (text == null || text.equals(lastNotiText)) {
            return;
        }
        lastNotiText = text;
        LAST_LOG = System.currentTimeMillis() % 100000 + "  " + text;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTI_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 询问通知：即使对话框弹不出来（没有悬浮窗权限 / 系统拦截后台启动），
     * 这条通知也会以横幅形式出现，按钮直接可点。
     */
    private void showPromptNotification(String desc, int w, int h) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }

        Intent open = new Intent(this, PromptActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PromptActivity.EXTRA_DESC, desc)
                .putExtra(PromptActivity.EXTRA_W, w)
                .putExtra(PromptActivity.EXTRA_H, h);
        PendingIntent contentPi = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Icon icon = Icon.createWithResource(this, R.drawable.ic_stat_rot);
        String body = "是否把手机内屏分辨率改成 " + w + "×" + h + "？\n" + desc;

        Notification n = new Notification.Builder(this, CH_PROMPT)
                .setSmallIcon(R.drawable.ic_stat_rot)
                .setContentTitle("检测到便携显示屏")
                .setContentText("是否把手机内屏改成 " + w + "×" + h + "？")
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder(icon, "启用", servicePi(211, ACTION_PROMPT_YES, 0)).build())
                .addAction(new Notification.Action.Builder(icon, "不用", servicePi(212, ACTION_PROMPT_NO, 0)).build())
                .build();

        try {
            nm.notify(NOTI_PROMPT, n);
        } catch (Throwable ignored) {
        }
    }
}
