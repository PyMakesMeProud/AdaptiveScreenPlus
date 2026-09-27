package com.wb.extrotator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

/**
 * 「投屏控制」的底层：<b>媒体投影 + 虚拟显示</b>。
 *
 * <p><b>它为什么必须是个前台服务</b>：不是为了「稳一点」，而是硬前提 —— targetSdk 34
 * （Android 14）起，应用只有在<b>已经跑着一个 mediaProjection 类型的前台服务</b>时才允许调
 * {@link MediaProjectionManager#getMediaProjection}，否则直接 SecurityException。所以顺序是死的：
 * <pre>
 *   页面点「开始投屏」→ 系统授权弹窗 → 拿到 (resultCode, data)
 *     → startForegroundService（本服务，类型 mediaProjection）
 *       → startForeground()
 *         → getMediaProjection()   ← 到这一步才合法
 *           → 页面把 TextureView 的 Surface 交过来 → createVirtualDisplay()
 * </pre>
 *
 * <p><b>画面是怎么到副屏上的</b>：虚拟显示把内屏（display 0，即 DEFAULT_DISPLAY）的内容，按我们
 * 给的尺寸渲染进一个 Surface —— 页面把它贴在 TextureView 上，就成了「实时画面」。反过来，手指在
 * TextureView 上的位置按同一比例换算回内屏坐标，交给 {@link ExtScreen#dragStart} 那条路注进去，
 * 就成了「能在副屏上操控」。
 *
 * <p>⚠ <b>两个只在这个版本成立的前提</b>：
 * <ul>
 *   <li><b>一个投影只能建一个虚拟显示</b>（Android 14 的行为变更）：页面重建（转屏、被系统回收
 *       再拉起）时<b>不能</b>再 createVirtualDisplay，只能 {@link VirtualDisplay#setSurface}
 *       换一块 Surface 上去 —— 所以 {@link #attach(Surface)} 里分了「第一次建」和「以后换」
 *       两支，别图省事写成一种；</li>
 *   <li><b>授权是一次性的</b>：每次投屏都要在系统弹窗里点一次同意（Android 14 起更严，连「上次
 *       同意过」都不认），所以本服务不做「开机常驻」之类的事，用完即停（{@link #stopCast}）。</li>
 * </ul>
 */
public class CastService extends Service {

    private static final String TAG = "ExtCast";

    /** 带着授权结果起服务 */
    public static final String ACTION_START = "com.wb.extrotator.action.CAST_START";
    /** 收工 */
    public static final String ACTION_STOP = "com.wb.extrotator.action.CAST_STOP";

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    /** 内屏此刻的逻辑尺寸（注入坐标空间）；页面探好了带过来 */
    public static final String EXTRA_SRC_W = "src_w";
    public static final String EXTRA_SRC_H = "src_h";
    /**
     * 虚拟显示的密度。
     *
     * <p>⚠ 密度<b>不参与镜像缩放</b>，别再拿它解释「画面铺不满投屏框」：实测框 1199×999、内屏与
     * vd 都是 1296×1080、dpi=320 ⇒ 画面<b>正好铺满</b>。
     *
     * <p>真正决定画面大小的是「<b>建虚拟显示那一刻的源屏尺寸 : 虚拟显示尺寸</b>」，而命门是
     * 「源屏尺寸得在建 vd 之前就落地」（见 {@code CastActivity.RESIZE_SETTLE_MS}）。
     */
    public static final String EXTRA_DPI = "dpi";

    private static final String CH_ID = "extrot_cast";
    private static final int NOTI_ID = 4201;

    /** 本进程內就一个实例；页面靠它拿"能不能贴画面了" */
    private static volatile CastService inst;

    private MediaProjection projection;
    private VirtualDisplay display;
    private Surface surface;

    private int srcW, srcH, srcDpi;
    private Listener listener;

    /**
     * 页面拿它知道"投屏起来 / 停了"。
     * 回调都在主线程 —— 停的时机可能来自系统（用户从通知栏或系统弹窗里撤销），
     * 那种情况下页面自己什么都不知道，全靠这一条。
     */
    public interface Listener {
        /**
         * @param projecting true = 授权到手、虚拟显示可贴；false = 停了
         * @param why        停的原因（给界面说实话用），起来时为 null
         */
        void onCastState(boolean projecting, String why);
    }

    public static CastService get() {
        return inst;
    }

    /** 授权到手了没（页面据此决定弹窗提示与按钮文案） */
    public static boolean ready() {
        CastService s = inst;
        return s != null && s.projection != null;
    }

