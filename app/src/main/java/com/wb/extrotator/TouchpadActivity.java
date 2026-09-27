package com.wb.extrotator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * 副屏触控板（伪应用）—— 把封面屏当笔记本触控板用，光标画在<b>内屏</b>上。
 *
 * <p>跟「把屏幕缩小了看」的老做法相反：面板上什么都不显示，手指盲摸，光标出现在你正在看的
 * 那块屏上（内屏，外接显示器上就是它镜像出去的那块）。
 *
 * <p>光标是自己挂的一层应用悬浮窗（见 {@link CursorOverlay}），需要「显示在其他应用上层」权限，
 * 没权限时本页会弹一次授权引导。系统光标画不出来 —— 只有真实鼠标设备才点亮系统指针，
 * 应用侧注入不点亮它，非 root 无解。
 *
 * <p>⚠ <b>目标屏就是本页所在屏时要掐掉</b>（见 {@link #blocked()}），不掐会自己点自己。
 *
 * <p>触摸注入走 {@link TouchInjector}：一次 Shizuku Binder 调用送进目标屏，不起进程、一发即走；
 * 注入跑在专用线程上，主线程只写「最新位置」，所以不积压。老版本的无障碍手势链会积压
 * （系统那边一条条串着放，派发快了就排队，松手后画面还在动），「按住」还得靠心跳续笔画，
 * 不然系统会自动抬手。直注不通（没装 Shizuku）时 {@link TouchInjector} 自己退回 {@code input}，
 * 界面右下角那行写着此刻走的哪条。
 */
public class TouchpadActivity extends Activity implements TouchpadView.Listener {

    private static final String TAG = "ExtTouchpad";

    /**
     * 「按住」至少要按这么久（底栏「长按」按钮的一次性时长，也是拖动时的补齐下限）。
     *
     * <p>拖动也要它的原因：本页是「本地判出长按（500ms）之后才把 DOWN 发下去」，目标应用从收到
     * DOWN 起才数自己的长按计时 —— 用户按 0.6 秒的本意是长按，到那边只剩 0.1 秒，会被当成一次
     * 普通点击。缺的那部分由 {@link TouchInjector} 在抬手前补在注入线程上，不占页面生命周期。
     */
    private static final int HOLD_MS = 700;

    private TouchpadView pad;
    private TextView tvTitle;
    private TextView tvHint;
    private Button btnSens;

    /** 内屏（只需要它的 displayId 发指令；坐标空间由 {@link CursorOverlay} 提供） */
    private ExtScreen.Dev dev;

    private boolean started;
    /** 按下时光标在哪 —— 拖拽的起点 */
    private int downCx, downCy;
    /** 此刻是不是"按住 / 拖动"中（已经把 DOWN 发下去了，还欠一个 UP） */
    private boolean heldDown;
    /** 光标没挪动就不必再发同一条 MOVE */
    private int lastMoveX = -1, lastMoveY = -1;
    /** 注入线程 —— 跟投屏页共用一套（见 {@link TouchInjector}） */
    private TouchInjector inj;
    /** 此刻实际在走的通道（给右下角那行说实话用） */
    private volatile boolean padDirect;
    /** 主线程 Handler（注入线程的节奏回调要回到这儿刷界面） */
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ext_touchpad);
        // 手里拿着手机盲摸，封面屏灭了就没得摸
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        /*
         * ⚠⚠ 这里**不能**挂 FLAG_NOT_FOCUSABLE。
         *
         * 本页通常是封面屏上唯一的应用窗口，给它挂上之后封面屏就永远没有「持有焦点的窗口」；
         * 系统每次派发输入都在等一个不存在的接收者，超时后报 ANR、把后面的触摸整批丢掉 ——
         * 症状是**第一次点有反应、第二次开始怎么点都没动静**（events 日志里是
         * {@code am_anr: Input dispatching timed out (Application does not have a focused window)}）。
         *
         * 它当初是为了防「注入的键喂回自己」：
         * 本页真落在目标屏上时由 {@link #onTargetScreen()} 兜底
         * —— 本页真落在目标屏上时干脆不发。所以这一条可以去掉了。
         */

        pad = findViewById(R.id.padView);
        tvTitle = findViewById(R.id.tvPadTitle);
        tvHint = findViewById(R.id.tvPadHint);
        btnSens = findViewById(R.id.btnPadSens);

        pad.setListener(this);
        pad.setCenterHint(getString(R.string.ext_pad_center_hint));

        ExtUi.info(this, R.id.iPadTop, R.string.ext_pad_title, R.string.info_ext_pad);

        findViewById(R.id.btnPadClose).setOnClickListener(v -> finish());
        btnSens.setOnClickListener(v -> {
            int next = (ExtPrefs.sensIndex(this) + 1) % ExtPrefs.SENS_ALL.length;
            ExtPrefs.setSensIndex(this, next);
            syncSensButton();
            flash(getString(R.string.ext_flash_sens, ExtPrefs.SENS_ALL[next]));
        });

        // 底栏：四个"只按一下"的动作，省得每次都要先把光标挪过去
        findViewById(R.id.btnPadClick).setOnClickListener(v -> tapAtCursor());
        findViewById(R.id.btnPadHold).setOnClickListener(v -> holdAtCursor());
        findViewById(R.id.btnPadBack).setOnClickListener(v -> sendKey("KEYCODE_BACK"));
        findViewById(R.id.btnPadHome).setOnClickListener(v -> sendKey("KEYCODE_HOME"));
        // 翻页 —— 在应用列表里找东西时不用再拖着图标蹭到屏幕边缘等它翻
        findViewById(R.id.btnPadPrevPage).setOnClickListener(v -> pageFlip(-1));
        findViewById(R.id.btnPadNextPage).setOnClickListener(v -> pageFlip(+1));

        // 无障碍服务没开时，触摸只能走 input 慢路（见 A11yInject）——
        // 这行字就是"怎么才能变快"的入口，点了直接跳系统无障碍设置页。
        tvHint.setOnClickListener(v -> {
            if (TouchInject.ready() || A11yInject.ready()) {
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                ExtUi.toast(this, getString(R.string.ext_pad_a11y_toast));
            } catch (Throwable e) {
                Log.w(TAG, "打不开无障碍设置页: " + e);
            }
        });

        syncSensButton();
        resolve();
    }

    private void syncSensButton() {
        btnSens.setText(getString(R.string.ext_sens_btn, ExtPrefs.sens(this)));
    }

    // ------------------------------------------------------------------ 起停

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        // 这一页在用的时候，别让本应用的旋转 / 分辨率那套来动显示设置（见 ExtControlMode）——
        // 否则用户看着外接屏上的镜像，画面会被当场拧 90°。
        ExtControlMode.on();
        // 范围被系统侧复核过（旋转 / wm 覆盖变了）要刷新右下角那句读数
        CursorOverlay.setBoundsListener(this::syncCursorText);
        // 用户可能是刚从系统设置里把无障碍开关打开回来的 —— 回到本页重判一次，
        // 免得提示行还挂着"走慢路"那句话
        if (tvHint != null) {
            syncCursorText();
            tvHint.setText(defaultHint());
        }
        if (dev != null) {
            startCursor();
            ensureInjector();
        }
    }

    @Override
    protected void onStop() {
        started = false;
        // 还按着就走人（例如用户直接点了「关闭」）必须把 UP 补上，
        // 否则目标屏那边一直维持按下状态，拖到一半卡住不放
        releaseHold();
        // 注入线程给这一页送终（它自己会把还按着的手指抬起来，别让目标屏卡在"按下"）
        stopInjector();
        // 光标跟着这一页走：离开这页就没必要还在屏上杵着
        CursorOverlay.hide();
        CursorOverlay.setBoundsListener(null);
        ExtControlMode.off();
        super.onStop();
    }

    private void resolve() {
        tvHint.setText(R.string.ext_finding);
        ExtUi.resolve(this, d -> {
            dev = d;
            tvTitle.setText(getString(R.string.ext_title_inner, getString(R.string.ext_pad_title)));
            if (dev == null) {
                tvHint.setText(R.string.ext_pad_no_target);
                pad.setInfoText("");
                return;
            }
            if (started) {
                startCursor();
                ensureInjector();
            }
        });
    }

    // ------------------------------------------------------------------ 注入线程

    /**
     * 按需建注入线程。
     *
     * <p>为什么不跟别的字段一起在 onCreate 里建：目标屏要等 {@link #resolve()} 回来才知道
     * 是哪一块（接了便携屏时是外接屏，不只是内屏），而 {@link TouchInjector} 的
     * 目标屏是构造时定死的 —— 建早了就注错屏。
     */
    private void ensureInjector() {
        if (inj != null || dev == null) {
            return;
        }
        // 第三个参数是"抬手前至少按多久"，见 HOLD_MS 那段
        inj = new TouchInjector("ExtPadInject", dev.id, HOLD_MS);
        inj.setStatus((direct, detail) -> {
            padDirect = direct;
            ui.post(this::syncCursorText);
        });
        inj.start();
    }

    private void stopInjector() {
        if (inj != null) {
            inj.stop();
            inj = null;
        }
    }

    /** 划一条直线（滚动 / 翻页用）—— 优先直注，直注不通才交给 ExtScreen 走老路 */
    private void swipe(int x0, int y0, int x1, int y1, int ms) {
        ensureInjector();
        if (inj != null) {
            inj.swipe(x0, y0, x1, y1, ms);
        } else {
            ExtScreen.swipe(dev.id, x0, y0, x1, y1, ms);
        }
    }

    private void startCursor() {
        if (!Settings.canDrawOverlays(this)) {
            needOverlayPermission();
            return;
        }
        if (!CursorOverlay.show(getApplicationContext(), ExtScreen.INNER_ID)) {
            tvHint.setText(R.string.ext_pad_cursor_fail);
            Log.w(TAG, "光标没挂上");
            return;
        }
        syncCursorText();
        tvHint.setText(defaultHint());
    }

    /**
     * 没有悬浮窗权限时的引导。
     *
     * <p>为什么非要这个权限：光标是挂在目标屏上的悬浮窗，系统对这个窗口类型的门槛
     * 就是它。没有别的画法 —— Presentation 走不通（只认辅助屏），
     * 拿 shell 身份挂窗也走不通（见 {@link CursorOverlay} 的类注释）。
     */
    private void needOverlayPermission() {
        tvHint.setText(R.string.ext_pad_need_overlay);
        pad.setInfoText("");
        new AlertDialog.Builder(this)
                .setTitle(R.string.ext_pad_title)
                .setMessage(R.string.info_ext_pad_overlay)
                .setPositiveButton(R.string.ext_grant, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable t) {
                        Log.w(TAG, "打不开悬浮窗设置页: " + t);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 提示行的默认文案 —— 无障碍服务没开时说清楚"现在走的是慢路、点一下能开"。
     *
     * <p>为什么非要摆在界面上：v3.7 的丝滑拖动跑在无障碍服务的 dispatchGesture 上，
     * 而真机查下来这个服务默认是关着的（系统里 enabled_accessibility_services 没它）。
     * 如果不提示，用户只会觉得"还是那么卡"，根本想不到去开一个开关。
     */
    private CharSequence defaultHint() {
        // 快路有两条：Shizuku 直注（首选）和无障碍手势。两条都不通才是"慢路"。
        if (TouchInject.ready() || A11yInject.ready()) {
            tvHint.setTextColor(0xFF6C7889);
            return getString(R.string.ext_pad_hint);
        }
        tvHint.setTextColor(0xFFE8B44A);
        return getString(R.string.ext_pad_need_a11y);
    }

    /** 面板右下角那行：光标现在在哪、目标屏多大、触摸走哪条路 */
    private void syncCursorText() {
        int[] b = CursorOverlay.bounds();
        if (b == null) {
            return;
        }
        pad.setInfoText(getString(R.string.ext_pad_info_fmt,
                CursorOverlay.x(), CursorOverlay.y(), b[0], b[1], pathText()));
    }

    /**
     * 触摸现在走的是哪条通道。
     *
     * <p>三种可能，从快到慢：<b>直注</b>（Shizuku Binder，一发即走）→
     * <b>手势</b>（无障碍 dispatchGesture，会排队）→ <b>输入</b>（input 命令，一条 47ms）。
     * 用户一看这行就知道"这次为什么卡"，省得去猜是不是坏了。
     */
    private String pathText() {
        if (padDirect || TouchInject.ready()) {
            return getString(R.string.ext_path_direct);
        }
        return getString(A11yInject.ready() ? R.string.ext_path_gesture : R.string.ext_path_input);
    }

    // ------------------------------------------------------------------ 手势

    @Override
    public void onMove(float dx, float dy) {
        if (!CursorOverlay.isOn()) {
            return;
        }
        float s = ExtPrefs.sens(this);
        CursorOverlay.move(
                CursorOverlay.x() + Math.round(dx * s),
                CursorOverlay.y() + Math.round(dy * s));
        syncCursorText();
    }

    @Override
    public void onPress(boolean down) {
        CursorOverlay.setHot(down);
        if (down) {
            downCx = CursorOverlay.x();
            downCy = CursorOverlay.y();
        }
    }

    @Override
    public void onTap() {
        tapAtCursor();
    }

    /**
     * 长按判出 —— 把 DOWN 发下去，从这一刻起目标屏就处于"按住"状态。
     *
     * <p>v3.6 之前这一下是憋到松手才发的（发一条 `input swipe`），所以手指划过去的
     * 过程里屏幕上毫无反应，用户的原话是"得我松手后才能滑动"。
     */
    @Override
    public void onHoldStart() {
        if (blocked()) {
            return;
        }
        downCx = CursorOverlay.x();
        downCy = CursorOverlay.y();
        heldDown = true;
        lastMoveX = lastMoveY = -1;
        /*
         * 一条直注 DOWN —— 从这一刻起目标屏就是「按住」状态。
         *
         * 老版本在这儿开一条无障碍手势链（willContinue 续笔画）：系统那边一条条串着放，派发快了就
         * 积压，而且「按住不动」要靠心跳补笔画才不会被系统自动抬手 —— 拖到一半东西掉了，多半就是
         * 心跳没接上。直注按住多久就是按住多久，中间一条多余的事件都不用发。
         */
        ensureInjector();
        if (inj != null) {
            inj.press(downCx, downCy);
        } else {
            ExtScreen.motionDown(dev.id, downCx, downCy);
        }
        flash(getString(R.string.ext_flash_hold, downCx, downCy));
    }

    /** 按住状态下手指在动 —— 把最新位置透给目标屏，东西就跟着走 */
    @Override
    public void onDragMove(float dx, float dy) {
        if (!heldDown || blocked()) {
            return;
        }
        int x = CursorOverlay.x(), y = CursorOverlay.y();
        if (x == lastMoveX && y == lastMoveY) {
            return;   // 光标没挪动就没必要发
        }
        lastMoveX = x;
        lastMoveY = y;
        if (inj != null) {
            /*
             * 只写格子，剩下的交给注入线程 —— **这里没有节流**。
             * 老版本要节流（MOVE_GAP_MS）是因为那条路一条命令几十毫秒，发快了就排长队；
             * 直注一次不到 1ms，禁掉节流反而更跟手。排不过来时落后的位置由它自己丢掉，
             * 画面追的是光标现在在哪。
             */
            inj.move(x, y);
        } else {
            ExtScreen.motionMove(dev.id, x, y);
        }
    }

    /** 松手 —— 这次按住 / 拖动收尾 */
    @Override
    public void onHoldEnd() {
        if (!heldDown) {
            return;
        }
        heldDown = false;
        if (dev == null) {
            return;
        }
        final int x = CursorOverlay.x(), y = CursorOverlay.y();
        /*
         * 这儿不能用 blocked() 提前返回 —— 那个判断一旦在 DOWN 发出去之后才成立，UP 就永远发不出去，
         * 目标屏会一直维持按下状态。
         *
         * 按得太短的话，目标应用还没到自己的长按阈值，会被当成一次普通点击 —— 缺的那部分由注入线程
         * 在抬手前补齐（构造时给的 HOLD_MS），它比页面命长，页面被关掉也不会漏发 UP。
         */
        if (inj != null) {
            inj.release(x, y);
        } else {
            ExtScreen.motionUp(dev.id, x, y);
        }
        if (x != downCx || y != downCy) {
            flash(getString(R.string.ext_flash_drag, downCx, downCy, x, y));
        }
    }

    @Override
    public void onScroll(float dy) {
        if (blocked()) {
            return;
        }
        int[] b = CursorOverlay.bounds();
        if (b == null) {
            return;
        }
        int x = CursorOverlay.x(), y = CursorOverlay.y();
        int ty = CursorOverlay.clamp(y + Math.round(dy * ExtPrefs.sens(this)), 0, b[1] - 1);
        swipe(x, y, x, ty, 260);
        flash(getString(R.string.ext_flash_scroll, ty - y));
    }
    /**
     * 双指左右滑 → 翻一屏。
     *
     * <p>用户点名的需求：在应用列表里找东西时，"拖着图标蹭到屏幕边缘等翻页"又慢又累，
     * 而且拖动本身是逐帧注入。翻页做成一个独立动作，一次到位。
     */
    @Override
    public void onPage(int dir) {
        pageFlip(dir);
    }


    // ------------------------------------------------------------------ 动作

    /**
     * 把没发完的 UP 补出去（页面要走的时候调用）。
     *
     * <p>⚠ 用 {@code abort} 而不是 {@code release}：abort 只抬手，
     * release 带"补一条短按"的语义（手指点太快时按下和抬起会被压成一轮）——
     * 页面被划走、点了关闭，绝不能顺手在目标屏上多点人家一下。
     */
    private void releaseHold() {
        if (!heldDown) {
            return;
        }
        heldDown = false;
        if (inj != null) {
            inj.abort();
        } else if (dev != null) {
            ExtScreen.motionUp(dev.id, CursorOverlay.x(), CursorOverlay.y());
        }
    }

    private void tapAtCursor() {
        if (blocked()) {
            return;
        }
        int x = CursorOverlay.x(), y = CursorOverlay.y();
        ensureInjector();
        if (inj != null) {
            inj.tap(x, y);
        } else {
            ExtScreen.tap(dev.id, x, y);
        }
        flash(getString(R.string.ext_flash_tap, x, y));
    }

    private void holdAtCursor() {
        if (blocked()) {
            return;
        }
        int x = CursorOverlay.x(), y = CursorOverlay.y();
        ensureInjector();
        if (inj != null) {
            inj.hold(x, y, HOLD_MS);
        } else {
            ExtScreen.hold(dev.id, x, y, HOLD_MS);
        }
        flash(getString(R.string.ext_flash_hold, x, y));
    }

    private void sendKey(String keyCode) {
        if (blocked()) {
            return;
        }
        ExtScreen.key(dev.id, keyCode);
    }

    /**
     * 目标屏是不是"本页自己所在的那块屏"。
     *
     * <p>为什么必须掐掉：本页如果就跑在目标屏上，`input -d <它> tap x y` 发出去
     * 会落到本页自己身上 —— 触控板收到这个"点击"又发一条指令，于是自己点自己，
     * 一圈一圈停不下来。
     *
     * @return true = 这条指令别发
     */
    /**
     * 翻一屏 —— 在目标屏中间打一条横向的滑动。
     *
     * <p>方向：dir &gt; 0 = 看下一屏（手指从右往左划），跟手机上的习惯一致。
     * 起终点取屏宽的 18% / 82%（不走满：贴边容易触发系统的返回手势）。
     */
    private void pageFlip(int dir) {
        if (blocked()) {
            return;
        }
        int[] b = CursorOverlay.bounds();
        if (b == null || b[0] <= 0 || b[1] <= 0) {
            flash(getString(R.string.ext_pad_cursor_fail));
            return;
        }
        int y = b[1] / 2;
        int from = (int) (b[0] * (dir > 0 ? 0.82f : 0.18f));
        int to = (int) (b[0] * (dir > 0 ? 0.18f : 0.82f));
        swipe(from, y, to, y, 220);
        flash(getString(dir > 0 ? R.string.ext_flash_page_next : R.string.ext_flash_page_prev));
    }


    private boolean blocked() {
        if (dev == null) {
            return true;
        }
        int mine;
        try {
            mine = getWindowManager().getDefaultDisplay().getDisplayId();
        } catch (Throwable t) {
            return false;
        }
        if (dev.id == mine) {
            String s = getString(R.string.ext_self_target);
            ExtUi.toast(this, s);
            flash(s);
            return true;
        }
        return false;
    }

    /** 把小动作回显在提示行上 —— 这类"点了看不见反馈"的界面，有回显和不回显是两种体验 */
    private void flash(String s) {
        if (tvHint == null) {
            return;
        }
        tvHint.setText(s);
        tvHint.removeCallbacks(restoreHint);
        tvHint.postDelayed(restoreHint, 1200);
    }

    private final Runnable restoreHint = () -> {
        if (tvHint != null && dev != null && !isFinishing()) {
            tvHint.setText(defaultHint());
        }
    };
}
