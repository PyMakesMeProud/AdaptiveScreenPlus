package com.wb.extrotator;

import android.content.Context;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 封面屏「允许启动的包」白名单 —— 纯反射，<b>零权限、不依赖 Shizuku</b>。
 *
 * <p>为什么需要它：封面屏上启动 {@code com.sec.android.app.launcher}（最近任务与桌面都住在
 * 这个包里）会被系统拦成「打开手机以继续」—— 那条提示的学名是
 * {@code Launching from CoverLauncher is blocked, ask to open phone}，写在系统日志里。
 * 白名单之外的目标，不论换成哪条 API 启动（三星封面屏通道、还是无障碍服务直启）都过不去；
 * 这也是「换成无障碍直启之后还弹那张卡片」的真正原因。
 *
 * <p>怎么进去：三星在 {@code IActivityTaskManager} 上留了
 * {@code setCoverLauncherPackageEnabled(String packageName, int userId)}，
 * 反射从 Binder 代理直接就能调 —— 不要任何权限。「阿田自用」每次往封面屏启动应用之前，
 * 都会先把所有桌面类应用（MAIN + LAUNCHER）挨个注册一遍，靠的就是这一下；它在封面屏上开后台，
 * 用的直启代码与我们逐字相同，差别只在这步白名单。
 *
 * <p>⚠ 另一条同类路是往 MultiStar 的 Secure 设置里写名单，那条要
 * {@code WRITE_SECURE_SETTINGS}（普通应用拿不到）。阿田两条一起写，Secure 那条失败了
 * 只打一行日志、照样往下走 —— 真正让启动过闸的是这里这条。
 */
public final class CoverPolicy {

    private static final String TAG = "CoverPolicy";

    /** 最近任务与桌面所在的包，封面屏最常被拦的就是它 */
    public static final String LAUNCHER_PKG = "com.sec.android.app.launcher";

    /** 三星「大封面屏应用」开关，阿田顺带写的另一条路（要 WRITE_SETTINGS，拿不到就算了） */
    private static final String KEY_SYSTEM_LARGE = "large_cover_screen_apps";

    /** 本进程里已经注册成功的包，免得每划一次手势都去敲一遍 Binder */
    private static final Set<String> DONE = Collections.synchronizedSet(new HashSet<String>());

    private CoverPolicy() {
    }

    /** 把 {@link #LAUNCHER_PKG} 注册进白名单 —— 开后台、回桌面都要它 */
    public static void ensureLauncher(Context ctx) {
        ensure(ctx, LAUNCHER_PKG);
    }

    /**
     * 幂等：同一个包在本进程里只真正注册一次。
     *
     * <p>⚠ 只有<b>成功</b>才记下。失败（这台机器没这个接口 / 被系统拒了）每次都会重试，
     * 好让日志里始终留着痕迹，不至于第一次没成就永远静默。
     */
    public static void ensure(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty() || DONE.contains(pkg)) {
            return;
        }
        if (enable(ctx, pkg)) {
            DONE.add(pkg);
        }
    }

    /**
     * 注册一个包。返回 true = 系统收下了。
     *
     * <p>顺带把三星那个「大封面屏应用」开关也打开 —— 阿田两条一起写，日志分开记，
     * 这样真机上一看就知道是哪条起的作用。
     */
    public static boolean enable(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        boolean ok = callFramework(pkg, true);
        if (ctx != null) {
            markLargeCover(ctx);
        }
        return ok;
    }

    /** 反注册（目前没人调，留着成对，将来做「从白名单里摘掉」时用） */
    public static boolean disable(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        return callFramework(pkg, false);
    }

    /** 直接敲框架那个方法；成败都留一行日志 */
    private static boolean callFramework(String pkg, boolean on) {
        String name = on ? "setCoverLauncherPackageEnabled" : "setCoverLauncherPackageDisabled";
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object svc = atm.getDeclaredMethod("getService").invoke(null);
            Method m = svc.getClass().getDeclaredMethod(name, String.class, int.class);
            // ⚠ userId 口径与 SecondaryLauncher.coverLaunch 保持一致：每 10 万一个用户，
            //    本应用跑在自己那个用户里。别用 Context.getUserId()，它不在公开 API 里。
            Object ret = m.invoke(svc, pkg, Process.myUid() / 100000);
            boolean ok = !(ret instanceof Integer) || ((Integer) ret).intValue() == 0;
            Log.i(TAG, name + "(" + pkg + ") => " + ret + (ok ? "  成功" : "  被拒"));
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, name + "(" + pkg + ") 调不动：" + t, t);
            return false;
        }
    }

    /**
     * 「大封面屏应用」开关（{@code Settings.System}）。
     *
     * <p>写 System 表要 {@code WRITE_SETTINGS}，普通应用拿不到就抛异常 —— 吞掉、只记一行。
     * 它不是主路，成了算捡的。
     */
    private static void markLargeCover(Context ctx) {
        try {
            Settings.System.putInt(ctx.getContentResolver(), KEY_SYSTEM_LARGE, 1);
            Log.i(TAG, KEY_SYSTEM_LARGE + " = 1");
        } catch (Throwable t) {
            Log.d(TAG, KEY_SYSTEM_LARGE + " 写不了（不影响主路）：" + t);
        }
    }
}