    /**
     * 上一次停下来的原因（null = 上一次是「起来了」）。
     *
     * <p>为什么需要它：页面是在服务<b>创建之后</b>才绑上监听的（授权结果回来才起服务，那时页面
     * 早过了 {@code onStart}），所以服务内部那次 {@code notifyState} 的回调页面收不到。页面绑上来
     * 时靠这条把话说全 —— 否则失败时状态行会退回「未投屏」，看不出到底是授权被拒、还是虚拟显示
     * 建失败。
     */
    public static String lastWhy() {
        return lastWhy;
    }

    private static volatile String lastWhy;

    /**
     * 起一个"要投屏"的前台服务。
     *
     * @param code 授权弹窗回的 resultCode（必须是 RESULT_OK）
     * @param data 授权弹窗回的 Intent
     */
    public static void start(Context ctx, int code, Intent data, int w, int h, int dpi) {
        Intent i = new Intent(ctx, CastService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_SRC_W, w)
                .putExtra(EXTRA_SRC_H, h)
                .putExtra(EXTRA_DPI, dpi);
        try {
            ctx.startForegroundService(i);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService 失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();

        if (intent == null) {
            // 被系统重新拉起（START_NOT_STICKY 一般走不到这儿），授权结果早没了
            stopCast("服务被系统重启，授权已失效");
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopCast("已停止");
            return START_NOT_STICKY;
        }

        if (projection != null) {
            // 已经投着了：只更新一下尺寸（页面重进时会带新的）
            srcW = intent.getIntExtra(EXTRA_SRC_W, srcW);
            srcH = intent.getIntExtra(EXTRA_SRC_H, srcH);
            notifyState(true, null);
            return START_NOT_STICKY;
        }

        final int code = intent.getIntExtra(EXTRA_CODE, 0);
        final Intent data = intent.getParcelableExtra(EXTRA_DATA);
        srcW = intent.getIntExtra(EXTRA_SRC_W, 0);
        srcH = intent.getIntExtra(EXTRA_SRC_H, 0);
        srcDpi = intent.getIntExtra(EXTRA_DPI, 320);
        if (code == 0 || data == null || srcW <= 0 || srcH <= 0) {
            stopCast("授权结果不完整（" + srcW + "×" + srcH + "）");
            return START_NOT_STICKY;
        }
        startProjection(code, data);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        inst = null;
        release(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 投影

    private void startProjection(int code, Intent data) {
        MediaProjection p = null;
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            p = mpm == null ? null : mpm.getMediaProjection(code, data);
        } catch (Throwable t) {
            Log.w(TAG, "getMediaProjection 失败: " + t);
        }
        if (p == null) {
            stopCast("拿不到投影对象（授权被系统拒绝）");
            return;
        }
        /*
         * ⚠ 回调必须在 createVirtualDisplay **之前**注册：
         * Android 14 起，没注册回调就建虚拟显示会抛 IllegalStateException。
         * 另外它是"投影被停"的唯一通知渠道 —— 用户在系统侧撤销时，
         * 我们这边只有这一条路能知道。
         */
        try {
            p.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "投影被系统停掉");
                    release(false);
                    notifyState(false, "投影已被系统停止");
                    stopForeground(true);
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            Log.w(TAG, "registerCallback 失败: " + t);
        }
        projection = p;
        Log.i(TAG, "投影就绪，目标尺寸 " + srcW + "×" + srcH + " dpi=" + srcDpi);
        notifyState(true, null);
    }

    /**
     * 把画面贴到这块 Surface 上（页面的 TextureView 一就绪就调）。
     *
     * <p>“第一次”和“以后”走的是两条路，见类注释里那条 Android 14 的限制。
     *
     * @return false = 还没授权 / 建失败，页面据此提示
     */
    public boolean attach(Surface s) {
        MediaProjection p = projection;
        if (p == null || s == null) {
            return false;
        }
        try {
            if (display == null) {
                display = p.createVirtualDisplay("extrot-cast", srcW, srcH, srcDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null,
                        new Handler(Looper.getMainLooper()));
                Log.i(TAG, "虚拟显示已建 " + srcW + "×" + srcH + " → " + (display != null));
            } else {
                display.setSurface(s);
                Log.i(TAG, "虚拟显示换了一块 Surface");
            }
            surface = s;
            return display != null;
        } catch (Throwable t) {
            Log.w(TAG, "attach 失败: " + t);
            notifyState(false, "建虚拟显示失败：" + t);
            return false;
        }
    }

    /**
     * 内屏的尺寸 / 方向变了：把虚拟显示按新几何重设一次。
     *
     * <p><b>为什么必须有这一下</b>：虚拟显示的几何是建它那一刻按源屏尺寸定死的，源屏之后
     * <b>自己</b>转了方向（竖 ↔ 横）它不会跟着动 —— 镜像内容被塞进旧比例的 buffer，
     * 页面那边又照旧尺寸换算触点，两边一起偏，表现就是"点哪儿都不准"。
     *
     * <p>⚠ <b>不能</b>释放了重建：Android 14 起一个投影只许 createVirtualDisplay 一次
     * （见类注释），所以只有 resize 这一条路。
     *
     * @return true = 尺寸确实变了、且重设成功
     */
    public boolean refit(int w, int h) {
        VirtualDisplay d = display;
        if (d == null || w <= 0 || h <= 0) {
            return false;
        }
        if (w == srcW && h == srcH) {
            return false;
        }
        try {
            d.resize(w, h, srcDpi);
            Log.i(TAG, "虚拟显示按新几何重设 " + srcW + "×" + srcH + " → " + w + "×" + h);
            srcW = w;
            srcH = h;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "虚拟显示重设失败: " + t);
            return false;
        }
    }

