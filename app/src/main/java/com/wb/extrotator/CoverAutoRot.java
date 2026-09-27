package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「外屏自动旋转」——让封面屏（外屏）自己跟着手机的横竖转（v4.23 起）。
 *
 * <p><b>为什么要自己做</b>：手机的「自动旋转」只管<b>默认屏</b>（内屏）。封面屏是三星自己钉死的
 * （默认 {@code lock 2} = 面板原生姿态），手机再怎么转它都不动，只能靠那个手动「旋转 90°」按钮
 * 一下一下点。这里把那件事自动化：读加速度计判断手机是横是竖，再下发同一批命令。
 *
 * <p><b>下发的是哪条命令</b>：跟手动旋转<b>完全同一条</b>（见 {@link CoverDisplay} 里那串实测结论）——
 * <pre>
 *   wm fixed-to-user-rotation -d 1 enabled          ← 前置，不开这条 lock 不落地
 *   cmd window user-rotation -d 1 lock &lt;0..3&gt;      ← 0/1/2/3 = 0°/90°/180°/270°
 * </pre>
 * 竖屏 = 0（正着拿）或 {@link CoverDisplay#ROT_NATIVE}（2，手机倒着拿）；
 * 横屏 = 1 或 3，看往哪边倒。
 *
 * <p><b>角度和锁值怎么对应</b>：Android 里 {@code rotation} 的定义是「设备逆时针转了 90°，
 * 画面就顺时针转 90° 补回来，报出来是 {@code ROTATION_90}」。设备逆时针转时，它的<b>右侧</b>
 * 朝上，此时重力在设备坐标里落在 <b>+x</b>。所以：
 * <pre>
 *   x &gt; 0（右侧朝上 / 逆时针转） ⇒ lock 1      x &lt; 0（左侧朝上 / 顺时针转） ⇒ lock 3
 *   y &gt; 0（正着拿） ⇒ lock 0                   y &lt; 0（倒着拿） ⇒ lock 2
 * </pre>
 * ⚠ 「倒着拿」= 竖直状态下绕屏幕法线转 180°：世界坐标里的重力没动，但设备坐标跟着转了，
 * 于是重力在设备坐标里的投影从 +y 翻到 -y —— 加速度计分得出来（这正是它和「平放」的区别）。
 * 若哪天真机上发现转反了，把 {@link #onAccel} 里那两个 {ROT_90, ROT_270} 对调即可；
 * ⚠ 竖屏正/倒这两档已经对调过一次了（用户 2026-09-26 实测：180/0 与陀螺仪是反的）——
 * 下面这张表就是实测之后的结论，别照「面板倒装所以正拿给 2」去推。
 *
 * <p><b>三道防抖</b>：① 两个门槛（横过来要 52°，回正只要 38°）—— 中间那圈是滞回区，
 * 免得停在边界上左右乱跳；② 方向要稳 {@link #SETTLE_MS} 才真的下发；③ 手机<b>平放</b>
 * （屏朝上/朝下）时判不出"哪边是上"，直接保持现状 —— 否则放桌上会自己乱转。
 *
 * <p>⚠ <b>活在哪</b>：传感器挂在 {@link VolumeKeyService}（那个常驻的无障碍服务）的进程里，
 * 由它 {@code onServiceConnected} 时调 {@link #sync} 补挂。所以本功能<b>要无障碍服务在跑</b> ——
 * 跟「外屏侧边栏」同一个前提。
 *
 * <p>⚠ 所有 {@code set / toggle} 都会走 shell（Shizuku），<b>必须在后台线程调用</b>。
 */
public final class CoverAutoRot {

    private static final String TAG = "CoverAutoRot";

    private static final String SP = "cover_autorot";
    /** 开关本身 */
    private static final String K_ON = "on";
    /** 开之前外屏是哪个角度，关的时候原样还回去 */
    private static final String K_BASE = "base";

    /** 竖着 → 横过来的门槛（离竖直多少度才算横屏） */
    private static final float TILT_TO_LAND = 52f;
    /** 已经横着 → 回正的门槛。比上面低一截，两个门槛之间就是滞回区 */
    private static final float TILT_TO_PORT = 38f;
    /** 方向要稳住这么久才真的下发，防手抖 */
    private static final long SETTLE_MS = 450L;

    private static final Object LOCK = new Object();

    private static SensorManager sensors;
    private static SensorEventListener listener;
    private static volatile Context appCtx;
    private static ExecutorService worker;

    /** 当前认定的角度（0..3）；{@link Integer#MIN_VALUE} = 还没定下来 */
    private static volatile int cur = Integer.MIN_VALUE;
    /** 正在等的候选角度与其起始时刻 */
    private static int pend = Integer.MIN_VALUE;
    private static long pendAt;

    private CoverAutoRot() {
    }

    // ------------------------------------------------------------------ 设置

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 开关现在是开着吗 */
    public static boolean on(Context c) {
        try {
            return sp(c).getBoolean(K_ON, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void flag(Context c, boolean on) {
        try {
            sp(c).edit().putBoolean(K_ON, on).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 拨开关。<b>阻塞</b>（要发 shell）。
     *
     * <p>开之前先把外屏当前角度记下来，关的时候原样还回去 —— 不能用"固定写回 2"，
     * 因为用户可能是手动转到别的角度之后才开的这个开关，关掉时该回到他原来那个角度。
     *
     * @return null = 成了；否则是给用户看的一句话（界面直接显示）
     */
    public static String set(Context c, boolean want) {
        if (c == null) {
            return "没有上下文";
        }
        Context app = c.getApplicationContext();
        int did = CoverDisplay.id(app);
        if (want) {
            if (did <= 0) {
                return "找不到外屏";
            }
            if (!ShellRunner.isReady()) {
                return "要 Shizuku";
            }
            // 传感器是挂在无障碍服务的进程里的，服务不在就没人读方向
            if (!VolumeKeyService.isRunning()) {
                return "要无障碍服务在跑";
            }
            int base = CoverDisplay.currentRotation(app, did);
            sp(app).edit().putBoolean(K_ON, true).putInt(K_BASE, base).apply();
            // 与手动旋转同一条前置：不开这条，后面的 lock 不落地
            ShellRunner.run("wm fixed-to-user-rotation -d " + did + " enabled");
            String err = start(app);
            if (err != null) {
                flag(app, false);       // 起不来就别留一个"开着但不干活"的开关
                return err;
            }
            return null;
        }
        flag(app, false);
        stop();
        if (did > 0 && ShellRunner.isReady()) {
            int base = sp(app).getInt(K_BASE, CoverDisplay.ROT_NATIVE);
            if (base < 0) {
                CoverDisplay.resetRotation(app);    // 原来是"跟传感器走"就还回去
            } else {
                ShellRunner.run("cmd window user-rotation -d " + did + " lock " + base);
            }
        }
        return null;
    }

    /** 拨一下。<b>阻塞</b>。@return null = 成了 */
    public static String toggle(Context c) {
        return c == null ? "没有上下文" : set(c, !on(c));
    }

    /**
     * 跟设置对齐：开着就把传感器挂上，关着就摘掉。服务连上时调一次
     * （服务被重建、系统回收过之后，这里会把传感器补回来）。
     */
    static void sync(Context c) {
        if (c == null) {
            return;
        }
        Context app = c.getApplicationContext();
        if (!on(app)) {
            stop();
            return;
        }
        String err = start(app);
        if (err != null) {
            Log.w(TAG, "外屏自动旋转没能跟上设置：" + err);
        }
    }

    // ------------------------------------------------------------------ 传感器

    private static String start(Context app) {
        synchronized (LOCK) {
            if (listener != null) {
                return null;
            }
            try {
                SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
                if (sm == null) {
                    return "没有传感器服务";
                }
                Sensor s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (s == null) {
                    return "没有加速度计";
                }
                appCtx = app.getApplicationContext();
                SensorEventListener l = new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent e) {
                        try {
                            onAccel(e.values);
                        } catch (Throwable t) {
                            Log.w(TAG, "判方向出错", t);
                        }
                    }

                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) {
                    }
                };
                // 回调挂在主线程：里面只有几次浮点运算，真正的下发丢给后台线程
                boolean ok = sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI, 0,
                        new Handler(Looper.getMainLooper()));
                if (!ok) {
                    return "加速度计挂不上";
                }
                sensors = sm;
                listener = l;
                pend = Integer.MIN_VALUE;
                // 先当竖屏起步，真实角度放后台读（那是 shell），免得一上来就跟现状打架
                cur = CoverDisplay.ROT_NATIVE;
                worker().execute(() -> {
                    Context a = appCtx;
                    if (a != null) {
                        cur = CoverDisplay.currentRotation(a, CoverDisplay.id(a));
                        Log.i(TAG, "外屏自动旋转已开，起始角度 " + cur);
                    }
                });
                return null;
            } catch (Throwable t) {
                Log.w(TAG, "起加速度计失败", t);
                return "加速度计起不来";
            }
        }
    }

    static void stop() {
        synchronized (LOCK) {
            if (sensors != null && listener != null) {
                try {
                    sensors.unregisterListener(listener);
                } catch (Throwable ignored) {
                }
            }
            sensors = null;
            listener = null;
            appCtx = null;
            cur = Integer.MIN_VALUE;
            pend = Integer.MIN_VALUE;
        }
    }

    /**
     * 加速度计来了一帧：判横竖，稳住了就下发。
     *
     * <p>重力在设备坐标里：竖直拿着时几乎全落在 y 上，横过来就转到 x 上，平放则全落在 z 上。
     */
    private static void onAccel(float[] v) {
        if (v == null || v.length < 3) {
            return;
        }
        float x = v[0];
        float y = v[1];
        float z = v[2];
        // 平放（屏朝上/朝下）：分不出哪边是上，保持现状
        if (Math.abs(z) > Math.abs(x) && Math.abs(z) > Math.abs(y)) {
            return;
        }
        int now = cur;
        boolean landNow = now == Surface.ROTATION_90 || now == Surface.ROTATION_270;
        float ang = (float) Math.toDegrees(Math.atan2(Math.abs(x), Math.abs(y)));
        boolean land = ang >= (landNow ? TILT_TO_PORT : TILT_TO_LAND);
        int want;
        if (land) {
            // 右侧朝上（x > 0）= 手机逆时针转 = 90°；左侧朝上 = 顺时针转 = 270°
            want = x > 0 ? Surface.ROTATION_90 : Surface.ROTATION_270;
        } else {
            // 竖着还得再分「正拿 / 倒拿」—— 只判横竖的话，手机倒过来时 want 仍等于 now，
            // 会被上面那句「和现状一样就跳过」吃掉，于是 0° 那一档永远轮不到（v471 的 bug）。
            want = y >= 0 ? Surface.ROTATION_0 : CoverDisplay.ROT_NATIVE;
        }
        if (want == now) {
            pend = Integer.MIN_VALUE;
            return;
        }
        long t = SystemClock.uptimeMillis();
        if (want != pend) {
            pend = want;
            pendAt = t;
            return;
        }
        if (t - pendAt < SETTLE_MS) {
            return;
        }
        pend = Integer.MIN_VALUE;
        fire(want);
    }

    /** 真的把角度钉下去。先在内存里记账，实际命令丢后台 —— 传感器回调在主线程上。 */
    private static void fire(final int rot) {
        final Context app = appCtx;
        if (app == null) {
            return;
        }
        cur = rot;              // 先记下，免得下一帧又排一次
        worker().execute(() -> {
            int did = CoverDisplay.id(app);
            if (did <= 0 || !ShellRunner.isReady()) {
                Log.w(TAG, "外屏自动旋转下发不了（外屏 " + did + "，Shizuku " + ShellRunner.isReady() + "）");
                return;
            }
            // 顺序不能反：先允许钉住，再钉角度（与 CoverDisplay.rotate 同一条）
            ShellRunner.run("wm fixed-to-user-rotation -d " + did + " enabled");
            ShellRunner.run("cmd window user-rotation -d " + did + " lock " + rot);
            // 回读一次：没落地就把账改回真实值，下一轮会重试
            int real = CoverDisplay.currentRotation(app, did);
            if (real >= 0 && real != rot) {
                Log.w(TAG, "外屏自动旋转没落地：想要 " + rot + "，实际 " + real);
                cur = real;
            } else {
                Log.i(TAG, "外屏自动旋转 → " + rot);
            }
        });
    }

    private static ExecutorService worker() {
        synchronized (LOCK) {
            if (worker == null) {
                worker = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "cover-autorot");
                    t.setDaemon(true);
                    return t;
                });
            }
            return worker;
        }
    }
}
