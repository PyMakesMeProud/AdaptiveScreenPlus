package com.wb.extrotator;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 「投屏控制」—— 把内屏（display 0）的画面实时贴到中间那个 TextureView 上，并让手指在这块屏上的动作
 * 换算回内屏坐标注进去：「在副屏上点 / 拖」就等于「在内屏对应位置上点 / 拖」。
 *
 * <p>画面由 {@link CastService} 用媒体投影 + 虚拟显示做出来，这里只当画布；本页自己跑在副屏上，
 * 不会出现「照镜子照到自己」。
 *
 * <p>坐标对齐是这一页最容易错的地方：{@link #fitMirror()} 把 TextureView 的宽高<b>算成</b>画面尺寸
 * （等比 + 居中），而不是铺满 —— 铺满会拉变形、触点跟着偏。这样「视图位置」与「内屏位置」
 * 只差一个纯比例，{@link #srcX}/{@link #srcY} 一个除法就够，不需要矩阵求逆。
 *
 * <p>触摸按下就开一条 {@code willContinue} 的笔画（点 = 立刻收，拖 = 一路续），
 * 注入交给 {@link TouchInjector}：优先 Shizuku 直注，探不到才退回 {@code input} 命令。
 */
public class CastActivity extends Activity implements CastService.Listener {

    private static final String TAG = "ExtCastUI";

    /** 系统授权弹窗的 requestCode */
    private static final int REQ_CAPTURE = 4701;

    /**
     * 进来就全屏。
     *
     * <p>给「一键投屏」用（见 {@code DualScreen.oneKeyCast}）：那边用户要的是"点一下就看着内屏
     * 画面"，中间再让他在小屏上找那颗「全屏」就白一键了。手动进来的入口不带这个参数。
     */
    public static final String EXTRA_FULLSCREEN = "fullscreen";
    /** 进来就自己开始投屏（同上，只有「一键投屏」带） */
    public static final String EXTRA_AUTO_CAST = "auto_cast";

    /**
     * 放大倍数上限。
     *
     * <p>再往上就不是"看得清"而是"看得糊"了 —— 内屏本身也就 1080 宽，
     * 副屏这块屏更小，6 倍以上只剩像素颗粒，点反而更不准。
     */
    private static final float ZOOM_MAX = 6f;

    /**
     * 单指按下之后，至少等这么久才真的往内屏发「按下」。
     *
     * <p>捏合是两根手指，第一根落下时还分不出用户要点、要拖、还是要捏合。原来「按下就发 DOWN」，
     * 等第二根手指落下才 {@code abort()} 抬起来 —— 内屏收到的就是「同一处按下 + 抬起」，
     * <b>那正是一次点击</b>：用户只想放大，内屏却把手指底下那个应用点开了。
     *
     * <p>窗口里各走各的路：落下第二根手指 ⇒ <b>一个事件都不发</b>；手指动了超过 touch slop ⇒
     * 当场判为拖动，按在起始点上（起点不受影响）；抬手 ⇒ 整次只有一次点击，而点击本来就在抬手那刻
     * 作数，这一档不亏延迟。
     *
     * <p>代价只有按住不动的长按会晚这么多秒按下（系统阈值 500ms，从这里起算仍够）。取 200ms：
     * 真捏合两根手指几乎一起落下（实测多在 60ms 内），余量给足；再大只是让长按更迟钝。
     */
    private static final long PRESS_GRACE_MS = 200L;

    /**
     * 改完内屏分辨率之后，至少等这么久才允许建虚拟显示。
     *
     * <p>⚠ 这不是「保险起见睡一下」，是实测的硬需求：镜像投影是系统在<b>创建虚拟显示那一刻</b>
     * 按「源屏当时的尺寸」算死的 —— 写完 {@code wm size} 后 0.378s 建 vd，画面只有框的 67.5%；
     * 0.56s 才是新尺寸；3.0s 才 100% 铺满。根子在 {@code wm size} 先到 WindowManager、
     * 再到 SurfaceFlinger：{@code dumpsys window displays} 里的 {@code cur=} 立刻就变，光看它会以为改好了。
     *
     * <p>1.2s = 实测上界（约 0.6s）的两倍。
     */
    private static final long RESIZE_SETTLE_MS = 1200;

    private TextView tvState, tvIdle, tvPath, btnToggle;
    private TextureView mirrorView;
    private FrameLayout mirrorBox;
    /** 「内屏分辨率重设」「允许放大」两个勾选项 */
    private CheckBox cbResize, cbZoom;

    /* ------------- 全屏 -------------
     * 全屏 = 把页面上所有控件（顶栏 / 勾选行 / 右侧那列 / 状态行）收起来，只留画面，
     * 系统栏也藏掉，整块屏让给内屏画面。见 {@link #setFullscreen}。
     */
    /** 此刻是不是全屏 */
    private boolean fullscreen;
    /** 根布局与三个要收起来的容器（画面框 mirrorBox 不在里面，它本来就留到最后） */
    private View rootCast, rowTop, rowOptions, colOps;
    /** 画面那一行 —— 全屏时还要把它的上边距也清掉，画面才顶得到最上面 */
    private View rowMain;
    /** 全屏前的内边距 / 画面那一行的上边距，退出全屏都按原样还回去 */
    private int padL, padT, padR, padB;
    private int marMainTop;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * 内屏此刻的逻辑尺寸 —— 既是虚拟显示的尺寸，也是注入坐标空间。
     * 页面一进来就在后台探（要一条 {@code dumpsys window displays}，几百毫秒，别放主线程）。
     *
     * <p>⚠ 刻意<b>不做</b>「探不到就用面板原生分辨率兜底」：本机内屏有 {@code wm size} 覆盖，
     * 面板 1080×2640、逻辑 1080×1920，拿前者当兜底等于把画面和触点一起搞偏。
     */
    private volatile int[] srcSize;

    /**
     * 内屏尺寸 / 方向的看门人。
     *
     * <p><b>为什么要有它</b>：srcSize 只在"我们自己主动改完立刻重探"时刷新（页面刚进来、
     * 勾选重设），而源屏<b>自己</b>转方向（用户把手机转过去）它根本不知道 —— 实测这就是
     * "竖屏时点不准"的根因：画面还按横的摆、触点还按 1920×1080 换算，内屏实际已是 1080×1920，
     * 一直错到退出重投为止。
     */
    private DisplayManager dmCast;

    /** 去抖：转屏动画期间 onDisplayChanged 会连发好几条，攒够了再动手 */
    private static final long REFIT_DEBOUNCE_MS = 900L;

    /**
     * 我们自己刚写过 wm size（「内屏分辨率重设」）之后，这段时间内的变化不算"源屏自己转了"：
     * 那一下是勾选动作造成的，而且紧接着本来就有一次重探，别在这儿插一脚。
     */
    private static final long REFIT_QUIET_MS = 3000L;

    private final Runnable refitTask = this::refitInner;

    private final DisplayManager.DisplayListener innerWatcher =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    /*
                     * ⚠ 这个回调什么信息都不带，只能先拿 displayId 筛一遍（密度、超时之类也会触发它）；
                     * 真正的判据在 refitInner 里 —— "探出来的尺寸到底变没变"。
                     */
                    if (displayId != ExtScreen.INNER_ID) {
                        return;
                    }
                    ui.removeCallbacks(refitTask);
                    ui.postDelayed(refitTask, REFIT_DEBOUNCE_MS);
                }
            };

    private Surface surface;

    /** 这次按下的起点与是否已经过了 touch slop（只用来打日志和区分点/拖） */
    private float downX, downY;
    /** 手指此刻在哪 —— 宽限窗口到点时就按在"最新位置"上（比按在起点更准） */
    private float lastX, lastY;
    private long downAt;
    private boolean moved;
    /**
     * "按下"到底发出去没有。
     * 假 = 还在宽限窗口里（或整次触摸都没轮到发）；真 = 内屏那边已经按下了。
     */
    private boolean downSent;

    /* ---------------- 注入：交给共用的 TouchInjector ----------------
     *
     * v3.19 把这套"独立线程 + 只认最新位置"写在了本页里，触控板那边还是老样子，
     * 于是同一个毛病要在两个地方面对两次。v3.20 抽成 {@link TouchInjector} 两页共用。
     *
     * 本页只剩两件事：把"视图坐标"换算成"内屏坐标"，然后丢进格子。
     */
    private TouchInjector inj;
    /** 此刻实际在走的通道（给界面那行说实话用） */
    private volatile boolean directPath;

    /* ------------- 放大用的变换 -------------
     * 用 View 自己的 {@code setScaleX/setScaleY/setTranslationX/Y}，不用
     * {@code TextureView.setTransform(矩阵)}：前者的「画在屏幕上的矩阵」和「框架反算触点用的矩阵」
     * 是同一个（ViewGroup.transformPointToViewLocal），所以「看到的」与「点到的」不可能对不上；
     * 后者的矩阵算在哪个坐标空间文档没写死，一旦猜错，放大之后点哪儿都偏、越放大偏得越多。
     * 顺带 {@link #srcX} 恢复成「视图坐标 ÷ 视图尺寸」—— 跟没放大时同一条路。
     */

    /** 放大倍数（1 = 没放大） */
    private float zoomS = 1f;
    /** 放大时的平移，单位是框里的像素 */
    private float zoomTx, zoomTy;
    /** 打"捏合"日志的节流时刻（捏合里一秒能来几十条，全打会把日志冲烂） */
    private long zoomLogAt;
    /** 正在双指捏合 */
    private boolean pinch;
    /**
     * 这一次触摸<b>整体</b>算「捏合用掉的」，抬手也不许当成点击。
     *
     * <p>为什么要跟 {@link #pinch} 分开：捏合中途先抬起一根手指时，{@code ACTION_POINTER_UP}
     * 一进来就把 {@code pinch} 置假，于是最后一根手指抬起走 {@code ACTION_UP} 时它已经是假，
     * 直接落到「抬手」那条路、注入线程补一条短按，<b>每次捏合都会顺手在内屏上点一下</b>。
     * 这个标记一直留到整次触摸结束。
     */
    private boolean pinchEnd;
    /** 捏合上一刻的两指距离与中点 */
    private float pinchDist, pinchX, pinchY;

    /* ---------------- 分辨率重设 ---------------- */

    /**
     * 这次投屏期间，内屏分辨率是不是我们改的。
     *
     * <p>静态字段：页面被系统重建（转屏 / 内存回收）时实例字段会丢，
     * 而"改过系统设置"这件事必须记住 —— 记不住就还不回去了。
     */
    private static boolean resizeApplied;
    /** 改之前那套覆盖的原文（{@code null} = 之前没有覆盖，还原时用 reset） */
    private static String prevForced;
    /** 实际写进去的效果尺寸，收工时对一下"还是不是我们改的那套" */
    private static String resizedTo;

    /**
     * 这次重设是什么时候写进去的（{@link SystemClock#uptimeMillis}，0 = 没改过）。
     *
     * <p>唯一用处是算"还差多久才算落地" —— 见 {@link #settleResize}。
     * 静态：页面重建（转屏 / 被系统回收）不该让它清零，否则重建一次又赶着去建虚拟显示了。
     */
    private static long resizedAt;

    /**
     * "授权弹窗回来的路上"（v3.20 第三处修正）。
     *
     * <p>用处只有一个：{@link #onStop} 里决定"能不能把内屏分辨率还回去"。
     * 点「开始投屏」到虚拟显示建好之间会过一次授权弹窗，那期间本页会走一次 onStop ——
     * 那一刻要是把分辨率还了，紧接着建的虚拟显示就照旧尺寸建，画面直接废掉。
     */
    private boolean castPending;

    /** 镜像画面每秒更新了多少次 —— 用来分辨"画面卡"还是"操作卡"，只打日志 */
    private int frameN;
    private long frameAt;

    /** 判"这次是点还是拖"的容差，用系统标准值（已按密度缩放） */
    private int slop;

    /* ------------- 全屏里的出口（v4.32） -------------
     * 全屏时页面上一个控件都没有，出口只留一条：手指从画面最顶上往下拉，
     * 拉出那条「退出全屏」。见 {@link #dispatchTouchEvent}。
     */

    /** 那条「退出全屏」按钮（平时 GONE） */
    private View barExit;
    /** 这一次触摸是从哪儿按下去的（纵坐标，判"是不是从顶上开始拉"） */
    private float pullDownY;
    /** 这一次触摸已经判成"下拉"了 —— 从此这一整笔都不当操作画面 */
    private boolean pulling;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cast);

        slop = Math.max(1, ViewConfiguration.get(this).getScaledTouchSlop());

        tvState = findViewById(R.id.tvCastState);
        tvIdle = findViewById(R.id.tvCastIdle);
        tvPath = findViewById(R.id.tvCastPath);
        btnToggle = findViewById(R.id.btnCastToggle);
        mirrorView = findViewById(R.id.mirrorView);
        mirrorBox = findViewById(R.id.mirrorBox);
        rootCast = findViewById(R.id.rootCast);
        rowTop = findViewById(R.id.rowCastTop);
        rowOptions = findViewById(R.id.rowCastOptions);
        colOps = findViewById(R.id.colCastOps);
        rowMain = findViewById(R.id.rowCastMain);
        if (rowMain != null && rowMain.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            marMainTop = ((ViewGroup.MarginLayoutParams) rowMain.getLayoutParams()).topMargin;
        }
        if (rootCast != null) {
            padL = rootCast.getPaddingLeft();
            padT = rootCast.getPaddingTop();
            padR = rootCast.getPaddingRight();
            padB = rootCast.getPaddingBottom();
        }

        ExtUi.info(this, R.id.iCastTop, R.string.ext_cast_title, R.string.cast_info);

        findViewById(R.id.btnCastClose).setOnClickListener(v -> finish());
        // 全屏：把整块屏让给画面（进去之后控件全收起，退出来靠那块屏的返回键）
        findViewById(R.id.btnCastFull).setOnClickListener(v -> setFullscreen(true));
        btnToggle.setOnClickListener(v -> {
            if (CastService.ready()) {
                CastService s = CastService.get();
                if (s != null) {
                    s.stopCast(getString(R.string.cast_stopped));
                }
            } else {
                requestCast();
            }
        });

        // 右侧那列"只按一下"的操作 —— 都是往内屏发的按键
        findViewById(R.id.btnCastBack).setOnClickListener(
                v -> ExtScreen.key(ExtScreen.INNER_ID, "KEYCODE_BACK"));
        findViewById(R.id.btnCastHome).setOnClickListener(
                v -> ExtScreen.key(ExtScreen.INNER_ID, "KEYCODE_HOME"));
        /*
         * 「后台」= 内屏的最近任务：按一下把最近任务拉出来，卡片滑掉或点「全部关闭」都行
         * （那些操作本身也是走画面触摸注进去的）。
         * 实测 `input -d 0 keyevent KEYCODE_APP_SWITCH` 能把内屏顶到 RecentsActivity。
         */
        findViewById(R.id.btnCastRecent).setOnClickListener(
                v -> ExtScreen.key(ExtScreen.INNER_ID, "KEYCODE_APP_SWITCH"));

        // 全屏里的出口：从画面最顶上往下拉把它拉出来，点它退出全屏
        barExit = findViewById(R.id.btnCastExitFull);
        if (barExit != null) {
            barExit.setOnClickListener(v -> {
                hideExitBar();
                setFullscreen(false);
            });
        }

        mirrorView.setOnTouchListener(this::onMirrorTouch);
        mirrorView.setSurfaceTextureListener(surfaceListener);
        mirrorBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fitMirror());

        bindOptions();

        probeSrcSizeAsync();
        watchInnerDisplay();
        startInjector();
        updateUi(CastService.ready(), null);

        // 进来时带的参数：全屏 / 自动开始投屏（一键投屏走这条，见 handleEntry）
        handleEntry(getIntent(), true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        /*
         * ⚠ 本页是 singleInstance：上一次进来过、还留在后台时，`am start` 走的是这里而不是
         * onCreate。不把新的换进去，往后 getIntent() 拿到的还是上一次那个。
         */
        setIntent(intent);
        handleEntry(intent, false);
    }

    /**
     * 处理进来时带的参数（目前只有「一键投屏」会带）。手动进来的入口什么都不带，等于什么都不做。
     *
     * @param fresh true = 页面刚建起来。这时候布局还没跑完，改框、起投屏都得等一帧。
     */
    private void handleEntry(Intent it, boolean fresh) {
        if (it == null) {
            return;
        }
        if (it.getBooleanExtra(EXTRA_FULLSCREEN, false)) {
            setFullscreen(true);
        }
        if (!it.getBooleanExtra(EXTRA_AUTO_CAST, false) || CastService.ready()) {
            return;
        }
        if (fresh) {
            // 等一帧：全屏那下会改框，而重设是按框算的（见 resizeForOption）
            mirrorBox.post(this::requestCast);
        } else {
            requestCast();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        /*
         * 这一页在用的时候，别让 RotationService 动显示设置 —— 跟三个控制页一个道理
         * （见 ExtControlMode）。v3.20 尤其需要：勾了「内屏分辨率重设」之后，
         * 内屏那套尺寸是我们自己写的，服务那边顺手重写一次就会把画面比例改掉。
         */
        ExtControlMode.on();
        // 回到本页时把已有状态补上（可能是在别的页面上投的屏）
        bindCast();
        /*
         * 回到这一页时，如果「内屏分辨率重设」还勾着，把分辨率补上 ——
         * onStop 里空闲离场时会还回去（理由见那儿），所以回来得重新改一遍。
         */
        if (cbResize != null && cbResize.isChecked() && !CastService.ready() && mirrorBox != null) {
            mirrorBox.post(this::resizeForOption);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // ⚠ 页面要走的时候要是还按着，必须抬起来 —— 不然内屏会一直维持"按下"，
        // 被拖的东西就卡在半路（跟 abortChain 同一个道理）
        cancelPress();
        downSent = false;
        injAbort();
        ExtControlMode.off();
        CastService s = CastService.get();
        if (s != null) {
            s.setListener(null);
        }
        /*
         * ⚠ 空闲着离场就把内屏分辨率还回去。这一项是「勾上就改」，页面开着时内屏就处在非原生
         * 比例上，用户切出去看内屏会一脸懵；回来时 onStart 会照勾选状态补做。
         *
         * ⚠ 两种「正在路上」绝不能还：正投着屏（还了画面比例当场不对）、授权弹窗回来的路上
         * （castPending，那正是马上要建虚拟显示的时刻）。
         */
        if (!castPending && !CastService.ready()) {
            restoreCastResize();
        }
        /*
         * 页面看不见了，全屏那点遮挡意义就没了 —— 侧滑栏和手势条按设置装回去。
         * 正常退出会走 setFullscreen(false)，但「切到别的应用 / 被系统收走」不走那条路，
         * 少了这一句它们就永远回不来了。
         */
        applyCoverOverlays(true);
    }

    @Override
    protected void onDestroy() {
        stopInjector();
        CastService s = CastService.get();
        if (s != null) {
            s.setListener(null);
            s.detach();
            /*
             * 页面真被关掉（不是转屏重建）时把投屏也收掉 —— 画面没了还留着投影，
             * 通知栏会一直挂一条"投屏控制运行中"，用户找不到地方关。
             *
             * ⚠ 只在 isFinishing() 时收：转屏 / 被系统重建时收掉的话，
             * 用户会看到通知栏闪一下、画面黑掉，再回来还得重新授权一次。
             */
            if (isFinishing()) {
                s.stopCast(getString(R.string.cast_stopped));
            }
        }
        unwatchInnerDisplay();
        resetZoom();
        releaseSurface();
        /*
         * 页面真被关掉时把内屏分辨率还回去。
         * ⚠ 必须在这儿显式还一次：上面那段停投屏是走 CastService.stopCast，
         * 而它的回调（onCastState）在 onStop 里已经被我们解绑了，等不到那条广播。
         * restoreCastResize 自己是幂等的（没改过就什么都不做），多调一次不要紧。
         */
        if (isFinishing()) {
            restoreCastResize();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 投屏开关

    private void requestCast() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            ExtUi.toast(this, getString(R.string.cast_fail, "系统没有媒体投影服务"));
            return;
        }
        if (!ShellRunner.isReady()) {
            // 没有 Shizuku 就绕不开弹窗，而弹窗在副屏上点不到（见 grantProjection）——
            // 与其让用户卡在「正在准备…」，不如当场说清楚该去哪块屏上点。
            ExtUi.toast(this, getString(R.string.cast_no_shizuku_dialog));
        }
        /*
         * ⚠ 顺序是死的：先改内屏分辨率，再办授权、再去拉弹窗。
         * 虚拟显示是照"内屏此刻的逻辑尺寸"建的，尺寸得在它之前定下来；
         * 反过来（先投屏再改）画面比例当场就错，只能重投一次。
         */
        // 从这里到虚拟显示建好之前，onStop 不许还分辨率（见 castPending）
        castPending = true;
        tvState.setText(getString(R.string.cast_preparing));
        applyCastResize(() -> {
            // 先把授权"预先办掉"，再去拉系统弹窗（理由见 grantProjection）
            grantProjection(() -> {
                try {
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
                } catch (Throwable t) {
                    Log.w(TAG, "拉起授权弹窗失败: " + t);
                    castPending = false;
                    ExtUi.toast(this, getString(R.string.cast_fail, String.valueOf(t)));
                }
            });
        });
    }

    // ------------------------------------------------------------------ 两个勾选项

    /**
     * 勾选项的初始化。
     *
     * <p>勾选状态存进 prefs —— 这两条是"我的习惯"，不是"这一趟的设置"，
     * 每次进来都要重新勾一遍太烦（跟灵敏度按钮一个道理）。
     */
    private void bindOptions() {
        cbResize = findViewById(R.id.cbCastResize);
        cbZoom = findViewById(R.id.cbCastZoom);
        if (cbResize == null || cbZoom == null) {
            return;
        }
        cbResize.setChecked(ExtPrefs.castResize(this));
        cbZoom.setChecked(ExtPrefs.castZoom(this));
        cbResize.setOnCheckedChangeListener((v, on) -> {
            ExtPrefs.setCastResize(this, on);
            /*
             * 已经投着的时候改这一项不生效（尺寸在建虚拟显示时就定了）——
             * 照实说一句，别让用户以为勾上就马上铺满了。
             */
            if (CastService.ready()) {
                ExtUi.toast(this, getString(R.string.cast_opt_resize_late));
                return;
            }
            /*
             * ⚠ 勾上就立刻改，**不等**点「开始投屏」（v3.20 第三处修正，见 resizeForOption）：
             * 改分辨率会同时动"源屏尺寸"和"画面视图尺寸"两样东西，而镜像投影是系统在
             * 建虚拟显示那一刻按当时的几何算死的 —— 都挤在点按钮那一瞬间，画面就缩。
             */
            if (on) {
                resizeForOption();
            } else {
                // 取消勾选就把借来的分辨率立刻还回去，不用等到收工
                restoreCastResize();
                srcSize = null;
                probeSrcSizeAsync();
            }
        });
        cbZoom.setOnCheckedChangeListener((v, on) -> {
            ExtPrefs.setCastZoom(this, on);
            if (!on) {
                // 关掉就把画面摆回原样，别留一个放大状态在那儿（下次打开会一脸懵）
                resetZoom();
            }
        });
        /*
         * 上次就勾着的话，页面一进来就把分辨率改好 —— 等用户点到「开始投屏」时，
         * 源屏和画面都早就是最终状态了（理由见 resizeForOption）。
         * 走到 post 里是因为这会儿布局还没跑，框还没量出来（要照框的比例算）。
         */
        if (cbResize.isChecked()) {
            mirrorBox.post(this::resizeForOption);
        }
    }

    /** 「允许放大」勾着没（控件还没建出来时算没勾） */
    private boolean zoomOn() {
        return cbZoom != null && cbZoom.isChecked();
    }

    // ------------------------------------------------------------------ 内屏分辨率重设

    /**
     * 「内屏分辨率重设」的实际动作 —— <b>勾上就改</b>，不是等点「开始投屏」才改。
     *
     * <p>镜像投影是系统在<b>创建虚拟显示那一刻</b>按当时的几何算死的，而改分辨率会让源屏逻辑尺寸
     * 与跟着它摆的 TextureView 尺寸同时变。两件事若都挤在点「开始投屏」那一瞬间，画面只会占框的
     * 67.5% / 46.8%，贴在角上；改成「勾上就改、等它落地、顺手摆好画面」之后，投屏那一刻什么都不再变，
     * 实测 100% 铺满、四边边距全 0。
     */
    private void resizeForOption() {
        if (cbResize == null || !cbResize.isChecked()) {
            return;
        }
        // 框还没量出来（onCreate 里调的时候布局还没跑）就等一帧再来
        if (mirrorBox != null && mirrorBox.getWidth() <= 0) {
            mirrorBox.post(this::resizeForOption);
            return;
        }
        applyCastResize(() -> {
            /*
             * 尺寸变了，之前探到的坐标空间就作废了 —— 重探一次；
             * probeSrcSizeAsync 探完会顺手 fitMirror()，画面尺寸也就跟着摆好了。
             */
            srcSize = null;
            probeSrcSizeAsync();
        });
    }

    /**
     * 全屏那一下，画面框有多大。
     *
     * <p>全屏 = 页面上的控件全收起 + 页边距清零，框就是整块可用区，也就是 {@link #rootCast}
     * 的尺寸；已经全屏了就直接量 {@link #mirrorBox}，那是最准的。
     *
     * <p>⚠ 别拿 {@link #mirrorBox} 在"没全屏"时的尺寸去配平（v4.32 之前就是这么干的）：
     * 那个框上面被顶栏占着、右边被操作列占着，照它配出来的比例，全屏之后必然上下留黑边。
     *
     * @return null = 布局还没跑完，量不出来
     */
    private int[] fullscreenBoxSize() {
        /*
         * ⚠ 一律拿 rootCast（v4.33 统一）：全屏时边距全清、画面那一行也贴到最上面，
         * 框就是 rootCast 这么大；没全屏时它的尺寸不变，正好就是"全屏之后会是多大"的预估值。
         * 两边共用同一套数，重设出来的比例才不会跟摆画面差一截。
         */
        if (rootCast != null && rootCast.getWidth() > 0 && rootCast.getHeight() > 0) {
            return new int[]{rootCast.getWidth(), rootCast.getHeight()};
        }
        return null;
    }

    /**
     * 把内屏的宽高比改成跟画面框一样。
     *
     * <p>内屏是竖长条（覆盖后 1080×1920），投到封面屏那个近似方形的框里，等比缩放后只占中间一条、
     * 上下两条黑边，手指能点的地方也就那么窄一条；比例改成跟框一样，缩放正好铺满。
     *
     * <p>⚠ 它改的是系统设置（{@code wm size}），必须能还回去：用户自己设过覆盖的话，
     * 收工直接 reset 会把他那套一并抹掉 —— 所以改之前先把原文抄下来（{@link ScreenSizeUtil#forced}），
     * 收工照着抄回去。
     *
     * @param then 办完（成功、失败、没勾上，都算办完）回主线程继续
     */
    private void applyCastResize(Runnable then) {
        if (cbResize == null || !cbResize.isChecked()) {
            then.run();
            return;
        }
        /*
         * ⚠ 框取「全屏那一下」的尺寸，不是眼前这个（v4.32）。全屏时控件全收起、页边距清零，
         * 框比现在大一圈、比例也不一样；照眼前这个框配平，一点全屏就又对不上了 ——
         * 用户点全屏要的是铺满整块屏，只铺满屏幕上的一个小窗没有意义。
         */
        int[] box = fullscreenBoxSize();
        final int bw = box == null ? 0 : box[0];
        final int bh = box == null ? 0 : box[1];
        new Thread(() -> {
            try {
                int base = 1080;
                int[] cur = srcSize;
                if (cur == null) {
                    cur = probeSrcSize();      // 后台线程里探，慢一点没关系
                }
                if (cur != null) {
                    // 基准取"短边"：短边不变，页面里那些控件的大小就不会跟着变
                    base = Math.min(cur[0], cur[1]);
                }
                int[] fit = ScreenSizeUtil.fitToBox(bw, bh, base);
                if (fit == null) {
                    Log.w(TAG, "重设跳过：框还没量出来（" + bw + "×" + bh + "）");
                } else {
                    if (!resizeApplied) {
                        prevForced = ScreenSizeUtil.forced();
                        resizeApplied = true;
                    }
                    boolean rotated = screenRotated90();
                    /*
                     * ⚠ 内屏可能正被转成横的（user_rotation = 1 / 3）。`wm size` 写的是「没转过」的
                     * 基准值，转过 90° 之后系统把宽高对调，所以想让看到的是 fit，写进去的就得把它俩
                     * 换个位置。问不到就按没转处理（那样长宽互换，画面有黑边但不会歪）。
                     */
                    int w = rotated ? fit[1] : fit[0];
                    int h = rotated ? fit[0] : fit[1];
                    /*
                     * ⚠ resizedTo 存的是写进去的那套（不是 fit）：它的用处是「回头看看设置里那套
                     * 还是不是当初我写的」，所以要比 ScreenSizeUtil.forced()（那边给的就是写进去的值）。
                     * 存成 fit 两边永远不相等，会被当成「用户改过了」而拒绝还原。
                     */
                    final String want = w + "x" + h;
                    if (want.equals(ScreenSizeUtil.forced())) {
                        /*
                         * 已经是我们要的那套了（勾上时就改好、落地了）——**别再写一遍**：哪怕值一模一样，
                         * 系统也会当成一次显示几何变更，而那正是让镜像投影变旧的源头（见 resizeForOption）。
                         * resizedAt 也不动，免得凭空多等一轮落地。
                         */
                        resizedTo = want;
                        Log.i(TAG, "内屏重设：已经就是 " + want + "，跳过重复写入");
                    } else {
                        ScreenSizeUtil.write(w, h, 0);
                        resizedTo = want;
                        resizedAt = SystemClock.uptimeMillis();
                        Log.i(TAG, "内屏重设 → 写入 " + w + "×" + h + "（转过90°=" + rotated
                                + "）· 期望看到 " + fit[0] + "x" + fit[1] + " · 框 " + bw + "×" + bh
                                + " · 原来是 " + (prevForced == null ? "无覆盖" : prevForced));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "内屏重设失败: " + t);
            }
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                /*
                 * 尺寸变了，原来探到的那个坐标空间就作废了 —— 置空让下面 ensureSrcSize
                 * 重新探一次（虚拟显示与触点换算都照新值来）。只探这一次，不会多花时间：
                 * 本来 srcSize 为空时也是要探的。
                 */
                srcSize = null;
                then.run();
            });
        }, "extrot-cast-resize").start();
    }

    /**
     * 等到内屏分辨率「真的落地」再往下走（见 {@link #RESIZE_SETTLE_MS}）。
     *
     * <p>等的是「还剩多久」而不是「睡满 1.2 秒」：从写设置到这一步本来就要花掉办预授权、拉弹窗、
     * 探尺寸那几百毫秒，那部分算在预算里。
     *
     * <p>⚠ 等待必须在后台线程：主线程睡 1.2 秒是 ANR 的路子。
     *
     * @param then 落地之后回主线程继续
     */
    private void settleResize(Runnable then) {
        final long left = RESIZE_SETTLE_MS - (SystemClock.uptimeMillis() - resizedAt);
        if (resizedAt == 0L || left <= 0L) {
            then.run();
            return;
        }
        Log.i(TAG, "等内屏尺寸落地：还差 " + left + "ms（改了分辨率不能马上建虚拟显示）");
        new Thread(() -> {
            SystemClock.sleep(left);
            ui.post(then);
        }, "extrot-cast-settle").start();
    }

    /**
     * 把内屏分辨率还回去（收工时调，幂等）。
     *
     * <p>⚠ 只在"确实是我们改的"才还：{@link #resizeApplied} 是静态的，
     * 页面重建也不会丢；还完立刻清标记，重复调用就是空转。
     */
    private void restoreCastResize() {
        if (!resizeApplied) {
            return;
        }
        resizeApplied = false;
        final String prev = prevForced;
        prevForced = null;
        final String mine = resizedTo;
        resizedTo = null;
        new Thread(() -> {
            try {
                String now = ScreenSizeUtil.forced();
                if (mine != null && now != null && !mine.equals(now)) {
                    /*
                     * 现在这套已经不是我写进去的那个了 —— 用户在别处改过（主界面的
                     * 「立即应用」之类）。那就别动它：还原的本意是"把借的还回去"，
                     * 不是"把用户后来的选择顶掉"。
                     */
                    Log.i(TAG, "内屏分辨率已被别处改过（现在是 " + now + "），不还原");
                    return;
                }
                String r = ScreenSizeUtil.restore(prev);
                Log.i(TAG, "内屏分辨率已还原 → " + (r == null ? "" : r.trim())
                        + "（还成 " + (prev == null ? "无覆盖" : prev) + "）");
            } catch (Throwable t) {
                Log.w(TAG, "内屏分辨率还原失败: " + t);
            }
        }, "extrot-cast-resize-back").start();
    }

    /**
     * 内屏此刻是不是被转成横的了。
     *
     * <p>凭据是 {@code settings get system user_rotation}：1 / 3 就是转 90°。
     * 本应用的 RotationService 写的就是这一项（「锁手机方向」），
     * 而本页在前面按住了 {@link ExtControlMode#on()}，它不会在我们干活时改这一项。
     */
    private boolean screenRotated90() {
        try {
            String s = ShellRunner.run("settings get system user_rotation");
            if (s == null) {
                return false;
            }
            String t = s.trim();
            return "1".equals(t) || "3".equals(t);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 用 Shizuku（shell 身份）把<b>本应用</b>的 {@code PROJECT_MEDIA} 运行权限预先设成 allow。
     *
     * <p>这不是优化，是这道功能在副屏上<b>唯一的活路</b>：系统的投屏授权弹窗按内屏尺寸布局
     * （实测 {@code Requested w=1080 h=1374}），而封面屏只有 748×654，「立即开始 / 取消」两个按钮
     * 永远落在屏外，用户在副屏上点不到。appops 已是 allow 时那个 Activity 直接返回 RESULT_OK、不弹窗。
     *
     * <p>影响面只有本包的 {@code PROJECT_MEDIA} 这一项（包名现取、不写死），别的应用完全不受影响；
     * 投屏结束时 {@link CastService} 会把它复位成系统默认。
     *
     * <p>做不到也不拦着：照旧去拉弹窗，那个在内屏上是点得到的。
     */
    private void grantProjection(Runnable then) {
        new Thread(() -> {
            String out = null;
            try {
                if (ShellRunner.isReady()) {
                    out = ShellRunner.run("appops set " + getPackageName()
                            + " PROJECT_MEDIA allow");
                }
            } catch (Throwable t) {
                Log.w(TAG, "PROJECT_MEDIA 预授权失败: " + t);
            }
            final String res = out;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (res != null) {
                    Log.i(TAG, "PROJECT_MEDIA 预授权 → " + res.trim());
                }
                then.run();
            });
        }, "extrot-cast-grant").start();
    }

    /**
     * 把本页挂到 {@link CastService} 上，并把当前状态刷到界面。
     *
     * <p>⚠ 不能只在 {@code onStart()} 里绑一次：服务的创建发生在 {@code onActivityResult} 之后，
     * 那时 {@code onStart()} 早跑过了，而 Service 起来不会让 Activity 再走一遍 ——
     * 结果就是画面已经投出来了，界面还停在「正在准备…」、按钮还是「开始投屏」。
     * 所以起完服务必须主动回来绑一次，见 {@link #awaitCast()}。
     */
    private void bindCast() {
        CastService s = CastService.get();
        if (s != null) {
            s.setListener(this);
        }
        updateUi(CastService.ready(), CastService.ready() ? null : CastService.lastWhy());
        attachIfPossible();
    }

    /**
     * 服务是异步起来的（{@code startForegroundService} → 主线程稍后 {@code onCreate}），
     * 所以起完之后小步重试着绑，绑上就停。
     *
     * <p>刻意设上限：2 秒内没起来就别再让 Handler 空转 —— 真起不来时，
     * 用户该看到的是状态行里的原因，而不是一个永远转着的「正在准备…」。
     */
    private void awaitCast() {
        final int[] tries = {0};
        Runnable probe = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (CastService.get() != null) {
                    bindCast();
                    return;
                }
                if (++tries[0] < 10) {
                    ui.postDelayed(this, 200L);
                }
            }
        };
        ui.postDelayed(probe, 120L);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        // 同意还是拒绝都算"回到页面了"，onStop 从这一刻起可以正常还分辨率
        castPending = false;
        if (req != REQ_CAPTURE) {
            super.onActivityResult(req, res, data);
            return;
        }
        if (res != RESULT_OK || data == null) {
            ExtUi.toast(this, getString(R.string.cast_denied));
            updateUi(false, getString(R.string.cast_denied));
            // 授权被拒也退出全屏：不然用户对着全屏的空白页，连原因那行字都看不见
            if (fullscreen) {
                setFullscreen(false);
            }
            return;
        }
        tvState.setText(getString(R.string.cast_preparing));
        // ⚠ 尺寸必须在起服务之前拿到：虚拟显示是照它建的，给错了画面会变形、触点会偏。
        // 探尺寸要一条 dumpsys（要 Shizuku），所以这里等它探完再起服务。
        ensureSrcSize(() -> {
            /*
             * ⚠ 先抓一份下来：探尺寸那个线程可能把 srcSize 置空（见 probeSrcSizeAsync），
             * 拿 srcSize[0] 直接进去会空指针。
             */
            final int[] wh = srcSize;
            if (wh == null) {
                updateUi(false, getString(R.string.cast_no_shizuku));
                return;
            }
            // 改了内屏分辨率的话，得等它真的落到 SurfaceFlinger，再建虚拟显示（见 RESIZE_SETTLE_MS）
            settleResize(() -> {
                /*
                 * 密度给 320 就行，不用去读内屏那个 480：实测（框 1199×999、内屏与 vd 都是
                 * 1296×1080、dpi=320）画面**正好铺满**，说明它不参与镜像缩放。
                 * 画面大小只由「建 vd 那一刻的源屏尺寸 : vd 尺寸」决定 —— 见 RESIZE_SETTLE_MS。
                 */
                CastService.start(this, res, data, wh[0], wh[1], 320);
                // 服务起来之后把画面贴上去、并把监听补上（服务是在这一步之后才创建的）
                awaitCast();
            });
        });
    }

    // ------------------------------------------------------------------ 尺寸 / 画面

    /** 挂上"内屏尺寸 / 方向"的看门人（页面起来挂一次，走到 onDestroy 摘掉） */
    private void watchInnerDisplay() {
        if (dmCast != null) {
            return;
        }
        dmCast = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dmCast != null) {
            dmCast.registerDisplayListener(innerWatcher, ui);
        }
    }

    private void unwatchInnerDisplay() {
        if (dmCast != null) {
            try {
                dmCast.unregisterDisplayListener(innerWatcher);
            } catch (Throwable ignored) {
            }
            dmCast = null;
        }
        ui.removeCallbacks(refitTask);
    }

    /**
     * 内屏的几何真的变了：重探一次坐标空间，把虚拟显示也改过来，再重摆画面。
     *
     * <p><b>为什么虚拟显示也得改</b>：只重探尺寸不够 —— 虚拟显示还是旧几何（比如 1920×1080），
     * 新的 1080×1920 只是把画面摆进了一根竖条，vd 里那份内容还是横的，画面与触点照样对不上。
     *
     * <p>三种情况直接放弃：没在投屏（尺寸本来就有 {@link #probeSrcSizeAsync} 管）、授权弹窗
     * 回来的路上（castPending，那正是马上要建虚拟显示的时刻）、我们自己刚写过 wm size 之后那 3 秒。
     */
    private void refitInner() {
        if (isFinishing() || isDestroyed() || castPending || !CastService.ready()) {
            return;
        }
        if (resizedAt != 0L && SystemClock.uptimeMillis() - resizedAt < REFIT_QUIET_MS) {
            return;
        }
        new Thread(() -> {
            final int[] wh = probeSrcSize();
            ui.post(() -> {
                if (wh == null || isFinishing() || isDestroyed()) {
                    return;
                }
                final int[] old = srcSize;
                if (old != null && old[0] == wh[0] && old[1] == wh[1]) {
                    // 尺寸没变（亮度 / 密度之类也会触发 onDisplayChanged）—— 什么都不用做
                    return;
                }
                CastService s = CastService.get();
                boolean ok = s != null && s.refit(wh[0], wh[1]);
                Log.i(TAG, "内屏几何变了 " + (old == null ? "?" : old[0] + "×" + old[1])
                        + " → " + wh[0] + "×" + wh[1] + " · 虚拟显示重设=" + ok);
                srcSize = wh;
                /*
                 * ⚠ 顺序是死的：先让虚拟显示按新几何重开，再摆画面。反过来的话，中间那一下正好是
                 * "画面照新比例摆好了、vd 还是旧的"，等于把内容拉一遍。
                 * 缓冲区尺寸也顺手报一次：TextureView 是照它反算画面的。
                 */
                syncSurfaceSize();
                fitMirror();
            });
        }, "extrot-cast-refit").start();
    }

    /** 后台探一次内屏的逻辑尺寸 */
    private void probeSrcSizeAsync() {
        if (srcSize != null) {
            return;
        }
        new Thread(() -> {
            final int[] wh = probeSrcSize();
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                /*
                 * ⚠ 只接受「探到了」的结果：探失败（Shizuku 没就绪 / 超时）时别把上一次的有效值冲掉 ——
                 * 冲掉之后 fitMirror 会直接返回（不摆画面），按下也算不出内屏坐标（srcX/srcY 都是 0）。
                 */
                if (wh != null) {
                    srcSize = wh;
                    Log.i(TAG, "内屏坐标空间 " + wh[0] + "×" + wh[1]);
                }
                fitMirror();
                updateUi(CastService.ready(), null);
            });
        }, "extrot-cast-size").start();
    }

    /** 探完再往下走；已经探过就直接走 */
    private void ensureSrcSize(Runnable then) {
        if (srcSize != null) {
            then.run();
            return;
        }
        new Thread(() -> {
            final int[] wh = probeSrcSize();
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (wh == null) {
                    // 探不到就别硬投：见 srcSize 字段上那段话
                    ExtUi.toast(this, getString(R.string.cast_no_shizuku));
                    updateUi(false, getString(R.string.cast_no_shizuku));
                    return;
                }
                srcSize = wh;
                then.run();
            });
        }, "extrot-cast-size").start();
    }

    /**
     * 内屏的逻辑尺寸 = 注入坐标空间。
     *
     * <p>用的是 {@link ExtScreen#injectionSize}（读 {@code dumpsys window displays} 的
     * {@code cur=WxH}），它认 {@code wm size} 覆盖，和 {@code input -d 0} 的落点是同一套。
     * ⚠ 别改用 {@code getRealSize()} / {@code getMode()}：前者会把本应用的兼容缩放算进去、
     * 后者不认覆盖，两个都会让画面和触点在一条轴上一起偏。
     *
     * @return null = 探不到（Shizuku 没就绪）
     */
    private int[] probeSrcSize() {
        try {
            if (ShellRunner.isReady()) {
                return ExtScreen.injectionSize(this, ExtScreen.INNER_ID);
            }
        } catch (Throwable t) {
            Log.w(TAG, "探内屏尺寸失败: " + t);
        }
        return null;
    }

    /**
     * 按内屏比例把画面摆好（等比缩放 + 居中）。
     *
     * <p>TextureView 默认把画面<b>拉伸铺满</b>整块视图 —— 内屏是竖条，
     * 铺到横着的副屏上就整个变形了，触点也跟着偏。这里改成按短边缩放、多出来的留黑。
     *
     * <p>摆好之后"画面内的位置"与"内屏上的位置"只差一个纯比例，
     * 这正是 {@link #srcX} 一个除法就够的原因。
     */
    private void fitMirror() {
        final int[] wh = srcSize;
        if (wh == null || wh[0] <= 0 || mirrorView == null || mirrorBox == null) {
            return;
        }
        int bw = mirrorBox.getWidth(), bh = mirrorBox.getHeight();
        if (bw <= 0 || bh <= 0) {
            return;
        }
        /*
         * ⚠ 框不能大过窗口真正给的那块地方：布局跑到一半时量出来的框会虚高
         * （v4.32 撞上过：框 1496，本页实际只有 1296），照虚高的框摆，画面右边一截
         * 露不出去，看着就是"没铺满"，触点也跟着偏。拿 **窗口内容**（decorView）当上限 ——
         * 稳态下两者本来就相等，只有布局中途才拦得住。
         *
         * ⚠ 别拿 getResources().getDisplayMetrics() 当上限（v4.33 改掉）：它扣掉的那点数
         * 跟窗口内容对不上（那块屏上是 1242 对 1308），会把画面白白压矮一截。
         */
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int capW = 0, capH = 0;
        if (getWindow() != null && getWindow().getDecorView() != null) {
            capW = getWindow().getDecorView().getWidth();
            capH = getWindow().getDecorView().getHeight();
        }
        if (capW > 0 && bw > capW) {
            Log.w(TAG, "框比窗口宽（" + bw + " > " + capW + "），按窗口算");
            bw = capW;
        }
        if (capH > 0 && bh > capH) {
            Log.w(TAG, "框比窗口高（" + bh + " > " + capH + "），按窗口算");
            bh = capH;
        }
        float scale = Math.min((float) bw / wh[0], (float) bh / wh[1]);
        int w = Math.max(1, Math.round(wh[0] * scale));
        int h = Math.max(1, Math.round(wh[1] * scale));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mirrorView.getLayoutParams();
        if (lp.width == w && lp.height == h) {
            // 尺寸没变，但缓冲区不一定跟上了（页面刚起那会儿就是）—— 补一次再走
            syncSurfaceSize();
            return;
        }
        /*
         * ⚠ 这行是"铺没铺满"的唯一硬数据 —— 之前只看截图估，估出来的数跟日志对不上，
         * 白绕了一圈。框、内屏、算得的画面三个数一起打出来，一读就知道是谁不对。
         */
        Log.i(TAG, "摆画面：框 " + bw + "×" + bh + " · 内屏 " + wh[0] + "×" + wh[1]
                + " · 画面 " + w + "×" + h + "（scale=" + scale + "）");
        /*
         * ⚠ 光有上面那三个数还不够：v4.32 那回画面按框摆得好好的，屏幕上却只占一块 ——
         * 查到最后是"框"跟"窗口真正给的空间"对不上。窗口尺寸、框在屏幕上的落点、
         * 本页的显示尺寸一起打出来，一读就知道是谁不对。
         */
        int[] loc = new int[2];
        if (mirrorBox != null) {
            mirrorBox.getLocationOnScreen(loc);
        }
        Log.i(TAG, "环境：窗 " + getWindow().getDecorView().getWidth()
                + "×" + getWindow().getDecorView().getHeight()
                + " · 框在屏 " + loc[0] + "," + loc[1]
                + " · 本页 " + dm.widthPixels + "×" + dm.heightPixels + " d=" + dm.density
                + " · 放大 " + zoomS + " · 全屏 " + fullscreen);
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.CENTER;
        mirrorView.setLayoutParams(lp);
        final int fw = w, fh = h;
        mirrorView.post(() -> {
            if (mirrorView != null) {
                Log.i(TAG, "画面到位：布 " + fw + "×" + fh + " · 实 "
                        + mirrorView.getWidth() + "×" + mirrorView.getHeight()
                        + " 左上 " + mirrorView.getLeft() + "," + mirrorView.getTop());
                syncSurfaceSize();
            }
        });
        /*
         * 视图尺寸变了，之前那套放大是按旧尺寸算的（pivot 在旧中心、平移也是照旧框钳的）——
         * 留着它会出现"画面明明没放大、边上却缺一块"。重设一次最省心
         * （用户重新捏一下就是了）。
         */
        if (zoomS != 1f || zoomTx != 0f || zoomTy != 0f) {
            resetZoom();
        }
    }

    /**
     * 把「默认缓冲区尺寸」压成**虚拟显示的尺寸**（v4.35 修的就是这里）。
     *
     * <p>⚠ 这个尺寸要的是 {@code srcSize}（虚拟显示多大），**不是视图多大**。
     * 系统往缓冲区写画面时不缩放、按 1:1 从左上角写：缓冲区比虚拟显示大，
     * 右边和下边就是一块永远填不上的黑 —— 缓冲区再大也没用，现象正是"没铺满"。
     * 写满缓冲区之后，视图会自己把它拉满，那才是"画面显示成多大"该操心的地方。
     *
     * <p>⚠ 两个调用时机都不能少：TextureView 在视图尺寸变化时会自己把缓冲区设回
     * <b>视图</b>尺寸，所以 {@code onSurfaceTextureSizeChanged} 里要再压一次；
     * 页面刚起、那条回调不来时，由 {@link #attachIfPossible()} 补上。
     */
    private void syncSurfaceSize() {
        if (mirrorView == null) {
            return;
        }
        SurfaceTexture tex = mirrorView.getSurfaceTexture();
        if (tex == null) {
            return;
        }
        int[] wh = srcSize;
        int w = (wh != null && wh[0] > 0) ? wh[0] : mirrorView.getWidth();
        int h = (wh != null && wh[1] > 0) ? wh[1] : mirrorView.getHeight();
        if (w > 0 && h > 0) {
            tex.setDefaultBufferSize(w, h);
            Log.i(TAG, "缓冲区 " + w + "×" + h
                    + " · 视图 " + mirrorView.getWidth() + "×" + mirrorView.getHeight());
        }
    }

    /** 画面就绪且已授权 → 把画面贴上去 */
    private void attachIfPossible() {
        CastService s = CastService.get();
        if (s == null || surface == null || !surface.isValid()) {
            return;
        }
        // 贴之前先把缓冲区尺寸定死：系统一开始写，尺寸就不好再改了
        syncSurfaceSize();
        fitMirror();
        s.attach(surface);
    }

    private void releaseSurface() {
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
            surface = null;
        }
    }

    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    releaseSurface();
                    surface = new Surface(st);
                    Log.i(TAG, "画面就绪 " + w + "×" + h);
                    attachIfPossible();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    /*
                     * ⚠ 这个回调来之前，TextureView 已经把缓冲区尺寸改成**视图**尺寸了 ——
                     * 那不是我们要的（见 syncSurfaceSize 上那段），这里再压回去。
                     * 点全屏正是走这条路：视图一变大，缓冲区要是跟着变大，画面就缩在左上角一块。
                     */
                    syncSurfaceSize();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    CastService s = CastService.get();
                    if (s != null) {
                        // 先把画面从虚拟显示上摘下来再放 Surface，别让虚拟显示指着块野内存
                        s.detach();
                    }
                    releaseSurface();
                    return true;
                }

                /**
                 * 每来一帧画面都会被叫一次 —— 拿它当帧率表，用来把「卡」拆成画面卡还是操作卡
                 * （这里每秒只有几帧 = 画面卡；这里 60fps 却拖不动 = 操作卡）。
                 */
                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    frameN++;
                    long now = SystemClock.uptimeMillis();
                    if (frameAt == 0L) {
                        frameAt = now;
                    } else if (now - frameAt >= 1000L) {
                        Log.i(TAG, "镜像帧率 " + frameN + " fps");
                        frameN = 0;
                        frameAt = now;
                    }
                }
            };

    // ------------------------------------------------------------------ 触摸 → 内屏

    /**
     * 画面上的触摸，换算成内屏坐标注进去。
     *
     * <p>⚠ 没投屏时直接返回 false：那时候画面是黑的，注进去等于"往看不见的地方点"，
     * 用户看到的是"我明明点了却没反应"，很难查。
     */
    private boolean onMirrorTouch(View v, MotionEvent e) {
        if (pulling) {
            // 正在"从顶上往下拉"（拉那条退出全屏）：这一整笔都不算操作画面，一个事件都不注
            return true;
        }
        if (!CastService.ready()) {
            return false;
        }
        float tx = e.getX(), ty = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                /*
                 * 诊断（v473）：一次触摸只在按下这一刻打一条，把"触点 / 画面 / 内屏"三个数
                 * 摆在一起 —— 用户报"全屏、且没勾分辨率重设时点不准"，光看注入坐标判不出
                 * 是画面比例不对还是换算不对；有这三个数，配上同一次触摸的
                 * "点(宽限内抬手) x,y"，一读就知道是哪一个。
                 */
                Log.i(TAG, "触点 v=" + (int) tx + "," + (int) ty + " · 画面 "
                        + (mirrorView == null ? 0 : mirrorView.getWidth()) + "×"
                        + (mirrorView == null ? 0 : mirrorView.getHeight()) + " · 内屏 "
                        + (srcSize == null ? "?" : srcSize[0] + "×" + srcSize[1]));
                downX = tx;
                downY = ty;
                lastX = tx;
                lastY = ty;
                downAt = SystemClock.uptimeMillis();
                moved = false;
                pinch = false;
                pinchEnd = false;
                downSent = false;
                /*
                 * ⚠ 这里**先不发**按下，等 PRESS_GRACE_MS —— 万一下一根手指马上落下来，
                 * 这次触摸就是捏合，一个事件都不该发（理由见 PRESS_GRACE_MS）。
                 */
                armPress();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                /*
                 * 第二根手指落下：整次触摸就此定性为「多指」，从此一个事件都不注。
                 * 还在宽限窗口里 ⇒ 按下压根没发出去，零误触；已经发出去了 ⇒ 只能抬起来，
                 * 那一下在内屏看来仍是一次点击（唯一剩下的误触窗口，见 PRESS_GRACE_MS）。
                 */
                if (e.getPointerCount() >= 2) {
                    pinchEnd = true;
                    cancelPress();
                    if (downSent) {
                        injAbort();
                        downSent = false;
                    }
                    if (zoomOn()) {
                        startPinch(e);
                    }
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pinch) {
                    movePinch(e);
                    return true;
                }
                // 多指手势（没勾「允许放大」时也一样）：不注、不点
                if (pinchEnd) {
                    return true;
                }
                lastX = tx;
                lastY = ty;
                /*
                 * 宽限窗口里就动过 touch slop ⇒ 这是拖动、不是捏合，当场按在起始点上。
                 * ⚠ 这一条事件刻意不再补位置：注入线程只认"最新位置"，
                 * 先写 press 再写 move 会被它合成"按在移动后的点上"，
                 * 拖动的起点就跑掉了。
                 */
                if (!downSent && !moved
                        && Math.hypot(tx - downX, ty - downY) > slop) {
                    moved = true;
                    flushPress();
                    return true;
                }
                if (downSent) {
                    injMove(srcX(tx, ty), srcY(tx, ty));
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // 捏合中途抬掉一根手指：剩下的那根不要再当拖动用（指针编号会错位）
                if (pinch && e.getPointerCount() <= 2) {
                    pinch = false;
                    pinchDist = 0f;
                }
                return true;

            case MotionEvent.ACTION_UP:
                cancelPress();
                if (pinch || pinchEnd) {
                    pinch = false;
                    pinchEnd = false;
                    pinchDist = 0f;
                    Log.i(TAG, "缩放结束 → " + zoomText());
                    return true;      // 这一下是"放下手指"，不是点击
                }
                if (downSent) {
                    injRelease(srcX(tx, ty), srcY(tx, ty));
                    Log.d(TAG, (moved ? "拖到 " : "点 ") + srcX(tx, ty) + "," + srcY(tx, ty));
                } else {
                    /*
                     * 整次触摸都没动过、也没等来第二根手指 ⇒ 就是一次点击。
                     * 宽限窗口里一次都没发过，现在补一条完整的点击（DOWN + 短停 + UP）。
                     */
                    injTap(srcX(tx, ty), srcY(tx, ty));
                    Log.d(TAG, "点(宽限内抬手) " + srcX(tx, ty) + "," + srcY(tx, ty));
                }
                flashPath();
                return true;

            case MotionEvent.ACTION_CANCEL:
                // 手指被系统抢走（来通知 / 划到边缘）：必须收尾，
                // 不然内屏那边一直维持"按下"，被拖的东西卡在半路
                cancelPress();
                pinch = false;
                pinchEnd = false;
                if (downSent) {
                    injAbort();
                    downSent = false;
                }
                return true;

            default:
                return false;
        }
    }

    // ------------------------------------------------------ 按下之前的那段宽限

    /** 宽限到点：真把"按下"发去内屏（按在手指此刻的位置上） */
    private final Runnable pressTask = new Runnable() {
        @Override
        public void run() {
            if (downSent || pinchEnd || isFinishing() || isDestroyed()
                    || !CastService.ready()) {
                return;
            }
            downSent = true;
            injPress(srcX(lastX, lastY), srcY(lastX, lastY));
            Log.d(TAG, "按下(宽限到) " + srcX(lastX, lastY) + "," + srcY(lastX, lastY));
        }
    };

    /** 按下之后先按兵不动，等这么久（见 {@link #PRESS_GRACE_MS}） */
    private void armPress() {
        ui.removeCallbacks(pressTask);
        ui.postDelayed(pressTask, PRESS_GRACE_MS);
    }

    private void cancelPress() {
        ui.removeCallbacks(pressTask);
    }

    /** 判定为拖动：立刻按在起始点上，不等宽限走完 */
    private void flushPress() {
        cancelPress();
        if (downSent) {
            return;
        }
        downSent = true;
        injPress(srcX(downX, downY), srcY(downX, downY));
        Log.d(TAG, "按下(拖动起手) " + srcX(downX, downY) + "," + srcY(downX, downY));
    }

    // ------------------------------------------------------------------ 放大（双指捏合）

    /**
     * 开始捏合：记下两指的距离与中点。
     *
     * <p>中点也要记 —— 捏合时手指会同时挪动（想放大某一块），
     * 只按距离缩放等于"以画面中心放大"，用户指哪儿偏哪儿。
     */
    private void startPinch(MotionEvent e) {
        pinch = true;
        pinchDist = spread(e);
        pinchX = (e.getX(0) + e.getX(1)) / 2f;
        pinchY = (e.getY(0) + e.getY(1)) / 2f;
    }

    /**
     * 捏合中：按"两指距离变了多少倍"缩放，按"中点挪了多少"平移。
     *
     * <p>每次都<b>在现有矩阵上再叠一次</b>，不是从零重算 —— 捏合是连续动作，累乘才跟手。
     * 缩放倍数、以及"画面不许缩得比框还小/不许拖出黑边"都在 {@link #clampZoom} 里把住。
     */
    private void movePinch(MotionEvent e) {
        float d = spread(e);
        if (pinchDist <= 0f || d <= 0f) {
            startPinch(e);
            return;
        }
        float f = d / pinchDist;
        float cx = (e.getX(0) + e.getX(1)) / 2f;
        float cy = (e.getY(0) + e.getY(1)) / 2f;

        int vw = mirrorView == null ? 0 : mirrorView.getWidth();
        int vh = mirrorView == null ? 0 : mirrorView.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        final float s0 = zoomS;
        float s = Math.min(ZOOM_MAX, Math.max(1f, s0 * f));
        /*
         * 两项要分开算（这里是最容易错的地方）：
         *   ① 不动点补偿：缩放是绕"内容中心"发生的，而用户想让它绕"两指中点"发生。
         *      中点在视图坐标里就是 pinchX，所以补 (s0 − s) × (中点 − 内容中心)。
         *   ② 跟着中点走：中点在框坐标里挪了 s0 × (视图坐标位移)
         *      —— 视图坐标 1 个单位在框里占 s0 个像素。
         */
        float nx = zoomTx + (s0 - s) * (pinchX - vw / 2f) + s0 * (cx - pinchX);
        float ny = zoomTy + (s0 - s) * (pinchY - vh / 2f) + s0 * (cy - pinchY);
        applyZoom(s, nx, ny);

        long now = SystemClock.uptimeMillis();
        if (now - zoomLogAt >= 200L) {
            zoomLogAt = now;
            // 两指中点压住的那个内容点应该**一直不变** —— 这一行就是它的读数
            Log.i(TAG, "捏合 " + zoomText() + " · 两指中点 → 内屏 "
                    + srcX(cx, cy) + "," + srcY(cx, cy));
        }

        pinchDist = d;
        pinchX = cx;
        pinchY = cy;
    }

    /** 两指之间的距离（不足两根手指返回 0） */
    private static float spread(MotionEvent e) {
        if (e.getPointerCount() < 2) {
            return 0f;
        }
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    /**
     * 把倍数与平移钳到合法范围，然后交给 View 自己去缩放/平移。
     *
     * <p>钳的规则：倍数 1×~{@link #ZOOM_MAX}×，且画面<b>不许露缝</b>（内容在这条轴上够长
     * 就卡在框内，不够长就居中，见 {@link #clampAxis}）。
     *
     * <p>⚠ setScale/setTranslation 之后<b>不用</b>再管触点：框架派发之前会用同一个矩阵反算回
     * 子视图坐标，所以 {@code e.getX()} 天然就是内容坐标。
     */
    private void applyZoom(float s, float tx, float ty) {
        int vw = mirrorView == null ? 0 : mirrorView.getWidth();
        int vh = mirrorView == null ? 0 : mirrorView.getHeight();
        int bw = mirrorBox == null ? 0 : mirrorBox.getWidth();
        int bh = mirrorBox == null ? 0 : mirrorBox.getHeight();
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) {
            // 布局还没跑完（页面刚起）：先记成"没放大"，等 fitMirror 之后再来
            zoomS = 1f;
            zoomTx = 0f;
            zoomTy = 0f;
            return;
        }
        zoomS = Math.min(ZOOM_MAX, Math.max(1f, s));
        zoomTx = clampAxis(tx, zoomS, vw, bw, mirrorView.getLeft());
        zoomTy = clampAxis(ty, zoomS, vh, bh, mirrorView.getTop());
        mirrorView.setPivotX(vw / 2f);
        mirrorView.setPivotY(vh / 2f);
        mirrorView.setScaleX(zoomS);
        mirrorView.setScaleY(zoomS);
        mirrorView.setTranslationX(zoomTx);
        mirrorView.setTranslationY(zoomTy);
    }

    /**
     * 一条轴上的钳位：内容盖得住框就只允许中心落在 [框 − 内容/2, 内容/2] 里（四边不露缝）；
     * 盖不住（没勾「重设」时画面本来就是细细一条）就强制居中，别歪在一边。
     *
     * @param t   想平移多少（框坐标）
     * @param s   放大倍数
     * @param vs  子视图在这条轴上的尺寸（未放大）
     * @param bs  框在这条轴上的尺寸
     * @param off 子视图在这条轴上相对框的布局位置
     */
    private static float clampAxis(float t, float s, int vs, int bs, int off) {
        float span = s * vs;
        float c = off + vs / 2f + t;
        if (span >= bs) {
            if (c < bs - span / 2f) {
                c = bs - span / 2f;
            }
            if (c > span / 2f) {
                c = span / 2f;
            }
        } else {
            c = bs / 2f;
        }
        return c - off - vs / 2f;
    }

    /** 摆回原样（关掉开关 / 换一次投屏 / 画面重新摆过时） */
    private void resetZoom() {
        pinch = false;
        pinchEnd = false;
        pinchDist = 0f;
        applyZoom(1f, 0f, 0f);
    }

    /** 此刻的放大状态（打日志用） */
    private String zoomText() {
        return String.format(java.util.Locale.US, "%.2f× 平移 %.0f,%.0f",
                zoomS, zoomTx, zoomTy);
    }

    // ------------------------------------------------------------------ 注入线程

    /**
     * 起注入线程 —— 那一套（独立线程 + 只认最新位置 + 直注/输入兜底）在
     * {@link TouchInjector} 里，两页共用。
     *
     * <p>这里补按住时长给 0：本页是"手指按下就往内屏发 DOWN"，目标应用收到的时刻
     * 就是用户按下的时刻，不需要替它补（触控板那边要，理由见 TouchInjector）。
     */
    private void startInjector() {
        if (inj != null) {
            return;
        }
        inj = new TouchInjector("ExtCastInject", ExtScreen.INNER_ID, 0L);
        inj.setStatus((direct, detail) -> {
            directPath = direct;
            ui.post(this::flashPath);
        });
        inj.start();
    }

    private void stopInjector() {
        if (inj != null) {
            inj.stop();
            inj = null;
        }
    }

    /** 手指按下了（UI 线程，只写格子） */
    private void injPress(int x, int y) {
        if (inj != null) {
            inj.press(x, y);
        }
    }

    /** 手指到了新位置（UI 线程，只写格子 —— 正在发的那个发不完就算了，绝不排队） */
    private void injMove(int x, int y) {
        if (inj != null) {
            inj.move(x, y);
        }
    }

    /** 补一次完整的点击（DOWN + 短停 + UP）—— 宽限窗口里一次都没发过时用它 */
    private void injTap(int x, int y) {
        if (inj != null) {
            inj.tap(x, y);
        }
    }

    /** 手指抬起（新位置和抬手一起交出去，让画面落在真正松开的那一点上） */
    private void injRelease(int x, int y) {
        if (inj != null) {
            inj.release(x, y);
        }
    }

    /** 这次触摸作废（被系统抢走 / 页面要走）—— 只抬手，不补位置 */
    private void injAbort() {
        if (inj != null) {
            inj.abort();
        }
    }

    /**
     * 画面上的位置 → 内屏坐标：视图坐标 ÷ 视图尺寸 × 内屏尺寸，永远是一条纯比例
     * （视图尺寸就是按内屏比例摆的，见 {@link #fitMirror}）。
     *
     * <p>⚠ 放大之后照样这么算：{@code e.getX()} 拿到的是子视图自己的坐标，框架派发之前
     * 已经用它自己的变换（pivot / scale / translation）反算回去了
     * （ViewGroup.transformPointToViewLocal），不用我们自己求逆矩阵。
     */
    private int srcX(float vx, float vy) {
        final int[] wh = srcSize;
        int w = mirrorView == null ? 0 : mirrorView.getWidth();
        if (wh == null || w <= 0) {
            return 0;
        }
        float u = Math.min(1f, Math.max(0f, vx / w));
        return Math.round(u * wh[0]);
    }

    private int srcY(float vx, float vy) {
        final int[] wh = srcSize;
        int h = mirrorView == null ? 0 : mirrorView.getHeight();
        if (wh == null || h <= 0) {
            return 0;
        }
        float u = Math.min(1f, Math.max(0f, vy / h));
        return Math.round(u * wh[1]);
    }

    // ------------------------------------------------------------------ 全屏

    /* ------------- 全屏里的唯一出口（v4.32） -------------
     * 全屏时页面上一个控件都没有，所以出口只留一条：手指<b>从画面最顶上往下拉</b>，
     * 拉出那条「退出全屏」。见 {@link #dispatchTouchEvent}。
     */

    /** 判"从顶上拉"的起手范围、以及要拉多远才算数：都是 24dp */
    private float pullDp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    /**
     * 全屏里从画面最顶上往下拉 → 把「退出全屏」滑出来。
     *
     * <p>⚠ 手势在这儿判，但事件照常往下发（不拦、不返回 true）：这么写画面上的点按拖动
     * 一切照旧，只是判成"下拉"的那一刻把已经按下去的那次注入收回来
     * （{@link #cancelPress} / {@link #injAbort}），免得"想拉出口、结果点到了内屏"。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (fullscreen && mirrorView != null) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pullDownY = e.getY();
                    pulling = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!pulling && pullDownY <= pullDp(24)
                            && e.getY() - pullDownY > pullDp(24)) {
                        pulling = true;
                        cancelPress();
                        injAbort();
                        showExitBar();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pulling = false;
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(e);
    }

    /** 把「退出全屏」滑出来（淡入一下，别一闪就出现） */
    private void showExitBar() {
        if (barExit == null || barExit.getVisibility() == View.VISIBLE) {
            return;
        }
        barExit.setVisibility(View.VISIBLE);
        barExit.setAlpha(0f);
        barExit.animate().alpha(1f).setDuration(140).start();
    }

    /** 收回「退出全屏」：刚进全屏、以及退出全屏时，它都该是收着的 */
    private void hideExitBar() {
        if (barExit != null) {
            barExit.setVisibility(View.GONE);
        }
    }

    /**
     * 全屏开关。
     *
     * <p>收起页面上所有控件（顶栏 / 勾选行 / 右侧那列 / 状态行）并清掉页边距，把整块屏让给画面；
     * 系统栏一并藏起来，画面才顶得到上下边（最上面那条挖孔带系统本来就不给用）。
     * 藏的时候留了"边上划一下唤出来"，所以全屏里一个控件都没有，也还退得出来。
     *
     * <p>⚠ 画面本身仍是<b>等比</b>缩放（见 {@link #fitMirror}）：比例对不上就留黑边，
     * <b>不拉伸</b>。想真铺满就勾「内屏分辨率重设」—— 它把内屏宽高比改成跟框一样，
     * 而框的尺寸全屏之后变了，所以这边要让它照新框重算一次。
     */
    private void setFullscreen(boolean on) {
        fullscreen = on;
        final int v = on ? View.GONE : View.VISIBLE;
        if (rowTop != null) {
            rowTop.setVisibility(v);
        }
        if (rowOptions != null) {
            rowOptions.setVisibility(v);
        }
        if (colOps != null) {
            colOps.setVisibility(v);
        }
        if (tvState != null) {
            tvState.setVisibility(v);
        }
        if (rootCast != null) {
            rootCast.setPadding(on ? 0 : padL, on ? 0 : padT, on ? 0 : padR, on ? 0 : padB);
        }
        /*
         * 画面那一行本来跟勾选行之间留了 5dp 缝 —— 全屏要的是"除了最上面那排刘海，
         * 整块屏都是画面"，这点缝也得去掉（v4.33）。
         */
        if (rowMain != null && rowMain.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) rowMain.getLayoutParams();
            mlp.topMargin = on ? 0 : marMainTop;
            rowMain.setLayoutParams(mlp);
        }
        // 进出全屏，出口条都该是收着的：刚进来要拉一下才出来，退出去它也没用了
        hideExitBar();
        applyFullscreenBars(on);
        applyCoverOverlays(!on);
        if (mirrorBox != null) {
            // 框变大了，画面要按新框重摆（还是等比 + 居中）
            mirrorBox.post(this::fitMirror);
        }
        /*
         * ⚠ 只有"还没投屏"时重设才有意义：虚拟显示是照建它那一刻的源尺寸算死的，
         * 投着屏再改内屏分辨率，画面比例当场就不对了（跟勾选项那一处同一个道理）。
         */
        if (cbResize != null && cbResize.isChecked() && !CastService.ready() && mirrorBox != null) {
            mirrorBox.post(this::resizeForOption);
        }
    }

    /**
     * 系统栏：全屏时藏起来，退出全屏放回去。
     *
     * <p>⚠ 全屏时不挂"边上划一下唤出来"（v4.32 撤掉）：出口已经换成"从画面顶上往下拉"
     * 那条「退出全屏」，留着它会先把下拉那一下抢走（先弹系统栏）；何况封面屏边上那条
     * 早被侧拉栏占了位置，本来就划不出来。
     */
    private void applyFullscreenBars(boolean on) {
        try {
            Window w = getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController c = w.getInsetsController();
                if (c == null) {
                    return;
                }
                /*
                 * ⚠ 不再挂「边上划一下唤出系统栏」（v4.32 撤掉）：用户要的出口是"顶部下拉"，
                 * 这个行为会把下拉那一下先抢走（先给他弹系统栏），两条手势打架；
                 * 何况封面屏边上那条早被侧拉栏占了位置，本来就划不出来。
                 */
                if (on) {
                    c.hide(WindowInsets.Type.systemBars());
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            } else if (on) {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "系统栏切换失败: " + t);
        }
    }

    /**
     * 外屏那两层覆盖窗（侧滑栏、手势条）跟着全屏走：全屏时摘掉，退出去按设置装回来。
     *
     * <p>为什么必须摘：它们的感应带贴在这块屏的边缘，触摸<b>先被那层窗口收走</b>，
     * 全屏里贴着边点画面就会被抢掉（用户实测）。
     *
     * <p>装回来走各自的 {@code sync} —— 它会照开关判断，没开的不会被装回来，
     * 所以这里不用记「原来是什么状态」。
     */
    private void applyCoverOverlays(boolean show) {
        try {
            if (!show) {
                CoverSidebar.remove();
                CoverRecentsGesture.remove();
                return;
            }
            AccessibilityService s = A11yInject.service();
            if (s == null) {
                // 无障碍服务没在跑，本来也没什么可装回去的
                return;
            }
            CoverSidebar.sync(s);
            CoverRecentsGesture.sync(s);
        } catch (Throwable t) {
            Log.w(TAG, "外屏覆盖层切换失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 界面

    @Override
    public void onCastState(boolean projecting, String why) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            updateUi(projecting, why);
            if (projecting) {
                castPending = false;    // 画面都建起来了，不必再挡着 onStop
                attachIfPossible();
            } else {
                // 不管是点「停止」停的、还是系统撤了授权停的，都从这儿收工 ——
                // 借来的内屏分辨率要在这一刻还回去
                restoreCastResize();
                /*
                 * 停了就退出全屏：全屏里画面就是全部内容，画面一没，整块屏只剩一片黑，
                 * 而那时候一个控件都没有，用户连"出了什么事"都看不到。
                 */
                if (fullscreen) {
                    setFullscreen(false);
                }
            }
        });
    }

    private void updateUi(boolean projecting, String why) {
        btnToggle.setText(projecting ? R.string.cast_stop : R.string.cast_start);
        tvIdle.setVisibility(projecting ? View.GONE : View.VISIBLE);
        if (why != null) {
            tvState.setText(why);
        } else if (projecting) {
            final int[] wh = srcSize;
            tvState.setText(getString(R.string.cast_state_fmt,
                    wh == null ? "?" : wh[0] + "×" + wh[1], pathText()));
        } else {
            tvState.setText(getString(R.string.cast_state_idle));
        }
        flashPath();
        fitMirror();
    }

    /**
     * 注射走的哪条通道（跟触控板右下角那行一个用处：解释"为什么这次不跟手"）。
     *
     * <p>v3.19 起这条路上只有两种可能：Shizuku 直注（快）或 {@code input} 命令（慢）。
     * 老版本显示的是"手势 / 输入"—— 那个手势链就是"拖完还在动"的元凶，已经不用了。
     */
    private String pathText() {
        /*
         * ⚠ 不能只看 directPath（那是"上一次真发成功过直注"的意思）——
         * 页面刚起来、还没碰过画面时它还是假，右下角就先写着「输入」，
         * 用户会以为又在走慢路。没发过就按"探到的通道"说，跟触控板页一个写法
         * （见 TouchpadActivity.pathText）。
         */
        return getString(directPath || TouchInject.ready()
                ? R.string.cast_path_direct : R.string.cast_path_input);
    }

    private void flashPath() {
        if (tvPath != null) {
            tvPath.setText(pathText());
        }
    }
}
