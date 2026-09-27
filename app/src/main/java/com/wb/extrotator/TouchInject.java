package com.wb.extrotator;

import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * 把触摸事件<b>直接</b>注进指定屏 —— 一次 Binder 调用，不起进程。
 *
 * <p>老路 {@code input} 命令每注一个事件都要起进程装 JVM，实测一条 47ms（串行 21 次/秒；
 * 4 路并发也才 44 次/秒），从根上追不上内屏 120Hz / 副屏 60Hz，拖动才会一跳一跳；
 * 换成 {@code IInputManager.injectInputEvent} 后事件率到几百/秒、单次延迟降到 1ms 级。
 *
 * <p>注入要 {@code INJECT_EVENTS} 权限（shell / 系统进程才有），所以经
 * {@link ShizukuBinderWrapper} 改由 Shizuku 的 shell 进程发出 —— 与 {@code adb shell input}
 * 同一个身份。
 *
 * <p>⚠ {@code IInputManager} 与 {@code MotionEvent.setDisplayId} 都是 @hide、全靠反射，
 * 任一环节炸掉 {@link #ready()} 就返回 false，调用方<b>必须</b>退回
 * {@link ExtScreen#motionMove} 那条老路 —— 宁可慢也不能不动。
 */
public final class TouchInject {

    private static final String TAG = "ExtTouch";

    /** IInputManager.INJECT_INPUT_EVENT_MODE_ASYNC：注完就走，不等目标应用处理完 */
    private static final int MODE_ASYNC = 0;

    /** 连续被拒这么多次就当这条路废了（瞬时抖动不算） */
    private static final int FAIL_LIMIT = 4;

    /** 探测失败之后隔多久再试一次（Shizuku 可能是后来才连上的） */
    private static final long REPROBE_MS = 5000L;

    private static boolean probed;
    private static boolean ok;
    private static String why = "还没探过";
    /** 探测卡死过一次就再也不试了 —— 重试只会又挂住一条线程 */
    private static boolean blockedOnce;

    private static Object im;
    private static Method mInject;
    private static Method mSetDisplay;
    private static int fails;

    /** 上次探测的时刻 —— 探测失败之后隔一会儿再探一次（用户可能刚把 Shizuku 连上） */
    private static long probedAt;

    private TouchInject() {
    }

    /** 这条路现在能不能用 */
    public static synchronized boolean ready() {
        if (!ok && !blockedOnce
                && (!probed || SystemClock.uptimeMillis() - probedAt > REPROBE_MS)) {
            probe();
        }
        return ok;
    }

    /** 不能用的话，卡在哪一步 —— 界面上要把这句说实话，别让用户对着"没反应"猜 */
    public static synchronized String why() {
        ready();
        return why;
    }

    /**
     * 探一次。⚠ <b>必须套看门狗</b> —— 这条路要碰 Shizuku 的 binder 和几个 @hide 反射，
     * 实测踩过一次：探测自己卡死，把注入线程一起带走，表现是"线程活着、一个事件都发不出去、
     * 日志里连一行都没有"，非常难查（线程栈也抓不到）。
     * 所以探测丢给一条独立线程，1.5 秒不回来就判这条路不通 —— 大不了退回 input 慢路，
     * <b>绝不允许"什么都不动"</b>。
     */
    private static void probe() {
        probed = true;
        probedAt = SystemClock.uptimeMillis();

        if (blockedOnce) {
            ok = false;
            why = "上次探测卡死过，不再重试";
            Log.w(TAG, why);
            return;
        }

        // 先把最便宜的一步单独做了：没连 Shizuku 时这一句立刻返回，
        // 不必为了它去起看门狗线程（注入线程每秒重试一次，不能每次都等 1.5 秒）。
        if (!Shizuku.pingBinder()) {
            ok = false;
            why = "Shizuku 没在跑";
            Log.i(TAG, "探测结果: " + why);
            return;
        }

        final String[] box = new String[1];
        Thread t = new Thread(() -> box[0] = tryProbe(), "extrot-touchinject-probe");
        t.setDaemon(true);
        t.start();
        try {
            t.join(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            blockedOnce = true;
            ok = false;
            why = "探测卡住了（1.5 秒没回来）—— 已退回 input 路";
        } else if (box[0] == null) {
            ok = true;
            why = "Shizuku 直注可用";
        } else {
            ok = false;
            why = box[0];
        }
        Log.i(TAG, "探测结果: " + why);
    }

    /** 真正去探。成功返回 null，失败返回原因。这段可能会卡住，所以由 {@link #probe()} 看着 */
    private static String tryProbe() {
        try {
            Log.i(TAG, "探测·1/3 取 input 服务");
            IBinder raw = SystemServiceHelper.getSystemService("input");
            if (raw == null) {
                return "拿不到 input 服务";
            }
            Log.i(TAG, "探测·2/3 IInputManager$Stub.asInterface");
            IBinder wrapped = new ShizukuBinderWrapper(raw);
            Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
            im = stub.getMethod("asInterface", IBinder.class).invoke(null, wrapped);

            Log.i(TAG, "探测·3/3 取 injectInputEvent 方法");
            mInject = Class.forName("android.hardware.input.IInputManager")
                    .getMethod("injectInputEvent", InputEvent.class, int.class);

            try {
                mSetDisplay = MotionEvent.class.getMethod("setDisplayId", int.class);
            } catch (Throwable ignored) {
                // 拿不到就先不发这个 —— 本机目标是 display 0，默认值本来就对
                mSetDisplay = null;
            }
            return null;
        } catch (Throwable t) {
            return "探测报错: " + t;
        }
    }

    /**
     * 注一个触摸动作。
     *
     * @param downTime MOVE / UP 必须与 DOWN 一致；≤0 表示用当前时刻
     * @return false = 这条路此刻不通，调用方请退到 {@code input}
     */
    public static synchronized boolean send(int displayId, int action, float x, float y,
                                            long downTime) {
        if (!ready()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = null;
        try {
            ev = MotionEvent.obtain(downTime <= 0L ? now : downTime, now, action, x, y, 0);
            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            if (mSetDisplay != null) {
                mSetDisplay.invoke(ev, displayId);
            }
            Object r = mInject.invoke(im, ev, MODE_ASYNC);
            if (r instanceof Boolean && ((Boolean) r)) {
                fails = 0;
                return true;
            }
            if (++fails >= FAIL_LIMIT) {
                ok = false;
                why = "injectInputEvent 一直被拒（权限？）—— 已退回 input 路";
                Log.w(TAG, why);
            }
        } catch (Throwable t) {
            ok = false;
            why = "注入报错: " + t;
            Log.w(TAG, why, t);
        } finally {
            if (ev != null) {
                ev.recycle();
            }
        }
        return false;
    }
}