    /** 页面要走了：把画面从虚拟显示上摘下来，但投影留着（回来还能贴回去） */
    public void detach() {
        if (display != null) {
            try {
                display.setSurface(null);
            } catch (Throwable ignored) {
            }
        }
        surface = null;
    }

    /** 收工：投影、虚拟显示、前台通知一起收掉 */
    public void stopCast(String why) {
        release(true);
        notifyState(false, why);
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        stopSelf();
        revokeGrant();
    }

    /**
     * 把投屏用的那条 appops 预授权还给系统。
     *
     * <p>为什么收工了要还：{@code CastActivity.grantProjection()} 为了绕开「授权弹窗在副屏上
     * 点不到」这件事，把本应用的 {@code PROJECT_MEDIA} 设成了 allow。不还的话它会一直留在系统里，
     * 变成一个「这个应用以后请求投屏都不再问」的长期口子。
     *
     * <p>它<b>只</b>影响本应用、<b>只</b>影响投屏这一项 —— appops 是按「包 + 权限项」记账的，
     * 别的应用各记各的（实测 {@code appops query-op PROJECT_MEDIA allow} 里看不到别人）。
     *
     * <p>⚠ 还成 {@code default}（系统默认值），<b>不是</b> {@code ignore} —— 别去替用户改系统
     * 默认策略，那才是越界。
     *
     * <p>⚠ 弱失败：Shizuku 没就绪 / 命令被拒都无所谓，下次投屏会重新授权一遍。这条是「尽力而为」，
     * 绝不能因为它拦住收工。
     */
    private void revokeGrant() {
        final String pkg = getPackageName();
        new Thread(() -> {
            try {
                if (ShellRunner.isReady()) {
                    String out = ShellRunner.run("appops set " + pkg + " PROJECT_MEDIA default");
                    Log.i(TAG, "PROJECT_MEDIA 预授权已归还 → " + (out == null ? "" : out.trim()));
                }
            } catch (Throwable t) {
                Log.w(TAG, "归还 PROJECT_MEDIA 失败: " + t);
            }
        }, "extrot-cast-revoke").start();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void notifyState(boolean projecting, String why) {
        // 记一份状态与原因：页面的监听是在服务创建之后才绑上的，
        // 那一次 notifyState 它收不到，只能靠这条在绑上时把话说全（见 lastWhy）。
        lastWhy = projecting ? null : why;
        Listener l = listener;
        if (l != null) {
            l.onCastState(projecting, why);
        }
    }

    /**
     * 释放。
     *
     * @param stopProjection 要不要顺带把投影也停掉。
     *                       ⚠ 从 {@code onStop()} 回调里进来时必须传 false —— 投影已经是停的
     *                       状态，再调一次 {@code stop()} 是多余的（而且这条路是系统回调里
     *                       进来的，别在那儿绕圈）。
     */
    private void release(boolean stopProjection) {
        if (display != null) {
            try {
                display.release();
            } catch (Throwable ignored) {
            }
            display = null;
        }
        MediaProjection p = projection;
        projection = null;
        if (p != null && stopProjection) {
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
        }
        // Surface 的所有权在页面（它建、它 release），这里只放引用
        surface = null;
    }

    // ------------------------------------------------------------------ 通知

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CH_ID, "投屏控制", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private void startAsForeground() {
        try {
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CH_ID)
                    .setSmallIcon(R.drawable.ic_stat_rot)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("投屏控制运行中")
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .build();
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (Throwable t) {
            // 起不来就别硬撑：多半是没有 FOREGROUND_SERVICE_MEDIA_PROJECTION
            Log.w(TAG, "startForeground 失败: " + t);
            stopSelf();
        }
    }
}
